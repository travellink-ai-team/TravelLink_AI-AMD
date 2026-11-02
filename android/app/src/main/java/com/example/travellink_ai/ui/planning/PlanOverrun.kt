package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.data.model.Stop

/**
 * 規劃中的行程超過使用者在精靈設定的結束時間。
 *
 * 只看最後一天（單日行程就是當天）：那是使用者自己選的收工時間；多日的前幾天是預設 18:00，
 * 看夜景這類需求本來就會排到更晚，拿來比會一直誤報。有回程車站時看「到達車站」，
 * 離島看最後一個景點結束（期限已扣掉回程航程與登船緩衝，同排程）。
 * 生成時排程本來就允許小幅超出（例如 14:00 收工、車站 14:07），超過 [GRACE_MIN] 分才算。
 */
data class Overrun(val day: Int, val plannedEnd: Int, val limit: Int, val userEnd: Int) {
    val overMin: Int get() = plannedEnd - limit
}

object PlanOverrun {
    const val GRACE_MIN = 10

    fun of(days: String, stops: List<Stop>, region: String?): Overrun? {
        if (!days.contains(" - ")) return null
        val last = DayPlanner.buildDayWindows(days.substringBefore(" - "), days.substringAfter(" - "))
            .lastOrNull() ?: return null
        val island = IslandRegistry.byDestination(region)
        val limit = last.endMins - (island?.let { it.sailingMins + IslandRegistry.BOARDING_BUFFER_MINS } ?: 0)
        val dayStops = stops.filter { it.dayIndex == last.dayIndex }.ifEmpty { return null }
        val endStop = dayStops.last()
        // 排過午夜的站 time 會是 00:xx，要接著前一站算成隔天（見 dayClockMins）
        val clock = DayPlanner.dayClockMins(dayStops)
        val plannedEnd = if (island == null && endStop.isStation && !endStop.isLodging && !endStop.isFerry) {
            clock.last()
        } else {
            dayStops.indexOfLast { !it.isStation && !it.isLodging && !it.isFerry }.takeIf { it >= 0 }
                ?.let { i -> clock[i]?.plus(dayStops[i].duration.toInt()) }
        } ?: return null
        return Overrun(last.dayIndex, plannedEnd, limit, last.endMins)
            .takeIf { it.overMin > GRACE_MIN }
    }
}
