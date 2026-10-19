package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.trip.CheckInRecord
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.deriveTripState
import com.example.travellink_ai.ui.trip.overnightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 多日行程兩天之間：今天的景點都完成、下一天第一站前 30 分才自動開始 */
class OvernightTest {

    private fun stop(name: String, day: Int, time: String, lodging: Boolean = false) =
        Stop(name = name, time = time, desc = "", emoji = "", duration = 60, order = 0,
             stopId = "id_$name", dayIndex = day, isLodging = lodging)

    private val stops = listOf(
        stop("A", 1, "10:00"), stop("B", 1, "13:00"), stop("民宿", 1, "17:00", lodging = true),
        stop("民宿2", 2, "09:00", lodging = true), stop("C", 2, "09:30"), stop("D", 2, "12:00")
    )

    private fun at(date: String, hhmm: String): Long =
        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).parse("$date $hhmm")!!.time

    private fun day1Done(extra: Map<String, Long> = emptyMap()) = TripProgress(
        status = "ongoing",
        checkIns = listOf("A", "B", "民宿").associate { "id_$it" to CheckInRecord(at = at("2026/10/22", "17:00")) },
        dayStartedAt = extra.mapKeys { it.key.toInt() }
    )

    private fun state(p: TripProgress, now: Long) =
        overnightState(stops, p, deriveTripState(stops, p, emptyList()), now, "2026/10/22")

    @Test
    fun `第 1 天都打卡後到隔天第一站前 30 分之間是休息狀態`() {
        val o = state(day1Done(), at("2026/10/22", "20:00"))
        assertNotNull(o)
        o!!
        assertEquals(1, o.finishedDay)
        assertEquals(2, o.nextDay)
        assertEquals(at("2026/10/23", "09:00"), o.nextStartMs)
        assertEquals(at("2026/10/23", "08:30"), o.switchAtMs)
        assertEquals("C", o.nextFirst.name)          // 不是住宿的第一個景點
        assertEquals(2, o.nextDayCount)
        assertEquals(3, o.doneCount)
        assertEquals("民宿", o.lodging?.name)
    }

    @Test
    fun `第一站前 30 分自動開始下一天`() {
        assertNotNull(state(day1Done(), at("2026/10/23", "08:29")))
        assertNull(state(day1Done(), at("2026/10/23", "08:30")))
    }

    @Test
    fun `有人按了提早開始，或下一天已經打卡，就不是休息狀態`() {
        assertNull(state(day1Done(mapOf("2" to at("2026/10/22", "21:00"))), at("2026/10/22", "21:01")))
        val p = day1Done().let { it.copy(checkIns = it.checkIns + ("id_民宿2" to CheckInRecord(at = at("2026/10/22", "21:00")))) }
        assertNull(state(p, at("2026/10/22", "21:05")))
    }

    @Test
    fun `今天還有沒走完的站、單日行程、還沒出發都不算`() {
        val partial = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = at("2026/10/22", "10:00"))))
        assertNull(state(partial, at("2026/10/22", "20:00")))
        val single = listOf(stop("A", 1, "10:00"), stop("B", 1, "13:00"))
        val p = TripProgress(status = "ongoing", checkIns = mapOf("id_A" to CheckInRecord(at = 1L), "id_B" to CheckInRecord(at = 2L)))
        assertNull(overnightState(single, p, deriveTripState(single, p, emptyList()), at("2026/10/22", "20:00"), "2026/10/22"))
        assertNull(state(day1Done().copy(status = ""), at("2026/10/22", "20:00")))
    }
}
