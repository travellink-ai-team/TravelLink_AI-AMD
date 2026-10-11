package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.trip.CheckInRecord
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.deriveTripState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * A5 Stage 3：多日行程的落後推算不跨夜。
 *
 * delay 是拿「當日分鐘數」相減算的，若讓它跨日繼承，第一天晚收工 40 分會讓
 * 第二天每一站都平白多 40 分；跳過站省下的時間同理也不該讓隔天提前。
 */
class CrossDayProgressTest {

    private fun stop(name: String, order: Long, time: String, day: Int, duration: Long = 60L) =
        Stop(name = name, time = time, desc = "", emoji = "📍",
             duration = duration, order = order, stopId = "id_$name", dayIndex = day)

    /** 今天的 HH:mm → epoch ms（deriveTripState 只取當日分鐘數，日期不影響結果） */
    private fun todayAt(hh: Int, mm: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val twoDayStops = listOf(
        stop("D1A", 1, "09:00", day = 1),
        stop("D1B", 2, "14:00", day = 1),
        stop("D2A", 3, "09:00", day = 2),
        stop("D2B", 4, "11:00", day = 2)
    )

    @Test
    fun `第一天落後不會累加到第二天的預估`() {
        // 第一天最後一站原訂 14:00，實際 14:40 才打卡 → 落後 40 分
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf(
                "id_D1A" to CheckInRecord(at = todayAt(9, 0)),
                "id_D1B" to CheckInRecord(at = todayAt(14, 40))
            )
        )
        val d = deriveTripState(twoDayStops, progress, emptyList())
        // 第二天的站維持原訂時間，不繼承昨天的 40 分
        assertEquals(9 * 60, d.estimatedTimes["id_D2A"])
        assertEquals(11 * 60, d.estimatedTimes["id_D2B"])
    }

    @Test
    fun `同一天內的落後照常順延`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_D1A" to CheckInRecord(at = todayAt(9, 30)))  // 落後 30
        )
        val d = deriveTripState(twoDayStops, progress, emptyList())
        assertEquals(14 * 60 + 30, d.estimatedTimes["id_D1B"])   // 同一天 → 順延
        assertEquals(9 * 60, d.estimatedTimes["id_D2A"])         // 跨夜 → 歸零
    }

    @Test
    fun `錨點在昨天時顯示用的落後歸零`() {
        // 第一天全部完成（最後一次打卡落後 40 分），目前站已是第二天
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf(
                "id_D1A" to CheckInRecord(at = todayAt(9, 0)),
                "id_D1B" to CheckInRecord(at = todayAt(14, 40))
            )
        )
        val d = deriveTripState(twoDayStops, progress, emptyList())
        assertEquals(2, d.currentDayIndex)
        assertEquals(0, d.delayMin)   // 隔了一晚，昨天的落後不算數
    }

    @Test
    fun `第一天跳過的站不會讓第二天提前`() {
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_D1A" to CheckInRecord(at = todayAt(9, 0))),
            skips = setOf("id_D1B")   // 跳過第一天第二站（停留 60 分）
        )
        val d = deriveTripState(twoDayStops, progress, listOf(0L, 15L, 15L, 15L))
        assertEquals(9 * 60, d.estimatedTimes["id_D2A"])
        assertEquals(11 * 60, d.estimatedTimes["id_D2B"])
    }

    @Test
    fun `單日行程的行為完全不變`() {
        val singleDay = listOf(
            stop("A", 1, "09:00", day = 1),
            stop("B", 2, "11:00", day = 1),
            stop("C", 3, "14:00", day = 1)
        )
        val progress = TripProgress(
            status = "ongoing",
            checkIns = mapOf("id_A" to CheckInRecord(at = todayAt(9, 25)))  // 落後 25
        )
        val d = deriveTripState(singleDay, progress, emptyList())
        assertEquals(1, d.dayCount)
        assertEquals(1, d.currentDayIndex)
        assertEquals(25, d.delayMin)
        assertEquals(11 * 60 + 25, d.estimatedTimes["id_B"])
        assertEquals(14 * 60 + 25, d.estimatedTimes["id_C"])
    }

    @Test
    fun `dayCount 與 currentDayIndex 正確反映進度`() {
        val progress = TripProgress(status = "ongoing")
        val d = deriveTripState(twoDayStops, progress, emptyList())
        assertEquals(2, d.dayCount)
        assertEquals(1, d.currentDayIndex)   // 還沒開始 → 第 1 天
    }
}
