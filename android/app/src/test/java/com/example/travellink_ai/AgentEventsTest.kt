package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentEvent
import com.example.travellink_ai.data.agent.AgentEvents
import com.example.travellink_ai.data.agent.AgentRequest
import com.example.travellink_ai.data.agent.AgentStop
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentTrip
import com.example.travellink_ai.data.agent.RainScenario
import com.example.travellink_ai.data.agent.SeaScenario
import com.example.travellink_ai.data.agent.SseParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 旅程應變 Agent 的事件解析與請求格式。fixture 是後端 gpt-oss-120b 實際執行錄下的事件
 * （rain／tired／no-issue／ferry），加上 App 合成的 question／fallback／error。
 */
class AgentEventsTest {

    private fun events(name: String): List<AgentEvent> {
        val o = JSONObject(File("src/debug/assets/agent_fixtures/$name.json").readText(Charsets.UTF_8))
        val arr = o.getJSONArray("events")
        return (0 until arr.length()).map { AgentEvents.parse(arr.getJSONObject(it)) }
    }

    @Test
    fun everyFixture_parsesFully_startsWithStart_endsWithTerminal() {
        for (name in listOf("rain", "tired", "no-issue", "ferry", "question", "fallback", "error")) {
            val es = events(name)
            assertTrue("$name 不該有不認得的事件：${es.filterIsInstance<AgentEvent.Unknown>()}",
                es.none { it is AgentEvent.Unknown })
            assertTrue("$name 第一個是 start", es.first() is AgentEvent.Start)
            assertTrue("$name 最後一個是結尾事件", es.last().isTerminal)
            assertEquals("$name 只有最後一個是結尾事件", 1, es.count { it.isTerminal })
            assertEquals("$name ms 不倒退", es.map { it.ms }, es.map { it.ms }.sorted())
        }
    }

    @Test
    fun rain_proposalReplacesOutdoorStops_withNewIdsPointingBack() {
        val p = events("rain").last() as AgentEvent.Proposal
        assertTrue(p.simulated)
        assertFalse(p.fallback)
        assertEquals(listOf("replace", "replace"), p.changes.map { it.type })
        assertEquals("臺東森林公園", p.changes[0].from)
        assertEquals("臺東美術館", p.changes[0].to)
        val added = p.draftStops.filter { it.agentAdded }
        assertEquals(listOf("s3", "s4"), added.map { it.replaces })
        assertTrue("替換的站拿到新的 id", added.none { it.id == it.replaces })
        assertEquals(5, p.meta!!.llmCalls)
        val check = events("rain").filterIsInstance<AgentEvent.CheckResult>().single()
        assertEquals(2, check.issues.size)
    }

    @Test
    fun tired_retimeCarriesStayChange() {
        val p = events("tired").last() as AgentEvent.Proposal
        val retime = p.changes.single { it.type == "retime" }
        assertEquals("臺東森林公園", retime.name)
        assertEquals(90, retime.stayFrom)
        assertEquals(60, retime.stayTo)
        assertEquals(2, p.changes.count { it.type == "remove" })
        assertEquals(3, p.draftStops.size)
    }

    @Test
    fun ferry_movesReturnLegEarlier() {
        val p = events("ferry").last() as AgentEvent.Proposal
        val move = p.changes.first { it.type == "move" }
        assertEquals(3, move.fromDay)
        assertEquals(2, move.day)
        val ret = p.ferry!!.after.single { it.direction == "回程" }
        assertEquals(2, ret.day)
        assertEquals(3, p.ferry!!.before.single { it.direction == "回程" }.day)
        assertTrue(p.ferry!!.note.isNotBlank())
    }

    @Test
    fun noIssue_skipsLlm() {
        val e = events("no-issue").last() as AgentEvent.NoChange
        assertTrue(e.llmSkipped)
    }

    @Test
    fun syntheticEndings() {
        val q = events("question").last() as AgentEvent.Question
        assertEquals(2, q.options.size)
        val fb = events("fallback")
        assertTrue(fb.any { it is AgentEvent.Fallback })
        assertTrue((fb.last() as AgentEvent.Proposal).fallback)
        val err = events("error").last() as AgentEvent.Error
        assertTrue(err.message.isNotBlank())
        assertNull(err.status)
    }

    @Test
    fun unknownType_doesNotCrash() {
        val e = AgentEvents.parse("""{"type":"thinking","ms":5}""")
        assertTrue(e is AgentEvent.Unknown)
        assertNull(AgentEvents.parse("not json"))
    }

    @Test
    fun sse_handlesSplitChunksAndCrlf() {
        val p = SseParser()
        val out = mutableListOf<String>()
        // 第一個事件被切成三段、第二第三個在同一段，行尾混用 \r\n 與 \n
        out += p.feed("data: {\"type\":\"st")
        out += p.feed("art\",\"ms\":1}\r")
        out += p.feed("\n\r\ndata: {\"type\":\"check\",\"label\":\"x\",\"ms\":2}\n\ndata: {\"type\":\"no_change\",\"reason\":\"ok\",\"ms\":3}")
        assertEquals(2, out.size)
        out += p.finish()   // 最後一個沒有空行收尾
        assertEquals(listOf("start", "check", "no_change"), out.map { JSONObject(it).getString("type") })
    }

    @Test
    fun sse_ignoresCommentsAndOtherFields() {
        val p = SseParser()
        val out = p.feed(": keep-alive\n\nevent: message\nid: 1\ndata: {\"type\":\"check\",\"ms\":1}\n\n")
        assertEquals(1, out.size)
    }

    @Test
    fun request_json_matchesHandoffFormat() {
        val trip = AgentTrip(
            title = "台東一日遊", region = "台東", startDate = "2026-10-03", endTime = "20:00", people = 2,
            stops = listOf(
                AgentStop("s1", 1, "09:00", 60, "臺東森林公園", 22.76, 121.15, timeLocked = true, keepReason = "已訂位"),
                AgentStop("s2", 1, "11:00", 45, "鐵花村")   // 沒有座標的站不送 lat/lng
            )
        )
        val weather = AgentRequest(trip, AgentTrigger.Weather, rain = RainScenario("2026-10-03", "13:00", "17:00")).toJson()
        assertEquals("weather", weather.getJSONObject("trigger").getString("type"))
        assertEquals(80, weather.getJSONObject("scenario").getJSONObject("rain").getInt("pop"))
        val stops = weather.getJSONObject("trip").getJSONArray("stops")
        assertTrue(stops.getJSONObject(0).getBoolean("timeLocked"))
        assertFalse(stops.getJSONObject(1).has("lat"))
        assertFalse(stops.getJSONObject(1).has("timeLocked"))

        val user = AgentRequest(trip, AgentTrigger.User("好累".repeat(200)), sea = SeaScenario("2026-10-05")).toJson()
        assertEquals(300, user.getJSONObject("trigger").getString("message").length)
        assertFalse(user.getJSONObject("scenario").has("rain"))
        assertEquals(3.5, user.getJSONObject("scenario").getJSONObject("sea").getDouble("waveMaxM"), 0.0)
        assertFalse(AgentRequest(trip, AgentTrigger.Weather).toJson().has("scenario"))
    }
}
