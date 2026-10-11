package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.BusinessHours
import com.example.travellink_ai.ui.planning.SpotCandidate
import com.example.travellink_ai.ui.planning.SunriseSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日出點的挑選。資料取自 9/21 的實測行程：AI 把海濱公園排成第 2 天日出首站，
 * 但候選裡也有鯉魚山（11:00 才開）、頂岩灣四格山步道（24 小時但沒有日出訊號）、
 * 烏龍院家庭食堂（餐廳）——挑錯一個，日出那天的第一站就是看不到日出的地方。
 */
class SunriseSpotsTest {

    /** 日出約 05:44、05:14 出發，抵達約 05:25 */
    private val arrive = 5 * 60 + 25

    private fun spot(
        name: String, type: String = "景點", desc: String = "", hours: String = "24 小時營業",
        bestTime: String = "", dining: Boolean = false, km: Double? = null
    ) = SpotCandidate(name, type, desc, bestTime, hours, dining, km)

    // ── isOpenAt ────────────────────────────────────────────────

    @Test
    fun `isOpenAt 依時段判斷抵達當下是否開著`() {
        assertEquals(true, BusinessHours.isOpenAt("24 小時營業", arrive, 30))
        assertEquals(true, BusinessHours.isOpenAt("全天開放", arrive, 30))
        assertEquals(false, BusinessHours.isOpenAt("11:00 - 21:00", arrive, 30))     // 鯉魚山
        assertEquals(false, BusinessHours.isOpenAt("08:30 - 17:00", arrive, 30))     // 富岡地質公園
        assertEquals(true, BusinessHours.isOpenAt("08:30 - 12:00, 13:30 - 17:00", 9 * 60, 30))
        assertEquals(false, BusinessHours.isOpenAt("休息", 9 * 60, 30))
    }

    @Test
    fun `isOpenAt 要待滿停留時間才算開著`() {
        assertEquals(false, BusinessHours.isOpenAt("08:30 - 17:00", 16 * 60 + 45, 30))   // 17:00 就關
        assertEquals(true, BusinessHours.isOpenAt("08:30 - 17:00", 16 * 60 + 30, 30))
    }

    @Test
    fun `isOpenAt 跨夜時段與沒有資料`() {
        assertEquals(true, BusinessHours.isOpenAt("16:00 - 00:00", 23 * 60, 30))         // 夜市
        assertEquals(false, BusinessHours.isOpenAt("16:00 - 00:00", 5 * 60, 30))
        assertNull(BusinessHours.isOpenAt("未提供", arrive, 30))
        assertNull(BusinessHours.isOpenAt("", arrive, 30))
        assertNull(BusinessHours.isOpenAt("看粉絲專頁", arrive, 30))
    }

    // ── score / pick ────────────────────────────────────────────

    @Test
    fun `實測案例海濱公園是合適的日出點`() {
        assertTrue(SunriseSpots.score(spot("海濱公園"), arrive) >= SunriseSpots.MIN_SCORE)
    }

    @Test
    fun `清晨沒開的不能當日出點`() {
        assertEquals(0, SunriseSpots.score(spot("鯉魚山", hours = "11:00 - 21:00"), arrive))
        assertEquals(0, SunriseSpots.score(spot("富岡地質公園", hours = "08:30 - 17:00"), arrive))
    }

    @Test
    fun `餐廳與室內類型不能當日出點`() {
        assertEquals(0, SunriseSpots.score(spot("海邊餐廳", type = "餐廳", dining = true), arrive))
        assertEquals(0, SunriseSpots.score(spot("海洋生態館", type = "博物館/文化館"), arrive))
        assertEquals(0, SunriseSpots.score(spot("海濱教堂", type = "教堂"), arrive))
    }

    @Test
    fun `24 小時開放但沒有日出訊號的不算`() {
        // 頂岩灣四格山步道、卑南大圳水利公園：全天開放，但沒有任何指向日出／海岸的資訊
        assertEquals(0, SunriseSpots.score(spot("頂岩灣四格山步道"), arrive))
        assertEquals(0, SunriseSpots.score(spot("卑南大圳水利公園", type = "公園/步道"), arrive))
        assertEquals(0, SunriseSpots.score(spot("利吉惡地", type = "自然景觀"), arrive))
    }

    @Test
    fun `描述明講日出就算數即使名字沒有訊號`() {
        val s = spot("某某觀景平台", desc = "清晨的最佳位置，視野開闊，是迎接日出的好地方")
        assertTrue(SunriseSpots.score(s, arrive) >= SunriseSpots.MIN_SCORE)
    }

    @Test
    fun `知識庫標清晨最佳也算訊號`() {
        assertTrue(SunriseSpots.score(spot("某某岬角", bestTime = "清晨"), arrive) >= SunriseSpots.MIN_SCORE)
    }

    @Test
    fun `營業時間不明的戶外景點仍可入選但分數較低`() {
        val known = SunriseSpots.score(spot("加路蘭遊憩區", hours = "全天開放"), arrive)
        val unknown = SunriseSpots.score(spot("加路蘭遊憩區", hours = "未提供"), arrive)
        assertTrue(unknown >= SunriseSpots.MIN_SCORE)
        assertTrue(known > unknown)
    }

    @Test
    fun `越遠分數越低`() {
        val near = SunriseSpots.score(spot("三仙台", km = 3.0), arrive)
        val far = SunriseSpots.score(spot("三仙台", km = 22.0), arrive)
        assertTrue(near > far)
    }

    @Test
    fun `pick 從一天的站裡挑出日出點`() {
        // 9/21 第 2 天 AI 選的站：只有海濱公園適合
        val day2 = listOf(
            spot("利吉惡地", type = "自然景觀"),
            spot("卑南遺址", hours = "休息"),
            spot("鷺鷥湖", type = "公園/步道"),
            spot("國立臺東生活美學館", type = "博物館/文化館", hours = "08:30 - 12:00"),
            spot("海濱公園"),
            spot("66萊樂輕食", type = "餐廳", dining = true, hours = "11:30 - 14:00")
        )
        assertEquals("海濱公園", SunriseSpots.pick(day2, arrive)?.name)
    }

    @Test
    fun `pick 同分時保留先出現的`() {
        val r = SunriseSpots.pick(listOf(spot("海濱公園"), spot("濱海公園")), arrive)
        assertEquals("海濱公園", r?.name)
    }

    @Test
    fun `沒有合適的就回 null 不硬湊`() {
        val none = listOf(
            spot("頂岩灣四格山步道"), spot("烏龍院家庭食堂", type = "餐廳", dining = true),
            spot("鯉魚山", hours = "11:00 - 21:00")
        )
        assertNull(SunriseSpots.pick(none, arrive))
        assertNull(SunriseSpots.pick(emptyList(), arrive))
    }

    @Test
    fun `去重只偏袒本來就是日出點的重複`() {
        // 實測：AI 把台東森林公園排了兩次，日出那天的優先規則不分青紅皂白，
        // 第 1 天的那筆被無故拿掉。海濱公園（日出點）才該享有這個優先權
        assertTrue(SunriseSpots.isSunriseCandidate("海濱公園"))
        assertTrue(SunriseSpots.isSunriseCandidate("某某觀景平台", "迎接日出的好地方"))
        assertFalse(SunriseSpots.isSunriseCandidate("台東森林公園"))
        assertFalse(SunriseSpots.isSunriseCandidate("頂岩灣四格山步道"))
        assertFalse(SunriseSpots.isSunriseCandidate("烏龍院家庭食堂"))
    }

    @Test
    fun `pick 遠處的補位候選也能比較`() {
        val cands = listOf(
            spot("三仙台", km = 24.0),
            spot("加路蘭遊憩區", km = 8.0, hours = "全天開放")
        )
        assertNotNull(SunriseSpots.pick(cands, arrive))
        assertEquals("加路蘭遊憩區", SunriseSpots.pick(cands, arrive)?.name)
        assertFalse(SunriseSpots.pick(cands, arrive)?.name == "三仙台")
    }
}
