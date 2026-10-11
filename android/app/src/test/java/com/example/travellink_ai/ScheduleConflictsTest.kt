package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.trip.CheckInRecord
import com.example.travellink_ai.ui.trip.ConflictKind
import com.example.travellink_ai.ui.trip.LeaveRecord
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.deriveTripState
import com.example.travellink_ai.ui.trip.detectScheduleConflicts
import com.example.travellink_ai.ui.trip.ferryConflict
import com.example.travellink_ai.ui.trip.trainConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 第 4 項：用預計時間（含調過的預計離開）檢查固定限制 */
class ScheduleConflictsTest {

    private fun stop(name: String, order: Long, time: String, duration: Long = 60L,
                     hours: String = "未提供", station: Boolean = false) =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = duration, order = order,
             businessHours = hours, isStation = station, stopId = "id_$name")

    private fun todayAt(hh: Int, mm: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // A 13:00 停 60 → B 14:30 停 60（營業到 16:00）→ 回程車站 16:00
    private val stops = listOf(
        stop("A", 1, "13:00", hours = "09:00 - 18:00"),
        stop("B", 2, "14:30", hours = "09:00 - 16:00"),
        stop("站", 3, "16:00", station = true)
    )

    private fun conflicts(progress: TripProgress, dayEnd: Int? = null) =
        detectScheduleConflicts(stops, progress, deriveTripState(stops, progress, emptyList()), "", dayEnd)

    @Test
    fun `照原定時間沒有衝突`() {
        val p = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(13, 0))))
        assertTrue(conflicts(p).isEmpty())
    }

    @Test
    fun `多待讓下一站停留期間打烊，並算出要提早多久`() {
        val p = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(13, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(14, 45)))   // 多待 45 → B 15:15 到
        )
        val c = conflicts(p).single()
        assertEquals(ConflictKind.CLOSES_DURING_VISIT, c.kind)
        assertEquals("id_B", c.stopId)
        assertEquals(15, c.shortenMin)   // 15:15 + 60 = 16:15，超過 16:00 共 15 分
    }

    @Test
    fun `到的時候已經打烊`() {
        val p = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(13, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(16, 0)))    // B 16:30 到
        )
        assertEquals(ConflictKind.CLOSED_ON_ARRIVAL, conflicts(p).first { it.stopId == "id_B" }.kind)
    }

    @Test
    fun `目前站預計離開晚於打烊`() {
        val closing = listOf(stop("A", 1, "13:00", hours = "09:00 - 14:00"), stop("B", 2, "14:30"))
        val p = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(13, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(14, 20)))
        )
        val c = detectScheduleConflicts(closing, p, deriveTripState(closing, p, emptyList()), "", null).single()
        assertEquals("id_A", c.stopId)
        assertEquals(20, c.shortenMin)
    }

    @Test
    fun `提早離開造成早到，提示可以多待`() {
        val late = listOf(stop("A", 1, "09:00"), stop("B", 2, "10:30", hours = "10:30 - 17:00"))
        val p = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(9, 40)))    // B 10:10 到
        )
        val c = detectScheduleConflicts(late, p, deriveTripState(late, p, emptyList()), "", null).single()
        assertEquals(ConflictKind.EARLY_ARRIVAL, c.kind)
        assertEquals(20, c.extendMin)
        assertTrue(c.isInfo)
    }

    @Test
    fun `收工時間超過原定才提醒`() {
        val p = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(13, 0))),
            leaveAt = mapOf("id_A" to LeaveRecord(at = todayAt(14, 30)))   // 全部延後 30 分
        )
        val overrun = conflicts(p, dayEnd = 16 * 60).first { it.kind == ConflictKind.DAY_OVERRUN }
        assertEquals(30, overrun.shortenMin)
    }

    @Test
    fun `末班船與回程火車`() {
        assertNull(ferryConflict(arriveMin = 16 * 60, lastDepartMin = 16 * 60 + 30, portName = "南寮漁港"))
        assertEquals(20, ferryConflict(17 * 60 + 20, 17 * 60, "南寮漁港")!!.shortenMin)
        assertNull(trainConflict(arriveMin = 16 * 60, departMin = 16 * 60 + 40, delayMin = 0, trainLabel = "自強"))
        assertEquals(10, trainConflict(16 * 60, 16 * 60 + 5, 0, "自強")!!.shortenMin)
    }
}
