package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.parseImageDays
import com.example.travellink_ai.ui.planning.PosterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多日插圖 imageDays：與網頁相同的讀取規則、每天標題下的時間 */
class IllustrationDaysTest {

    private fun day(d: Int, url: String, boxesUrl: String? = url) = mapOf(
        "day" to d.toLong(), "imageUrl" to url,
        "imageStopBoxes" to mapOf("imageUrl" to boxesUrl, "model" to "web-layout", "at" to 1L,
            "boxes" to listOf(mapOf("stopId" to "s$d", "box" to listOf(100, 100, 200, 200), "point" to listOf(150, 150))))
    )

    @Test
    fun `第 1 天和 imageUrl 一致才算每日插圖`() {
        val raw = listOf(day(2, "https://b"), day(1, "https://a"))
        val days = parseImageDays(raw, "https://a")
        assertEquals(listOf(1, 2), days.map { it.day })
        assertEquals("s2", days[1].boxes?.boxes?.single()?.stopId)
        assertEquals(2L, days[1].raw["day"])          // 原樣保留，只重畫一天時寫回
    }

    @Test
    fun `之後有人只畫了單張（imageUrl 換了）就退回單張`() {
        assertTrue(parseImageDays(listOf(day(1, "https://a"), day(2, "https://b")), "https://new").isEmpty())
    }

    @Test
    fun `第 1 天沒畫成只有其他天時照樣算數`() {
        assertEquals(listOf(2), parseImageDays(listOf(day(2, "https://b")), "https://old-single").map { it.day })
    }

    @Test
    fun `站框不是這張圖的就不用，非 https 的圖略過`() {
        val days = parseImageDays(listOf(day(1, "https://a", boxesUrl = "https://other"), day(2, "http://x")), "https://a")
        assertEquals(listOf(1), days.map { it.day })
        assertNull(days[0].boxes)
    }

    @Test
    fun `每天標題下的時間＝日期＋第一站到最後一站離開`() {
        fun stop(t: String, dur: Long) = Stop(name = "x", time = t, desc = "", emoji = "", duration = dur, order = 0)
        assertEquals("2026/10/18 09:00–18:30",
            PosterLayout.dayRange("2026/10/18", listOf(stop("09:00", 60), stop("2026/10/18 17:00", 90))))
        assertEquals("09:00–10:00", PosterLayout.dayRange(null, listOf(stop("09:00", 60))))
    }
}
