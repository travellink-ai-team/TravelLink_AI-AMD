package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.BreakfastCandidate
import com.example.travellink_ai.ui.planning.BreakfastSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 早出發那天的早餐。實測 9/21 第 2 天 05:14 出發、11:49 才有第一餐。
 * 本地景點庫台東市 12 公里內，週二 09:00 前開門的餐飲只有麥當勞、植物園餐廳、剉冰，
 * 沒有早餐店——所以候選由 Google Places 提供，這裡只測「哪一家適合」。
 */
class BreakfastSpotsTest {

    private fun cand(
        name: String, ratings: Int = 200, km: Double = 1.0, hours: String = "05:30 - 13:00"
    ) = BreakfastCandidate(name, ratings, km, hours)

    // ── 什麼時候需要早餐 ─────────────────────────────────────────

    @Test
    fun `出發時間不晚於 0730 才需要另外排早餐`() {
        assertTrue(BreakfastSpots.needsBreakfast(5 * 60 + 14))    // 日出那天
        assertTrue(BreakfastSpots.needsBreakfast(7 * 60 + 30))    // 邊界含
        assertFalse(BreakfastSpots.needsBreakfast(7 * 60 + 31))
        assertFalse(BreakfastSpots.needsBreakfast(9 * 60))        // 一般出發不受影響
        assertFalse(BreakfastSpots.needsBreakfast(11 * 60))       // 實測第 1 天 11:00 出發
    }

    @Test
    fun `看完日出後才吃早餐所以預計時間往後推`() {
        val start = 5 * 60 + 14
        assertEquals(start + 10, BreakfastSpots.targetMins(start, afterSunrise = false))
        assertEquals(start + 10 + 60, BreakfastSpots.targetMins(start, afterSunrise = true))
    }

    // ── 適合不適合 ───────────────────────────────────────────────

    private val target = 5 * 60 + 14 + 10 + 60    // 06:24

    @Test
    fun `預計時間店家有開才算`() {
        assertTrue(BreakfastSpots.score(cand("阿婆早餐店", hours = "05:30 - 13:00"), target) > 0)
        assertEquals(0, BreakfastSpots.score(cand("九點才開", hours = "09:00 - 20:00"), target))
        assertEquals(0, BreakfastSpots.score(cand("公休", hours = "休息"), target))
    }

    @Test
    fun `營業時間不明的不敢排`() {
        assertEquals(0, BreakfastSpots.score(cand("不知道", hours = "未提供"), target))
        assertEquals(0, BreakfastSpots.score(cand("空字串", hours = ""), target))
    }

    @Test
    fun `要待滿停留時間都開著`() {
        // 06:24 到、待 40 分 → 07:04；06:50 就關的不行
        assertEquals(0, BreakfastSpots.score(cand("太早收", hours = "05:00 - 06:50"), target))
        assertTrue(BreakfastSpots.score(cand("剛好", hours = "05:00 - 07:04"), target) > 0)
    }

    @Test
    fun `評論太少的不排`() {
        assertEquals(0, BreakfastSpots.score(cand("可能歇業了", ratings = 3), target))
        assertTrue(BreakfastSpots.score(cand("剛好門檻", ratings = BreakfastSpots.MIN_RATINGS), target) > 0)
    }

    @Test
    fun `人氣高與距離近的分數較高`() {
        val popular = BreakfastSpots.score(cand("人氣店", ratings = 800), target)
        val quiet = BreakfastSpots.score(cand("小店", ratings = 30), target)
        assertTrue(popular > quiet)
        val near = BreakfastSpots.score(cand("近", km = 0.5), target)
        val far = BreakfastSpots.score(cand("遠", km = 4.0), target)
        assertTrue(near > far)
    }

    @Test
    fun `過遠不會讓分數掉到零以下`() {
        assertTrue(BreakfastSpots.score(cand("很遠", ratings = 20, km = 50.0), target) >= 1)
    }

    // ── pick ─────────────────────────────────────────────────────

    @Test
    fun `pick 挑最適合的並略過沒開的`() {
        val r = BreakfastSpots.pick(listOf(
            cand("九點才開", ratings = 2000, hours = "09:00 - 20:00"),   // 人氣最高但沒開
            cand("阿婆早餐店", ratings = 300, km = 1.2),
            cand("遠的早餐店", ratings = 300, km = 4.5)
        ), target)
        assertEquals("阿婆早餐店", r?.name)
    }

    @Test
    fun `沒有合適的就回 null 不硬湊`() {
        assertNull(BreakfastSpots.pick(emptyList(), target))
        assertNull(BreakfastSpots.pick(listOf(
            cand("九點才開", hours = "09:00 - 20:00"),
            cand("不明", hours = "未提供"),
            cand("沒人評論", ratings = 2)
        ), target))
    }

    @Test
    fun `本地庫實測的三家早開餐飲在日出後那個時刻的判斷`() {
        // 麥當勞 04:00 起開得夠早；剉冰 09:00 才開；植物園餐廳 08:30 才開
        assertTrue(BreakfastSpots.score(cand("麥當勞-台東中華餐廳", hours = "04:00 - 23:00"), target) > 0)
        assertEquals(0, BreakfastSpots.score(cand("古早味阿桑剉冰", hours = "09:00 - 21:00"), target))
        assertEquals(0, BreakfastSpots.score(cand("台東原生應用植物園", hours = "08:30 - 17:00"), target))
    }
}
