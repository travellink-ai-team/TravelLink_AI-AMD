package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.BudgetSwap
import com.example.travellink_ai.ui.planning.PlaceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 超出預算時的替換建議：只換有真實票價的最貴景點，候選是附近估算門票較便宜的景點 */
class BudgetSwapTest {

    private fun place(name: String, lat: Double, lng: Double, fee: Int? = null,
                      type: String = "景點", hours: String = "", duration: Long = 60) =
        PlaceCost(name, lat, lng, fee, "", null, "", -1, type, hours, duration)

    private fun stop(name: String, lat: Double?, lng: Double?, time: String = "10:00",
                     type: String = "景點", day: Int = 1) =
        Stop(name = name, time = time, desc = "", emoji = "📍", duration = 90, order = 1,
            stopType = type, lat = lat, lng = lng, dayIndex = day)

    // 富岡地質公園附近（台東市北邊）
    private val fugang = place("富岡地質公園", 22.7910, 121.1930, fee = 200)
    private val beach = place("加路蘭", 22.8100, 121.2000)                       // 約 2 km，無票價資料→估 60
    private val free = place("三和海濱公園", 22.7800, 121.1800, fee = 0)          // 約 1.8 km，免費
    private val far = place("鹿野高台", 22.9500, 121.1300)                        // 約 19 km，太遠
    private val pricey = place("初鹿牧場", 22.8000, 121.1900, fee = 200)          // 不比目標便宜
    private val food = place("某餐廳", 22.7900, 121.1900, type = "餐廳")

    private val places = listOf(fugang, beach, free, far, pricey, food)
    private val fees = places.associate { it.name to it.fee }
    private val lookup: (String, Double?, Double?) -> Int? = { n, _, _ -> fees[n] }
    private val date: (Int) -> String = { "2026/09/23" }   // 週三

    @Test
    fun `挑有真實票價的最貴景點，候選依距離排序且比它便宜`() {
        val stops = listOf(
            stop("臺東車站", 22.79, 121.12).copy(isStation = true),
            stop("富岡地質公園", 22.7910, 121.1930),
            stop("卑南遺址公園", 22.7930, 121.1150)
        )
        val p = BudgetSwap.propose(stops, places + place("卑南遺址公園", 22.7930, 121.1150, fee = 30),
            { n, _, _ -> if (n == "卑南遺址公園") 30 else fees[n] }, date)!!
        assertEquals("富岡地質公園", p.target.name)
        assertEquals(200, p.targetFee)
        assertEquals(listOf("三和海濱公園", "加路蘭"), p.options.map { it.place.name })
        val freeOpt = p.options.first()
        assertTrue(freeOpt.feeKnown)
        assertEquals(200, freeOpt.saving)
        val beachOpt = p.options[1]
        assertFalse(beachOpt.feeKnown)
        assertEquals(60, beachOpt.fee)          // 類型估算，與費用卡一致
    }

    @Test
    fun `只有類型估算票價的景點不當替換對象`() {
        val stops = listOf(stop("臺東美術館", 22.75, 121.15, type = "博物館"))
        assertNull(BudgetSwap.propose(stops, places, lookup, date))
    }

    @Test
    fun `餐廳、住宿不當替換對象`() {
        val stops = listOf(
            stop("富岡地質公園", 22.7910, 121.1930, type = "餐廳"),
            stop("富岡地質公園", 22.7910, 121.1930).copy(isStation = true, isLodging = true)
        )
        assertNull(BudgetSwap.propose(stops, places, lookup, date))
    }

    @Test
    fun `已在行程中的景點不列為候選`() {
        val stops = listOf(stop("富岡地質公園", 22.7910, 121.1930), stop("三和海濱公園", 22.78, 121.18))
        val p = BudgetSwap.propose(stops, places, lookup, date)!!
        assertEquals(listOf("加路蘭"), p.options.map { it.place.name })
    }

    @Test
    fun `當天公休或抵達時沒開的候選排除`() {
        val closedWed = place("加路蘭", 22.8100, 121.2000,
            hours = "星期二: 09:00 – 17:00\n星期三: 休息")
        val opensLate = place("三和海濱公園", 22.7800, 121.1800, fee = 0,
            hours = "星期三: 16:00 – 22:00")
        val p = BudgetSwap.propose(listOf(stop("富岡地質公園", 22.7910, 121.1930)),
            listOf(fugang, closedWed, opensLate), lookup, date)!!
        assertTrue(p.options.isEmpty())
    }

    @Test
    fun `遊客中心、公車站這類設施不列為候選`() {
        val visitor = place("小野柳遊客中心", 22.7912, 121.1932)
        val bus = place("富岡公車站", 22.7915, 121.1935)
        val p = BudgetSwap.propose(listOf(stop("富岡地質公園", 22.7910, 121.1930)),
            listOf(fugang, visitor, bus, free), lookup, date)!!
        assertEquals(listOf("三和海濱公園"), p.options.map { it.place.name })
    }

    @Test
    fun `目標沒存座標時用本地資料的座標找附近`() {
        val p = BudgetSwap.propose(listOf(stop("富岡地質公園", null, null)), places, lookup, date)!!
        assertEquals("三和海濱公園", p.options.first().place.name)
    }

    @Test
    fun `替換後保留 stopId、天數、順序，換上新景點資料與當天營業時間`() {
        val target = stop("富岡地質公園", 22.7910, 121.1930, day = 2).copy(order = 4)
        val opt = BudgetSwap.Option(
            place("三和海濱公園", 22.78, 121.18, fee = 0, hours = "星期三: 休息\n星期四: 08:00 – 18:00", duration = 45),
            1.8, 0, true, 200)
        val s = BudgetSwap.applyTo(target, opt, "2026/09/24")
        assertEquals(target.stopId, s.stopId)
        assertEquals(2, s.dayIndex)
        assertEquals(4L, s.order)
        assertEquals("三和海濱公園", s.name)
        assertEquals(45L, s.duration)
        assertEquals("08:00 - 18:00", s.businessHours)
        assertEquals(22.78, s.lat!!, 1e-9)
    }
}
