package com.example.travellink_ai

import com.example.travellink_ai.ui.map.MergedArea
import com.google.android.gms.maps.model.LatLng
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大景區範圍（2026-11-02 回報台東森林公園的圓太大）：近似區塊與 OSM 邊界挑選 */
class MergedAreaTest {

    // 「範圍測試」行程實際資料：鷺鷥湖＋活水湖併成台東森林公園，站點座標是兩湖中點、半徑 1134m
    private val egretLake = LatLng(22.7753733, 121.1474087)
    private val liveLake = LatLng(22.7652114, 121.1647656)
    private val center = LatLng(22.77029235, 121.15608715)

    private fun areaKm2(poly: List<LatLng>): Double {
        val lat0 = poly.first().latitude
        val xy = poly.map { Pair(it.longitude * 111_320 * Math.cos(Math.toRadians(lat0)), it.latitude * 110_540) }
        var a = 0.0
        for (i in xy.indices) { val p = xy[i]; val q = xy[(i + 1) % xy.size]; a += p.first * q.second - q.first * p.second }
        return Math.abs(a) / 2 / 1e6
    }

    @Test
    fun twoMembersInLineBecomeCapsuleNotHugeBox() {
        val path = MergedArea.approxPath(center, listOf(egretLake, liveLake), 1134)
        // 兩湖約 2.1 km、外擴 120m：膠囊約 0.55 km²，原本的圓 4.04 km²、網頁退回的方塊 3.24 km²
        val area = areaKm2(path)
        assertTrue("面積 $area", area in 0.4..0.8)
        // 兩個湖都在範圍內（離邊界至少 100m 內緣）
        listOf(egretLake, liveLake).forEach { lake ->
            assertTrue(path.minOf { MergedArea.distanceMeters(it, lake) } >= 100)
        }
    }

    @Test
    fun threeMembersUseHullPaddedOutward() {
        val third = LatLng(22.7760, 121.1600)   // 離中心約 750m（超過 1200m 會被當離群點丟掉）、明顯偏離兩湖連線
        val path = MergedArea.approxPath(center, listOf(egretLake, liveLake, third), 1134)
        val hull = MergedArea.convexHull(listOf(egretLake, liveLake, third, center))
        assertEquals(hull.size, path.size)
        assertTrue(areaKm2(path) > areaKm2(hull))
    }

    @Test
    fun outlierMemberDropped() {
        val near1 = LatLng(22.7705, 121.1565); val near2 = LatLng(22.7700, 121.1555)
        val farAway = LatLng(22.8500, 121.2500)   // 被 geocode 到十幾公里外
        val path = MergedArea.approxPath(LatLng(22.7703, 121.1560), listOf(near1, near2, farAway), 300)
        assertTrue(path.all { MergedArea.distanceMeters(it, LatLng(22.7703, 121.1560)) < 1000 })
    }

    @Test
    fun noMembersFallsBackToBox() {
        val path = MergedArea.approxPath(center, emptyList(), 2000)
        assertEquals(4, path.size)
        assertEquals(900.0, MergedArea.distanceMeters(LatLng(path[0].latitude, center.longitude), center), 5.0)
    }

    @Test
    fun queryMatchesBothTaiSpellingsAndSkipsRoutes() {
        assertEquals("[台臺]東森林公園", MergedArea.nameRegex("台東森林公園（黑森林）"))
        assertEquals("", MergedArea.nameRegex("湖"))
        val q = MergedArea.query("台東森林公園", center, 1134)!!
        assertTrue(q.contains("""way["name"~"[台臺]東森林公園"](around:2268,"""))
        assertTrue(q.contains("""["type"!~"route"]"""))
    }

    // ── pick：合成的 Overpass 回應 ──

    private fun square(c: LatLng, halfM: Double, closed: Boolean = true): JSONArray {
        val dLat = halfM / 110_540; val dLng = halfM / (111_320 * Math.cos(Math.toRadians(c.latitude)))
        val pts = listOf(
            c.latitude + dLat to c.longitude - dLng, c.latitude + dLat to c.longitude + dLng,
            c.latitude - dLat to c.longitude + dLng, c.latitude - dLat to c.longitude - dLng
        ).let { if (closed) it + it.first() else it }
        return JSONArray(pts.map { JSONObject().put("lat", it.first).put("lon", it.second) })
    }

    private fun way(name: String, geom: JSONArray, vararg tags: Pair<String, String>) = JSONObject()
        .put("type", "way").put("tags", JSONObject().put("name", name).also { t -> tags.forEach { t.put(it.first, it.second) } })
        .put("geometry", geom)

    private fun resp(vararg els: JSONObject) = JSONObject().put("elements", JSONArray(els.toList()))

    @Test
    fun exactNameBeatsCloserBuildingThatContainsTheName() {
        val park = way("臺東森林公園", square(LatLng(22.7665, 121.1598), 900.0), "leisure" to "park")
        val office = way("臺東森林公園管理站", square(center, 20.0), "building" to "yes")
        val ring = MergedArea.pick(resp(office, park), "台東森林公園", center, 1134)
        assertNotNull(ring)
        assertTrue("挑到公園而不是管理站", areaKm2(ring!!) > 3.0)
    }

    @Test
    fun rejectsOpenLinesWaterFarAndUnrelated() {
        val open = way("台東森林公園", square(center, 500.0, closed = false))
        val water = way("台東森林公園", square(center, 500.0), "natural" to "water")
        val far = way("台東森林公園", square(LatLng(22.80, 121.18), 300.0))
        val other = way("臺東大學", square(center, 300.0))
        assertNull(MergedArea.pick(resp(open, water, far, other), "台東森林公園", center, 1134))
    }

    @Test
    fun relationTakesLargestOuterRing() {
        val small = JSONObject().put("role", "outer").put("geometry", square(center, 100.0))
        val big = JSONObject().put("role", "outer").put("geometry", square(center, 600.0))
        val inner = JSONObject().put("role", "inner").put("geometry", square(center, 50.0))
        val rel = JSONObject().put("type", "relation").put("tags", JSONObject().put("name", "臺東森林公園"))
            .put("members", JSONArray(listOf(small, inner, big)))
        val ring = MergedArea.pick(resp(rel), "台東森林公園", center, 1134)!!
        assertEquals(1.44, areaKm2(ring), 0.05)
    }

    // ── 內建邊界（Overpass 掛掉時也畫得出來）──

    @Test
    fun builtInForestParkCoversBothLakes() {
        val ring = MergedArea.builtIn("臺東森林公園（黑森林）", center)
        assertNotNull(ring)
        assertEquals(2.13, areaKm2(ring!!), 0.05)
        assertEquals(ring, MergedArea.builtIn("台東森林公園", center))
    }

    @Test
    fun builtInIgnoresOtherNamesAndFarAwaySameName() {
        assertNull(MergedArea.builtIn("哈巴狗與睡美人岩", LatLng(22.653986, 121.507175)))
        assertNull(MergedArea.builtIn("鷺鷥湖", egretLake))
        assertNull(MergedArea.builtIn("台東森林公園", LatLng(22.80, 121.20)))
    }

    @Test
    fun cacheKeyIgnoresTaiSpelling() {
        assertEquals(MergedArea.cacheKey("台東森林公園", center), MergedArea.cacheKey("臺東森林公園", center))
    }
}
