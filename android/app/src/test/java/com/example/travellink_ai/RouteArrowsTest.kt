package com.example.travellink_ai

import com.example.travellink_ai.ui.map.RouteArrows
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路線方向箭頭的擺放（測試回饋：地圖路線看不出往哪走） */
class RouteArrowsTest {

    // 台東火車站往北約 1.1 公里
    private val south = LatLng(22.7930, 121.1230)
    private val north = LatLng(22.8030, 121.1230)

    @Test
    fun bearingNorthAndEast() {
        assertEquals(0f, RouteArrows.bearing(south, north), 0.5f)
        assertEquals(90f, RouteArrows.bearing(south, LatLng(22.7930, 121.1330)), 0.5f)
        assertEquals(180f, RouteArrows.bearing(north, south), 0.5f)
    }

    @Test
    fun evenlySpacedAlongPath() {
        val arrows = RouteArrows.place(listOf(south, north), spacingMeters = 200.0)
        // 約 1112 m、間距 200 m、第一個在 100 m → 6 個
        assertEquals(6, arrows.size)
        assertTrue(arrows.all { it.bearing < 1f || it.bearing > 359f })
        assertTrue(arrows.zipWithNext().all { (a, b) -> b.position.latitude > a.position.latitude })
    }

    @Test
    fun shortPathGetsOneArrowInTheMiddle() {
        val arrows = RouteArrows.place(listOf(south, north), spacingMeters = 5_000.0)
        assertEquals(1, arrows.size)
        assertEquals((south.latitude + north.latitude) / 2, arrows[0].position.latitude, 1e-6)
    }

    @Test
    fun capsArrowsPerSegment() {
        val arrows = RouteArrows.place(listOf(south, north), spacingMeters = 1.0)
        assertTrue(arrows.size <= RouteArrows.MAX_PER_SEGMENT)
        assertTrue(arrows.size >= RouteArrows.MAX_PER_SEGMENT - 1)
    }

    @Test
    fun followsTurns() {
        val corner = LatLng(22.8030, 121.1330)
        val arrows = RouteArrows.place(listOf(south, north, corner), spacingMeters = 300.0)
        assertTrue(arrows.any { it.bearing < 1f })
        assertTrue(arrows.any { kotlin.math.abs(it.bearing - 90f) < 1f })
    }

    @Test
    fun degenerateInput() {
        assertTrue(RouteArrows.place(listOf(south), 100.0).isEmpty())
        assertTrue(RouteArrows.place(listOf(south, south), 100.0).isEmpty())
    }
}
