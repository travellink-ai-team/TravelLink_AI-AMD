package com.example.travellink_ai

import com.example.travellink_ai.ui.poster.PhotoRef
import com.example.travellink_ai.ui.poster.PhotoSlot
import com.example.travellink_ai.ui.poster.TestLayouts
import com.example.travellink_ai.ui.poster.withPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版面範本與選片填入的純邏輯測試（不需裝置）。
 * 這些數值對齊網頁端 MEMORY_TEMPLATES，回歸時若被改動要能被抓到。
 */
class PosterLayoutTest {

    @Test
    fun templates_haveExpectedCardCounts() {
        assertEquals(4, TestLayouts.classic().cards.size)
        assertEquals(6, TestLayouts.grid6().cards.size)
        assertEquals(5, TestLayouts.collage5().cards.size)
        assertEquals(5, TestLayouts.feature5().cards.size)
    }

    @Test
    fun byKey_fallsBackToFirstOnUnknown() {
        assertEquals("grid6", TestLayouts.byKey("grid6").templateKey)
        assertEquals(TestLayouts.all.first().templateKey, TestLayouts.byKey("nope").templateKey)
    }

    /** 「整齊六宮」必須用固定比例才會整齊；錯落範本用照片比例。 */
    @Test
    fun grid6_isSquare_collageIsPhotoAspect() {
        assertTrue("grid6 應全為方形", TestLayouts.grid6().cards.all { it.aspect == 1f })
        assertTrue("collage5 應依照片比例", TestLayouts.collage5().cards.all { it.aspect == null })
    }

    @Test
    fun classic_hasTilt_othersUpright() {
        assertTrue("classic 應有傾斜卡", TestLayouts.classic().cards.any { it.angle != 0f })
        assertTrue("grid6 應直立", TestLayouts.grid6().cards.all { it.angle == 0f })
    }

    @Test
    fun withPhotos_firstIsBackground_restAreCardsInOrder() {
        val photos = (1..6).map { PhotoRef("p$it", "url$it") }
        val layout = TestLayouts.collage5().withPhotos(photos)   // capacity 6 = 1 底圖 + 5 卡
        assertEquals("p1", layout.background?.photoRef?.photoId)
        assertEquals(5, layout.cards.size)
        assertEquals("p2", layout.cards.first().photoRef?.photoId)
        assertEquals("p6", layout.cards.last().photoRef?.photoId)
    }

    @Test
    fun withPhotos_fewerThanCapacity_dropsEmptyCards() {
        val photos = listOf(PhotoRef("p1", "u1"), PhotoRef("p2", "u2"))
        val layout = TestLayouts.collage5().withPhotos(photos)
        assertEquals("p1", layout.background?.photoRef?.photoId)  // 第 1 張＝底圖
        assertEquals("只有 1 張小卡", 1, layout.cards.size)
        assertEquals("p2", layout.cards.first().photoRef?.photoId)
    }

    @Test
    fun withPhotos_empty_leavesNoPhoto() {
        val layout = TestLayouts.collage5().withPhotos(emptyList())
        assertNull(layout.background?.photoRef)
    }

    @Test
    fun allCardWidths_withinBounds() {
        TestLayouts.all.flatMap { it.cards }.forEach {
            assertTrue("卡寬 ${it.w} 超出上限", it.w <= PhotoSlot.MAX_W)
            assertTrue("卡寬 ${it.w} 低於下限", it.w >= PhotoSlot.MIN_W - 1e-4f)
        }
    }

    @Test
    fun allTemplates_haveVisibleTitle() {
        TestLayouts.all.forEach {
            assertTrue("${it.templateKey} 應有標題", it.titleCard?.visible == true)
        }
    }
}
