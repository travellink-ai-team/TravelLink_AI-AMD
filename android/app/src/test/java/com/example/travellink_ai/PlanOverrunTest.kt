package com.example.travellink_ai

import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.PlanOverrun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 規劃中行程超過精靈設定的結束時間 */
class PlanOverrunTest {

    private fun s(name: String, time: String, dur: Long = 60, day: Int = 1, station: Boolean = false) =
        Stop(name = name, time = time, desc = "", emoji = "", duration = dur, order = 0, isStation = station, dayIndex = day)

    private val oneDay = "2026/10/04 09:00 - 2026/10/04 18:00"

    @Test
    fun returnStationArrival_comparedToUserEnd() {
        val stops = listOf(s("台東車站", "09:00", 0, station = true), s("A", "16:30", 60), s("台東車站", "18:40", 0, station = true))
        val o = PlanOverrun.of(oneDay, stops, "台東")!!
        assertEquals(18 * 60 + 40, o.plannedEnd)
        assertEquals(40, o.overMin)
    }

    @Test
    fun withinGrace_noWarning() {
        // 生成時排程允許小幅超出（實測 14:00 收工、車站 14:07）
        val stops = listOf(s("A", "16:30", 60), s("台東車站", "18:08", 0, station = true))
        assertNull(PlanOverrun.of(oneDay, stops, "台東"))
    }

    @Test
    fun withoutReturnStation_usesLastStopEnd() {
        val stops = listOf(s("A", "17:30", 60))
        assertEquals(30, PlanOverrun.of(oneDay, stops, "台東")!!.overMin)
    }

    @Test
    fun multiDay_onlyLastDayCounts() {
        val days = "2026/10/04 09:00 - 2026/10/05 16:00"
        // 第 1 天排到 21:00（看夜景之類，預設 18:00 不算使用者設定）不警告
        val ok = listOf(s("夜景", "20:00", 60, day = 1), s("B", "14:00", 60, day = 2))
        assertNull(PlanOverrun.of(days, ok, "台東"))
        val over = listOf(s("夜景", "20:00", 60, day = 1), s("B", "15:50", 60, day = 2))
        assertEquals(2, PlanOverrun.of(days, over, "台東")!!.day)
    }

    @Test
    fun island_limitLeavesTimeForReturnFerry() {
        val isl = IslandRegistry.byDestination("綠島")!!
        val buffer = isl.sailingMins + IslandRegistry.BOARDING_BUFFER_MINS
        // 最後一站在設定的 18:00 前結束，但沒留回程航程與登船時間 → 仍算超時
        val stops = listOf(s("朝日溫泉", "16:00", 90))
        val o = PlanOverrun.of(oneDay, stops, "綠島")
        assertNotNull(o)
        assertEquals(18 * 60 - buffer, o!!.limit)
        assertEquals(18 * 60, o.userEnd)
    }
}
