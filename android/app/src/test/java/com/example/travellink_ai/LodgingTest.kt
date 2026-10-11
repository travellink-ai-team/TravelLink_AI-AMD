package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.Lodging
import com.example.travellink_ai.ui.planning.LodgingLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兩天一夜的住宿站（A 型：第 1 天最後一站）。
 * 住宿站用 isStation + isLodging，換來「不算景點、不算費用、不能編輯」的既有待遇，
 * 所以旗標是否正確是這組測試最在意的事——弄錯就會被當成景點計費或打卡。
 */
class LodgingTest {

    private fun mins(h: Int, m: Int) = h * 60 + m

    // ── 入住時間 ─────────────────────────────────────────────────

    @Test
    fun `解析使用者填的入住時間`() {
        assertEquals(mins(16, 0), Lodging.parseCheckIn("16:00"))
        assertEquals(mins(15, 30), Lodging.parseCheckIn(" 15:30 "))
        assertNull(Lodging.parseCheckIn(""))
        assertNull(Lodging.parseCheckIn("下午四點"))
        assertNull(Lodging.parseCheckIn("25:00"))
    }

    @Test
    fun `抵達時間早於入住時間就等到入住時間`() {
        // 使用者填 16:00，第 1 天 13:30 就收工到飯店 → 入住顯示 16:00
        val s = Lodging.checkInStop("鮪魚飯店", 22.75, 121.15, mins(13, 30), mins(16, 0), 1)
        assertEquals("16:00", s.time)
    }

    @Test
    fun `抵達時間晚於入住時間就用抵達時間`() {
        val s = Lodging.checkInStop("鮪魚飯店", 22.75, 121.15, mins(16, 40), mins(15, 0), 1)
        assertEquals("16:40", s.time)
    }

    @Test
    fun `沒指定入住時間就用抵達時間不擅自假設`() {
        val s = Lodging.checkInStop("鮪魚飯店", 22.75, 121.15, mins(14, 12), null, 1)
        assertEquals("14:12", s.time)
    }

    // ── 旗標：決定它會不會被當成景點 ──────────────────────────────

    @Test
    fun `入住站是住宿站而不是景點`() {
        val s = Lodging.checkInStop("鮪魚飯店", 22.75, 121.15, mins(16, 0), null, 1)
        assertTrue("isStation 才會被排除在景點數、費用、打卡、回憶之外", s.isStation)
        assertTrue(s.isLodging)
        assertFalse(s.isFerry)
        assertEquals("住宿", s.stopType)
        assertEquals("🏨", s.emoji)
        assertEquals(1, s.dayIndex)
        assertEquals(Lodging.CHECK_IN_STAY_MINS.toLong(), s.duration)
    }

    @Test
    fun `入住站帶座標讓地圖與車程計算用得上`() {
        val s = Lodging.checkInStop("鮪魚飯店", 22.7512, 121.1523, mins(16, 0), null, 1)
        assertEquals(22.7512, s.lat!!, 1e-9)
        assertEquals(121.1523, s.lng!!, 1e-9)
    }

    // ── 飯店早餐 ─────────────────────────────────────────────────

    @Test
    fun `飯店早餐站名與入住站不同但座標相同`() {
        // 排程與座標表都以站名為 key，同名會互相覆蓋
        val checkIn = Lodging.checkInStop("鮪魚飯店", 22.7512, 121.1523, mins(16, 0), null, 1)
        val breakfast = Lodging.breakfastStop("鮪魚飯店", 22.7512, 121.1523, 2)
        assertNotEquals(checkIn.name, breakfast.name)
        assertEquals("鮪魚飯店（早餐・退房）", breakfast.name)
        assertEquals(checkIn.lat, breakfast.lat)
        assertEquals(checkIn.lng, breakfast.lng)
    }

    @Test
    fun `飯店早餐站同樣是住宿站且不編造營業時間`() {
        val s = Lodging.breakfastStop("鮪魚飯店", 22.7512, 121.1523, 2)
        assertTrue(s.isStation); assertTrue(s.isLodging)
        assertEquals(2, s.dayIndex)
        assertEquals("未提供", s.businessHours)     // 不知道飯店供餐時段，不編
        assertEquals(Lodging.BREAKFAST_STAY_MINS.toLong(), s.duration)
    }

    // ── 日出後回飯店退房 ──────────────────────────────────────────

    @Test
    fun `第 2 天有日出且有住宿才需要退房站`() {
        assertTrue(Lodging.needsCheckoutStop(2, hasHotel = true, hasSunrise = true, isIsland = false))
    }

    @Test
    fun `沒日出就不必回飯店，一般 09 點出發直接從飯店走`() {
        assertFalse(Lodging.needsCheckoutStop(2, hasHotel = true, hasSunrise = false, isIsland = false))
    }

    @Test
    fun `第 1 天還沒住進去、沒有住宿、離島都不排退房站`() {
        assertFalse("第 1 天日出：前一晚不在這家飯店", Lodging.needsCheckoutStop(1, true, true, false))
        assertFalse("查不到住宿", Lodging.needsCheckoutStop(2, false, true, false))
        assertFalse("離島由船班決定起訖", Lodging.needsCheckoutStop(2, true, true, true))
    }

    @Test
    fun `退房站是住宿站且站名與入住、早餐站都不同`() {
        val out = Lodging.checkoutStop("鮪魚飯店", 22.7512, 121.1523, 2)
        assertTrue(out.isStation); assertTrue(out.isLodging)
        assertEquals("鮪魚飯店（退房）", out.name)
        assertEquals("退房", out.desc)
        assertEquals(2, out.dayIndex)
        assertEquals(Lodging.CHECK_OUT_STAY_MINS.toLong(), out.duration)
        assertEquals(22.7512, out.lat!!, 1e-9)
        val names = setOf(
            Lodging.checkInStop("鮪魚飯店", 22.7512, 121.1523, 0, null, 1).name,
            Lodging.breakfastStopName("鮪魚飯店"), out.name
        )
        assertEquals("同名會在排程與座標表互相覆蓋", 3, names.size)
    }

    @Test
    fun `退房站不編造營業時間`() {
        // 預設值即可：不能出現一個編出來的時段讓排程器去「等開門」
        val out = Lodging.checkoutStop("鮪魚飯店", 22.75, 121.15, 2)
        assertTrue(out.businessHours.isBlank() || out.businessHours == "未提供")
    }

    @Test
    fun `日出後休息到十點再離開`() {
        assertEquals(mins(10, 0), Lodging.restUntilMins(mins(15, 0)))   // 實測第 2 天 05:14–15:00
        assertEquals(mins(10, 0), Lodging.restUntilMins(mins(18, 0)))
    }

    @Test
    fun `休息站說明寫出離開時間，含早餐則多寫早餐`() {
        assertEquals("休息・退房（約 10:00 離開）", Lodging.restDesc(false, mins(10, 0)))
        assertEquals("早餐・休息・退房（約 10:05 離開）", Lodging.restDesc(true, mins(10, 5)))
    }

    @Test
    fun `當天結束得太早就不休息以免整天只剩一兩站`() {
        assertNull(Lodging.restUntilMins(mins(12, 0)))
        assertNull("剛好差一分鐘不到三小時", Lodging.restUntilMins(mins(12, 59)))
        assertEquals(mins(10, 0), Lodging.restUntilMins(mins(13, 0)))
    }

    // ── 欄位下方的查詢提示 ────────────────────────────────────────

    @Test
    fun `找到時顯示正式名稱與距離簡稱時說明對應關係`() {
        // 實測：使用者填「鮪魚飯店」，Google 上是「鮪魚家族飯店臺東館」
        val text = Lodging.statusText(LodgingLookup.Found("鮪魚家族飯店臺東館", 1.234), "鮪魚飯店")
        assertTrue(text.startsWith("✅ 找到：鮪魚家族飯店臺東館"))
        assertTrue("要讓使用者看得出簡稱被對應到哪一家", text.contains("你填的是「鮪魚飯店」"))
        assertTrue(text.contains("1.2 公里"))
    }

    @Test
    fun `名稱完全一致時不重複說明`() {
        val text = Lodging.statusText(LodgingLookup.Found("鮪魚家族飯店臺東館", 0.8), " 鮪魚家族飯店臺東館 ")
        assertFalse(text.contains("你填的是"))
    }

    @Test
    fun `查不到時講清楚後果而不只說找不到`() {
        val text = Lodging.statusText(LodgingLookup.NotFound, "不存在旅店")
        assertTrue(text.contains("找不到「不存在旅店」"))
        assertTrue("使用者要知道後果", text.contains("不會排入住宿站"))
    }

    @Test
    fun `網路失敗時明講不代表飯店不存在`() {
        val text = Lodging.statusText(LodgingLookup.Failed, "鮪魚飯店")
        assertTrue(text.contains("不代表飯店不存在"))
        assertFalse("網路問題不該叫使用者去改名稱", text.contains("請確認名稱"))
    }

    @Test
    fun `查詢中`() {
        assertTrue(Lodging.statusText(LodgingLookup.Searching, "x").contains("查詢"))
    }

    // ── prompt ───────────────────────────────────────────────────

    @Test
    fun `沒有住宿時 prompt 區段是空字串以維持原 prompt 逐字不變`() {
        assertEquals("", Lodging.promptSection(null))
        assertEquals("", Lodging.promptSection(""))
        assertEquals("", Lodging.promptSection("   "))
    }

    @Test
    fun `查不到位置時明講行程不會有住宿站並禁止寫成回到飯店`() {
        // 實測：查不到「鮪魚飯店」仍告訴 AI 有住宿，AI 寫「最後回到市區鮪魚飯店、第二天從飯店出發」，
        // 但行程裡根本沒有飯店
        val text = Lodging.unresolvedPromptSection("鮪魚飯店")
        assertTrue(text.startsWith("\n- 【住宿】"))
        assertTrue(text.contains("不會排入住宿站"))
        assertTrue(text.contains("請勿寫成「回到飯店」"))
        assertFalse("不能像查到時那樣要求第 2 天從住宿出發", text.contains("從住宿出發"))
    }

    @Test
    fun `有住宿時 prompt 說明第一天收工入住第二天從住宿出發`() {
        val text = Lodging.promptSection("鮪魚飯店")
        assertTrue(text.startsWith("\n- 【住宿】鮪魚飯店"))
        assertTrue(text.contains("第 2 天從住宿出發"))
    }
}
