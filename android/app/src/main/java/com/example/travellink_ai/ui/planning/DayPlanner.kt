package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

/**
 * 多日行程的「分日器」（A5 Stage 2）。
 *
 * 設計原則是**不動排程核心**：`optimizeAndScheduleStops` 是單日全排列窮舉
 * （n ≤ 8，超過就退回原順序不優化），把兩天 12 站直接丟進去不只慢，是直接
 * 退化成沒有優化，而且它沒有「過夜」的概念——會試圖把第二天的景點排進第一天晚上。
 * 網頁端就是這樣：dayIndex 有標、時間軸沒分，實際排出 22:11 逛波浪屋
 * （實例 my_1784460114502，見 docs/計畫_A5離島支援與多日行程.md §10-2）。
 *
 * 所以這裡只做三件事，排程器一行不改：
 *   ① 依起訖日期算出每日的時間窗（[buildDayWindows]）
 *   ② 把景點切成每日一組，每組 ≤ [MAX_STOPS_PER_DAY]（[splitIntoDays]）
 *   ③ 由呼叫端對每一組各跑一次現有排程器，錨點依日切換
 *
 * 全部是純函式，不碰 Android 與網路，可直接單元測試。
 */
object DayPlanner {

    /** 非首日的預設開始時間（09:00）。首日用使用者選的出發時間 */
    const val DEFAULT_DAY_START_MINS = 9 * 60

    /** 非末日的預設結束時間（18:00）。末日用使用者選的結束時間 */
    const val DEFAULT_DAY_END_MINS = 18 * 60

    /**
     * 單日站數上限。刻意等於 `optimizeAndScheduleStops` 的全排列上限——
     * 超過那個數字排程器就不優化了，切天時就該把每組壓在這個範圍內。
     */
    const val MAX_STOPS_PER_DAY = 8

    /** 每段交通的粗估（分鐘），只用於切天時的容量估算，真實車程由 Directions 決定 */
    const val AVG_TRANSIT_MINS = 15
    const val AVG_TRANSIT_MINS_WALKING = 20

    /**
     * 一天的時間窗。[date] 為 `yyyy/MM/dd`（可能為空字串，代表來源沒有日期）。
     */
    data class DayWindow(
        val dayIndex: Int,        // 1-based
        val date: String,
        val startMins: Int,
        val endMins: Int
    ) {
        val lengthMins: Int get() = (endMins - startMins).coerceAtLeast(0)
    }

    data class SplitResult(
        /** 每日一組，index 0 = 第 1 天。可能有空組（該日排不下任何站） */
        val byDay: List<List<Stop>>,
        /** 時間不夠而放不進任何一天的站；呼叫端應告知使用者而不是靜默丟掉 */
        val dropped: List<Stop>,
        /** true = 沿用 AI 標的 dayIndex，false = 程式重新切 */
        val usedAiHint: Boolean
    )

    // ── 時間窗 ────────────────────────────────────────────────────

    /**
     * 由「yyyy/MM/dd HH:mm」的起訖時間算出每日時間窗。
     *
     * 首日 09:00（或使用者選的出發時間）到 [DEFAULT_DAY_END_MINS]；
     * 中間日整天；末日到使用者選的結束時間。
     * 起訖同日或日期解析不出來時回傳單一時間窗（＝維持現有單日行為）。
     */
    fun buildDayWindows(startDateTime: String, endDateTime: String): List<DayWindow> {
        val startDate = startDateTime.substringBefore(" ").trim()
        val endDate   = endDateTime.substringBefore(" ").trim()
        val startMins = parseHhMm(startDateTime.substringAfter(" ", "")) ?: DEFAULT_DAY_START_MINS
        val endMins   = parseHhMm(endDateTime.substringAfter(" ", "")) ?: DEFAULT_DAY_END_MINS

        val dayCount = daysBetween(startDate, endDate)
        if (dayCount <= 1) {
            return listOf(DayWindow(1, startDate, startMins, endMins))
        }
        return (1..dayCount).map { day ->
            DayWindow(
                dayIndex  = day,
                date      = addDays(startDate, day - 1),
                startMins = if (day == 1) startMins else DEFAULT_DAY_START_MINS,
                endMins   = if (day == dayCount) endMins else DEFAULT_DAY_END_MINS
            )
        }
    }

    // ── 公休換日 ──────────────────────────────────────────────────

    data class RelocationResult(
        val byDay: List<List<Stop>>,
        /** 換到別天的站與目的天（0-based） */
        val moved: List<Pair<Stop, Int>>,
        /** 每天都排不進去而剔除的站 */
        val dropped: List<Stop>
    )

    /**
     * 把「所在那天公休」的站換到有開的那天；沒有哪天能換就剔除。
     *
     * 切天之後才知道每站落在哪一天，公休與否得到這時候才能判斷。剔除的時間不會空著：
     * 排程器的加站補位會用當天有開的景點遞補。
     *
     * 目的天必須：不是原本那天、站數未達 [MAX_STOPS_PER_DAY]、當天有開、沒有同名的站
     * （同一個地方不必兩天各去一次）。多個候選天時挑站數最少的，讓兩天負擔平均。
     *
     * @param isClosedOn (站, 第幾天(0-based)) → 該站在那天是否公休
     * @param canAccept (站, 目的天(0-based), 目的天目前的站) → 目的天收不收得下這一站。
     *   實測「七里坡」週三公休被換到第 1 天，但第 1 天正餐額度已滿，後面又被餐飲上限移除——
     *   換日時沒看目的天的額度，結果說明卻寫「改排第 1 天」。預設全收（純公休判斷）。
     */
    fun relocateClosed(
        byDay: List<List<Stop>>,
        // 有預設值的參數放在前面、isClosedOn 留在最後：既有呼叫端用「尾端 lambda」傳
        // isClosedOn（relocateClosed(byDay) { s, d -> … }），順序若反過來，
        // 那個 lambda 會被綁到 canAccept 上而編譯失敗
        canAccept: (Stop, Int, List<Stop>) -> Boolean = { _, _, _ -> true },
        isClosedOn: (Stop, Int) -> Boolean
    ): RelocationResult {
        val days = byDay.map { it.toMutableList() }
        val moved = mutableListOf<Pair<Stop, Int>>()
        val dropped = mutableListOf<Stop>()
        for (d in days.indices) {
            for (stop in days[d].filter { isClosedOn(it, d) }) {
                days[d].remove(stop)
                val target = days.indices
                    .filter { it != d && days[it].size < MAX_STOPS_PER_DAY }
                    .filter { days[it].none { other -> other.name == stop.name } }
                    .filter { !isClosedOn(stop, it) }
                    .filter { canAccept(stop, it, days[it]) }
                    .minByOrNull { days[it].size }
                if (target != null) {
                    days[target].add(stop.copy(dayIndex = target + 1))
                    moved += stop to target
                } else {
                    dropped += stop
                }
            }
        }
        return RelocationResult(days, moved, dropped)
    }

    // ── 時間型特別需求（看日出／日落／夜景／看星星）───────────────────

    /** 日出前幾分鐘出發（抵達第一站還要再加一段車程，所以是「日出前一點點」到） */
    const val SUNRISE_LEAD_MINS = 30
    /** 再早也不排到這個時間以前出發 */
    const val EARLIEST_START_MINS = 4 * 60 + 30
    /** 日落後多留幾分鐘 */
    const val SUNSET_TAIL_MINS = 20
    const val NIGHT_END_MINS = 21 * 60
    const val STARS_END_MINS = 21 * 60 + 30
    /** 延長後的收工時間上限；超過就不再延（也是反推時間窗時的合理性上限） */
    const val MAX_EXTENDED_END_MINS = 23 * 60 + 30

    /**
     * 依時間型需求調整每日時間窗。**沒有需求時原樣回傳**（單日、多日行為都與改動前一致）。
     *
     * - 日出：單日排在當天、多日排在「住一晚後的第 2 天」，出發提早到日出前 [SUNRISE_LEAD_MINS] 分
     * - 日落／夜景／看星星：都在第 1 天，把收工時間延後（只延不縮）
     *
     * 調整後時間窗若不足 1 小時就放棄該日的調整，寧可不動也不要排出荒謬的窗。
     * 這個函式是冪等的（套用兩次結果相同），單日行程因此可以先改寫起訖字串、下游再套一次。
     */
    fun applyTimeRequests(windows: List<DayWindow>, requests: Set<TimeRequest>): List<DayWindow> {
        if (requests.isEmpty() || windows.isEmpty()) return windows
        val sunriseDay = SpecialRequests.sunriseDay(windows)
        return windows.map { w ->
            var start = w.startMins
            var end = w.endMins
            if (TimeRequest.SUNRISE in requests && w.dayIndex == sunriseDay) {
                val target = (SpecialRequests.sunriseOf(w) - SUNRISE_LEAD_MINS).coerceAtLeast(EARLIEST_START_MINS)
                start = minOf(start, target)
            }
            if (w.dayIndex == 1) {
                if (TimeRequest.SUNSET in requests) end = maxOf(end, SpecialRequests.sunsetOf(w) + SUNSET_TAIL_MINS)
                if (TimeRequest.NIGHT in requests) end = maxOf(end, NIGHT_END_MINS)
                if (TimeRequest.STARS in requests) end = maxOf(end, STARS_END_MINS)
                end = maxOf(w.endMins, minOf(end, MAX_EXTENDED_END_MINS))
            }
            if (end - start < 60) w else w.copy(startMins = start, endMins = end)
        }
    }

    /**
     * 生成**之後**的實際每日時間窗（重算時間、預覽剩餘時間用）。
     *
     * 持久化的 `days` 只有頭尾兩個時刻，第 2 天提早出發、第 1 天延後收工這類「中間日」的調整
     * 存不進去（多存欄位要動 Room migration 與 Firestore 共用文件，不值得）。改從已排好的
     * 站點時間反推：
     *  - 第 2 天起：第一站的時間早於預設起點（[EARLIEST_START_MINS]～預設起點之間），就以它為起點；
     *    重算時因此不會把日出那天硬拉回 09:00。壞資料（如曾出現的第 2 天 22:06）不在此範圍。
     *  - 收工：最後一站結束時間晚於預設收工、又不超過 [MAX_EXTENDED_END_MINS]，就以它為收工時間。
     * 沒有這類站點時與 [buildDayWindows] 完全相同。
     */
    fun effectiveWindows(days: String, stops: List<Stop>): List<DayWindow> {
        if (!days.contains(" - ")) return emptyList()
        val base = buildDayWindows(days.substringBefore(" - "), days.substringAfter(" - "))
        if (base.size <= 1) return base
        return base.map { w ->
            val dayStops = stops.filter { it.dayIndex == w.dayIndex && !it.isStation }
            val first = dayStops.minByOrNull { it.order }?.let { parseHhMm(it.time) }
            val last = dayStops.maxByOrNull { it.order }?.let { s -> parseHhMm(s.time)?.plus(s.duration.toInt()) }
            val start = if (w.dayIndex > 1 && first != null && first in EARLIEST_START_MINS until w.startMins)
                first else w.startMins
            val end = if (last != null && last > w.endMins && last <= MAX_EXTENDED_END_MINS)
                last else w.endMins
            w.copy(startMins = start, endMins = end)
        }
    }

    /**
     * 兩個 `yyyy/MM/dd` 相差幾天（含頭尾，同日 = 1 天）。
     * 解析失敗、或結束早於開始時回 1——寧可退回單日，也不要生出負數天或半截行程。
     */
    fun daysBetween(startDate: String, endDate: String): Int {
        val s = parseDate(startDate) ?: return 1
        val e = parseDate(endDate) ?: return 1
        val diff = ((e - s) / 86_400_000L).toInt()
        return if (diff < 0) 1 else diff + 1
    }

    // ── 切天 ──────────────────────────────────────────────────────

    /**
     * 把（已依地理動線排序的）景點切成每日一組。
     *
     * 優先沿用 AI 標的 `dayIndex`，但必須通過 [isUsableAiHint] 的檢查；不通過就依
     * 「每日可用時間 × 站數上限」重切。**刻意維持傳入順序切成連續區塊**：傳進來的
     * 已經是最近鄰排序過的動線，連續區塊本身就是地理相鄰的一段，不必再做分群，
     * 也不會把動線打散（切完每日各自還會再跑一次全排列優化）。
     *
     * @param stops       已排序的景點（不含車站／住宿等錨點）
     * @param windows     [buildDayWindows] 的結果
     * @param avgTransitMins 每段交通的粗估，用於容量估算
     */
    fun splitIntoDays(
        stops: List<Stop>,
        windows: List<DayWindow>,
        avgTransitMins: Int = AVG_TRANSIT_MINS
    ): SplitResult {
        if (windows.size <= 1) {
            // 單日：完全不介入，維持既有行為（含「>8 站交給排程器自己處理」）
            return SplitResult(listOf(stops), emptyList(), usedAiHint = false)
        }
        if (stops.isEmpty()) {
            return SplitResult(windows.map { emptyList() }, emptyList(), usedAiHint = false)
        }

        if (isUsableAiHint(stops, windows.size)) {
            val byDay = windows.map { w -> stops.filter { it.dayIndex == w.dayIndex } }
            return SplitResult(byDay, emptyList(), usedAiHint = true)
        }

        val byDay = MutableList(windows.size) { mutableListOf<Stop>() }
        var dayIdx = 0
        var usedMins = 0
        val dropped = mutableListOf<Stop>()

        for (stop in stops) {
            val cost = stop.duration.toInt().coerceAtLeast(0) + avgTransitMins
            while (dayIdx < windows.size) {
                // 預留一段「回到當日終點錨點」的交通
                val budget = (windows[dayIdx].lengthMins - avgTransitMins).coerceAtLeast(0)
                val full = byDay[dayIdx].size >= MAX_STOPS_PER_DAY ||
                    (byDay[dayIdx].isNotEmpty() && usedMins + cost > budget)
                if (!full) break
                dayIdx++
                usedMins = 0
            }
            if (dayIdx >= windows.size) {
                // 每一天都滿了：剩下的站放不進去。回報給呼叫端，不靜默吞掉
                dropped += stop
                continue
            }
            // 空的一天一律至少收一站，否則單站超長（例如 duration > 整日）會讓所有天都空著
            byDay[dayIdx] += stop
            usedMins += cost
        }

        return SplitResult(byDay.map { it.toList() }, dropped, usedAiHint = false)
    }

    /**
     * AI 標的 dayIndex 是否可以直接用。四個條件都要成立：
     *   ① 真的有標（至少有一站 dayIndex > 1，否則就是「全是預設值 1」）
     *   ② 每個 dayIndex 都落在 1..dayCount
     *   ③ 每一天都有站（有空的一天代表 AI 少排了一天）
     *   ④ 每一天都不超過 [MAX_STOPS_PER_DAY]（超過排程器就不優化了）
     *
     * 不檢查「同一天的站是否在動線上相鄰」——那是排程器的工作，它每日各自
     * 跑全排列會自己修好順序。
     */
    fun isUsableAiHint(stops: List<Stop>, dayCount: Int): Boolean {
        if (stops.none { it.dayIndex > 1 }) return false
        if (stops.any { it.dayIndex < 1 || it.dayIndex > dayCount }) return false
        val grouped = stops.groupBy { it.dayIndex }
        if (grouped.size != dayCount) return false
        return grouped.values.none { it.size > MAX_STOPS_PER_DAY }
    }

    // ── 跨日銜接檢查 ──────────────────────────────────────────────

    /**
     * 跨日銜接只驗一件事：當日最後一站的收工時間有沒有超出該日時間窗。
     * 回傳超時的天（1-based）與超出的分鐘數，供呼叫端記 log 或提示。
     *
     * 刻意不做跨日全域最佳化——兩天的景點本來就該按地理位置分邊，
     * 投報率極低，而且會讓「哪一站排在哪一天」變得無法預測。
     */
    fun overrunMinutesByDay(
        scheduledByDay: List<List<Stop>>,
        windows: List<DayWindow>
    ): Map<Int, Int> = buildMap {
        scheduledByDay.forEachIndexed { i, dayStops ->
            val window = windows.getOrNull(i) ?: return@forEachIndexed
            val last = dayStops.lastOrNull() ?: return@forEachIndexed
            val endMins = (parseHhMm(last.time) ?: return@forEachIndexed) + last.duration.toInt()
            if (endMins > window.endMins) put(window.dayIndex, endMins - window.endMins)
        }
    }

    // ── 內部工具 ──────────────────────────────────────────────────

    /** 分鐘數 → "HH:mm"（顯示與 log 用） */
    fun hhmmOf(mins: Int): String = "%02d:%02d".format((mins / 60) % 24, mins % 60)

    /** "HH:mm" → 分鐘數；格式不符回 null */
    internal fun parseHhMm(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].take(2).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** "yyyy/MM/dd" 或 "yyyy-MM-dd" → epoch ms（當地時區零時）；格式不符回 null */
    private fun parseDate(value: String): Long? {
        val v = value.trim().replace('-', '/')
        if (v.length != 10 || v[4] != '/' || v[7] != '/') return null
        val y = v.substring(0, 4).toIntOrNull() ?: return null
        val mo = v.substring(5, 7).toIntOrNull() ?: return null
        val d = v.substring(8, 10).toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        return java.util.Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d)
        }.timeInMillis
    }

    /** "yyyy/MM/dd" 加 n 天；解析失敗回原值 */
    internal fun addDays(date: String, days: Int): String {
        val base = parseDate(date) ?: return date
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = base
            add(java.util.Calendar.DAY_OF_MONTH, days)
        }
        return "%04d/%02d/%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
