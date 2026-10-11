package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.PlaceNameMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名稱比對。實測：使用者填「鮪魚飯店」，Google 上是「鮪魚家族飯店臺東館」，
 * 舊比對只認「包含」，得 0 分，真實存在的飯店被判成不存在。
 */
class PlaceNameMatchTest {

    private val menThreshold = 12   // verifyUnknownPOI 的通過門檻（名稱分＋距離分）

    @Test
    fun `實測案例簡稱能對上全名`() {
        val strict = PlaceNameMatch.score("鮪魚飯店", "鮪魚家族飯店臺東館")
        val lenient = PlaceNameMatch.score("鮪魚飯店", "鮪魚家族飯店臺東館", lenient = true)
        assertEquals("嚴格模式維持原樣：對不上", 0, strict)
        assertTrue("寬鬆模式要能過門檻（另有距離分）", lenient >= menThreshold)
    }

    @Test
    fun `臺與台的寫法不影響比對`() {
        assertEquals(18, PlaceNameMatch.score("台東糖廠", "臺東糖廠文創園區"))
        assertEquals(18, PlaceNameMatch.score("臺東美術館", "台東美術館"))
    }

    @Test
    fun `包含關係的既有分數不變`() {
        assertEquals(18, PlaceNameMatch.score("海濱公園", "台東海濱公園"))         // 候選包含查詢
        assertEquals(12, PlaceNameMatch.score("鮪魚家族飯店臺東館官方網站", "鮪魚家族飯店"))  // 查詢包含候選
        assertEquals(0, PlaceNameMatch.score("完全不同", "另一個地方"))
    }

    @Test
    fun `空白與標點不影響`() {
        assertEquals(18, PlaceNameMatch.score("鮪魚 家族 飯店", "鮪魚家族飯店（臺東館）"))
    }

    // ── 寬鬆模式不能放過不相干的名稱 ───────────────────────────────

    @Test
    fun `品牌名被拆開的不算簡稱`() {
        assertEquals(0, PlaceNameMatch.score("鮪魚飯店", "鮪家魚飯店", lenient = true))
    }

    @Test
    fun `不同品牌不算簡稱`() {
        assertEquals(0, PlaceNameMatch.score("鮪魚飯店", "海洋家族飯店", lenient = true))
        assertEquals(0, PlaceNameMatch.score("鮪魚飯店", "知本老爺酒店", lenient = true))
    }

    @Test
    fun `字要依序出現`() {
        // 「店飯」順序顛倒
        assertEquals(0, PlaceNameMatch.score("鮪魚飯店", "鮪魚家族店飯", lenient = true))
    }

    @Test
    fun `兩個字的名稱太短不適用簡稱規則`() {
        assertEquals(0, PlaceNameMatch.score("鮪魚", "鮪家魚旅店", lenient = true))
        // 但仍走一般的包含規則
        assertEquals(18, PlaceNameMatch.score("鮪魚", "鮪魚家族飯店", lenient = true))
    }

    @Test
    fun `AI 選點的驗證維持嚴格不吃簡稱`() {
        // AI 會編造不存在的景點；嚴格模式下簡稱對不上，幻覺不會更容易過關
        assertEquals(0, PlaceNameMatch.score("東海岸大草原", "東海岸風景區大草原景觀台"))
        assertTrue(PlaceNameMatch.score("東海岸大草原", "東海岸風景區大草原景觀台", lenient = true) > 0)
    }

    @Test
    fun `完全相同名稱得最高分`() {
        assertEquals(18, PlaceNameMatch.score("鮪魚家族飯店臺東館", "鮪魚家族飯店臺東館"))
    }
}
