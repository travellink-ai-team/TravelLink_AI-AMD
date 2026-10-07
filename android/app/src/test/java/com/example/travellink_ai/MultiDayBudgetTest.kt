package com.example.travellink_ai

import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.ui.planning.DayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5：多日行程的「可用時間」必須是各日時間窗的總和。
 *
 * 這條是從實測行程 my_1786436822737 回推出來的迴歸測試：兩天一夜
 * （08/13 10:00 → 08/14 12:00）當時被算成 120 分鐘，於是 prompt 告訴 AI
 * 「約 3 站」，整趟兩天只生出 6 個景點、第 2 天只有 1 站。
 */
class MultiDayBudgetTest {

    /** 與 buildPass1Prompt 內同一套算式 */
    private fun availableMins(start: String, end: String, islandCode: String? = null): Int {
        val windows = DayPlanner.buildDayWindows(start, end)
        val island = islandCode?.let { code -> IslandRegistry.all.first { it.code == code } }
        if (windows.size <= 1) {
            val s = DayPlanner.parseHhMm(start.substringAfter(" ")) ?: 540
            val e = DayPlanner.parseHhMm(end.substringAfter(" ")) ?: 1080
            return e - s
        }
        return windows.sumOf { w ->
            val extra = island?.let {
                (if (w.dayIndex == 1) it.sailingMins else 0) +
                    (if (w.dayIndex == windows.size) it.sailingMins + IslandRegistry.BOARDING_BUFFER_MINS else 0)
            } ?: 0
            (w.lengthMins - extra).coerceAtLeast(0)
        }
    }

    @Test
    fun `單日仍是頭尾相減`() {
        assertEquals(8 * 60, availableMins("2026/08/13 09:00", "2026/08/13 17:00"))
    }

    @Test
    fun `兩天一夜不會被算成兩小時`() {
        // 舊算式：12:00 − 10:00 = 120 分（錯）
        val mins = availableMins("2026/08/13 10:00", "2026/08/14 12:00")
        // 第 1 天 10:00–18:00 = 480、第 2 天 09:00–12:00 = 180
        assertEquals(480 + 180, mins)
        assertTrue("兩天一夜的可用時間不該少於一個整日", mins > 8 * 60)
    }

    @Test
    fun `離島兩天一夜要扣掉來回航程與登船緩衝`() {
        val mins = availableMins("2026/08/13 10:00", "2026/08/14 12:00", islandCode = "ludao")
        // 第 1 天 480 − 上島 50 = 430；第 2 天 180 − (回程 50 + 緩衝 40) = 90
        assertEquals(430 + 90, mins)
    }

    @Test
    fun `三天兩夜的中間日整天都算進去`() {
        val mins = availableMins("2026/08/13 09:00", "2026/08/15 15:00")
        // 09:00–18:00、09:00–18:00、09:00–15:00
        assertEquals(540 + 540 + 360, mins)
    }
}
