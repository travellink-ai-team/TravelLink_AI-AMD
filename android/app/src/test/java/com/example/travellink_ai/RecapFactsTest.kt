package com.example.travellink_ai

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.PlaceCost
import com.example.travellink_ai.ui.recap.RecapFacts
import com.example.travellink_ai.ui.recap.RecapStop
import com.example.travellink_ai.ui.recap.RecapTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** AI 字幕的實用資訊（C4）：只給拿得準的數字 */
class RecapFactsTest {
    private val week = "星期一: 休息\n星期二: 11:30 – 14:00, 17:00 – 20:30\n星期三: 11:30 – 14:00, 17:00 – 20:30\n" +
        "星期四: 11:30 – 14:00\n星期五: 11:30 – 14:00\n星期六: 10:00 – 22:00\n星期日: 10:00 – 22:00"

    @Test fun hoursPickTheTripDay() {
        // 2026/08/13 是星期四；第 3 天是星期六
        val start = RecapFacts.tripStart("2026/08/13 - 2026/08/15")!!
        assertEquals("星期四", RecapFacts.weekdayOf(start, 1))
        assertEquals("星期六", RecapFacts.weekdayOf(start, 3))
        assertEquals("11:30–14:00", RecapFacts.hoursOn(week, "星期四"))
        assertEquals("11:30–14:00、17:00–20:30", RecapFacts.hoursOn(week, "星期二"))
        assertNull("公休不寫", RecapFacts.hoursOn(week, "星期一"))
    }

    @Test fun hoursWithoutDate() {
        assertNull("每天不同又不知道日期：不給", RecapFacts.hoursOn(week, null))
        assertEquals("09:00–17:00", RecapFacts.hoursOn("星期一：09:00 - 17:00\n星期二：09:00 - 17:00", null))
        assertEquals("09:00–17:00", RecapFacts.hoursOn("09:00 - 17:00", null))
        assertEquals("24 小時營業", RecapFacts.hoursOn("星期一: 24 小時營業\n星期二: 24 小時營業", "星期二"))
        assertNull(RecapFacts.hoursOn("未提供", "星期一"))
        assertNull(RecapFacts.hoursOn("不定期營業", null))
        assertNull(RecapFacts.tripStart("1"))
    }

    private fun cost(fee: Int?, meal: Int?) = PlaceCost("x", 0.0, 0.0, fee, "", meal, "", -1)

    @Test fun feePrefersTicketThenMeal() {
        assertEquals("免費入場", RecapFacts.feeText(cost(0, 300)))
        assertEquals("門票 50 元", RecapFacts.feeText(cost(50, null)))
        assertEquals("每人約 300 元", RecapFacts.feeText(cost(null, 300)))
        assertNull(RecapFacts.feeText(cost(null, null)))
    }

    @Test fun buildCombinesAndSkipsLodging() {
        fun stop(id: String, hours: String, day: Int = 1, lodging: Boolean = false) = Stop(name = id, time = "10:00", desc = "", emoji = "",
            duration = 60, order = 0, stopId = id, businessHours = hours, dayIndex = day, isLodging = lodging, lat = 22.7, lng = 121.1)
        val item = LocalItinerary(title = "t", aiTitle = "", aiReply = "", region = "台東", days = "2026/08/13 - 2026/08/14", people = "2",
            stops = listOf(stop("a", week), stop("hotel", "24 小時營業", lodging = true), stop("b", "未提供", day = 2)))
        val trip = RecapTrip("t", "台東", "", "", 1.0, "car", item.stops.map { RecapStop(it.stopId, it.name, 22.7, 121.1, "car", 60, it.dayIndex) })
        val facts = RecapFacts.build(item, trip, { name, _, _ -> if (name == "a") cost(50, null) else null }, { _, _ -> 3 })
        assertEquals("營業時間 11:30–14:00；門票 50 元；停好車走約 3 分鐘", facts["a"])
        assertFalse("住宿站不寫", facts.containsKey("hotel"))
        assertEquals("停好車走約 3 分鐘", facts["b"])
        // 不是開車：不給停車
        assertNull(RecapFacts.build(item, trip, { _, _, _ -> null }, null)["b"])
    }
}
