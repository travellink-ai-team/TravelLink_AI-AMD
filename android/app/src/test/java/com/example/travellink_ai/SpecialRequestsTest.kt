package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.DayPlanner
import com.example.travellink_ai.ui.planning.SpecialRequests
import com.example.travellink_ai.ui.planning.SunTimes
import com.example.travellink_ai.ui.planning.TimeRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 時間型特別需求（看日出／日落／夜景／看星星）。
 *
 * 最重要的兩條：沒有需求時時間窗必須原封不動（不能影響既有行程）；
 * 生成後的重算不能把日出那天拉回 09:00（否則整個安排白做）。
 */
class SpecialRequestsTest {

    private fun mins(h: Int, m: Int) = h * 60 + m

    private fun stop(name: String, time: String, order: Long, day: Int, duration: Long = 45L, station: Boolean = false) =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = duration,
            order = order, dayIndex = day, isStation = station)

    // ── 日出日落 ────────────────────────────────────────────────

    @Test
    fun `台東夏至日出日落在合理範圍`() {
        val (rise, set) = SunTimes.of("2026/06/21")!!
        assertTrue("日出 ${DayPlanner.hhmmOf(rise)}", rise in mins(5, 0)..mins(5, 20))
        assertTrue("日落 ${DayPlanner.hhmmOf(set)}", set in mins(18, 35)..mins(18, 55))
    }

    @Test
    fun `台東冬至日出日落在合理範圍`() {
        val (rise, set) = SunTimes.of("2026/12/21")!!
        assertTrue("日出 ${DayPlanner.hhmmOf(rise)}", rise in mins(6, 25)..mins(6, 45))
        assertTrue("日落 ${DayPlanner.hhmmOf(set)}", set in mins(17, 5)..mins(17, 25))
    }

    @Test
    fun `全年日出都早於日落且支援橫線日期`() {
        for (month in 1..12) {
            val (rise, set) = SunTimes.of("2026-%02d-15".format(month))!!
            assertTrue("$month 月", rise < set)
        }
    }

    @Test
    fun `日期壞掉時回 null`() {
        assertNull(SunTimes.of(""))
        assertNull(SunTimes.of("7-21"))
        assertNull(SunTimes.of("2026/13/40"))
    }

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun `一般說法會被辨識`() {
        assertEquals(setOf(TimeRequest.SUNRISE), SpecialRequests.parse("想看日出"))
        assertEquals(setOf(TimeRequest.SUNSET, TimeRequest.STARS), SpecialRequests.parse("想看日落和星空"))
        assertEquals(setOf(TimeRequest.NIGHT), SpecialRequests.parse("晚上想逛夜市"))
    }

    @Test
    fun `否定說法不算需求`() {
        assertTrue(SpecialRequests.parse("不想看日出").isEmpty())
        assertTrue(SpecialRequests.parse("不要太早，也不想特地去看日出").isEmpty())
        assertEquals(setOf(TimeRequest.SUNRISE), SpecialRequests.parse("想看日出，不想逛夜市"))
    }

    @Test
    fun `含有否定字的詞不會被誤判`() {
        // 「特別」含別、「免費」含免、「不錯」含不——單字比對會全部誤判
        assertEquals(setOf(TimeRequest.SUNRISE), SpecialRequests.parse("特別想看日出"))
        assertEquals(setOf(TimeRequest.NIGHT), SpecialRequests.parse("免費夜市"))
        assertEquals(setOf(TimeRequest.SUNRISE), SpecialRequests.parse("不錯的日出景點"))
    }

    @Test
    fun `空白文字沒有需求`() {
        assertTrue(SpecialRequests.parse("").isEmpty())
        assertTrue(SpecialRequests.parse("想去衝浪").isEmpty())
    }

    @Test
    fun `標籤與自由文字合併且室內標籤不產生時間需求`() {
        assertEquals(
            setOf(TimeRequest.SUNRISE, TimeRequest.NIGHT),
            SpecialRequests.resolve(listOf("sunrise", "indoor"), "想逛夜市")
        )
        assertTrue(SpecialRequests.resolve(listOf("indoor"), "").isEmpty())
        assertTrue(SpecialRequests.resolve(listOf("不存在的標籤"), "").isEmpty())
    }

    // ── 時間窗調整 ──────────────────────────────────────────────

    @Test
    fun `沒有需求時原樣回傳`() {
        val single = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 17:00")
        val multi = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 12:00")
        assertEquals(single, DayPlanner.applyTimeRequests(single, emptySet()))
        assertEquals(multi, DayPlanner.applyTimeRequests(multi, emptySet()))
    }

    @Test
    fun `單日看日出提早出發且收工不變`() {
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 17:00"), setOf(TimeRequest.SUNRISE))
        assertEquals(1, w.size)
        assertTrue("提早出發 ${DayPlanner.hhmmOf(w[0].startMins)}", w[0].startMins in mins(4, 30)..mins(5, 30))
        assertEquals(mins(17, 0), w[0].endMins)
    }

    @Test
    fun `多日看日出只動第二天`() {
        val base = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00")
        val w = DayPlanner.applyTimeRequests(base, setOf(TimeRequest.SUNRISE))
        assertEquals(base[0], w[0])                       // 第 1 天不動
        assertTrue(w[1].startMins < mins(6, 0))           // 第 2 天提早
        assertEquals(base[1].endMins, w[1].endMins)       // 收工不變
    }

    @Test
    fun `日出出發時間不會早於下限`() {
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/06/21 09:00", "2026/06/21 17:00"), setOf(TimeRequest.SUNRISE))
        assertTrue(w[0].startMins >= DayPlanner.EARLIEST_START_MINS)
    }

    @Test
    fun `使用者出發時間已經比日出早時不再往後拉`() {
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/07/21 04:30", "2026/07/21 17:00"), setOf(TimeRequest.SUNRISE))
        assertEquals(mins(4, 30), w[0].startMins)
    }

    @Test
    fun `夜景與看星星延後第一天收工且只延不縮`() {
        val multi = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00")
        val night = DayPlanner.applyTimeRequests(multi, setOf(TimeRequest.NIGHT))
        assertEquals(DayPlanner.NIGHT_END_MINS, night[0].endMins)
        assertEquals(multi[1], night[1])                  // 末日不動

        val stars = DayPlanner.applyTimeRequests(multi, setOf(TimeRequest.NIGHT, TimeRequest.STARS))
        assertEquals(DayPlanner.STARS_END_MINS, stars[0].endMins)

        val late = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 22:30")
        assertEquals(mins(22, 30), DayPlanner.applyTimeRequests(late, setOf(TimeRequest.NIGHT))[0].endMins)
    }

    @Test
    fun `夏天日落晚於預設收工時會延後冬天不會`() {
        val summer = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/06/21 09:00", "2026/06/22 15:00"), setOf(TimeRequest.SUNSET))
        assertTrue(summer[0].endMins > DayPlanner.DEFAULT_DAY_END_MINS)
        val winter = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/12/21 09:00", "2026/12/22 15:00"), setOf(TimeRequest.SUNSET))
        assertEquals(DayPlanner.DEFAULT_DAY_END_MINS, winter[0].endMins)
    }

    @Test
    fun `套用兩次與一次結果相同`() {
        val base = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00")
        val all = setOf(TimeRequest.SUNRISE, TimeRequest.SUNSET, TimeRequest.NIGHT, TimeRequest.STARS)
        val once = DayPlanner.applyTimeRequests(base, all)
        assertEquals(once, DayPlanner.applyTimeRequests(once, all))
        val single = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 17:00")
        val s1 = DayPlanner.applyTimeRequests(single, all)
        assertEquals(s1, DayPlanner.applyTimeRequests(s1, all))
    }

    @Test
    fun `日期解析不出來時用保底日出時間`() {
        val base = DayPlanner.buildDayWindows("7-21 09:00", "7-21 17:00")
        val w = DayPlanner.applyTimeRequests(base, setOf(TimeRequest.SUNRISE))
        assertEquals(SunTimes.FALLBACK_SUNRISE - DayPlanner.SUNRISE_LEAD_MINS, w[0].startMins)
    }

    // ── 生成後反推 ──────────────────────────────────────────────

    private val twoDays = "2026/07/21 09:00 - 2026/07/22 15:00"

    @Test
    fun `沒有異常站點時反推結果與預設時間窗相同`() {
        val stops = listOf(
            stop("A", "09:07", 1, 1), stop("B", "10:10", 2, 1),
            stop("C", "09:05", 3, 2), stop("D", "10:00", 4, 2)
        )
        assertEquals(
            DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00"),
            DayPlanner.effectiveWindows(twoDays, stops)
        )
    }

    @Test
    fun `第二天提早出發不會在重算時被拉回九點`() {
        val stops = listOf(
            stop("A", "09:07", 1, 1),
            stop("日出點", "05:17", 2, 2), stop("D", "07:00", 3, 2)
        )
        val w = DayPlanner.effectiveWindows(twoDays, stops)
        assertEquals(mins(9, 0), w[0].startMins)          // 第 1 天不受影響
        assertEquals(mins(5, 17), w[1].startMins)
    }

    @Test
    fun `第二天壞資料的深夜時間不會被當成起點`() {
        // 過去曾出現第 2 天 22:06 出發的壞資料，反推只接受「早於預設起點」的時間
        val stops = listOf(stop("A", "09:07", 1, 1), stop("B", "22:06", 2, 2))
        assertEquals(DayPlanner.DEFAULT_DAY_START_MINS, DayPlanner.effectiveWindows(twoDays, stops)[1].startMins)
    }

    @Test
    fun `第一天延後收工會被反推到但過晚的不採用`() {
        val night = listOf(stop("夜市", "19:30", 1, 1, duration = 60), stop("C", "09:05", 2, 2))
        assertEquals(mins(20, 30), DayPlanner.effectiveWindows(twoDays, night)[0].endMins)

        val tooLate = listOf(stop("X", "23:20", 1, 1, duration = 60), stop("C", "09:05", 2, 2))
        assertEquals(DayPlanner.DEFAULT_DAY_END_MINS, DayPlanner.effectiveWindows(twoDays, tooLate)[0].endMins)
    }

    @Test
    fun `車站站點不參與反推`() {
        val stops = listOf(
            stop("台東車站", "04:50", 0, 1, station = true),
            stop("A", "09:07", 1, 1), stop("B", "09:05", 2, 2)
        )
        assertEquals(mins(9, 0), DayPlanner.effectiveWindows(twoDays, stops)[0].startMins)
    }

    @Test
    fun `單日與非標準格式的行為維持不變`() {
        val stops = listOf(stop("A", "05:10", 1, 1))
        assertEquals(1, DayPlanner.effectiveWindows("2026/07/21 05:00 - 2026/07/21 17:00", stops).size)
        assertTrue(DayPlanner.effectiveWindows("8小時", stops).isEmpty())
    }

    // ── 說明文字 ────────────────────────────────────────────────

    @Test
    fun `沒有需求時 prompt 區段是空字串以維持原 prompt 逐字不變`() {
        val w = DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/21 17:00")
        assertEquals("", SpecialRequests.promptSection(w, emptySet()))
        assertTrue(SpecialRequests.describe(w, emptySet()).isEmpty())
    }

    @Test
    fun `日出需求會寫進 prompt 並禁止第一站排餐廳`() {
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00"), setOf(TimeRequest.SUNRISE))
        val text = SpecialRequests.promptSection(w, setOf(TimeRequest.SUNRISE))
        assertTrue(text.startsWith("\n- 【日出需求】"))
        assertTrue(text.contains("第 2 天"))
        assertTrue(text.contains("嚴禁把餐廳"))
    }

    @Test
    fun `日出結果檢查趕上與沒趕上的說法不同`() {
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/07/21 09:00", "2026/07/22 15:00"), setOf(TimeRequest.SUNRISE))
        val ok = SpecialRequests.sunriseOutcome(w, listOf(stop("海岸", "05:17", 1, 2)))
        assertNotNull(ok); assertFalse(ok!!.contains("⚠️")); assertTrue(ok.contains("抵達"))
        val missed = SpecialRequests.sunriseOutcome(w, listOf(stop("博物館", "09:03", 1, 2)))
        assertTrue(missed!!.contains("⚠️"))
        assertNull(SpecialRequests.sunriseOutcome(w, emptyList()))
    }

    @Test
    fun `日出檢查以最早抵達時間為準而不是 order`() {
        // 實測回歸：排程器重排後 order 還是 AI 原始順序。海濱公園 order 最小但排在 10:06，
        // 真正的第一站是 05:34 的利吉惡地。
        val w = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("2026/09/21 11:00", "2026/09/22 15:00"), setOf(TimeRequest.SUNRISE))
        val text = SpecialRequests.sunriseOutcome(w, listOf(
            stop("海濱公園", "10:06", order = 1, day = 2),
            stop("利吉惡地", "05:34", order = 5, day = 2),
            stop("卑南遺址", "06:27", order = 3, day = 2)
        ))!!
        assertTrue(text, text.contains("利吉惡地"))
        assertFalse(text, text.contains("⚠️"))
    }
}
