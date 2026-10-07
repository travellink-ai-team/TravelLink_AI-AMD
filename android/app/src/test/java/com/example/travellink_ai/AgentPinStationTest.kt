package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 延誤提案套用後回程車站固定在套用前的時間（實測：刪一站後車站從 13:5x 被重算成 12:59，一直顯示晚到） */
class AgentPinStationTest {

    private fun stop(id: String, time: String, day: Int = 1, station: Boolean = false, lodging: Boolean = false) =
        Stop(name = id, time = time, desc = "", emoji = "", duration = 30, order = 0,
            isStation = station, isLodging = lodging, stopId = id, dayIndex = day)

    @Test
    fun returnStation_keepsTimeBeforeApply() {
        val before = listOf(stop("st", "10:00", station = true), stop("a", "10:20"), stop("b", "11:10"),
            stop("c", "12:00"), stop("d", "13:00"), stop("en", "13:52", station = true))
        // 提案刪掉 c，merge 後車站的時間會被重算往前
        val after = listOf(before[0], before[1], before[2], before[4], stop("en", "12:59", station = true))
        val pinned = AgentTripMapper.pinReturnStations(before, after)
        assertEquals("13:52", pinned.last().time)
        assertEquals(13 * 60 + 52, pinned.last().manualStartMin)
        // 出發車站與景點不動
        assertNull(pinned.first().manualStartMin)
        assertNull(pinned[1].manualStartMin)
    }

    @Test
    fun multiDay_pinsOnlyReturnStationNotLodging() {
        val before = listOf(stop("st", "09:00", station = true), stop("a", "10:00"),
            stop("l", "18:00", station = true, lodging = true), stop("b", "09:00", day = 2),
            stop("en", "15:00", day = 2, station = true))
        val pinned = AgentTripMapper.pinReturnStations(before, before)
        assertNull(pinned[2].manualStartMin)                  // 住宿站有自己的規則
        assertEquals(1440 + 15 * 60, pinned.last().manualStartMin)   // 第 2 天：(day−1)×1440＋分鐘
    }

    @Test
    fun existingManualTime_untouched() {
        val before = listOf(stop("st", "10:00", station = true), stop("en", "13:00", station = true))
        val after = listOf(before[0], before[1].copy(manualStartMin = 14 * 60))
        assertEquals(14 * 60, AgentTripMapper.pinReturnStations(before, after).last().manualStartMin)
    }
}
