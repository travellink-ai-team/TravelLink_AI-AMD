package com.example.travellink_ai.data.agent

import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.DayPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * App 行程 ↔ 旅程應變 Agent 的請求／草稿（格式見 TravelLink_AI-AMD docs/amd-agent/t4-delay-trigger-spec.md）。
 *
 * 站的 id 一律是 App 的 stopId——讀 Firestore 時已優先取 collabStopId（見 stableStopId），兩者同值。
 * 套回：只替換送出的那些站，其餘站（沒送的錨點、已走過的站）原位保留；時間交給 App 的排程重算。
 */
object AgentTripMapper {

    data class Built(val trip: AgentTrip, val sentIds: Set<String>)

    private val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")
    private val FOOD_TYPES = setOf("餐廳", "小吃", "咖啡廳", "冰品", "早午餐", "甜點", "美食", "早餐")

    /** 天氣／文字需求送哪些站：出發／回程車站與住宿不送，船班港口要送（後端靠它判斷搭船、停航時整段改天） */
    fun isSendable(s: Stop): Boolean = s.isFerry || (!s.isStation && !s.isLodging)

    /** 錨點類型（T4 規格 anchor 欄位） */
    fun anchorOf(s: Stop): String? = when {
        s.isFerry -> "ferry"
        s.isLodging -> "lodging"
        s.isStation -> "station"
        else -> null
    }

    /** scenic｜food｜transit；住宿不歸類 */
    fun stopTypeOf(s: Stop): String? = when {
        s.isFerry || (s.isStation && !s.isLodging) -> "transit"
        s.isLodging -> null
        s.stopType in FOOD_TYPES || s.stopType.contains("餐") -> "food"
        else -> "scenic"
    }

    private fun toAgentStop(s: Stop, anchor: String?) = AgentStop(
        id = s.stopId, day = s.dayIndex, time = s.time.take(5), stayMin = s.duration.toInt(), name = s.name,
        lat = s.lat, lng = s.lng,
        stopType = stopTypeOf(s),
        anchor = anchor,
        businessHours = s.businessHours.takeIf { it.isNotBlank() && it != "未提供" },
        timeLocked = s.manualStartMin != null
    )

    /**
     * 每天的結束時間：精靈選的收工時間，但不早於行程原本排定的結束（最後一站離開／抵達回程車站）。
     * App 排程允許收工時間小幅超出（例如選 14:00、回程車站排在 14:07）。若送 14:00，後端在還沒延誤前
     * 就判定排不下，而回程車站是錨點、時間固定，AI 怎麼刪站都解不開，只好一直問要不要延後收工。
     */
    internal fun planEndMins(itin: Itinerary, windowEnd: Int): Int {
        val lastDay = itin.stops.maxOfOrNull { it.dayIndex } ?: return windowEnd
        val planned = itin.stops.filter { it.dayIndex == lastDay }.mapNotNull { s ->
            DayPlanner.parseHhMm(s.time)?.let { t -> if (s.isStation && !s.isLodging) t else t + s.duration.toInt() }
        }.maxOrNull() ?: return windowEnd
        return maxOf(windowEnd, planned.coerceAtMost(23 * 60 + 59))
    }

    private fun tripOf(itin: Itinerary, stops: List<AgentStop>): AgentTrip? {
        if (!itin.days.contains(" - ")) return null
        val windows = DayPlanner.buildDayWindows(itin.days.substringBefore(" - "), itin.days.substringAfter(" - "))
        val startDate = windows.firstOrNull()?.date?.takeIf { it.isNotBlank() }?.replace('/', '-') ?: return null
        return AgentTrip(
            title = itin.title.ifBlank { "我的行程" },
            // 後端依 region 判斷離島（綠島／蘭嶼）與查天氣；本島一律送「台東」
            region = IslandRegistry.byDestination(itin.region)?.name ?: "台東",
            startDate = startDate,
            endTime = DayPlanner.hhmmOf(planEndMins(itin, windows.last().endMins)),
            people = Regex("\\d+").find(itin.people)?.value?.toIntOrNull()?.coerceIn(1, 50) ?: 2,
            stops = stops
        )
    }

    /** 天氣／文字需求：送整趟還沒走過的可調整站。行程沒有日期或沒有可調整的站時回 null */
    fun build(itin: Itinerary, doneIds: Set<String> = emptySet()): Built? {
        val sent = itin.stops.filter { isSendable(it) && it.stopId !in doneIds }
        if (sent.isEmpty()) return null
        // 這兩種觸發不標 anchor：停航情境要能把整段船班移到別天，標了錨點就動不了
        val trip = tripOf(itin, sent.map { toAgentStop(it, anchor = null) }) ?: return null
        return Built(trip, sent.map { it.stopId }.toSet())
    }

    /**
     * T4 延誤（規格第 2 節）：只送 [day] 這一天、目前所在站之後還沒開始的站，加上當天在它之後的錨點站。
     * 目前所在的站放在 trigger.from，不放進 stops；它之前的站（含出發車站）都已經過去了。
     */
    fun buildDelay(itin: Itinerary, doneIds: Set<String>, here: Stop, day: Int): Built? {
        val idx = itin.stops.indexOfFirst { it.stopId == here.stopId }
        if (idx < 0) return null
        val sent = itin.stops.drop(idx + 1).filter { it.dayIndex == day && it.stopId !in doneIds }
        if (sent.isEmpty()) return null
        val trip = tripOf(itin, sent.map { toAgentStop(it, anchorOf(it)) }) ?: return null
        return Built(trip, sent.map { it.stopId }.toSet())
    }

    /** App 行程裡某站到「下一站」的車程（transitToNextMin 的來源） */
    data class Leg(val nextId: String, val mins: Int)

    /**
     * App 的逐段車程 → 每站到下一站的 Leg。[mins] 必須是這份 [stops] 的車程（長度 = 站數 − 1，
     * 呼叫端先驗過站序指紋、而且每段都是實查的，不是直線估算）。
     * 跨日、船班段（航程時間固定，不是車程）、0 分（查不到）與超出 1–600 的段不給。
     */
    fun transitLegs(stops: List<Stop>, mins: List<Long>): Map<String, Leg> {
        if (stops.size < 2 || mins.size != stops.size - 1) return emptyMap()
        val out = mutableMapOf<String, Leg>()
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (a.dayIndex != b.dayIndex || a.isFerry || b.isFerry) continue
            val m = mins[i]
            if (m < 1 || m > 600) continue
            out[a.stopId] = Leg(b.stopId, m.toInt())
        }
        return out
    }

    /**
     * 把車程填進請求：陣列裡相鄰的兩站，在 App 行程裡也必須相鄰才填（中間有沒送的站就不填）。
     * 每次都重新決定（[legs] 空的就全部清掉），回答 question 重送時不會留著舊的值。
     */
    fun withTransit(req: AgentRequest, legs: Map<String, Leg>): AgentRequest {
        val stops = req.trip.stops
        val filled = stops.mapIndexed { i, s ->
            val next = stops.getOrNull(i + 1)
            s.copy(transitToNextMin = legs[s.id]?.takeIf { next != null && it.nextId == next.id }?.mins)
        }
        val trigger = (req.trigger as? AgentTrigger.Delay)?.let { t ->
            val first = stops.firstOrNull()
            t.copy(from = t.from.copy(
                transitToNextMin = legs[t.from.stopId]?.takeIf { first != null && it.nextId == first.id }?.mins
            ))
        } ?: req.trigger
        return req.copy(trip = req.trip.copy(stops = filled), trigger = trigger)
    }

    /** 第 [day] 天的日期 yyyy-MM-dd（用 trip.startDate 推算） */
    fun dateOfDay(startDate: String, day: Int): String =
        runCatching { LocalDate.parse(startDate).plusDays((day - 1).toLong()).toString() }.getOrDefault(startDate)

    /** 某天的分鐘數 → 帶 +08:00 的 ISO 時間；超過 24 小時就算到隔天 */
    fun isoAt(date: String, minutes: Int): String {
        val d = runCatching { LocalDate.parse(date.replace('/', '-')) }.getOrNull() ?: return ""
        val day = d.plusDays((minutes / 1440).toLong())
        val m = ((minutes % 1440) + 1440) % 1440
        return "%sT%02d:%02d:00+08:00".format(day, m / 60, m % 60)
    }

    /** epoch 毫秒（demo 時是假時鐘）→ 台灣時間的 ISO，例如 2026-11-07T14:30:00+08:00 */
    fun isoNow(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(TAIPEI).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * 草稿能不能套回這份行程：每一站都必須是送出去的站，或 Agent 新加的站。
     * 重播錄製的 mock 時，草稿是錄製當時的行程（id 對不上），只能預覽。
     */
    fun canApply(sentIds: Set<String>, draft: List<DraftStop>): Boolean =
        draft.isNotEmpty() && draft.all { it.id in sentIds || it.agentAdded }

    /**
     * 天氣／文字需求提案的「固定時間」規則（T4 規格 6.4，沿用網頁做法）：
     * 時刻只在兩種情況寫死——原本就是手動時間（照時間差平移），或提案明確改了這站的時間（retime）。
     * 延誤（T4）提案傳 null：不鎖時間，只套用刪站與縮短停留，時間交給排程從預計離開重算。
     *
     * @param sent 送出時每站的時間與停留（id → AgentStop）
     * @param retimedNames 提案 changes 裡 retime 且前後時間不同的站名
     */
    data class TimeLock(val sent: Map<String, AgentStop>, val retimedNames: Set<String>) {
        companion object {
            fun of(request: AgentRequest, changes: List<AgentChange>): TimeLock? {
                if (request.trigger is AgentTrigger.Delay) return null
                return TimeLock(
                    sent = request.trip.stops.associateBy { it.id },
                    retimedNames = changes.filter { it.type == "retime" && it.from != null && it.to != null && it.from != it.to }
                        .mapNotNull { it.name?.trim() }.toSet()
                )
            }
        }
    }

    /** 網頁的 manualStartMin：多日行程是「(第幾天−1)×1440＋當日分鐘」 */
    private fun absMin(day: Int, hhmm: String): Int? = DayPlanner.parseHhMm(hhmm)?.let { (day - 1) * 1440 + it }

    /**
     * 把草稿套回目前的站點清單（只改提案涉及的站，用 id 對應）。
     *
     * 逐站走過原清單：遇到某天第一個送出的站時，整批放入草稿裡那一天的站（依時間排序），
     * 其他送出的站略過；沒送出的站（錨點、已走過的站）原位保留。草稿有、原本卻沒有送出站的那一天
     * （例如停航時船班與後續站移到別天），插在下一天的第一站之前，沒有就插在回程車站前。
     */
    fun merge(
        current: List<Stop>,
        sentIds: Set<String>,
        draft: List<DraftStop>,
        newStop: (DraftStop) -> Stop = ::defaultNewStop,
        timeLock: TimeLock? = null
    ): List<Stop> {
        val byId = current.associateBy { it.stopId }
        val blocks = draft.groupBy { it.day }
            .mapValues { (_, v) -> v.sortedBy { DayPlanner.parseHhMm(it.time) ?: 0 } }

        fun toStop(d: DraftStop): Stop {
            val existing = byId[d.id]?.takeIf { it.stopId in sentIds }
            if (existing == null) {
                val created = newStop(d)
                // 取代的是手動時間的站：新站沿用提案時間並固定
                val replaced = d.replaces?.let { byId[it] }
                return if (timeLock != null && replaced?.manualStartMin != null)
                    created.copy(manualStartMin = absMin(d.day, d.time)) else created
            }
            var stop = existing.copy(dayIndex = d.day, duration = d.stayMin.toLong(), time = d.time)
            val sent = timeLock?.sent?.get(d.id) ?: return stop
            val newMin = DayPlanner.parseHhMm(d.time)
            val oldMin = DayPlanner.parseHhMm(sent.time)
            if (newMin != null && oldMin != null && newMin != oldMin) {
                stop = when {
                    stop.manualStartMin != null -> stop.copy(manualStartMin = stop.manualStartMin!! + (newMin - oldMin))
                    existing.name.trim() in timeLock.retimedNames -> stop.copy(manualStartMin = absMin(d.day, d.time))
                    else -> stop
                }
            }
            if (d.stayMin != sent.stayMin) {
                stop = stop.copy(manualEndMin = if (stop.manualEndMin != null && stop.manualStartMin != null)
                    stop.manualStartMin!! + d.stayMin else null)
            }
            return stop
        }

        val emitted = mutableSetOf<Int>()
        val out = mutableListOf<Stop>()
        for (s in current) {
            if (s.stopId in sentIds) {
                if (emitted.add(s.dayIndex)) blocks[s.dayIndex]?.forEach { out += toStop(it) }
                continue
            }
            out += s
        }
        for ((day, block) in blocks.toSortedMap()) {
            if (day in emitted) continue
            val next = out.indexOfFirst { it.dayIndex > day }
            val idx = when {
                next >= 0 -> next
                out.lastOrNull()?.let { it.isStation && !it.isFerry } == true -> out.lastIndex
                else -> out.size
            }
            out.addAll(idx, block.map(::toStop))
            emitted += day
        }
        return out.mapIndexed { i, s -> s.copy(order = (i + 1).toLong()) }
    }

    /**
     * 延誤提案套用後，回程車站固定在套用前的時間（寫入 manualStartMin，排程「開始不早於它」）。
     *
     * 套用後 App 會從行程開始時間照原規劃重算每站時間；刪了一站，回程車站的規劃時間就跟著往前
     * （實測 Demo 行程 13:5x → 12:59），而實際進度是從預計離開推的（約 13:45 到），
     * 結果一直顯示「晚到」，和「回程車站的時間不動」矛盾。只往後推、不往前拉，同住宿站的規則。
     * 回程車站＝當天最後一站、是車站（不是住宿、不是船班），而且不是行程第一站。
     */
    fun pinReturnStations(before: List<Stop>, after: List<Stop>): List<Stop> {
        val beforeById = before.associateBy { it.stopId }
        return after.mapIndexed { i, s ->
            val isReturn = i > 0 && s.isStation && !s.isLodging && !s.isFerry &&
                after.getOrNull(i + 1)?.dayIndex != s.dayIndex
            val orig = beforeById[s.stopId]
            if (!isReturn || orig == null || s.manualStartMin != null) s
            else absMin(s.dayIndex, orig.time)?.let { s.copy(manualStartMin = it, time = orig.time) } ?: s
        }
    }

    /** Agent 新加的站：後端附上的營業時間、類型、介紹與 emoji 直接用；新的 id 由 App 產生 */
    fun defaultNewStop(d: DraftStop): Stop {
        val food = d.stopType == "food" || d.kind == "food"
        return Stop(
            name = d.name,
            time = d.time,
            desc = d.desc?.takeIf { it.isNotBlank() } ?: "AI 代理人建議的替代景點",
            emoji = d.emoji?.takeIf { it.isNotBlank() } ?: if (food) "🍽️" else "📍",
            duration = d.stayMin.toLong(),
            order = 0,
            businessHours = d.businessHours?.takeIf { it.isNotBlank() } ?: "未提供",
            stopType = if (food) "餐廳" else "景點",
            lat = d.lat,
            lng = d.lng,
            dayIndex = d.day,
            stopId = UUID.randomUUID().toString()
        )
    }
}
