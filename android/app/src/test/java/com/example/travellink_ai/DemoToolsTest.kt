package com.example.travellink_ai

import com.example.travellink_ai.debug.DemoClock
import com.example.travellink_ai.debug.LocationSimulator
import com.example.travellink_ai.ui.trip.haversineMeters
import com.google.android.gms.maps.model.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoToolsTest {

    @After fun tearDown() = DemoClock.reset()

    private val a = LatLng(22.7937, 121.1231)      // 台東車站
    private val b = LatLng(22.7537, 121.1459)      // 鐵花新聚落

    @Test
    fun `沿路線：挑端點最接近兩站的那段，前後接上站點座標`() {
        val road = listOf(LatLng(22.7930, 121.1235), LatLng(22.7800, 121.1300), LatLng(22.7540, 121.1455))
        val other = listOf(LatLng(22.70, 121.10), LatLng(22.71, 121.11))
        val path = LocationSimulator.roadPath(listOf(other, road), a, b)
        assertEquals(listOf(a) + road + listOf(b), path)
    }

    @Test
    fun `找不到這兩站之間的路線就退回直線`() {
        assertEquals(listOf(a, b), LocationSimulator.roadPath(emptyList(), a, b))
        val far = listOf(LatLng(23.30, 121.40), LatLng(23.31, 121.41))
        assertEquals(listOf(a, b), LocationSimulator.roadPath(listOf(far), a, b))
    }

    @Test
    fun `離開本站：只取前 700 公尺`() {
        val cut = LocationSimulator.truncate(listOf(a, b), 700.0)
        assertEquals(2, cut.size)
        assertEquals(700.0, haversineMeters(a, cut.last()), 5.0)
    }

    @Test
    fun `demo 時鐘：快轉、跳到某時間不倒退、歸零`() {
        val t0 = DemoClock.now()
        DemoClock.advance(15 * 60_000L)
        assertTrue(DemoClock.now() - t0 >= 15 * 60_000L)
        assertTrue(DemoClock.isShifted())
        val before = DemoClock.now()
        DemoClock.jumpTo(before - 60 * 60_000L)          // 往回跳：不動
        assertTrue(DemoClock.now() >= before)
        val target = before + 30 * 60_000L
        DemoClock.jumpTo(target)
        assertEquals(target.toDouble(), DemoClock.now().toDouble(), 1_000.0)
        assertEquals(target - DemoClock.offsetMs.value, DemoClock.toRealTime(target))
        DemoClock.reset()
        assertTrue(!DemoClock.isShifted())
    }

    @Test
    fun `demo 時鐘可以直接設成較早的行程開始時間`() {
        val start = System.currentTimeMillis() - 3 * 60 * 60_000L   // 3 小時前
        DemoClock.setTo(start)
        assertEquals(start.toDouble(), DemoClock.now().toDouble(), 1_000.0)
        assertTrue(DemoClock.isShifted())
        // 鬧鐘換回真實時間：demo 的「開始後 1 小時」＝ 真實的 2 小時前
        assertEquals((start + 60 * 60_000L - DemoClock.offsetMs.value).toDouble(),
            DemoClock.toRealTime(start + 60 * 60_000L).toDouble(), 1.0)
    }
}
