package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.WishedSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishedSpotsTest {

    @Test
    fun `範本帶入的清單拆成一項一項，括號裡的空白不影響`() {
        assertEquals(
            listOf("萬富倉庫", "台東鐵道藝術村 (鐵花新聚落)", "晃晃二手書店"),
            WishedSpots.parse("萬富倉庫、台東鐵道藝術村 (鐵花新聚落)、晃晃二手書店")
        )
    }

    @Test
    fun `各種分隔符號都拆，太短與重複的略過`() {
        assertEquals(listOf("三仙台", "多良車站"), WishedSpots.parse("三仙台，多良車站；\n三仙台、a"))
    }

    @Test
    fun `活動不是地點`() {
        assertTrue(WishedSpots.isActivity("看日出"))
        assertTrue(WishedSpots.isActivity("想泡溫泉"))
        assertTrue(WishedSpots.isActivity("逛夜市"))
        assertFalse(WishedSpots.isActivity("臺東觀光夜市"))
        assertFalse(WishedSpots.isActivity("萬富倉庫"))
    }

    @Test
    fun `名稱比對：去括號、臺台、包含、括號別名`() {
        assertTrue(WishedSpots.sameName("台東鐵道藝術村 (鐵花新聚落)", "台東鐵道藝術村"))
        assertTrue(WishedSpots.sameName("台東鐵道藝術村 (鐵花新聚落)", "鐵花新聚落"))
        assertTrue(WishedSpots.sameName("臺東森林公園", "台東森林公園"))
        assertTrue(WishedSpots.sameName("三仙台", "三仙台風景區"))
        assertFalse(WishedSpots.sameName("萬富倉庫", "晃晃二手書店"))
        // 兩字太短不算包含，避免「海灘」命中所有海灘
        assertFalse(WishedSpots.sameName("海灘", "杉原海灘"))
    }

    @Test
    fun `找出行程裡沒有的指定景點`() {
        val wished = listOf("萬富倉庫", "台東鐵道藝術村 (鐵花新聚落)", "晃晃二手書店")
        val stops = listOf("台東車站", "萬富倉庫", "台東鐵道藝術村", "台東森林公園")
        assertEquals(listOf("晃晃二手書店"), WishedSpots.missing(wished, stops))
    }

    @Test
    fun `沒有指定時 prompt 段落為空`() {
        assertEquals("", WishedSpots.promptSection(emptyList()))
        assertTrue(WishedSpots.promptSection(listOf("萬富倉庫")).contains("萬富倉庫"))
    }

    @Test
    fun `指定的餐廳在 prompt 標明它就是那一餐`() {
        val withDining = WishedSpots.promptSection(listOf("萬富倉庫", "晃晃二手書店"), listOf("萬富倉庫"))
        assertTrue(withDining.contains("其中萬富倉庫是餐廳"))
        assertFalse(WishedSpots.promptSection(listOf("晃晃二手書店")).contains("是餐廳"))
    }
}
