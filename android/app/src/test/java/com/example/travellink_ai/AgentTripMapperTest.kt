package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.DraftStop
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旅程應變 Agent：App 行程 → 請求，與草稿套回行程 */
class AgentTripMapperTest {

    private fun stop(
        id: String, name: String, day: Int = 1, time: String = "10:00", dur: Long = 60,
        station: Boolean = false, lodging: Boolean = false, ferry: Boolean = false
    ) = Stop(
        name = name, time = time, desc = "", emoji = "📍", duration = dur, order = 0,
        isStation = station, isLodging = lodging, isFerry = ferry, stopId = id, dayIndex = day
    )

    private fun draft(id: String, name: String, day: Int, time: String, stay: Int = 60,
                      replaces: String? = null, added: Boolean = false) =
        DraftStop(id, day, time, stay, name, 22.75, 121.15, "scenic", replaces, added)

    private val oneDay = listOf(
        stop("st", "台東車站", time = "09:00", station = true),
        stop("a", "臺東森林公園", time = "09:15"),
        stop("b", "加路蘭", time = "11:00"),
        stop("c", "鐵花村", time = "14:00"),
        stop("en", "台東車站", time = "17:00", station = true)
    )

    private fun itin(stops: List<Stop>, days: String = "2026/10/03 09:00 - 2026/10/03 18:00", region: String = "台東") =
        Itinerary(title = "台東一日遊", aiTitle = "", aiReply = "", region = region, stops = stops, days = days, people = "2人")

    @Test
    fun build_sendsOnlyAdjustableStops_withIsoDateAndEndTime() {
        val b = AgentTripMapper.build(itin(oneDay))!!
        assertEquals(setOf("a", "b", "c"), b.sentIds)
        assertEquals("2026-10-03", b.trip.startDate)
        assertEquals("18:00", b.trip.endTime)
        assertEquals("台東", b.trip.region)
        assertEquals(2, b.trip.people)
        assertEquals(listOf("09:15", "11:00", "14:00"), b.trip.stops.map { it.time })
    }

    @Test
    fun build_skipsDoneStops_keepsFerryAnchors_mapsIsland() {
        val stops = listOf(
            stop("p1", "富岡漁港", ferry = true, station = true),
            stop("x", "綠島燈塔"),
            stop("l", "民宿", lodging = true),
            stop("y", "朝日溫泉", day = 2),
            stop("p2", "南寮漁港", day = 2, ferry = true)
        )
        val b = AgentTripMapper.build(itin(stops, "2026/10/03 08:00 - 2026/10/04 18:00", region = "綠島"), doneIds = setOf("x"))!!
        assertEquals(setOf("p1", "y", "p2"), b.sentIds)
        assertEquals("綠島", b.trip.region)
    }

    @Test
    fun build_nullWithoutDatesOrStops() {
        assertNull(AgentTripMapper.build(itin(oneDay, days = "3小時")))
        assertNull(AgentTripMapper.build(itin(oneDay.filter { it.isStation })))
    }

    @Test
    fun canApply_rejectsDraftFromAnotherTrip() {
        val sent = setOf("a", "b", "c")
        assertTrue(AgentTripMapper.canApply(sent, listOf(draft("a", "x", 1, "09:00"), draft("n1", "y", 1, "10:00", added = true))))
        assertFalse("錄製 mock 的 id 對不上", AgentTripMapper.canApply(sent, listOf(draft("s1", "x", 1, "09:00"))))
        assertFalse(AgentTripMapper.canApply(sent, emptyList()))
    }

    @Test
    fun merge_replaceAndRemove_keepsStationsInPlace() {
        val sent = setOf("a", "b", "c")
        val d = listOf(
            draft("a", "臺東森林公園", 1, "09:15", stay = 45),
            draft("n1", "臺東美術館", 1, "11:00", replaces = "b", added = true)
            // c（鐵花村）被刪掉
        )
        val out = AgentTripMapper.merge(oneDay, sent, d)
        assertEquals(listOf("台東車站", "臺東森林公園", "臺東美術館", "台東車站"), out.map { it.name })
        assertEquals(45L, out[1].duration)
        assertEquals("a", out[1].stopId)            // 原站保留 id（共編、照片都靠它）
        assertTrue(out[2].stopId !in setOf("b", "n1")) // 新站拿 App 自己的 id
        assertEquals("景點", out[2].stopType)
        assertEquals(listOf(1L, 2L, 3L, 4L), out.map { it.order })
    }

    @Test
    fun merge_leavesDoneStopsUntouched() {
        // 進行中：a 已打卡，只送 b、c
        val sent = setOf("b", "c")
        val d = listOf(draft("c", "鐵花村", 1, "12:00"))
        val out = AgentTripMapper.merge(oneDay, sent, d)
        assertEquals(listOf("台東車站", "臺東森林公園", "鐵花村", "台東車站"), out.map { it.name })
        assertEquals("a", out[1].stopId)
    }

    @Test
    fun merge_moveToEarlierDay_ferryCase() {
        // 三天：第 3 天的回程船班整段提前到第 2 天，第 3 天的島上站全刪
        val stops = listOf(
            stop("st", "台東車站", station = true),
            stop("g1", "富岡漁港", 1, "08:00", ferry = true),
            stop("g3", "綠島遊客中心", 1, "10:00"),
            stop("g9", "梅花鹿生態園區", 2, "09:00"),
            stop("g12", "過山古道", 2, "14:00"),
            stop("g15", "大白沙", 3, "09:00"),
            stop("g19", "南寮漁港", 3, "14:30", ferry = true),
            stop("g20", "富岡漁港", 3, "16:30", ferry = true),
            stop("en", "台東車站", 3, "18:00", station = true)
        )
        val sent = AgentTripMapper.build(itin(stops, "2026/10/03 08:00 - 2026/10/05 18:00", "綠島"))!!.sentIds
        val d = listOf(
            draft("g1", "富岡漁港", 1, "08:00"),
            draft("g3", "綠島遊客中心", 1, "10:00"),
            draft("g9", "梅花鹿生態園區", 2, "09:00"),
            draft("g19", "南寮漁港", 2, "15:00"),
            draft("g20", "富岡漁港", 2, "17:00")
        )
        val out = AgentTripMapper.merge(stops, sent, d)
        assertEquals(
            listOf("台東車站", "富岡漁港", "綠島遊客中心", "梅花鹿生態園區", "南寮漁港", "富岡漁港", "台東車站"),
            out.map { it.name }
        )
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3), out.map { it.dayIndex })
        assertTrue(out[4].isFerry)                  // 原站的旗標跟著保留
    }

    @Test
    fun merge_dayWithoutSentStops_insertedBeforeNextDay() {
        // 第 2 天原本只有住宿錨點；Agent 在第 2 天新增一站
        val stops = listOf(
            stop("a", "A", 1, "09:00"),
            stop("l", "民宿", 1, "18:00", lodging = true),
            stop("b", "B", 3, "09:00")
        )
        val d = listOf(
            draft("a", "A", 1, "09:00"),
            draft("n", "新景點", 2, "10:00", added = true),
            draft("b", "B", 3, "09:00")
        )
        val out = AgentTripMapper.merge(stops, setOf("a", "b"), d)
        assertEquals(listOf("A", "民宿", "新景點", "B"), out.map { it.name })
    }
}
