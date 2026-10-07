package com.example.travellink_ai

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.TripStatus
import com.example.travellink_ai.ui.planning.matchesQuery
import com.example.travellink_ai.ui.planning.statusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 我的微旅行：狀態徽章與搜尋規則（對齊網頁 MT_STATUS_LABEL / filterMyTripsList）。
 */
class MyTripsFilterTest {

    private fun stop(name: String, isStation: Boolean = false) =
        Stop(name = name, time = "09:00", desc = "", emoji = "📍", duration = 60, order = 1, isStation = isStation)

    private fun trip(
        title: String = "台東慢遊",
        region: String = "台東",
        days: String = "2026/06/10 09:00 - 2026/06/10 17:00",
        stops: List<Stop> = listOf(stop("台東車站", isStation = true), stop("鐵花村"))
    ) = LocalItinerary(
        title = title, aiTitle = "", aiReply = "", region = region,
        days = days, people = "2人", stops = stops
    )

    private fun tokens(q: String) = q.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)

    @Test
    fun `status follows shared micro_trips status field`() {
        assertEquals(TripStatus.ONGOING, statusOf("ongoing", trip(), isCollab = false))
        assertEquals(TripStatus.COMPLETED, statusOf("completed", trip(), isCollab = true))
        assertEquals(TripStatus.PLANNING, statusOf("planning", trip(), isCollab = false))
        assertEquals(TripStatus.PLANNING, statusOf("", trip(), isCollab = false))
    }

    @Test
    fun `personal trip with only stations is not generated yet, collab is still planning`() {
        val empty = trip(stops = listOf(stop("台東車站", isStation = true)))
        assertEquals(TripStatus.NOT_GENERATED, statusOf("", empty, isCollab = false))
        assertEquals(TripStatus.PLANNING, statusOf("", empty, isCollab = true))
    }

    @Test
    fun `every token must match - region plus unpadded date`() {
        val t = trip()
        assertTrue(matchesQuery(t, TripStatus.PLANNING, false, tokens("台東 6/10")))
        assertTrue(matchesQuery(t, TripStatus.PLANNING, false, tokens("台東 06/10")))
        assertFalse(matchesQuery(t, TripStatus.PLANNING, false, tokens("花蓮 6/10")))
    }

    @Test
    fun `status and collab keywords are searchable`() {
        val t = trip()
        assertTrue(matchesQuery(t, TripStatus.ONGOING, false, tokens("進行中")))
        assertFalse(matchesQuery(t, TripStatus.PLANNING, false, tokens("進行中")))
        assertTrue(matchesQuery(t, TripStatus.PLANNING, true, tokens("共編")))
        assertFalse(matchesQuery(t, TripStatus.PLANNING, false, tokens("共編")))
    }

    @Test
    fun `blank query matches everything and search ignores case`() {
        assertTrue(matchesQuery(trip(title = "Taitung Trip"), TripStatus.PLANNING, false, tokens("   ")))
        assertTrue(matchesQuery(trip(title = "Taitung Trip"), TripStatus.PLANNING, false, tokens("taitung")))
    }
}
