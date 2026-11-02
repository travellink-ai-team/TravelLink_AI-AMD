package com.example.travellink_ai

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.recap.RecapMusic
import com.example.travellink_ai.ui.recap.RecapPhoto
import com.example.travellink_ai.ui.recap.RecapStop
import com.example.travellink_ai.ui.recap.RecapTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回顧短片配樂建議（C6）：指令內容、回應解析、代表照挑選、夜間判斷 */
class RecapMusicTest {
    private val trip = RecapTrip("台東兩天", "台東", "", "", 40.0, "car", listOf(
        RecapStop("a", "鐵花村", 22.75, 121.14, "car", 60, 1),
        RecapStop("b", "三仙台", 23.12, 121.40, "car", 60, 2)
    ))

    @Test fun moodBaseFollowsTemplate() {
        assertEquals("輕快、高能量", RecapMusic.moodBase("brisk"))
        assertEquals("明亮、溫暖", RecapMusic.moodBase("standard"))
        assertEquals("舒緩、悠閒", RecapMusic.moodBase("leisurely"))
        assertEquals("電影感、壯闊", RecapMusic.moodBase("cinematic"))
        assertEquals("明亮、溫暖", RecapMusic.moodBase("unknown"))
    }

    @Test fun promptCarriesTripFacts() {
        val hints = mapOf("a" to RecapMusic.StopHint("夜市", true), "b" to RecapMusic.StopHint("自然景觀", false))
        val p = RecapMusic.prompt(trip, "brisk", hints, 2)
        assertTrue(p.contains("共 2 天、2 個景點"))
        assertTrue(p.contains("有晚上的行程：有"))
        assertTrue(p.contains("1. 鐵花村（夜市）・第 1 天"))
        assertTrue(p.contains("氣氛基調：輕快、高能量"))
        assertTrue(p.contains("另附 2 張照片"))
        assertTrue(p.contains("不可建議具體的歌名"))
    }

    @Test fun promptWithoutPhotosDropsPhotoLines() {
        val p = RecapMusic.prompt(trip, "standard", emptyMap(), 0)
        // 節奏說明本身有「看照片」，只檢查附照片才有的句子
        assertFalse(p.contains("另附"))
        assertFalse(p.contains("與照片"))
        assertTrue(p.contains("有晚上的行程：沒有"))
        assertTrue(p.contains("1. 鐵花村・第 1 天"))
    }

    @Test fun parseCleansKeywordsAndTrimsReason() {
        val s = RecapMusic.parse("""```json
            {"mood":" 輕快明亮 ","reason":"${"海".repeat(40)}","keywords":["#summer chill","Summer Chill","城市漫遊","acoustic beach","多的"]}
            ```""")!!
        assertEquals("輕快明亮", s.mood)
        assertEquals(RecapMusic.REASON_MAX, s.reason.length)
        assertEquals(listOf("summer chill", "城市漫遊", "acoustic beach"), s.keywords)
    }

    @Test fun parseRejectsMissingFields() {
        assertNull(RecapMusic.parse("沒有 JSON"))
        assertNull(RecapMusic.parse("""{"mood":"","keywords":["a"]}"""))
        assertNull(RecapMusic.parse("""{"mood":"舒緩","keywords":[]}"""))
    }

    @Test fun pickPhotosKeepsOrderAndEnds() {
        val photos = (0 until 10).reversed().map { RecapPhoto(it, "u$it") }
        val picked = RecapMusic.pickPhotos(photos).map { it.stopIndex }
        assertEquals(RecapMusic.MAX_PHOTOS, picked.size)
        assertEquals(0, picked.first())
        assertEquals(9, picked.last())
        assertEquals(picked.sorted(), picked)
        assertEquals(listOf(1, 3), RecapMusic.pickPhotos(listOf(RecapPhoto(3, "x"), RecapPhoto(1, "y"))).map { it.stopIndex })
    }

    @Test fun hintsDetectNightButNotLodging() {
        fun stop(id: String, time: String, best: String = "", lodging: Boolean = false, station: Boolean = false) =
            Stop(name = id, time = time, desc = "", emoji = "", duration = 60, order = 0, stopId = id,
                bestTime = best, stopType = "景點", isLodging = lodging, isStation = station)
        val item = LocalItinerary(title = "t", aiTitle = "", aiReply = "", region = "台東", days = "1", people = "2",
            stops = listOf(stop("day", "10:00"), stop("eve", "19:30"), stop("best", "15:00", best = "晚上"),
                stop("hotel", "20:00", lodging = true), stop("st", "21:00", station = true)))
        val h = RecapMusic.hints(item)
        assertFalse(h.getValue("day").night)
        assertTrue(h.getValue("eve").night)
        assertTrue(h.getValue("best").night)
        assertFalse(h.getValue("hotel").night)
        assertFalse(h.containsKey("st"))
        assertEquals("景點", h.getValue("day").stopType)
    }
}
