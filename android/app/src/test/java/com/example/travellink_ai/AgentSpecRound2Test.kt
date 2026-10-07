package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentChange
import com.example.travellink_ai.data.agent.AgentEvent
import com.example.travellink_ai.data.agent.AgentEvents
import com.example.travellink_ai.data.agent.AgentRequest
import com.example.travellink_ai.data.agent.AgentStop
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentTrip
import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.DelayFrom
import com.example.travellink_ai.data.agent.DraftStop
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.stableStopIds
import com.example.travellink_ai.data.model.webCollabStopId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 規格第二輪（2026-10-03）：id 碰撞、固定時間規則、retimeOnly */
class AgentSpecRound2Test {

    @Test
    fun stableStopIds_keepsStoredIds_suffixesCollisionsOnNewOnes() {
        val derived = webCollabStopId("", "加路蘭", 1)
        val ids = stableStopIds(listOf(
            mapOf("name" to "A", "collabStopId" to derived),   // 舊的已存了這個值
            mapOf("name" to "加路蘭"),                           // 現算剛好撞到 → 加 -2
            mapOf("name" to "B", "stopId" to "s-b"),
            mapOf("name" to "C")
        ))
        assertEquals(derived, ids[0])
        assertEquals("$derived-2", ids[1])
        assertEquals("s-b", ids[2])
        assertEquals(webCollabStopId("", "C", 3), ids[3])
        assertEquals(4, ids.toSet().size)
    }

    private fun stop(id: String, name: String, time: String, dur: Long = 60, day: Int = 1,
                     manualStart: Int? = null, manualEnd: Int? = null) =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = dur, order = 0,
            stopId = id, dayIndex = day, manualStartMin = manualStart, manualEndMin = manualEnd)

    private val current = listOf(
        stop("a", "臺東森林公園", "13:30", 90),
        stop("b", "加路蘭", "15:30", 60, manualStart = 930, manualEnd = 990),
        stop("c", "鐵花村", "17:30", 90)
    )
    private val sentStops = current.map { AgentStop(it.stopId, it.dayIndex, it.time, it.duration.toInt(), it.name) }
    private val trip = AgentTrip("t", "台東", "2026-10-03", "20:00", 2, stops = sentStops)

    private fun draft(id: String, name: String, time: String, stay: Int) =
        DraftStop(id, 1, time, stay, name, null, null, "scenic", null, false)

    @Test
    fun weatherProposal_locksExplicitRetime_shiftsExistingManual() {
        val req = AgentRequest(trip, AgentTrigger.Weather)
        val changes = listOf(
            AgentChange("retime", 1, null, "13:30", "臺東森林公園", "13:30", "14:00", 90, 60),
            AgentChange("retime", 1, null, null, "加路蘭", "15:30", "16:00", null, null)
        )
        val lock = AgentTripMapper.TimeLock.of(req, changes)!!
        val out = AgentTripMapper.merge(current, setOf("a", "b", "c"), listOf(
            draft("a", "臺東森林公園", "14:00", 60),     // 明確 retime → 固定成 14:00
            draft("b", "加路蘭", "16:00", 60),           // 原本就手動 → 照差值 +30 平移
            draft("c", "鐵花村", "18:00", 90)            // 只是被順延，沒 retime → 不固定
        ), timeLock = lock)
        assertEquals(14 * 60, out[0].manualStartMin)
        assertNull("停留改了但原本沒有手動起訖 → manualEndMin 為 null", out[0].manualEndMin)
        assertEquals(960, out[1].manualStartMin)
        assertEquals(990, out[1].manualEndMin)          // 停留沒變，起訖不動
        assertNull(out[2].manualStartMin)
    }

    @Test
    fun weatherProposal_stayChangeOnManualStop_updatesManualEnd() {
        val req = AgentRequest(trip, AgentTrigger.User("好累"))
        val lock = AgentTripMapper.TimeLock.of(req, emptyList())!!
        val out = AgentTripMapper.merge(current, setOf("b"), listOf(draft("b", "加路蘭", "15:30", 40)), timeLock = lock)
        val b = out.single { it.stopId == "b" }
        assertEquals(930, b.manualStartMin)
        assertEquals(970, b.manualEndMin)
        assertEquals(40L, b.duration)
    }

    @Test
    fun multiDayManualStart_carriesDayOffset() {
        val d2 = listOf(stop("x", "加路蘭", "10:00", day = 2))
        val t2 = trip.copy(stops = listOf(AgentStop("x", 2, "10:00", 60, "加路蘭")))
        val lock = AgentTripMapper.TimeLock.of(AgentRequest(t2, AgentTrigger.Weather),
            listOf(AgentChange("retime", 2, null, null, "加路蘭", "10:00", "11:00", null, null)))!!
        val out = AgentTripMapper.merge(d2, setOf("x"),
            listOf(DraftStop("x", 2, "11:00", 60, "加路蘭", null, null, "scenic", null, false)), timeLock = lock)
        assertEquals(1440 + 660, out.single().manualStartMin)   // 同網頁 (day−1)×1440＋時刻
    }

    @Test
    fun delayProposal_neverLocksTime() {
        val req = AgentRequest(trip, AgentTrigger.Delay(1, "2026-10-03T14:30:00+08:00",
            DelayFrom("z", "某站", null, null, "2026-10-03T15:20:00+08:00", 50)))
        assertNull("延誤提案不鎖時間", AgentTripMapper.TimeLock.of(req, listOf(
            AgentChange("retime", 1, null, null, "臺東森林公園", "13:30", "14:20", 90, 60))))
        val out = AgentTripMapper.merge(current, setOf("a", "b", "c"), listOf(
            draft("a", "臺東森林公園", "14:20", 60),
            draft("b", "加路蘭", "16:20", 60)
        ), timeLock = null)
        assertNull(out[0].manualStartMin)
        assertEquals(60L, out[0].duration)              // 縮短停留照套
        assertEquals("原本的手動時間不動", 930, out[1].manualStartMin)
        assertTrue(out.none { it.stopId == "c" })       // 刪站照套
    }

    @Test
    fun endTime_neverEarlierThanPlannedReturn() {
        // 實測：精靈選 14:00 收工，App 排出的回程車站在 14:07 → 送 14:00 會讓後端還沒延誤就判定排不下
        val stops = listOf(
            Stop(name = "台東車站", time = "09:00", desc = "", emoji = "", duration = 0, order = 1, isStation = true, stopId = "st"),
            Stop(name = "臺東森林公園", time = "12:30", desc = "", emoji = "", duration = 60, order = 2, stopId = "a"),
            Stop(name = "台東車站", time = "14:07", desc = "", emoji = "", duration = 0, order = 3, isStation = true, stopId = "en")
        )
        val itin = com.example.travellink_ai.data.model.Itinerary(
            title = "t", aiTitle = "", aiReply = "", region = "台東", stops = stops,
            days = "2026/10/03 09:00 - 2026/10/03 14:00", people = "2人"
        )
        assertEquals("14:07", AgentTripMapper.build(itin)!!.trip.endTime)
        // 排得比收工早時維持精靈的時間
        val early = itin.copy(stops = stops.map { if (it.stopId == "en") it.copy(time = "13:40") else it })
        assertEquals("14:00", AgentTripMapper.build(early)!!.trip.endTime)
    }

    @Test
    fun proposal_parsesRetimeOnlyAndLlmSkipped() {
        val p = AgentEvents.parse("""{"type":"proposal","summary":"順延","retimeOnly":true,"llmSkipped":true,
            "changes":[{"type":"retime","name":"A","from":"14:00","to":"14:50"}],"draft":{"stops":[]},"ms":54}""")
            as AgentEvent.Proposal
        assertTrue(p.retimeOnly)
        assertTrue(p.llmSkipped)
    }
}
