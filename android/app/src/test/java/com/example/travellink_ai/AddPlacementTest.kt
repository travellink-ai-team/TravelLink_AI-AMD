package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.AddPlacement
import com.example.travellink_ai.ui.planning.AddPlacement.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加站位置：哪一天、住宿前或入住後。資料照 2026-10-21 實測的兩天一夜
 * （第 1 天卑南遊客中心 16:28 後 18:35 入住；第 2 天 13:19 剉冰後回程）。
 */
class AddPlacementTest {

    private fun s(day: Int, time: String, dur: Long, name: String, station: Boolean = false, checkIn: Boolean = false) =
        Stop(name = name, time = time, desc = if (checkIn) "入住" else "", emoji = "📍", duration = dur, order = 0L,
            isStation = station || checkIn, isLodging = checkIn, dayIndex = day, stopId = name)

    private val trip = listOf(
        s(1, "10:00", 0, "台東車站", station = true),       // 0
        s(1, "14:19", 70, "四格山步道"),                     // 1
        s(1, "15:42", 45, "賓朗"),                           // 2
        s(1, "16:28", 45, "卑南遊客中心"),                    // 3
        s(1, "18:35", 30, "娜路彎大酒店", checkIn = true),    // 4
        s(2, "09:00", 45, "利吉旅遊資訊站"),                  // 5
        s(2, "12:35", 40, "兒童運動公園"),                    // 6
        s(2, "13:19", 25, "古早味剉冰"),                      // 7
        s(2, "13:52", 0, "森林公園", station = true)          // 8
    )
    private val dayEnd = mapOf(1 to 18 * 60, 2 to 15 * 60)

    /** 營業時間只看時段：[ranges] 內抵達就算有開 */
    private fun openIn(vararg ranges: IntRange) = { _: Int, arrive: Int? -> arrive?.let { a -> ranges.any { a in it } } }
    private val lastIndex = { slot: AddPlacement.Slot -> slot.hi }

    @Test fun lodgingDaySplitsIntoBeforeAndAfterCheckIn() {
        val slots = AddPlacement.slots(trip)
        assertEquals(listOf(Triple(1, false, 1 to 4), Triple(1, true, 5 to 5), Triple(2, false, 5 to 8)),
            slots.map { Triple(it.day, it.afterCheckIn, it.lo to it.hi) })
    }

    @Test fun eveningOnlyPlaceGoesAfterCheckIn() {
        // 鐵花村：只有 18:00 以後開。過去被排到第 2 天中午
        val c = AddPlacement.choose(trip, Request(), 60, dayEnd, openIn(18 * 60..22 * 60), lastIndex)!!
        assertEquals(1, c.slot.day)
        assertTrue(c.slot.afterCheckIn)
        assertEquals(5, c.index)                         // 接在入住後、第 2 天之前
        assertEquals(18 * 60 + 35 + 30 + 15, c.arriveMins)  // 入住 30 分＋車程
        assertEquals("第 1 天 19:20（入住後）", AddPlacement.label(trip, c))
    }

    @Test fun daytimePlaceStaysBeforeCheckInWhenItFits() {
        // 全天開、沒指定：照舊放一般時段，空檔多的那天（第 2 天 13:59 後還有 61 分）
        val c = AddPlacement.choose(trip, Request(), 30, dayEnd, { _, _ -> true }, lastIndex)!!
        assertFalse(c.slot.afterCheckIn)
    }

    @Test fun unknownHoursNeverMovesToEvening() {
        val c = AddPlacement.choose(trip, Request(), 45, dayEnd, { _, _ -> null }, lastIndex)!!
        assertFalse(c.slot.afterCheckIn)
    }

    @Test fun userRequestWins() {
        val after = AddPlacement.choose(trip, Request(day = 1, afterCheckIn = true), 45, dayEnd, { _, _ -> true }, lastIndex)!!
        assertTrue(after.slot.afterCheckIn)
        val day2 = AddPlacement.choose(trip, Request(day = 2), 60, dayEnd, openIn(18 * 60..22 * 60), lastIndex)!!
        assertEquals(2, day2.slot.day)                   // 指定第 2 天：就算那時沒開也照做（衝突提示另外講）
        // 第 2 天沒有入住：要求「入住後」就放那天的一般時段
        val noCheckIn = AddPlacement.choose(trip, Request(day = 2, afterCheckIn = true), 30, dayEnd, { _, _ -> true }, lastIndex)!!
        assertEquals(2, noCheckIn.slot.day)
        assertFalse(noCheckIn.slot.afterCheckIn)
    }

    @Test fun ongoingTripOnlyPlacesAfterNow() {
        // 第 1 天 17:00：第 1 天入住前只能插在卑南遊客中心之後（前面的站都開始了）
        val slots = AddPlacement.slots(trip, today = 1, nowMins = 17 * 60)
        assertEquals(4, slots.first { it.day == 1 && !it.afterCheckIn }.lo)
        // 第 2 天：第 1 天整個不考慮
        assertTrue(AddPlacement.slots(trip, today = 2, nowMins = 10 * 60).none { it.day == 1 })
        // 抵達時間不會早於現在
        val c = AddPlacement.choose(trip, Request(day = 1, afterCheckIn = false), 30, dayEnd, { _, _ -> true }, lastIndex,
            today = 1, nowMins = 17 * 60 + 30)!!
        assertTrue(c.arriveMins!! >= 17 * 60 + 30)
    }

    @Test fun singleDayTripHasOneSlotAndNoDayInLabel() {
        val one = listOf(s(1, "09:00", 0, "台東車站", station = true), s(1, "09:20", 60, "鐵花村"),
            s(1, "10:40", 60, "海濱公園"), s(1, "12:00", 0, "台東車站回程", station = true))
        val slots = AddPlacement.slots(one)
        assertEquals(listOf(1 to 3), slots.map { it.lo to it.hi })
        val c = AddPlacement.choose(one, Request(), 30, mapOf(1 to 17 * 60), { _, _ -> true }, lastIndex)!!
        assertEquals("11:55", AddPlacement.label(one, c))
    }
}
