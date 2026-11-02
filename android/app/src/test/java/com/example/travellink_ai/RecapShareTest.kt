package com.example.travellink_ai

import com.example.travellink_ai.ui.recap.RecapShare
import com.example.travellink_ai.ui.recap.RecapStop
import com.example.travellink_ai.ui.recap.RecapTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回顧短片分享文案（C5） */
class RecapShareTest {
    private fun trip(n: Int, region: String = "台東縣", days: Int = 2) = RecapTrip("台東慢慢走", region, "", "", 40.0, "car",
        (1..n).map { RecapStop("s$it", "景點$it", 22.7, 121.1, "car", 60, if (it > n / 2) days else 1) })

    @Test fun captionHasRouteLinkAndTags() {
        val c = RecapShare.caption(trip(3))
        assertTrue(c.startsWith("台東慢慢走\n"))
        assertTrue(c.contains("📍 台東縣・2 天 3 個景點"))
        assertTrue(c.contains("景點1 → 景點2 → 景點3"))
        assertTrue(c.contains("travel-link-ai.duckdns.org"))
        assertTrue(c.endsWith("#TravelLinkAI #旅遊回顧 #台東旅遊 #台東景點"))
        // 公開貼文不能帶共編邀請
        assertFalse(c.contains("travellink://"))
    }

    @Test fun longRouteIsShortened() {
        val c = RecapShare.caption(trip(8))
        assertTrue(c.contains("景點1 → 景點2 → 景點3 → 景點4 → 景點5（共 8 站）"))
        assertFalse(c.contains("景點6"))
    }

    @Test fun blankRegionDropsRegionTags() {
        val c = RecapShare.caption(trip(2, region = " "))
        assertTrue(c.contains("📍 2 天 2 個景點"))
        assertTrue(c.endsWith("#TravelLinkAI #旅遊回顧"))
    }

    @Test fun regionTag() {
        assertEquals("台東", RecapShare.regionTag("台東縣"))
        assertEquals("台北", RecapShare.regionTag("台北市"))
        assertEquals("台東", RecapShare.regionTag("台東"))
        assertEquals("花蓮台東", RecapShare.regionTag("花蓮 / 台東"))
        assertNull(RecapShare.regionTag(" ・ "))
    }
}
