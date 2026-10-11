package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentRequest
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.DelayFrom
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** transitToNextMin：只給陣列裡下一站剛好也是 App 行程下一站的那段 */
class AgentTransitTest {

    private fun stop(
        id: String, day: Int = 1, station: Boolean = false, lodging: Boolean = false, ferry: Boolean = false
    ) = Stop(
        name = id, time = "10:00", desc = "", emoji = "📍", duration = 60, order = 0,
        isStation = station, isLodging = lodging, isFerry = ferry, stopId = id, dayIndex = day
    )

    // 台東車站 → a → b → c → 民宿 ｜ 第 2 天 d → 回程車站
    private val stops = listOf(
        stop("st", station = true), stop("a"), stop("b"), stop("c"), stop("l", lodging = true),
        stop("d", day = 2), stop("en", day = 2, station = true)
    )
    private val mins = listOf(12L, 20L, 0L, 15L, 30L, 25L)

    private fun itin(s: List<Stop>) = Itinerary(
        title = "台東兩天", aiTitle = "", aiReply = "", region = "台東", stops = s,
        days = "2026/11/07 09:00 - 2026/11/08 18:00", people = "2人"
    )

    @Test
    fun transitLegs_skipsZeroCrossDayAndWrongLength() {
        val legs = AgentTripMapper.transitLegs(stops, mins)
        assertEquals(AgentTripMapper.Leg("b", 20), legs["a"])
        assertNull(legs["b"])            // 0 分＝查不到
        assertNull(legs["l"])            // 民宿 → 第 2 天，跨日
        assertEquals(AgentTripMapper.Leg("l", 15), legs["c"])
        assertTrue(AgentTripMapper.transitLegs(stops, mins.drop(1)).isEmpty())
    }

    @Test
    fun transitLegs_skipsFerryAndOutOfRange() {
        val s = listOf(stop("a"), stop("port", ferry = true), stop("x"), stop("y"))
        val legs = AgentTripMapper.transitLegs(s, listOf(10L, 50L, 601L))
        assertTrue(legs.isEmpty())
    }

    @Test
    fun weatherBuild_onlyAdjacentInAppList() {
        val built = AgentTripMapper.build(itin(stops))!!
        // 送出 a、b、c、d（車站與民宿不送）
        assertEquals(listOf("a", "b", "c", "d"), built.trip.stops.map { it.id })
        val req = AgentTripMapper.withTransit(
            AgentRequest(built.trip, AgentTrigger.Weather),
            AgentTripMapper.transitLegs(stops, listOf(12L, 20L, 18L, 15L, 30L, 25L))
        )
        val t = req.trip.stops.associate { it.id to it.transitToNextMin }
        assertEquals(20, t["a"])
        assertEquals(18, t["b"])
        assertNull(t["c"])   // App 下一站是民宿，陣列下一站是 d
        assertNull(t["d"])   // 最後一站
        val json = req.toJson().getJSONObject("trip").getJSONArray("stops")
        assertEquals(20, json.getJSONObject(0).getInt("transitToNextMin"))
        assertFalse(json.getJSONObject(2).has("transitToNextMin"))
    }

    @Test
    fun delay_fromLegOnlyWhenFirstSentIsNext() {
        val legs = AgentTripMapper.transitLegs(stops, listOf(12L, 20L, 18L, 15L, 30L, 25L))
        fun req(done: Set<String>): AgentRequest {
            val b = AgentTripMapper.buildDelay(itin(stops), done, stops[1], day = 1)!!
            val trig = AgentTrigger.Delay(
                day = 1, now = "2026-11-07T10:30:00+08:00",
                from = DelayFrom("a", "a", null, null, "2026-11-07T11:00:00+08:00", 30)
            )
            return AgentTripMapper.withTransit(AgentRequest(b.trip, trig), legs)
        }
        val ok = req(emptySet())
        assertEquals(20, (ok.trigger as AgentTrigger.Delay).from.transitToNextMin)
        assertEquals(
            20, ok.toJson().getJSONObject("trigger").getJSONObject("from").getInt("transitToNextMin")
        )
        // b 已跳過：stops[0] 是 c，不是 a 的下一站
        val skipped = req(setOf("b"))
        assertEquals("c", skipped.trip.stops.first().id)
        assertNull((skipped.trigger as AgentTrigger.Delay).from.transitToNextMin)
    }

    @Test
    fun emptyLegs_clearsPreviousValues() {
        val built = AgentTripMapper.build(itin(stops))!!
        val filled = AgentTripMapper.withTransit(
            AgentRequest(built.trip, AgentTrigger.Weather), AgentTripMapper.transitLegs(stops, mins)
        )
        assertTrue(filled.trip.stops.any { it.transitToNextMin != null })
        val cleared = AgentTripMapper.withTransit(filled, emptyMap())
        assertTrue(cleared.trip.stops.all { it.transitToNextMin == null })
    }
}
