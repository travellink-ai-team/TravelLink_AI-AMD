package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.AssistantPlaces
import com.example.travellink_ai.ui.planning.LocalAlternatives
import com.example.travellink_ai.ui.planning.PlaceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隨行管家換景點要看營業時間（實測：9 點出發的第一站被換成 11 點才開的店） */
class AssistantHoursTest {

    private val lunchOnly = "星期一: 11:00 – 20:00\n星期二: 休息\n星期三: 11:00 – 20:00\n星期四: 11:00 – 20:00\n" +
        "星期五: 11:00 – 20:00\n星期六: 11:00 – 20:00\n星期日: 11:00 – 20:00"

    private fun place(name: String, hours: String) =
        PlaceCost(name, 22.755, 121.15, null, "", null, "", -1, typeName = "景點", businessHours = hours, duration = 60)

    private val center = 22.7570 to 121.1500
    private val mon = "2026/09/21"
    private val tue = "2026/09/22"

    @Test
    fun openForVisit_rejectsArrivalBeforeOpening() {
        val p = place("十一點才開", lunchOnly)
        assertFalse(LocalAlternatives.openForVisit(p, mon, 9 * 60 + 10, 40))
        assertTrue(LocalAlternatives.openForVisit(p, mon, 11 * 60 + 30, 40))
        // 沒有營業時間資料就不擋
        assertTrue(LocalAlternatives.openForVisit(place("沒資料", ""), mon, 9 * 60, 40))
    }

    @Test
    fun pick_dropsPlacesClosedThatDay() {
        val ps = listOf(place("週二公休", lunchOnly), place("天天開", ""))
        assertEquals(listOf("天天開"), AssistantPlaces.pick(ps, center, emptyList(), tue).map { it.place.name })
        assertEquals(2, AssistantPlaces.pick(ps, center, emptyList(), mon).size)
    }

    @Test
    fun promptSection_showsThatDaysHours() {
        val c = AssistantPlaces.pick(listOf(place("十一點才開", lunchOnly)), center, emptyList(), mon)
        val line = AssistantPlaces.promptSection(c, mon)
        assertTrue(line, line.contains("營業 11:00 - 20:00"))
        assertFalse(AssistantPlaces.promptSection(c).contains("營業"))
    }
}
