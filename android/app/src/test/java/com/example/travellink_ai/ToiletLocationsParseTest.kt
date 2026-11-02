package com.example.travellink_ai

import com.example.travellink_ai.data.model.parseToiletLocations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 共編行程的 nearbyToiletLocations：網頁寫的是數字座標（2026-11-02 地圖開廁所閃退） */
class ToiletLocationsParseTest {

    @Test
    fun numericCoordsBecomeStrings() {
        val raw = listOf(mapOf("name" to "鐵花村公廁", "lat" to 22.7547, "lng" to 121.1503))
        val parsed = parseToiletLocations(raw)
        assertEquals(1, parsed.size)
        assertEquals(22.7547, parsed[0]["lat"]?.toDoubleOrNull()!!, 1e-9)
        assertEquals(121.1503, parsed[0]["lng"]?.toDoubleOrNull()!!, 1e-9)
        assertEquals("鐵花村公廁", parsed[0]["name"])
    }

    @Test
    fun stringCoordsKeptAndJunkSkipped() {
        val raw = listOf(mapOf("lat" to "22.75", "lng" to "121.15", "note" to null), "壞資料", null)
        val parsed = parseToiletLocations(raw)
        assertEquals(listOf(mapOf("lat" to "22.75", "lng" to "121.15")), parsed)
    }

    @Test
    fun missingFieldIsEmpty() {
        assertTrue(parseToiletLocations(null).isEmpty())
        assertTrue(parseToiletLocations("不是陣列").isEmpty())
    }
}
