package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import com.example.travellink_ai.ui.trip.CheckInRecord
import com.example.travellink_ai.ui.trip.LeaveRecord
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.deriveTripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 行程進行中調整「預計離開時間」：後續站的預計時間跟著順延，原規劃（Stop.duration）不動。
 */
class LeaveTimeProgressTest {

    private fun stop(name: String, order: Long, time: String, duration: Long = 60L, day: Int = 1) =
        Stop(name = name, time = time, desc = "", emoji = "📍",
             duration = duration, order = order, stopId = "id_$name", dayIndex = day)

    private fun todayAt(hh: Int, mm: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // A 09:00 停 60 → 車程 → B 10:30 停 60 → C 12:00
    private val stops = listOf(
        stop("A", 1, "09:00"),
        stop("B", 2, "10:30"),
        stop("C", 3, "12:00")
    )

    @Test
    fun `沒調離開時間時和原本只看打卡的順延一致`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 20)))  // 晚到 20 分
        )
        val d = deriveTripState(stops, progress, emptyList())
        assertEquals(10 * 60 + 50, d.estimatedTimes["id_B"])
        assertEquals(12 * 60 + 20, d.estimatedTimes["id_C"])
        val here = d.here!!
        assertEquals("id_A", here.stop.stopId)
        assertEquals(9 * 60 + 20, here.arrivedMin)
        assertEquals(10 * 60 + 20, here.leaveMin)       // 到達 + 原定停留 60
        assertFalse(here.isAdjusted)
    }

    @Test
    fun `延後離開會順延後續站，原定停留不變`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(10, 30)))   // 多待 30 分
        )
        val d = deriveTripState(stops, progress, emptyList())
        assertEquals(11 * 60, d.estimatedTimes["id_B"])
        assertEquals(12 * 60 + 30, d.estimatedTimes["id_C"])
        assertEquals(30, d.delayMin)
        assertTrue(d.here!!.isAdjusted)
        assertEquals(10 * 60, d.here!!.defaultLeaveMin)
        assertEquals(60L, stops[0].duration)
    }

    @Test
    fun `提早離開會讓後續站提前`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(9, 40)))    // 少待 20 分
        )
        val d = deriveTripState(stops, progress, emptyList())
        assertEquals(10 * 60 + 10, d.estimatedTimes["id_B"])
        assertEquals(-20, d.delayMin)
    }

    @Test
    fun `預計離開不能早於到達`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 30))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(9, 0)))
        )
        val d = deriveTripState(stops, progress, emptyList())
        assertEquals(9 * 60 + 30, d.here!!.leaveMin)
    }

    @Test
    fun `打卡下一站後，上一站調過的離開時間不再影響`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf(
                "id_A" to CheckInRecord(at = todayAt(9, 0)),
                "id_B" to CheckInRecord(at = todayAt(10, 30))                // 準時到 B
            ),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(11, 0)))
        )
        val d = deriveTripState(stops, progress, emptyList())
        assertEquals("id_B", d.here!!.stop.stopId)
        assertEquals(12 * 60, d.estimatedTimes["id_C"])
    }

    @Test
    fun `還沒打卡時沒有你在這裡`() {
        val d = deriveTripState(stops, TripProgress(status = "ongoing"), emptyList())
        assertNull(d.here)
    }

    @Test
    fun `舊的進行中頁路由導到行程頁，子頁回來不改返回目標`() {
        val holder = ItineraryStateHolder()
        holder.currentScreen = "history"
        holder.currentScreen = "trip_progress"
        assertEquals("preview", holder.currentScreen)
        assertEquals("history", holder.previewReturnScreen)
        holder.currentScreen = "stop_detail"
        holder.currentScreen = "preview"
        assertEquals("history", holder.previewReturnScreen)
    }
}
