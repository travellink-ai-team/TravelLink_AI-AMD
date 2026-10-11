package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.LocalAlternatives
import com.example.travellink_ai.ui.planning.PlaceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每站的附近替代景點：本地資料即時算，餐廳換餐廳、景點換景點 */
class LocalAlternativesTest {

    private fun place(name: String, lat: Double, lng: Double, type: String = "景點", hours: String = "") =
        PlaceCost(name, lat, lng, null, "", null, "", -1, type, hours, 60)

    private fun stop(name: String, lat: Double?, lng: Double?, type: String = "景點", time: String = "12:00") =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = 60, order = 1,
            stopType = type, lat = lat, lng = lng)

    private val sight = place("加路蘭", 22.8100, 121.2000)
    private val sightFar = place("鹿野高台", 22.9500, 121.1300)          // 約 19 km
    private val cafe = place("海邊咖啡", 22.7900, 121.1950, type = "咖啡廳")
    private val diner = place("小吃店", 22.7920, 121.1920, type = "餐廳")
    private val places = listOf(sight, sightFar, cafe, diner)

    @Test
    fun `景點只換景點、依距離排序、太遠的不列`() {
        val r = LocalAlternatives.nearby(stop("富岡地質公園", 22.7910, 121.1930), emptyList(), places, "")
        assertEquals(listOf("加路蘭"), r.map { it.place.name })
    }

    @Test
    fun `餐廳換餐廳或咖啡廳`() {
        val lunch = stop("某海鮮餐廳", 22.7910, 121.1930, type = "餐廳")
        val r = LocalAlternatives.nearby(lunch, listOf(lunch), places, "")
        assertEquals(listOf("小吃店", "海邊咖啡"), r.map { it.place.name })
    }

    @Test
    fun `車站、住宿、船班不給替代`() {
        val s = stop("富岡地質公園", 22.7910, 121.1930)
        assertTrue(LocalAlternatives.nearby(s.copy(isStation = true), emptyList(), places, "").isEmpty())
        assertTrue(LocalAlternatives.nearby(s.copy(isLodging = true), emptyList(), places, "").isEmpty())
        assertTrue(LocalAlternatives.nearby(s.copy(isFerry = true), emptyList(), places, "").isEmpty())
    }

    @Test
    fun `抵達時沒開的不列`() {
        val night = place("夜市", 22.7950, 121.1950, hours = "星期三: 17:00 – 23:00")
        val r = LocalAlternatives.nearby(stop("富岡地質公園", 22.7910, 121.1930), emptyList(),
            listOf(night, sight), "2026/09/23")
        assertEquals(listOf("加路蘭"), r.map { it.place.name })
    }

    @Test
    fun `行程日期依天數往後推`() {
        assertEquals("2026/09/21", LocalAlternatives.dateOfDay("2026/09/21 08:00 - 2026/09/22 18:00", 1))
        assertEquals("2026/09/22", LocalAlternatives.dateOfDay("2026/09/21 08:00 - 2026/09/22 18:00", 2))
        assertEquals("", LocalAlternatives.dateOfDay("", 1))
    }

    @Test
    fun `換成餐廳時用餐廳圖示`() {
        val s = LocalAlternatives.applyTo(stop("某海鮮餐廳", 22.79, 121.19, type = "餐廳"), diner, "", "x")
        assertEquals("🍽️", s.emoji)
        assertEquals("餐廳", s.stopType)
    }
}
