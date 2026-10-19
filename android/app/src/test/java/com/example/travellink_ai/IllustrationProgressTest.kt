package com.example.travellink_ai

import com.example.travellink_ai.data.model.IllustrationStopBoxes
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.hitTest
import com.example.travellink_ai.ui.trip.CheckInRecord
import com.example.travellink_ai.ui.trip.MAX_RATIO_BEFORE_ARRIVAL
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.computeIllustrationPosition
import com.example.travellink_ai.ui.trip.deriveTripState
import com.example.travellink_ai.ui.trip.ratioAlongPath
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 互動插圖：站框解析、點擊命中、行程進行中「我」在插圖上的進度 */
class IllustrationProgressTest {

    private fun stop(name: String, order: Long, time: String, duration: Long = 60L, station: Boolean = false) =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = duration, order = order,
             stopId = "id_$name", isStation = station, lat = 22.75 + order * 0.01, lng = 121.15)

    private fun todayAt(hh: Int, mm: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // 車站 08:30 出發 →(30 分)→ A 09:00 停 60 →(30 分)→ B 10:30 停 60 →(30 分)→ 車站 12:00
    private val stops = listOf(
        stop("S", 0, "08:30", duration = 0, station = true),
        stop("A", 1, "09:00"),
        stop("B", 2, "10:30"),
        stop("R", 3, "12:00", duration = 0, station = true)
    )
    private val transit = listOf(30L, 30L, 30L)
    private fun coord(s: Stop) = LatLng(s.lat!!, s.lng!!)

    private fun position(progress: TripProgress, nowMs: Long, loc: LatLng? = null) =
        computeIllustrationPosition(
            stops, progress, deriveTripState(stops, progress, transit), nowMs, loc,
            coordOf = ::coord, roadSegments = emptyList(), transitTimes = transit
        )

    @Test
    fun `站框從 0～1000 換成比例，壞資料略過`() {
        val parsed = IllustrationStopBoxes.fromMap(mapOf(
            "imageUrl" to "https://x",
            "boxes" to listOf(
                mapOf("stopId" to "a", "name" to "A", "box" to listOf(100L, 200L, 300L, 400L)),
                mapOf("stopId" to "b", "box" to listOf(500, 500, 400, 600)),   // ymax < ymin
                mapOf("stopId" to "c", "box" to listOf(1, 2, 3))                // 少一個
            )
        ))!!
        assertEquals(1, parsed.boxes.size)
        val a = parsed.boxes[0]
        assertEquals(0.1f, a.top, 1e-6f); assertEquals(0.2f, a.left, 1e-6f)
        assertEquals(0.3f, a.bottom, 1e-6f); assertEquals(0.4f, a.right, 1e-6f)
        assertNull(IllustrationStopBoxes.fromMap(mapOf("boxes" to emptyList<Any>())))
    }

    @Test
    fun `有路線標記位置時小點錨在標記上，沒有就用框中心`() {
        val parsed = IllustrationStopBoxes.fromMap(mapOf("imageUrl" to "u", "boxes" to listOf(
            mapOf("stopId" to "a", "box" to listOf(100, 100, 300, 500), "point" to listOf(120L, 150L)),
            mapOf("stopId" to "b", "box" to listOf(100, 100, 300, 500), "point" to listOf(1, 2, 3))
        )))!!.boxes
        assertEquals(0.15f, parsed[0].anchorX, 1e-6f); assertEquals(0.12f, parsed[0].anchorY, 1e-6f)
        assertEquals(0.3f, parsed[1].anchorX, 1e-6f); assertEquals(0.2f, parsed[1].anchorY, 1e-6f)
    }

    @Test
    fun `點擊落在重疊框內取小框，沒點中但很近取最近的`() {
        val boxes = IllustrationStopBoxes.fromMap(mapOf("imageUrl" to "u", "boxes" to listOf(
            mapOf("stopId" to "big", "box" to listOf(0, 0, 500, 500)),
            mapOf("stopId" to "small", "box" to listOf(100, 100, 200, 200)),
            mapOf("stopId" to "far", "box" to listOf(800, 800, 900, 900))
        )))!!.boxes
        assertEquals("small", boxes.hitTest(0.15f, 0.15f)?.stopId)
        assertEquals("big", boxes.hitTest(0.4f, 0.4f)?.stopId)
        assertEquals("far", boxes.hitTest(0.92f, 0.92f)?.stopId)
        assertNull(boxes.hitTest(0.7f, 0.2f))
    }

    @Test
    fun `起點等於終點共用一個框時，進行中點下去開要去的那站`() {
        val start = stops[0]; val end = stops[0].copy(stopId = "id_end", order = 9)
        val cands = listOf(start, end)
        assertEquals("id_S", com.example.travellink_ai.ui.planning.pickSharedStop(cands, null, emptySet())?.stopId)
        val heading = com.example.travellink_ai.ui.trip.IllustrationPosition(fromStop = stops[2], toStop = end, ratio = 0.3f)
        assertEquals("id_end", com.example.travellink_ai.ui.planning.pickSharedStop(cands, heading, setOf("id_A"))?.stopId)
        // 沒有指定的站時開還沒走過的
        val elsewhere = com.example.travellink_ai.ui.trip.IllustrationPosition(atStop = stops[1])
        assertEquals("id_end", com.example.travellink_ai.ui.planning.pickSharedStop(cands, elsewhere, setOf("id_S"))?.stopId)
    }

    @Test
    fun `投影到折線上的比例`() {
        val path = listOf(LatLng(22.0, 121.0), LatLng(22.0, 121.01), LatLng(22.01, 121.01))
        assertEquals(0f, ratioAlongPath(path, LatLng(21.999, 120.999))!!, 0.01f)
        // 第一段的中點，略偏離路線也一樣
        val mid = ratioAlongPath(path, LatLng(22.0003, 121.005))!!
        assertTrue(mid in 0.2f..0.3f)
        assertEquals(1f, ratioAlongPath(path, LatLng(22.02, 121.01))!!, 0.01f)
        assertNull(ratioAlongPath(listOf(LatLng(22.0, 121.0)), LatLng(22.0, 121.0)))
    }

    @Test
    fun `沒開始行程不顯示`() {
        assertNull(position(TripProgress(), todayAt(9, 0)))
    }

    @Test
    fun `打卡後在停留時間內就是在站上`() {
        val p = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))))
        assertEquals("id_A", position(p, todayAt(9, 30))!!.atStop?.stopId)
    }

    @Test
    fun `沒 GPS 時用離站後經過時間除以車程，最多 95 趴`() {
        val p = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))))
        // 預計 10:00 離開 A，車程 30 分；10:15 → 一半
        val half = position(p, todayAt(10, 15))!!
        assertNull(half.atStop)
        assertEquals("id_A", half.fromStop?.stopId)
        assertEquals("id_B", half.toStop?.stopId)
        assertEquals(0.5f, half.ratio, 0.01f)
        // 遠超過車程也停在 95%，等打卡才到站
        assertEquals(MAX_RATIO_BEFORE_ARRIVAL, position(p, todayAt(11, 30))!!.ratio, 1e-6f)
    }

    @Test
    fun `還沒打任何卡時從出發車站往第一站`() {
        val p = TripProgress(status = "ongoing")
        val pos = position(p, todayAt(8, 45))!!
        assertEquals("id_S", pos.fromStop?.stopId)
        assertEquals("id_A", pos.toStop?.stopId)
        assertEquals(0.5f, pos.ratio, 0.01f)
    }

    @Test
    fun `GPS 離開站超過 500 公尺就算移動中，進到下一站 300 公尺內算抵達`() {
        val p = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))))
        val a = coord(stops[1]); val b = coord(stops[2])
        // 停留時間還沒到，但人已經在 A、B 中間
        val moving = position(p, todayAt(9, 20), LatLng((a.latitude + b.latitude) / 2, a.longitude))!!
        assertNull(moving.atStop)
        assertTrue(moving.byGps)
        assertEquals(0.5f, moving.ratio, 0.05f)
        val arrived = position(p, todayAt(9, 40), LatLng(b.latitude - 0.001, b.longitude))!!
        assertTrue(arrived.arrived)
        assertEquals(1f, arrived.ratio, 1e-6f)
    }

    @Test
    fun `跳過的站不當出發點，車程把跳過那段也算進去`() {
        val p = TripProgress(status = "ongoing", skips = setOf("id_A"))
        val pos = position(p, todayAt(8, 45))!!
        assertEquals("id_S", pos.fromStop?.stopId)
        assertEquals("id_B", pos.toStop?.stopId)
        // 08:30 出發，S→A→B 車程 60 分；08:45 → 25%
        assertEquals(0.25f, pos.ratio, 0.01f)
    }

    @Test
    fun `景點都完成後往回程車站`() {
        val p = TripProgress(status = "ongoing", checkIns = mapOf(
            "id_A" to CheckInRecord(at = todayAt(9, 0)), "id_B" to CheckInRecord(at = todayAt(10, 30))))
        val pos = position(p, todayAt(11, 45))!!
        assertNotNull(pos.toStop)
        assertEquals("id_R", pos.toStop?.stopId)
        assertEquals("id_B", pos.fromStop?.stopId)
    }
}
