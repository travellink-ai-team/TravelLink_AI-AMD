package com.example.travellink_ai

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.recap.RECAP_MAX_PHOTOS
import com.example.travellink_ai.ui.recap.RecapPhoto
import com.example.travellink_ai.ui.recap.RecapRepository
import com.example.travellink_ai.ui.recap.capRecapPhotos
import com.example.travellink_ai.ui.recap.downsampleRoute
import com.example.travellink_ai.ui.recap.recapSegmentsFrom
import com.example.travellink_ai.ui.recap.routeGeometryFromFirestore
import com.example.travellink_ai.ui.recap.routeGeometryToFirestore
import com.example.travellink_ai.ui.recap.recapPhotosFor
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * buildTrip 的純邏輯測試（過濾、mode 映射、距離）。
 * db/auth/database 用 mock —— buildTrip 不碰它們。
 * buildPhotos（讀 Firestore）與 buildRoutePoints（PolyUtil/LatLng 依賴 Android）不在此測。
 */
class RecapRepositoryTest {

    private val repo = RecapRepository(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    private fun stop(
        name: String, stopId: String, lat: Double?, lng: Double?,
        isStation: Boolean = false, duration: Long = 60, dayIndex: Int = 1
    ) = Stop(
        name = name, time = "09:00", desc = "", emoji = "📍",
        duration = duration, order = 0, stopId = stopId,
        lat = lat, lng = lng, isStation = isStation, dayIndex = dayIndex
    )

    private fun trip(stops: List<Stop>, transportMode: String = "taxi") = LocalItinerary(
        title = "台東", aiTitle = "", aiReply = "", region = "台東",
        days = "1", people = "2", stops = stops, transportMode = transportMode
    )

    @Test
    fun buildTrip_excludesStationsAndNoCoords() {
        val t = repo.buildTrip(trip(listOf(
            stop("台東車站", "s0", 22.79, 121.12, isStation = true),
            stop("鐵花村", "s1", 22.75, 121.15),
            stop("無座標景點", "s2", null, null)
        )))
        assertEquals("只留非車站、有座標的", 1, t.stops.size)
        assertEquals("鐵花村", t.stops[0].name)
    }

    @Test
    fun buildTrip_mapsMode() {
        fun modeOf(tm: String) =
            repo.buildTrip(trip(listOf(stop("A", "a", 22.7, 121.1)), tm)).stops[0].mode
        // 契約枚舉含 taxi，直接用；walking→walk；driving→car
        assertEquals("taxi", modeOf("taxi"))
        assertEquals("walk", modeOf("walking"))
        assertEquals("car", modeOf("driving"))
    }

    @Test
    fun buildTrip_carriesTripMeta() {
        val item = LocalItinerary(
            title = "兩日", aiTitle = "", aiReply = "", region = "台東",
            days = "2026/08/13 - 2026/08/14", people = "2人",
            stops = listOf(stop("A", "a", 22.7, 121.1)), transportMode = "taxi"
        )
        val t = repo.buildTrip(item)
        assertEquals("台東", t.region)
        assertEquals("2026/08/13 - 2026/08/14", t.dateLabel)
        assertEquals("2人", t.people)
        assertEquals("taxi", t.transportMode)
    }

    @Test
    fun buildTrip_computesDistance() {
        val t = repo.buildTrip(trip(listOf(
            stop("A", "a", 22.75, 121.15), stop("B", "b", 23.12, 121.41)
        )))
        // 台東到花蓮南端約 40~55 km，驗 haversine 落在合理範圍
        assertTrue("距離異常：${t.distanceKm}", t.distanceKm in 40.0..55.0)
    }

    @Test
    fun buildTrip_carriesStayAndDay() {
        val t = repo.buildTrip(trip(listOf(stop("A", "a", 22.7, 121.1, duration = 90, dayIndex = 2))))
        assertEquals(90, t.stops[0].stayMin)
        assertEquals(2, t.stops[0].dayIndex)
    }

    @Test
    fun buildTrip_titleFallsBack() {
        val t = repo.buildTrip(
            LocalItinerary(
                title = "", aiTitle = "", aiReply = "", region = "台東",
                days = "1", people = "2", stops = listOf(stop("A", "a", 22.7, 121.1)),
                transportMode = "taxi"
            )
        )
        assertEquals("旅程回顧", t.title)
    }

    @Test
    fun buildTrip_arriveTimeIsHhMm() {
        fun at(time: String) = repo.buildTrip(trip(listOf(stop("A", "a", 22.7, 121.1).copy(time = time)))).stops[0].arriveTime
        assertEquals("09:00", at("09:00"))
        assertEquals("09:30", at("9:30 - 10:00"))
        assertEquals("", at("未定"))
        assertEquals("", at("25:00"))
    }

    // ── 要送哪幾張照片（recapPhotosFor／capRecapPhotos）──

    private fun photos(counts: List<Int>) = counts.flatMapIndexed { i, n -> (0 until n).map { k -> RecapPhoto(i, "https://x/$i-$k.jpg", "p$i-$k") } }

    @Test
    fun photosFor_v1TakesFirstPerStop_reelsTakesAll() {
        val all = photos(listOf(2, 0, 3))
        assertEquals(listOf("p0-0", "p2-0"), recapPhotosFor(all, "standard").map { it.photoId })
        assertEquals(all, recapPhotosFor(all, "reels"))
    }

    @Test
    fun cap_perStopTenAndDuplicateUrlsOnce() {
        val all = photos(listOf(12)) + RecapPhoto(1, "https://x/0-0.jpg", "dup")
        val out = capRecapPhotos(all)
        assertEquals(10, out.size)
        assertTrue(out.all { it.stopIndex == 0 })
        assertEquals("p0-0", out.first().photoId)
    }

    @Test
    fun cap_overTwentyFourKeepsEveryStop() {
        // 6 站各 6 張＝36 張：輪流取，每站 4 張，順序仍是「站 → 相簿順序」
        val out = capRecapPhotos(photos(List(6) { 6 }))
        assertEquals(RECAP_MAX_PHOTOS, out.size)
        assertEquals(List(6) { 4 }, (0 until 6).map { s -> out.count { it.stopIndex == s } })
        assertEquals(listOf("p0-0", "p0-1", "p0-2", "p0-3", "p1-0"), out.take(5).map { it.photoId })
    }
    // ── 路線折線（跨端契約 §4）──

    /** 從 a 到 b 的假折線：頭尾就在兩站上，中間 n-2 個點 */
    private fun line(a: Stop, b: Stop, n: Int = 4) = (0 until n).map { k ->
        val t = k / (n - 1.0)
        listOf(a.lat!! + (b.lat!! - a.lat!!) * t, a.lng!! + (b.lng!! - a.lng!!) * t)
    }

    private val station = stop("台東車站", "st", 22.79, 121.12, isStation = true)
    private val p1 = stop("鐵花村", "p1", 22.75, 121.15)
    private val p2 = stop("森林公園", "p2", 22.77, 121.16)
    private val p3 = stop("小野柳", "p3", 22.79, 121.20)

    @Test
    fun segments_skipStationAtStart() {
        // 車站 → p1 → p2 → p3：短片只有 p1..p3，第一段（車站→p1）丟掉
        val stops = listOf(station, p1, p2, p3)
        val out = recapSegmentsFrom(stops, listOf(line(station, p1), line(p1, p2), line(p2, p3)))!!
        assertEquals(2, out.size)
        assertEquals(line(p1, p2), out[0])
    }

    @Test
    fun segments_mergeAcrossStationInMiddle() {
        // p1 → 車站 → p2：兩段接成一段，接點不重複
        val out = recapSegmentsFrom(listOf(p1, station, p2), listOf(line(p1, station), line(station, p2)))!!
        assertEquals(1, out.size)
        assertEquals(line(p1, station) + line(station, p2).drop(1), out[0])
    }

    @Test
    fun segments_rejectMismatchOrStaleOrMissing() {
        val stops = listOf(p1, p2, p3)
        assertNull("段數不符", recapSegmentsFrom(stops, listOf(line(p1, p2))))
        assertNull("有一段沒查到", recapSegmentsFrom(stops, listOf(line(p1, p2), emptyList())))
        assertNull("站改過、折線是舊的", recapSegmentsFrom(stops, listOf(line(p1, p3), line(p3, p2))))
    }

    @Test
    fun downsample_keepsEndsAndCap() {
        val seg = (0 until 500).map { listOf(it.toDouble(), 0.0) }
        val out = downsampleRoute(seg, 120)
        assertEquals(120, out.size)
        assertEquals(seg.first(), out.first())
        assertEquals(seg.last(), out.last())
    }

    @Test
    fun routeGeometry_v2RoundTripAndRules() {
        val segs = listOf(line(p1, p2), line(p2, p3))
        val doc = routeGeometryToFirestore("p1|p2|p3", segs, 1L)
        assertEquals(2, doc["v"])
        // 每段攤平成 [lat, lng, ...]，沒有陣列裡的陣列（Firestore 才收）
        @Suppress("UNCHECKED_CAST")
        val first = ((doc["segments"] as List<Map<String, Any>>)[0]["points"] as List<Any>)
        assertTrue(first.all { it is Double })
        assertEquals(segs, routeGeometryFromFirestore(doc, "p1|p2|p3", 2))
        // Firestore 讀回來數字是 Long／Double 混著
        val asRead = doc + ("v" to 2L)
        assertEquals(segs, routeGeometryFromFirestore(asRead, "p1|p2|p3", 2))
        assertNull("sig 不符", routeGeometryFromFirestore(doc, "p1|p3", 2))
        assertNull("段數不符", routeGeometryFromFirestore(doc, "p1|p2|p3", 3))
        assertNull("不是 complete", routeGeometryFromFirestore(doc + ("complete" to false), "p1|p2|p3", 2))
        assertNull("舊格式（陣列裡的陣列）", routeGeometryFromFirestore(mapOf("sig" to "p1|p2|p3", "segments" to segs), "p1|p2|p3", 2))
    }
}
