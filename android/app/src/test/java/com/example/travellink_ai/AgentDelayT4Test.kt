package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentRequest
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.AppConflict
import com.example.travellink_ai.data.agent.Deadline
import com.example.travellink_ai.data.agent.DelayFrom
import com.example.travellink_ai.data.agent.DraftStop
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.stableStopId
import com.example.travellink_ai.data.model.webCollabStopId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 延誤觸發（TravelLink_AI-AMD docs/amd-agent/t4-delay-trigger-spec.md）與站點 id 規則 */
class AgentDelayT4Test {

    // ── 第 5 節：站的 id ─────────────────────────────────────────────

    @Test
    fun webCollabStopId_matchesWebAlgorithmBitForBit() {
        // 期望值由網頁 ai-travel-planner-v8.js 的 getStableCollabStopId 以 Node 實際算出
        assertEquals("cstop-1sy2j9p", webCollabStopId("", "臺東森林公園", 2))
        assertEquals("cstop-1r6o797", webCollabStopId("start", "台東車站", 0))
        assertEquals("cstop-oea45i", webCollabStopId("", "Alamu kitchen 阿拉慕廚房", 7))
        assertEquals("cstop-g1megn", webCollabStopId("", "加路蘭", 13))
        assertEquals("cstop-1oq50yn", webCollabStopId("end", "台東車站", 5))
    }

    @Test
    fun stableStopId_prefersCollabStopId_thenStopId_neverWebPrefix() {
        assertEquals("c1", stableStopId(mapOf("collabStopId" to "c1", "stopId" to "s1", "name" to "A"), 0))
        assertEquals("s1", stableStopId(mapOf("collabStopId" to "", "stopId" to "s1", "name" to "A"), 0))
        val derived = stableStopId(mapOf("name" to "臺東森林公園", "order" to 3L), 2)
        assertEquals("cstop-1sy2j9p", derived)
        assertFalse(derived.startsWith("web_"))
    }

    // ── 第 2 節：請求 ─────────────────────────────────────────────────

    private fun stop(
        id: String, name: String, day: Int = 1, time: String = "10:00", dur: Long = 60,
        station: Boolean = false, lodging: Boolean = false, ferry: Boolean = false,
        type: String = "景點", hours: String = "未提供", manual: Int? = null
    ) = Stop(
        name = name, time = time, desc = "", emoji = "📍", duration = dur, order = 0,
        businessHours = hours, isStation = station, isLodging = lodging, isFerry = ferry,
        stopId = id, dayIndex = day, stopType = type, manualStartMin = manual
    )

    private val twoDays = listOf(
        stop("st", "台東車站", 1, "09:00", station = true),
        stop("a", "臺東森林公園", 1, "09:15", hours = "星期六: 24 小時營業"),
        stop("b", "榕樹下米苔目", 1, "11:00", type = "餐廳", manual = 660),
        stop("c", "鐵花村", 1, "14:00"),
        stop("l", "民宿", 1, "18:00", lodging = true),
        stop("d", "加路蘭", 2, "09:00"),
        stop("en", "台東車站", 2, "17:00", station = true)
    )

    private fun itin(stops: List<Stop>) = Itinerary(
        title = "台東兩天", aiTitle = "", aiReply = "", region = "台東", stops = stops,
        days = "2026/11/07 09:00 - 2026/11/08 18:00", people = "2人"
    )

    @Test
    fun buildDelay_sendsOnlyThatDaysUpcomingStops_plusItsAnchors() {
        // 人在 a（還沒離開）：送 b、c 與當天終點的住宿；出發車站（已過）、第 2 天都不送
        val b = AgentTripMapper.buildDelay(itin(twoDays), emptySet(), twoDays[1], day = 1)!!
        assertEquals(listOf("b", "c", "l"), b.trip.stops.map { it.id })
        assertEquals(setOf("b", "c", "l"), b.sentIds)
        val lodging = b.trip.stops.single { it.id == "l" }
        assertEquals("lodging", lodging.anchor)
        assertNull(lodging.stopType)
        val food = b.trip.stops.single { it.id == "b" }
        assertEquals("food", food.stopType)
        assertTrue("manualStartMin 不是 null → timeLocked", food.timeLocked)
        assertNull(b.trip.stops.single { it.id == "c" }.anchor)
        assertFalse(b.trip.stops.single { it.id == "c" }.timeLocked)
        assertEquals("11:00", food.time)   // 原定計畫的時間，不先順延
    }

    @Test
    fun buildDelay_lastDayIncludesReturnStationAsAnchor_skipsDone() {
        val b = AgentTripMapper.buildDelay(itin(twoDays), setOf("x"), twoDays[5], day = 2)
        assertNull("第 2 天在 d 之後只剩回程車站", b?.trip?.stops?.firstOrNull { it.id == "d" })
        assertEquals(listOf("en"), b!!.trip.stops.map { it.id })
        assertEquals("station", b.trip.stops.single().anchor)
        assertEquals("transit", b.trip.stops.single().stopType)

        val done = AgentTripMapper.buildDelay(itin(twoDays), setOf("b"), twoDays[1], day = 1)!!
        assertFalse("已打卡／跳過的站不送", "b" in done.sentIds)
    }

    @Test
    fun weatherBuild_sendsHoursAndType_butNoAnchors() {
        val b = AgentTripMapper.build(itin(twoDays))!!
        assertTrue(b.trip.stops.none { it.anchor != null })
        assertEquals("星期六: 24 小時營業", b.trip.stops.single { it.id == "a" }.businessHours)
        assertNull("未提供不送", b.trip.stops.single { it.id == "c" }.businessHours)
    }

    @Test
    fun isoTimes_carryDateAndTaipeiOffset() {
        assertEquals("2026-11-07T15:20:00+08:00", AgentTripMapper.isoAt("2026-11-07", 15 * 60 + 20))
        assertEquals("2026-11-07T15:20:00+08:00", AgentTripMapper.isoAt("2026/11/07", 920))
        assertEquals("過了午夜算隔天", "2026-11-08T00:30:00+08:00", AgentTripMapper.isoAt("2026-11-07", 24 * 60 + 30))
        // 2026-11-07 06:30 UTC ＝ 台灣 14:30
        assertEquals("2026-11-07T14:30:00+08:00", AgentTripMapper.isoNow(1_794_033_000_000L))
        assertEquals("2026-11-08", AgentTripMapper.dateOfDay("2026-11-07", 2))
    }

    @Test
    fun delayRequest_json_matchesSpecSection2() {
        val built = AgentTripMapper.buildDelay(itin(twoDays), emptySet(), twoDays[1], day = 1)!!
        val req = AgentRequest(
            trip = built.trip.copy(updatedAt = 1_794_050_400_000L),
            trigger = AgentTrigger.Delay(
                day = 1,
                now = "2026-11-07T14:30:00+08:00",
                from = DelayFrom("a", "臺東森林公園", 22.7698, 121.1608, "2026-11-07T15:20:00+08:00", 50),
                returnTrain = Deadline("2026-11-07T18:05:00+08:00", "台東車站"),
                lastFerry = null,
                appConflicts = listOf(AppConflict("closes_during_visit", "c", "趕不上 17:00 關門"))
            ),
            delayMinutes = 50
        ).toJson()

        val t = req.getJSONObject("trigger")
        assertEquals("delay", t.getString("type"))
        assertEquals("app", t.getString("source"))
        assertEquals(1, t.getInt("day"))
        assertEquals("2026-11-07T14:30:00+08:00", t.getString("now"))
        val from = t.getJSONObject("from")
        assertEquals("a", from.getString("stopId"))
        assertEquals("2026-11-07T15:20:00+08:00", from.getString("leaveAt"))
        assertEquals(50, from.getInt("delayMin"))
        assertEquals(22.7698, from.getDouble("lat"), 0.0)
        assertEquals("台東車站", t.getJSONObject("returnTrain").getString("station"))
        assertFalse(t.has("lastFerry"))
        assertEquals("closes_during_visit", t.getJSONArray("appConflicts").getJSONObject(0).getString("kind"))

        val trip = req.getJSONObject("trip")
        assertEquals(1_794_050_400_000L, trip.getLong("updatedAt"))
        val s0 = trip.getJSONArray("stops").getJSONObject(0)
        assertTrue(s0.has("id") && s0.has("day") && s0.has("time") && s0.has("stayMin") && s0.has("name"))
        assertEquals("lodging", trip.getJSONArray("stops").getJSONObject(2).getString("anchor"))
        assertEquals(50, req.getJSONObject("scenario").getJSONObject("delay").getInt("minutes"))
    }

    // ── 第 3、6 節：提案套回 ──────────────────────────────────────────

    @Test
    fun delayProposal_removeAndShorten_keepsAnchorsAndPastStops() {
        val sent = setOf("b", "c", "l")
        val draft = listOf(
            DraftStop("b", 1, "12:00", 40, "榕樹下米苔目", null, null, "food", null, false),
            // c 被刪掉
            DraftStop("l", 1, "18:00", 0, "民宿", null, null, null, null, false, anchor = "lodging")
        )
        val out = AgentTripMapper.merge(twoDays, sent, draft)
        assertEquals(listOf("台東車站", "臺東森林公園", "榕樹下米苔目", "民宿", "加路蘭", "台東車站"), out.map { it.name })
        assertEquals(40L, out[2].duration)
        assertEquals(660, out[2].manualStartMin)   // 手動鎖定時間原樣保留
        assertTrue(out[3].isLodging)
    }

    @Test
    fun agentAddedStop_usesBackendDetails() {
        val s = AgentTripMapper.defaultNewStop(
            DraftStop("n1", 1, "13:00", 45, "臺東美術館", 22.75, 121.14, "scenic", "c", true,
                stopType = "scenic", businessHours = "09:00 - 17:00", desc = "室內展館", emoji = "🖼️")
        )
        assertEquals("09:00 - 17:00", s.businessHours)
        assertEquals("室內展館", s.desc)
        assertEquals("🖼️", s.emoji)
        assertTrue(s.stopId != "n1")
    }
}
