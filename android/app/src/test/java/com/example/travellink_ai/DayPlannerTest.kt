package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.DayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 Stage 2：分日器。
 *
 * 最重要的一條是「單日行程不能被動到」——`splitIntoDays` 在單一時間窗時必須
 * 原封不動把所有站當一組回傳，行為與改動前一致。
 */
class DayPlannerTest {

    private fun stop(name: String, order: Long, duration: Long = 60L, day: Int = 1) =
        Stop(name = name, time = "00:00", desc = "", emoji = "📍",
             duration = duration, order = order, dayIndex = day)

    private fun stops(n: Int, duration: Long = 60L) =
        (1..n).map { stop("S$it", it.toLong(), duration) }

    // ── buildDayWindows ─────────────────────────────────────────

    @Test
    fun `起訖同日回傳單一時間窗`() {
        val w = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 17:00")
        assertEquals(1, w.size)
        assertEquals(9 * 60, w[0].startMins)
        assertEquals(17 * 60, w[0].endMins)
        assertEquals(1, w[0].dayIndex)
    }

    @Test
    fun `兩天一夜切出兩個時間窗且日期連續`() {
        val w = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 12:00")
        assertEquals(2, w.size)
        // 首日用使用者選的出發時間，尾端用預設 18:00
        assertEquals(9 * 60, w[0].startMins)
        assertEquals(DayPlanner.DEFAULT_DAY_END_MINS, w[0].endMins)
        // 末日用預設 09:00 起，結束時間用使用者選的
        assertEquals(DayPlanner.DEFAULT_DAY_START_MINS, w[1].startMins)
        assertEquals(12 * 60, w[1].endMins)
        assertEquals("2026/07/21", w[0].date)
        assertEquals("2026/07/22", w[1].date)
    }

    @Test
    fun `跨月的三天行程日期正確進位`() {
        val w = DayPlanner.buildDayWindows("2026/07/30 09:00", "2026/08/01 15:00")
        assertEquals(3, w.size)
        assertEquals(listOf("2026/07/30", "2026/07/31", "2026/08/01"), w.map { it.date })
    }

    @Test
    fun `日期格式壞掉時退回單日而不是產生負數天`() {
        val w = DayPlanner.buildDayWindows("7-21 09:00", "7-22 17:00")
        assertEquals(1, w.size)
    }

    @Test
    fun `結束日早於出發日時退回單日`() {
        assertEquals(1, DayPlanner.daysBetween("2026/07/22", "2026/07/21"))
    }

    // ── splitIntoDays：單日不介入 ────────────────────────────────

    @Test
    fun `單日行程原封不動回傳一組`() {
        val all = stops(10)   // 刻意超過 8 站
        val result = DayPlanner.splitIntoDays(all, DayPlanner.buildDayWindows(
            "2026/07/21 09:00", "2026/07/21 17:00"))
        assertEquals(1, result.byDay.size)
        assertEquals(all, result.byDay[0])
        assertTrue(result.dropped.isEmpty())
    }

    // ── splitIntoDays：容量切分 ─────────────────────────────────

    @Test
    fun `兩天 12 站依可用時間切開且每組不超過上限`() {
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val result = DayPlanner.splitIntoDays(stops(12), windows)
        assertEquals(2, result.byDay.size)
        result.byDay.forEach { assertTrue(it.size <= DayPlanner.MAX_STOPS_PER_DAY) }
        // 切完仍維持傳入的動線順序（連續區塊）
        assertEquals(
            stops(12).map { it.name },
            result.byDay.flatten().map { it.name } + result.dropped.map { it.name }
        )
    }

    @Test
    fun `站數超過總容量時多的站回報在 dropped 而不是靜默消失`() {
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val result = DayPlanner.splitIntoDays(stops(30), windows)
        assertTrue(result.dropped.isNotEmpty())
        assertEquals(30, result.byDay.flatten().size + result.dropped.size)
    }

    @Test
    fun `單站時間超過整日時仍會被放進某一天而不是全部落空`() {
        // duration 600 分 > 一日可用時間，不能因此讓每天都空著
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val result = DayPlanner.splitIntoDays(listOf(stop("超長", 1, duration = 600L)), windows)
        assertEquals(1, result.byDay.flatten().size)
        assertTrue(result.dropped.isEmpty())
    }

    // ── AI 標註的採用與否 ───────────────────────────────────────

    @Test
    fun `AI 標好且合格時直接沿用`() {
        val labelled = listOf(
            stop("A", 1, day = 1), stop("B", 2, day = 1),
            stop("C", 3, day = 2), stop("D", 4, day = 2)
        )
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val result = DayPlanner.splitIntoDays(labelled, windows)
        assertTrue(result.usedAiHint)
        assertEquals(listOf("A", "B"), result.byDay[0].map { it.name })
        assertEquals(listOf("C", "D"), result.byDay[1].map { it.name })
    }

    @Test
    fun `AI 漏標某一天時整份重切`() {
        // 全部都在第 1 天 → 第 2 天沒有站，等於少排一天
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val result = DayPlanner.splitIntoDays(stops(6), windows)
        assertFalse(result.usedAiHint)
    }

    @Test
    fun `AI 把某天塞超過上限時整份重切`() {
        val labelled = (1..9).map { stop("S$it", it.toLong(), day = 1) } +
            listOf(stop("X", 10, day = 2))
        assertFalse(DayPlanner.isUsableAiHint(labelled, dayCount = 2))
    }

    @Test
    fun `AI 標出超出天數範圍的日子時不採用`() {
        val labelled = listOf(stop("A", 1, day = 1), stop("B", 2, day = 3))
        assertFalse(DayPlanner.isUsableAiHint(labelled, dayCount = 2))
    }

    // ── 跨日銜接檢查 ────────────────────────────────────────────

    @Test
    fun `當日收工超過時間窗時回報超時分鐘數`() {
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val day1 = listOf(stop("晚收工", 1, duration = 60L).copy(time = "17:30"))  // 18:30 收工
        val day2 = listOf(stop("正常", 2, duration = 60L).copy(time = "10:00", dayIndex = 2))
        val overruns = DayPlanner.overrunMinutesByDay(listOf(day1, day2), windows)
        assertEquals(mapOf(1 to 30), overruns)
    }

    @Test
    fun `都在時間窗內時沒有超時回報`() {
        val windows = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 17:00")
        val day1 = listOf(stop("A", 1, duration = 60L).copy(time = "16:00"))
        val day2 = listOf(stop("B", 2, duration = 60L).copy(time = "10:00", dayIndex = 2))
        assertTrue(DayPlanner.overrunMinutesByDay(listOf(day1, day2), windows).isEmpty())
    }
}
