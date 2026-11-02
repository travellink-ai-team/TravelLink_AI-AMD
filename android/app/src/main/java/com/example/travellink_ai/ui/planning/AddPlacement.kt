package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

/**
 * 加站（隨行管家、推薦卡「加入」、搜尋新增）要插在哪一天、住宿前還是入住後。
 *
 * 過去只挑「剩餘自由時間最多的那天」：兩天一夜第 1 天 18:00 收工、最後一站 17:13 結束只剩 47 分，
 * 第 2 天剩 76 分，晚上才開的鐵花村就被排到第 2 天中午（2026-10-21 實測）。
 *
 * 現在：
 *  - 住宿站照舊是當天最後一站（很多人入住時間一到先辦入住、休息一下再出門），
 *    但入住之後可以再加站（晚上出門），所以有住宿的那天分成「住宿前」「入住後」兩段。
 *  - 使用者講了哪天、入住前或後（[Request]）就照做。
 *  - 沒講就看營業時間：先找有開、待得下的一般時段（含住宿前），空檔多的優先；
 *    一般時段都沒開（夜市這類）才排到入住後的晚上。
 *  - 行程進行中只排在「現在之後」：今天已經過去的位置、以前的天都不考慮。
 */
object AddPlacement {
    /** 入住後晚上出門，最晚待到幾點（與「看星星」的收工時間相同） */
    const val EVENING_END_MINS = DayPlanner.STARS_END_MINS
    /** 估抵達時間用的車程（插進去之後真正的車程由路線重算） */
    const val TRAVEL_GUESS_MINS = 15

    /** 第 [day] 天的一段可插入範圍：插在索引 [lo]..[hi] 的前面（[hi] 可等於下一段的開頭） */
    data class Slot(val day: Int, val afterCheckIn: Boolean, val lo: Int, val hi: Int)

    /** 使用者指定的位置；null 表示沒講 */
    data class Request(val day: Int? = null, val afterCheckIn: Boolean? = null)

    data class Choice(val slot: Slot, val index: Int, val arriveMins: Int?, val open: Boolean?)

    /** 入住站（第 1 天收工後的飯店）；第 2 天的早餐、退房也是住宿站，但不是入住 */
    fun isCheckIn(s: Stop) = s.isLodging && s.desc.startsWith("入住")

    /**
     * 每一天可以插的段落。[today]／[nowMins]：行程進行中時只留現在之後的位置。
     */
    fun slots(stops: List<Stop>, today: Int? = null, nowMins: Int? = null): List<Slot> {
        if (stops.isEmpty()) return emptyList()
        val minLo = if (stops.first().isStation) 1 else 0
        val maxHi = if (stops.last().isStation && !stops.last().isLodging) stops.size - 1 else stops.size
        val out = mutableListOf<Slot>()
        for (day in stops.map { it.dayIndex }.distinct().sorted()) {
            if (today != null && day < today) continue
            val idxs = stops.indices.filter { stops[it].dayIndex == day }
            val scenic = idxs.filter { !stops[it].isStation }
            val checkIn = idxs.firstOrNull { isCheckIn(stops[it]) }
            val before = scenic.filter { checkIn == null || it < checkIn }
            val (lo, hi) = when {
                before.isNotEmpty() -> before.first() to before.last() + 1
                checkIn != null -> checkIn to checkIn
                // 這天沒有景點：插在回程車站前、或這天的最後
                else -> idxs.last().let { if (stops[it].isStation && it == stops.lastIndex) it to it else it + 1 to it + 1 }
            }
            out += Slot(day, false, lo.coerceIn(minLo, maxHi), hi.coerceIn(minLo, maxHi))
            if (checkIn != null) {
                val after = scenic.filter { it > checkIn }
                out += Slot(day, true, checkIn + 1, if (after.isEmpty()) checkIn + 1 else after.last() + 1)
            }
        }
        if (today == null || nowMins == null) return out
        // 今天：只能插在還沒開始的站前面（或這段的最後）
        return out.mapNotNull { s ->
            if (s.day != today) return@mapNotNull s
            val lo = (s.lo..s.hi).firstOrNull { i ->
                i == s.hi || (DayPlanner.parseHhMm(stops[i].time)?.let { it >= nowMins } ?: true)
            } ?: s.hi
            s.copy(lo = lo)
        }
    }

    /** 插在 [index] 時大概幾點到：前一站（同一天）結束＋車程；這天的第一個位置就用下一站原本的時間 */
    fun arrival(stops: List<Stop>, index: Int, day: Int): Int? {
        val prev = stops.getOrNull(index - 1)?.takeIf { it.dayIndex == day }
        if (prev != null) return DayPlanner.parseHhMm(prev.time)?.plus(prev.duration.toInt() + TRAVEL_GUESS_MINS)
        return stops.getOrNull(index)?.takeIf { it.dayIndex == day }?.let { DayPlanner.parseHhMm(it.time) }
    }

    /**
     * 選位置。[openAt]＝那天那個時間有沒有開（null＝沒資料）；[bestIndex]＝這段裡繞路最少的位置；
     * [dayEnd]＝每天收工時間（入住後一律算到 [EVENING_END_MINS]）。
     */
    fun choose(
        stops: List<Stop>,
        request: Request,
        durationMins: Int,
        dayEnd: Map<Int, Int>,
        openAt: (day: Int, arriveMins: Int?) -> Boolean?,
        bestIndex: (Slot) -> Int,
        today: Int? = null,
        nowMins: Int? = null
    ): Choice? {
        val all = slots(stops, today, nowMins)
        // 指定的條件找不到（那天沒有入住、或沒有那一天）就放寬，不要整個加不進去
        val byDay = all.filter { request.day == null || it.day == request.day }.ifEmpty { all }
        val wanted = byDay.filter { request.afterCheckIn == null || it.afterCheckIn == request.afterCheckIn }
            .ifEmpty { byDay.filter { !it.afterCheckIn } }.ifEmpty { byDay }
        if (wanted.isEmpty()) return null

        class Eval(val choice: Choice, val tier: Int, val free: Int)
        val evals = wanted.map { slot ->
            val idx = bestIndex(slot).coerceIn(slot.lo, slot.hi)
            var arrive = arrival(stops, idx, slot.day)
            if (slot.day == today && nowMins != null) arrive = maxOf(arrive ?: 0, nowMins + TRAVEL_GUESS_MINS)
            val open = openAt(slot.day, arrive)
            val end = if (slot.afterCheckIn) EVENING_END_MINS else dayEnd[slot.day] ?: DayPlanner.DEFAULT_DAY_END_MINS
            val free = end - (arrive ?: end)
            val fits = arrive == null || arrive + durationMins <= end
            // 入住後只在「確定有開」時才主動排（沒有營業時間資料的還是放住宿前）
            val tier = when {
                !slot.afterCheckIn && open != false && fits -> 0
                slot.afterCheckIn && open == true && fits -> 1
                !slot.afterCheckIn && open != false -> 2
                slot.afterCheckIn && open != false -> 3
                !slot.afterCheckIn -> 4
                else -> 5
            }
            Eval(Choice(slot, idx, arrive, open), tier, free)
        }
        return evals.sortedWith(compareBy<Eval> { it.tier }.thenByDescending { it.free }.thenBy { it.choice.slot.day })
            .first().choice
    }

    /** 給使用者看的位置：「第 1 天 19:05（入住後）」；單日行程只寫時間 */
    fun label(stops: List<Stop>, choice: Choice): String {
        val multiDay = stops.map { it.dayIndex }.distinct().size > 1
        val hasCheckIn = stops.any { it.dayIndex == choice.slot.day && isCheckIn(it) }
        val parts = listOfNotNull(
            "第 ${choice.slot.day} 天".takeIf { multiDay },
            choice.arriveMins?.let { DayPlanner.hhmmOf(it) }
        ).joinToString(" ").ifBlank { "行程中" }
        return parts + when {
            choice.slot.afterCheckIn -> "（入住後）"
            hasCheckIn -> "（入住前）"
            else -> ""
        }
    }
}
