package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.StopEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 編輯景點 sheet 的儲存：在原本那一站上改 */
class StopEditTest {

    private val day2 = Stop(
        name = "朝日溫泉", time = "09:00", desc = "原本的", emoji = "♨️", duration = 90, order = 6,
        businessHours = "星期一: 00:00 – 23:59", placeId = "pid", stopId = "sid", stopType = "景點",
        lat = 22.63, lng = 121.50, dayIndex = 2, walkNext = true, manualStartMin = 1980,
        nearbyToiletLocations = listOf(mapOf("name" to "公廁"))
    )

    @Test
    fun sameName_keepsDayAndIdentity() {
        // 只改停留時間：過去會被重建成第 1 天、換新 stopId
        val out = StopEdit.apply(day2, "朝日溫泉", "09:00", "原本的", "♨️", 60, day2.businessHours)
        assertEquals(2, out.dayIndex)
        assertEquals("sid", out.stopId)
        assertEquals(60L, out.duration)
        assertEquals(22.63, out.lat!!, 0.0)
        assertEquals("pid", out.placeId)
        assertEquals("景點", out.stopType)
        assertEquals(6L, out.order)
        assertTrue(out.walkNext)
        assertEquals(1980, out.manualStartMin)
        assertEquals(1, out.nearbyToiletLocations.size)
    }

    @Test
    fun renamed_keepsDayButDropsOldPlace() {
        // 套用 AI 建議：換了地點，舊座標／placeId／類型不能沿用，第幾天和識別碼要留著
        val out = StopEdit.apply(day2, " 綠島燈塔 ", "10:30", "白色燈塔", "🗼", 45, "未提供")
        assertEquals(2, out.dayIndex)
        assertEquals("sid", out.stopId)
        assertEquals("綠島燈塔", out.name)
        assertEquals("綠島燈塔", out.searchKeyword)
        assertNull(out.lat)
        assertNull(out.lng)
        assertEquals("", out.placeId)
        assertEquals("", out.stopType)
        assertTrue(out.nearbyToiletLocations.isEmpty())
        assertEquals(45L, out.duration)
    }

    @Test
    fun blankTime_keepsExisting() {
        val out = StopEdit.apply(day2, "朝日溫泉", " ", "", "", 90, day2.businessHours)
        assertEquals("09:00", out.time)
        assertEquals("📍", out.emoji)
    }
}
