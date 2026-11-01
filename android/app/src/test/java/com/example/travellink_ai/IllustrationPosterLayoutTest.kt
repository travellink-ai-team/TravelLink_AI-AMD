package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.PosterLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插圖版面：同名站合併成一個「1／N」標記、由上往下排、草稿不畫回程線、行程時間縮寫 */
class IllustrationPosterLayoutTest {

    private fun stop(name: String, i: Int) =
        Stop(name = name, time = "", desc = "", emoji = "📍", duration = 60, order = i.toLong(), stopId = "s$i")

    private val trip = listOf("台東車站", "糖廠", "豬血湯", "臺東車站").mapIndexed { i, n -> stop(n, i) }

    @Test
    fun `起點等於終點只放一個標記，編號寫成 1／4，兩站共用`() {
        val l = PosterLayout.build(trip)
        assertEquals(3, l.places.size)
        assertEquals("1／4", l.places[0].label)
        assertEquals(listOf("s0", "s3"), l.places[0].stopIds)
        assertEquals(listOf(0, 1, 2, 0), l.order)
    }

    @Test
    fun `1 號在上面，之後由上往下、左右交錯`() {
        val l = PosterLayout.build(trip)
        assertTrue(l.places.zipWithNext().all { (a, b) -> b.y > a.y })
        assertTrue(l.places[0].x < PosterLayout.W / 2 && l.places[1].x > PosterLayout.W / 2)
        assertTrue(l.places[0].y >= PosterLayout.H * 0.15f)   // 上面留給標題
    }

    @Test
    fun `草稿路線不畫回到起點那段`() {
        assertEquals(listOf(0 to 1, 1 to 2), PosterLayout.sketchSegments(PosterLayout.build(trip)))
    }

    @Test
    fun `隔天從同一間住宿出發只算一個地點`() {
        val l = PosterLayout.build(listOf("車站", "A", "民宿", "民宿", "B", "車站").mapIndexed { i, n -> stop(n, i) })
        assertEquals(listOf("1／6", "2", "3／4", "5"), l.places.map { it.label })
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), PosterLayout.sketchSegments(l))
    }

    @Test
    fun `底圖指令只給畫面描述、不給站名，禁止任何文字、直式`() {
        val l = PosterLayout.build(trip)
        val p = PosterLayout.basePrompt(l, "台東", "", mapOf(1 to "有月台的鐵路車站", 2 to "紅磚倉庫"))
        assertTrue(p.contains("圓點 1：📍有月台的鐵路車站\n圓點 2：📍紅磚倉庫\n圓點 3：📍"))
        // 站名一個都不能出現（給了名字模型就會寫進圖裡）
        listOf("台東車站", "糖廠", "豬血湯").forEach { assertFalse(it, p.contains(it)) }
        assertFalse(p.contains("圓點 4"))
        assertTrue(p.contains("整張圖不能有任何文字"))
        assertTrue(p.contains("9:16 直式"))
        assertTrue(p.contains("可愛的小旅人"))
    }

    @Test
    fun `描述指令帶站名與類型，壞掉的描述略過`() {
        val l = PosterLayout.build(trip)
        val d = PosterLayout.describePrompt(l, "台東")
        assertTrue(d.contains("1. 台東車站\n2. 糖廠\n3. 豬血湯"))
        assertEquals(mapOf(1 to "有月台的車站", 3 to "一碗熱湯"),
            PosterLayout.parseDescriptions("""{"1":"有月台的車站","2":"  ","3":"一碗熱湯","9":"多的"}""", l))
        assertEquals(emptyMap<Int, String>(), PosterLayout.parseDescriptions("不是 JSON", l))
    }

    @Test
    fun `同一天的行程時間縮成一個日期，跨日與其他格式不動`() {
        assertEquals("2026/10/17 11:00–17:00", PosterLayout.dateLabel("2026/10/17 11:00 - 2026/10/17 17:00"))
        assertEquals("2026/10/17 09:00 - 2026/10/18 17:00", PosterLayout.dateLabel("2026/10/17 09:00 - 2026/10/18 17:00"))
        assertEquals("6小時", PosterLayout.dateLabel("6小時"))
    }
}
