package com.example.travellink_ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.map.PolyUtil
import com.example.travellink_ai.ui.poster.RouteProjector
import com.example.travellink_ai.ui.poster.TripPosterData
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 海報資料層與投影的行為測試。
 *
 * 重點在**降級路徑**：實際行程的座標與折線常常不齊（舊行程、網頁端建立、
 * 編碼失敗），Phase 1 必須每一種都畫得出東西，不能只在資料完美時才work。
 */
@RunWith(AndroidJUnit4::class)
class PosterDataTest {

    private fun stop(name: String, order: Long, lat: Double? = null, lng: Double? = null) =
        Stop(
            name = name, time = "09:00", desc = "", emoji = "📍",
            duration = 60, order = order, lat = lat, lng = lng
        )

    private fun trip(
        stops: List<Stop>,
        encoded: String? = null,
        transit: String? = null
    ) = LocalItinerary(
        title = "台東三日", aiTitle = "", aiReply = "", region = "台東",
        days = "3", people = "2", stops = stops,
        roadSegmentsEncoded = encoded, transitTimesJson = transit
    )

    // ── TripPosterData 的降級判定 ────────────────────────────────

    @Test
    fun noCoordinates_degradesToNoGeometry() {
        val d = TripPosterData.from(trip(listOf(stop("鐵花村", 0), stop("三仙台", 1))))
        assertEquals(TripPosterData.Quality.NO_GEOMETRY, d.quality)
        assertTrue("無座標時不應宣稱有幾何", !d.hasGeometry)
        assertEquals("站點仍要保留供文字版面使用", 2, d.stops.size)
    }

    @Test
    fun coordinatesButNoPolyline_degradesToStraightLines() {
        val d = TripPosterData.from(
            trip(listOf(stop("鐵花村", 0, 22.75, 121.15), stop("三仙台", 1, 23.12, 121.41)))
        )
        assertEquals(TripPosterData.Quality.STRAIGHT_LINES, d.quality)
        assertTrue(d.hasGeometry)
        assertTrue("沒有折線可用", d.routeSegments.isEmpty())
    }

    /**
     * ★ 舊格式用 `|` 分隔，但 `|` 本身是 polyline 編碼字元，無法安全分割。
     * 必須整批放棄退化成直線，不可嘗試硬解（硬解會產生亂七八糟的折線）。
     */
    @Test
    fun legacyPipeSeparatedPolyline_isRejected() {
        val a = PolyUtil.encode(listOf(LatLng(22.75, 121.15), LatLng(22.80, 121.20)))
        val b = PolyUtil.encode(listOf(LatLng(22.80, 121.20), LatLng(23.12, 121.41)))

        val d = TripPosterData.from(
            trip(
                listOf(stop("A", 0, 22.75, 121.15), stop("B", 1, 23.12, 121.41)),
                encoded = "$a|$b"          // 舊格式
            )
        )
        assertEquals(TripPosterData.Quality.STRAIGHT_LINES, d.quality)
        assertTrue("舊格式必須整批放棄", d.routeSegments.isEmpty())
    }

    @Test
    fun newlineSeparatedPolyline_isDecoded() {
        val a = PolyUtil.encode(listOf(LatLng(22.75, 121.15), LatLng(22.80, 121.20)))
        val b = PolyUtil.encode(listOf(LatLng(22.80, 121.20), LatLng(23.12, 121.41)))

        val d = TripPosterData.from(
            trip(
                listOf(stop("A", 0, 22.75, 121.15), stop("B", 1, 23.12, 121.41)),
                encoded = "$a\n$b",
                transit = "12, 8"
            )
        )
        assertEquals(TripPosterData.Quality.FULL, d.quality)
        assertEquals(2, d.routeSegments.size)
        assertEquals("車程應加總", 20L, d.stats.totalTransitMins)
    }

    @Test
    fun stationStops_areExcluded() {
        val stops = listOf(
            stop("台東車站", 0, 22.79, 121.12).copy(isStation = true),
            stop("鐵花村", 1, 22.75, 121.15)
        )
        val d = TripPosterData.from(trip(stops))
        assertEquals("車站站點不進主視覺", 1, d.stops.size)
        assertEquals("鐵花村", d.stops[0].name)
        assertEquals(1, d.stats.stopCount)
    }

    @Test
    fun stats_haveFourEntries() {
        // 已定案：天數／景點數／總車程／地區
        val d = TripPosterData.from(trip(listOf(stop("A", 0))))
        assertEquals(4, d.stats.asPairs().size)
    }

    /**
     * ★ `LocalItinerary.days` 存的是日期範圍字串而非天數
     * （ItineraryViewModel 寫 `"$startDateTime - $endDateTime"`），
     * 直接拿來顯示會在海報上變成「2026/08/0… 行程」。
     */
    @Test
    fun dayCount_parsedFromDateRange() {
        val item = trip(listOf(stop("A", 0))).copy(days = "2026/08/09 09:00 - 2026/08/11 18:00")
        val d = TripPosterData.from(item)
        assertEquals(3, d.stats.dayCount)               // 含頭含尾
        assertEquals("3 天", d.stats.asPairs()[0].first)
    }

    @Test
    fun dayCount_singleDate_isOneDay() {
        val item = trip(listOf(stop("A", 0))).copy(days = "2026/08/09 09:00")
        assertEquals(1, TripPosterData.from(item).stats.dayCount)
    }

    @Test
    fun dayCount_unparseable_showsPlaceholder() {
        val item = trip(listOf(stop("A", 0))).copy(days = "三天兩夜")
        val d = TripPosterData.from(item)
        assertNull("解析不出來就不要猜", d.stats.dayCount)
        assertEquals("—", d.stats.asPairs()[0].first)   // 不拿日期字串充數
    }

    @Test
    fun transitTime_overAnHour_readsAsHours() {
        val item = trip(listOf(stop("A", 0)), transit = "60,45,40")   // 145 分
        assertEquals("2 小時 25 分", TripPosterData.from(item).stats.asPairs()[2].first)
    }

    // ── RouteProjector ─────────────────────────────────────────

    @Test
    fun projector_flipsYSoNorthIsUp() {
        val south = LatLng(22.75, 121.15)
        val north = LatLng(23.12, 121.15)
        val p = RouteProjector.fit(listOf(south, north), 0f, 0f, 1000f, 1000f)!!

        assertTrue(
            "緯度較高的點應該畫在畫布上方（y 較小）",
            p.project(north).y < p.project(south).y
        )
    }

    @Test
    fun projector_fitsInsideBox() {
        val pts = listOf(
            LatLng(22.75, 121.10), LatLng(23.12, 121.41), LatLng(22.90, 121.25)
        )
        val left = 100f; val top = 200f; val right = 900f; val bottom = 1200f
        val p = RouteProjector.fit(pts, left, top, right, bottom)!!

        pts.forEach {
            val pt = p.project(it)
            assertTrue("x=${pt.x} 超出框", pt.x >= left - 0.5f && pt.x <= right + 0.5f)
            assertTrue("y=${pt.y} 超出框", pt.y >= top - 0.5f && pt.y <= bottom + 0.5f)
        }
    }

    /**
     * ★ 這支是為了抓住實際發生過的 bug：`mercatorX` 回傳「度」而 `mercatorY`
     * 回傳弧度基底，兩者差 π/180，路線被橫向拉伸約 14 倍壓成一條水平線。
     *
     * 原本的測試只驗「有沒有超出框」與「南北順序」，這兩件事在錯誤比例下依然成立，
     * 所以完全沒發現。長寬比必須直接量。
     */
    @Test
    fun projector_preservesAspectRatio() {
        // 台東常見的南北向行程：緯度跨度是經度的約 3.7 倍
        val latSpan = 0.37
        val lngSpan = 0.10
        val base = LatLng(22.75, 121.10)
        val pts = listOf(base, LatLng(base.latitude + latSpan, base.longitude + lngSpan))

        // 給一個正方形框，讓投影自己決定長寬
        val p = RouteProjector.fit(pts, 0f, 0f, 1000f, 1000f)!!
        val a = p.project(pts[0])
        val b = p.project(pts[1])

        val drawnW = kotlin.math.abs(b.x - a.x)
        val drawnH = kotlin.math.abs(b.y - a.y)

        // 真實的 Mercator 長寬比：Δy/Δx ≈ (latSpan / cos(lat)) / lngSpan
        val expected = (latSpan / kotlin.math.cos(Math.toRadians(22.9))) / lngSpan
        val actual = drawnH / drawnW

        assertTrue(
            "長寬比錯誤：預期約 ${"%.2f".format(expected)}，實得 ${"%.2f".format(actual)}。" +
                "若接近 1/14 表示 mercatorX/mercatorY 單位不一致。",
            actual > expected * 0.85 && actual < expected * 1.15
        )
        assertTrue("南北向行程畫出來應該是直的（高 > 寬）", drawnH > drawnW)
    }

    @Test
    fun projector_singlePoint_doesNotCrash() {
        val p = RouteProjector.fit(listOf(LatLng(22.75, 121.15)), 0f, 0f, 100f, 100f)
        assertNotNull("單點應退化置中而非回 null", p)
        val pt = p!!.project(LatLng(22.75, 121.15))
        assertTrue("單點應落在框內", pt.x in 0f..100f && pt.y in 0f..100f)
    }

    @Test
    fun projector_emptyInput_returnsNull() {
        assertNull(RouteProjector.fit(emptyList(), 0f, 0f, 100f, 100f))
    }
}
