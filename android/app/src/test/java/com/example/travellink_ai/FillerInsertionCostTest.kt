package com.example.travellink_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 補位景點的挑選成本：最便宜插入，不是離重心最近。
 *
 * 「離重心近」與「插進去便宜」是兩件事：一個點可能離重心很近但在動線的垂直
 * 方向上，繞過去很貴；另一個離重心較遠卻剛好在兩站之間的路上。重心對橫跨型
 * 的行程尤其不可靠——它會落在中間那片誰也沒去的地方。
 *
 * 座標取自實測行程 my_1786545289305（綠島兩天一夜）的第 1 天。
 * 註：那一趟補入過山古道並不是排序造成的——當時整個候選池只剩它一個可補，
 * 沒得選。這組測試驗的是規則本身的性質，不是那次的結果。
 *
 * 這裡複製 pickFillerStops 的 detourMeters（純幾何運算）。修改時請同步。
 */
class FillerInsertionCostTest {

    private data class P(val lat: Double, val lng: Double)

    private fun distanceMeters(a: P, b: P): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** route = 起點 + 各站 + 回起點 */
    private fun detourMeters(route: List<P>, poi: P): Double =
        (0 until route.size - 1).minOf { i ->
            distanceMeters(route[i], poi) + distanceMeters(poi, route[i + 1]) -
                distanceMeters(route[i], route[i + 1])
        }

    private fun centroid(stops: List<P>) =
        P(stops.map { it.lat }.average(), stops.map { it.lng }.average())

    private fun detourMins(m: Double) = Math.round(m / 500.0).toInt().coerceIn(5, 45)

    // 綠島實測座標
    private val 南寮漁港 = P(22.659485, 121.473767)
    private val 小長城 = P(22.660833, 121.506816)
    private val 綠島觀音洞 = P(22.671427, 121.506476)
    private val 白色恐怖園區 = P(22.674437, 121.497674)
    private val 富富廚房 = P(22.674333, 121.472831)
    private val 綠島遊客中心 = P(22.672247, 121.468645)
    private val 過山古道 = P(22.651273, 121.487006)   // 島中央，橫貫步道
    private val 柴口浮潛區 = P(22.676644, 121.482876) // 北岸，在既有動線上

    private val day1 = listOf(小長城, 綠島觀音洞, 白色恐怖園區, 富富廚房, 綠島遊客中心)
    private val route = listOf(南寮漁港) + day1 + listOf(南寮漁港)

    @Test
    fun `在動線上的點比要往中央繞的點便宜`() {
        // 柴口浮潛區在北岸、既有動線上；過山古道是橫貫步道，得往島中央繞
        val 柴口繞路 = detourMeters(route, 柴口浮潛區)
        val 過山繞路 = detourMeters(route, 過山古道)
        assertTrue("柴口 ${柴口繞路.toInt()}m 應遠低於過山古道 ${過山繞路.toInt()}m",
                   柴口繞路 < 過山繞路)
    }

    @Test
    fun `離重心的距離與插入成本可以完全相反`() {
        // 這一組正好是反例：過山古道離重心比較「遠」，繞路卻比較貴；
        // 兩個指標不但不等價，連方向都未必一致，所以不能拿距離當代理
        val c = centroid(day1)
        assertTrue("過山古道離重心較遠", distanceMeters(c, 過山古道) > distanceMeters(c, 柴口浮潛區))
        assertTrue("繞路成本也是過山古道較貴", detourMeters(route, 過山古道) > detourMeters(route, 柴口浮潛區))
        // 真正的重點：重心落在誰也沒去的地方
        val 重心到最近站 = day1.minOf { distanceMeters(c, it) }
        assertTrue("橫跨型行程的重心離所有站都不近（實測 ${重心到最近站.toInt()}m）", 重心到最近站 > 800)
    }

    @Test
    fun `正好落在兩站之間的點幾乎不用多繞`() {
        // 取白色恐怖園區與富富廚房連線的中點
        val mid = P((白色恐怖園區.lat + 富富廚房.lat) / 2, (白色恐怖園區.lng + 富富廚房.lng) / 2)
        assertTrue("直線中點的繞路應接近 0", detourMeters(route, mid) < 50.0)
        assertEquals("換算成分鐘會落在下限", 5, detourMins(detourMeters(route, mid)))
    }

    @Test
    fun `繞路換算成分鐘有上下限`() {
        assertEquals("再近也算 5 分鐘（停車、找路）", 5, detourMins(0.0))
        assertEquals(5, detourMins(1000.0))
        assertEquals(20, detourMins(10_000.0))
        assertEquals("再遠也不超過 45 分，否則等於永遠不補", 45, detourMins(100_000.0))
    }

    @Test
    fun `繞路成本不會是負的`() {
        listOf(過山古道, 柴口浮潛區, 小長城, 南寮漁港).forEach {
            assertTrue("${it} 的繞路成本不該小於 0", detourMeters(route, it) >= -0.001)
        }
    }
}
