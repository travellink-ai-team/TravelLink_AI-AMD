package com.example.travellink_ai

import com.example.travellink_ai.data.local.Converters
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.dayCount
import com.example.travellink_ai.data.model.normalizeDaysField
import com.example.travellink_ai.data.model.parseDayIndex
import com.example.travellink_ai.data.model.stopsOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 Stage 1：多日行程資料模型。
 *
 * 重點在「舊資料不能被改變語意」——既有行程沒有 dayIndex 欄位，
 * 全部必須落在第 1 天（1-based，與網頁端一致）。
 * 不需要 Android Context 與 Firestore，可在 JVM 直接執行。
 */
class MultiDayModelTest {

    private val converters = Converters()

    private fun stop(name: String, order: Long, day: Int = 1) =
        Stop(name = name, time = "09:00", desc = "", emoji = "📍",
             duration = 60L, order = order, dayIndex = day)

    // ── StopDeserializer：舊 JSON 相容 ────────────────────────────

    @Test
    fun `舊行程 JSON 沒有 dayIndex 時視為第 1 天`() {
        val legacy = """
            [{"name":"三仙台","time":"09:00","desc":"","emoji":"📍",
              "duration":90,"order":1,"businessHours":"未提供"}]
        """.trimIndent()
        val stops = converters.toStopList(legacy)
        assertEquals(1, stops.size)
        assertEquals(1, stops[0].dayIndex)
        assertFalse(stops[0].isFerry)
        assertFalse(stops[0].isLodging)
    }

    @Test
    fun `dayIndex 為 0 的髒值正規化成 1`() {
        // 網頁端若某版本寫 0-based，讀進來不能變成「第 0 天」而多切一日
        val dirty = """
            [{"name":"鐵花村","time":"19:00","desc":"","emoji":"📍",
              "duration":60,"order":1,"dayIndex":0}]
        """.trimIndent()
        assertEquals(1, converters.toStopList(dirty)[0].dayIndex)
    }

    @Test
    fun `dayIndex 為 2 時原樣保留`() {
        val json = """
            [{"name":"利吉惡地","time":"09:00","desc":"","emoji":"📍",
              "duration":48,"order":11,"dayIndex":2}]
        """.trimIndent()
        assertEquals(2, converters.toStopList(json)[0].dayIndex)
    }

    @Test
    fun `新欄位可以完整往返序列化`() {
        val original = listOf(
            stop("富岡漁港", 1).copy(isFerry = true),
            stop("南寮民宿", 2).copy(isLodging = true),
            stop("朝日溫泉", 3, day = 2)
        )
        val restored = converters.toStopList(converters.fromStopList(original))
        assertTrue(restored[0].isFerry)
        assertTrue(restored[1].isLodging)
        assertEquals(2, restored[2].dayIndex)
    }

    // ── dayCount / stopsOfDay ────────────────────────────────────

    @Test
    fun `單日行程的 dayCount 為 1`() {
        assertEquals(1, listOf(stop("A", 1), stop("B", 2)).dayCount)
    }

    @Test
    fun `兩天一夜的 dayCount 為 2`() {
        val stops = listOf(stop("A", 1), stop("B", 2), stop("C", 3, day = 2))
        assertEquals(2, stops.dayCount)
        assertEquals(listOf("A", "B"), stops.stopsOfDay(1).map { it.name })
        assertEquals(listOf("C"), stops.stopsOfDay(2).map { it.name })
    }

    @Test
    fun `空行程的 dayCount 為 1 而不是 0`() {
        assertEquals(1, emptyList<Stop>().dayCount)
    }

    // ── parseDayIndex：Firestore map ─────────────────────────────

    @Test
    fun `Firestore stop 沒有 dayIndex 時回 1`() {
        assertEquals(1, parseDayIndex(mapOf("name" to "台東車站")))
    }

    @Test
    fun `Firestore stop 的 dayIndex 是 Long 也能讀`() {
        // Firestore 數字一律回 Long，不是 Int
        assertEquals(2, parseDayIndex(mapOf("dayIndex" to 2L)))
    }

    // ── normalizeDaysField ──────────────────────────────────────

    @Test
    fun `appDays 存在時優先採用`() {
        val result = normalizeDaysField(
            appDays = "2026/05/18 09:00 - 2026/05/18 17:00",
            rawDays = "8小時",
            wizard  = mapOf("departureDate" to "2026-07-21")
        )
        assertEquals("2026/05/18 09:00 - 2026/05/18 17:00", result)
    }

    @Test
    fun `網頁端兩天行程由 wizardData 補成 App 格式`() {
        // 取自實際文件 my_1784460114502
        val result = normalizeDaysField(
            appDays = null,
            rawDays = "2天",
            wizard  = mapOf(
                "departureDate" to "2026-07-21",
                "returnDate"    to "2026-07-22",
                "startTime"     to "09:00",
                "day1Hours"     to 8L,
                "day2EndTime"   to "12:00"
            )
        )
        assertEquals("2026/07/21 09:00 - 2026/07/22 12:00", result)
    }

    @Test
    fun `網頁端單日行程由 day1Hours 推算結束時間`() {
        val result = normalizeDaysField(
            appDays = null,
            rawDays = "8小時",
            wizard  = mapOf(
                "departureDate" to "2026-07-21",
                "startTime"     to "09:00",
                "day1Hours"     to 8L
            )
        )
        assertEquals("2026/07/21 09:00 - 2026/07/21 17:00", result)
    }

    @Test
    fun `沒有 wizardData 時保留原值`() {
        assertEquals("8小時", normalizeDaysField(null, "8小時", null))
    }

    @Test
    fun `departureDate 格式不對時保留原值不猜日期`() {
        val result = normalizeDaysField(
            appDays = null,
            rawDays = "2天",
            wizard  = mapOf("departureDate" to "7/21", "returnDate" to "7/22")
        )
        assertEquals("2天", result)
    }

    @Test
    fun `結束時間不會跨過午夜`() {
        // 起始 20:00 + 8 小時會超過 24:00，夾在 23:59 而不是變成隔天 04:00
        val result = normalizeDaysField(
            appDays = null,
            rawDays = "8小時",
            wizard  = mapOf(
                "departureDate" to "2026-07-21",
                "startTime"     to "20:00",
                "day1Hours"     to 8L
            )
        )
        assertEquals("2026/07/21 20:00 - 2026/07/21 23:59", result)
    }
}
