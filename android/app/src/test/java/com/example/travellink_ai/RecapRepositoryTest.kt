package com.example.travellink_ai

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.recap.RecapRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * buildTrip 的純邏輯測試（過濾、mode 映射、距離）。
 * db/auth/database 用 mock —— buildTrip 不碰它們。
 * buildPhotos（讀 Firestore）與 buildRoutePoints（PolyUtil/LatLng 依賴 Android）不在此測。
 */
class RecapRepositoryTest {

    private val repo = RecapRepository(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

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
}
