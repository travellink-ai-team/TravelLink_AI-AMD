package com.example.travellink_ai

import com.example.travellink_ai.ui.explore.QuickPick
import com.example.travellink_ai.ui.planning.CostReference
import com.example.travellink_ai.ui.planning.PlaceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** 首頁「現在去哪？」：本地資料、現在有開、由近到遠放寬、天氣與時段加權 */
class QuickPickTest {

    // 緯度差 0.01 ≈ 1.1 公里
    private val lat = 22.7554
    private val lng = 121.1465

    private fun place(
        name: String, dLat: Double = 0.005, type: String = "景點", hours: String = "24 小時營業",
        rating: Double = 4.5, desc: String = ""
    ) = PlaceCost(name, lat + dLat, lng, null, "", null, "", -1, type, hours, 60, rating, desc)

    private fun mins(t: String) = t.substringBefore(":").toInt() * 60 + t.substringAfter(":").toInt()

    private fun cond(now: String = "14:00", rain: Int = -1) =
        QuickPick.Conditions(lat, lng, mins(now), "2026/11/02", "2026/11/01", rain)

    private fun names(r: QuickPick.Result) = r.candidates.map { it.place.name }.toSet()

    @Test
    fun `遊客中心不推、現在沒開的不推`() {
        val places = listOf(
            place("加路蘭"), place("小野柳遊客中心"), place("某博物館", hours = "09:00 - 12:00"),
            place("步道A"), place("步道B")
        )
        val r = QuickPick.candidates(places, cond("14:00"), QuickPick.Category.ANY)
        assertEquals(setOf("加路蘭", "步道A", "步道B"), names(r))
    }

    @Test
    fun `關門前待不滿一小時的不推，有開的標出幾點關`() {
        val museum = place("某博物館", hours = "09:00 - 17:00")
        val others = listOf(place("a"), place("b"), place("c"))
        assertTrue("某博物館" !in names(QuickPick.candidates(others + museum, cond("16:30"), QuickPick.Category.ANY)))
        val c = QuickPick.candidates(others + museum, cond("15:00"), QuickPick.Category.ANY)
            .candidates.first { it.place.name == "某博物館" }
        assertEquals("營業到 17:00", QuickPick.hoursLabel(c))
    }

    @Test
    fun `沒有營業時間資料的照樣列入，但分數比較低`() {
        val r = QuickPick.candidates(
            listOf(place("有資料"), place("沒資料", hours = ""), place("c")), cond(), QuickPick.Category.ANY
        )
        val known = r.candidates.first { it.place.name == "有資料" }
        val unknown = r.candidates.first { it.place.name == "沒資料" }
        assertNull(unknown.open)
        assertEquals("營業時間未確認", QuickPick.hoursLabel(unknown))
        assertTrue(unknown.score < known.score)
    }

    @Test
    fun `3 公里內不夠 3 個才放寬到 5 公里、再到 10 公里`() {
        val near = listOf(place("近1", 0.005), place("近2", 0.01))
        val mid = place("中", 0.04)        // 約 4.4 公里
        val far = place("遠", 0.08)        // 約 8.9 公里
        assertEquals(3.0, QuickPick.candidates(near + place("近3", 0.015), cond(), QuickPick.Category.ANY).radiusKm, 0.0)
        val r5 = QuickPick.candidates(near + mid + far, cond(), QuickPick.Category.ANY)
        assertEquals(5.0, r5.radiusKm, 0.0)
        assertEquals(setOf("近1", "近2", "中"), names(r5))
        assertEquals(10.0, QuickPick.candidates(near + far, cond(), QuickPick.Category.ANY).radiusKm, 0.0)
    }

    @Test
    fun `吃的：附近沒有餐廳資料、和有資料但都沒開，要分開說`() {
        val sights = listOf(place("加路蘭"))
        assertEquals(QuickPick.Empty.NO_DATA, QuickPick.candidates(sights, cond(), QuickPick.Category.FOOD).empty)
        val closed = sights + place("午餐店", type = "餐廳", hours = "11:00 - 14:00")
        assertEquals(QuickPick.Empty.ALL_CLOSED, QuickPick.candidates(closed, cond("20:00"), QuickPick.Category.FOOD).empty)
        val r = QuickPick.candidates(closed + place("咖啡", type = "咖啡廳"), cond("20:00"), QuickPick.Category.FOOD)
        assertEquals(setOf("咖啡"), names(r))
    }

    @Test
    fun `下雨時戶外景點降權、室內加權`() {
        val places = listOf(place("森林步道"), place("某美術館"), place("x"))
        fun score(r: QuickPick.Result, n: String) = r.candidates.first { it.place.name == n }.score
        val dry = QuickPick.candidates(places, cond(rain = 10), QuickPick.Category.ANY)
        assertEquals(score(dry, "森林步道"), score(dry, "某美術館"), 1e-9)
        val wet = QuickPick.candidates(places, cond(rain = 80), QuickPick.Category.ANY)
        assertTrue(score(wet, "森林步道") < score(wet, "某美術館") / 3)
        val c = wet.candidates.first { it.place.name == "某美術館" }
        assertEquals("接下來降雨機率 80%，挑了室內的", QuickPick.hint(c, cond(rain = 80)))
    }

    @Test
    fun `晚上 24 小時開放的海灘大幅降權，夜市不受影響`() {
        val places = listOf(place("杉原海灘"), place("觀光夜市"), place("x"))
        val r = QuickPick.candidates(places, cond("21:00"), QuickPick.Category.ANY)
        val beach = r.candidates.first { it.place.name == "杉原海灘" }
        val market = r.candidates.first { it.place.name == "觀光夜市" }
        assertTrue(beach.score < market.score * 0.2)
    }

    @Test
    fun `凌晨看昨天的跨夜時段`() {
        val bar = place("宵夜店", type = "餐廳", hours = "18:00 - 02:00")
        val c = QuickPick.candidates(listOf(bar), cond("00:30"), QuickPick.Category.FOOD).candidates.single()
        assertEquals(true, c.open)
        assertEquals("營業到 02:00", QuickPick.hoursLabel(c))
        assertEquals("02:00 就關門，要去得快一點", QuickPick.hint(c, cond("00:30")))
        // 01:30 到、待不滿一小時就關了
        assertTrue(QuickPick.candidates(listOf(bar), cond("01:30"), QuickPick.Category.FOOD).candidates.isEmpty())
    }

    @Test
    fun `換一個不重複，全部看過回 null`() {
        val r = QuickPick.candidates(listOf(place("a"), place("b"), place("c")), cond(), QuickPick.Category.ANY)
        val rnd = Random(7)
        val seen = mutableSetOf<String>()
        repeat(3) { seen += QuickPick.draw(r.candidates, seen, rnd)!!.place.name }
        assertEquals(setOf("a", "b", "c"), seen)
        assertNull(QuickPick.draw(r.candidates, seen, rnd))
    }

    @Test
    fun `室內戶外關鍵字`() {
        assertEquals(QuickPick.Setting.INDOOR, QuickPick.classify(place("國立臺灣史前文化博物館")))
        assertEquals(QuickPick.Setting.OUTDOOR, QuickPick.classify(place("鹿野高台")))
        assertEquals(QuickPick.Setting.INDOOR, QuickPick.classify(place("某海鮮", type = "餐廳")))
        assertEquals(QuickPick.Setting.UNKNOWN, QuickPick.classify(place("無名之地")))
        assertEquals(QuickPick.Setting.OUTDOOR, QuickPick.classify(place("無名之地", desc = "可以看日出的露天平台")))
    }

    @Test
    fun `真實資料：八成以上的景點分得出室內戶外`() {
        val places = CostReference.parsePlaces(File("src/main/assets/local_places.json").readText(Charsets.UTF_8))
        val sights = places.filter { QuickPick.matches(it, QuickPick.Category.SIGHT) }
        val unknown = sights.filter { QuickPick.classify(it) == QuickPick.Setting.UNKNOWN }
        println("分不出室內戶外 ${unknown.size}/${sights.size}：${unknown.joinToString("、") { it.name }}")
        assertTrue("${unknown.size}/${sights.size}", unknown.size * 5 <= sights.size)
    }

    @Test
    fun `距離顯示`() {
        assertEquals("350 公尺", QuickPick.distanceLabel(0.34))
        assertEquals("2.1 公里", QuickPick.distanceLabel(2.06))
    }
}
