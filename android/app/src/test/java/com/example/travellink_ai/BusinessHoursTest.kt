package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.BusinessHours
import com.example.travellink_ai.ui.planning.DayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 營業時間依日期取用。資料取自實測行程（2026/09/21 週一、09/22 週二，兩天一夜）：
 * 臺東觀光夜市週一二日休、只有週三到週六 16:00 後營業，卻被排在週一 11:12；
 * 生活美學館與臺東故事館週二休，卻被排在週二。
 */
class BusinessHoursTest {

    private val nightMarket = "星期一: 休息\n星期二: 休息\n星期三: 16:00 – 00:00\n星期四: 16:00 – 00:00\n" +
        "星期五: 16:00 – 00:00\n星期六: 16:00 – 00:00\n星期日: 休息"

    private val museum = "星期一: 08:30 – 12:00, 13:30 – 17:00\n星期二: 休息\n星期三: 08:30 – 12:00, 13:30 – 17:00\n" +
        "星期四: 08:30 – 12:00, 13:30 – 17:00\n星期五: 08:30 – 12:00, 13:30 – 17:00\n" +
        "星期六: 08:30 – 12:00, 13:30 – 17:00\n星期日: 08:30 – 12:00, 13:30 – 17:00"

    private val mon = "2026/09/21"
    private val tue = "2026/09/22"
    private val wed = "2026/09/23"

    // ── resolve ─────────────────────────────────────────────────

    @Test
    fun `依日期取出當天那一行`() {
        assertEquals("08:30 - 12:00, 13:30 - 17:00", BusinessHours.resolve(museum, mon))
        assertEquals("休息", BusinessHours.resolve(museum, tue))
        assertEquals("16:00 - 00:00", BusinessHours.resolve(nightMarket, wed))
    }

    @Test
    fun `日期帶時間也能解析`() {
        assertEquals("休息", BusinessHours.resolve(museum, "2026/09/22 09:00"))
    }

    @Test
    fun `全形冒號的格式不會被切在時間裡的冒號`() {
        val raw = "星期一：08:30-12:00\n星期二：休息"
        assertEquals("08:30-12:00", BusinessHours.resolve(raw, mon))
        assertEquals("休息", BusinessHours.resolve(raw, tue))
    }

    @Test
    fun `單日格式與無法解析的情況原樣回傳`() {
        assertEquals("10:00 - 20:00", BusinessHours.resolve("10:00 - 20:00", mon))
        assertEquals("未提供", BusinessHours.resolve("未提供", mon))
        assertEquals(museum, BusinessHours.resolve(museum, ""))
        assertEquals(museum, BusinessHours.resolve(museum, "壞日期"))
        // 找不到該星期那一行
        val partial = "星期一: 09:00 - 17:00"
        assertEquals(partial, BusinessHours.resolve(partial, tue))
    }

    // ── isClosed ────────────────────────────────────────────────

    @Test
    fun `只有休息字樣才算公休`() {
        assertTrue(BusinessHours.isClosed("休息"))
        assertTrue(BusinessHours.isClosed("公休"))
        assertTrue(BusinessHours.isClosed("  休館 "))
        assertFalse(BusinessHours.isClosed("08:30 - 12:00"))
        assertFalse(BusinessHours.isClosed("24 小時營業"))
        assertFalse(BusinessHours.isClosed("未提供"))
        assertFalse(BusinessHours.isClosed(""))
        // 全週字串裡有一天休息，但整體有時間，不算公休
        assertFalse(BusinessHours.isClosed(museum))
    }

    @Test
    fun `實測案例夜市週一二公休週三開`() {
        assertTrue(BusinessHours.closedOn(nightMarket, mon))
        assertTrue(BusinessHours.closedOn(nightMarket, tue))
        assertFalse(BusinessHours.closedOn(nightMarket, wed))
        assertFalse(BusinessHours.closedOn(museum, mon))
        assertTrue(BusinessHours.closedOn(museum, tue))
    }

    // ── promptLabel ─────────────────────────────────────────────

    @Test
    fun `單日行程只顯示當天`() {
        assertEquals("08:30 - 12:00, 13:30 - 17:00", BusinessHours.promptLabel(museum, listOf(mon)))
        assertEquals("公休", BusinessHours.promptLabel(museum, listOf(tue)))
        assertEquals("16:00 - 00:00", BusinessHours.promptLabel(nightMarket, listOf(wed)))
    }

    @Test
    fun `多日行程各天不同時逐天標示讓 AI 知道哪天不能排`() {
        assertEquals(
            "第 1 天 08:30 - 12:00, 13:30 - 17:00／第 2 天 公休",
            BusinessHours.promptLabel(museum, listOf(mon, tue))
        )
    }

    @Test
    fun `各天相同時合併成一個值`() {
        assertEquals("公休", BusinessHours.promptLabel(nightMarket, listOf(mon, tue)))
    }

    @Test
    fun `全天營業與未知`() {
        assertEquals("全天", BusinessHours.promptLabel("24 小時營業", listOf(mon)))
        assertEquals("時間未知", BusinessHours.promptLabel("未提供", listOf(mon)))
        assertEquals("時間未知", BusinessHours.promptLabel("", listOf(mon)))
    }

    @Test
    fun `含有 24 的時間不會被誤判成全天`() {
        // 過去只要字串含「24」就標成全天，12:24 之類也算
        assertEquals("12:24 - 18:00", BusinessHours.promptLabel("12:24 - 18:00", listOf(mon)))
    }

    @Test
    fun `沒有行程日期時不亂挑一天`() {
        assertEquals("時間依星期而異", BusinessHours.promptLabel(museum, emptyList()))
        assertEquals("時間依星期而異", BusinessHours.promptLabel(museum, listOf("")))
        val alwaysOpen = (listOf("一", "二", "三", "四", "五", "六", "日")).joinToString("\n") { "星期$it: 24 小時營業" }
        assertEquals("全天", BusinessHours.promptLabel(alwaysOpen, emptyList()))
    }

    // ── relocateClosed ──────────────────────────────────────────

    private fun stop(name: String, day: Int = 1) =
        Stop(name = name, time = "00:00", desc = "", emoji = "📍", duration = 45L, order = 1L, dayIndex = day)

    private fun closedMap(vararg entries: Pair<String, Set<Int>>): (Stop, Int) -> Boolean {
        val m = entries.toMap()
        return { s, d -> d in (m[s.name] ?: emptySet()) }
    }

    @Test
    fun `沒有公休時原樣不動`() {
        val byDay = listOf(listOf(stop("A"), stop("B")), listOf(stop("C", 2)))
        val r = DayPlanner.relocateClosed(byDay) { _, _ -> false }
        assertEquals(byDay, r.byDay)
        assertTrue(r.moved.isEmpty()); assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `公休的站換到有開的那天`() {
        // 夜市週一二休、週三開；兩天行程都休 → 換不了。故事館週二休 → 換到第 1 天
        val byDay = listOf(listOf(stop("A")), listOf(stop("故事館", 2), stop("C", 2)))
        val r = DayPlanner.relocateClosed(byDay, isClosedOn = closedMap("故事館" to setOf(1)))
        assertEquals(listOf("A", "故事館"), r.byDay[0].map { it.name })
        assertEquals(listOf("C"), r.byDay[1].map { it.name })
        assertEquals(1, r.moved.size)
        assertEquals(0, r.moved[0].second)
        assertEquals(1, r.byDay[0].last().dayIndex)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `每天都休的站直接剔除`() {
        // 實測：夜市週一、週二都休，兩天行程沒有能換的日子
        val byDay = listOf(listOf(stop("夜市"), stop("A")), listOf(stop("B", 2)))
        val r = DayPlanner.relocateClosed(byDay, isClosedOn = closedMap("夜市" to setOf(0, 1)))
        assertEquals(listOf("A"), r.byDay[0].map { it.name })
        assertEquals(listOf("夜市"), r.dropped.map { it.name })
        assertTrue(r.moved.isEmpty())
    }

    @Test
    fun `目的天已滿或已有同名站時不換`() {
        val full = (1..DayPlanner.MAX_STOPS_PER_DAY).map { stop("F$it") }
        val r1 = DayPlanner.relocateClosed(listOf(full, listOf(stop("X", 2)))) { s, d -> s.name == "X" && d == 1 }
        assertEquals(listOf("X"), r1.dropped.map { it.name })   // 第 1 天已滿

        val r2 = DayPlanner.relocateClosed(listOf(listOf(stop("海濱公園")), listOf(stop("海濱公園", 2)))) { s, d ->
            s.name == "海濱公園" && d == 1 && s.dayIndex == 2
        }
        assertEquals(1, r2.dropped.size)                         // 第 1 天已有同名，不重複去
    }

    @Test
    fun `重放實測行程週一週二兩天一夜`() {
        // 2026/09/21（一）、09/22（二）：實測排出的是——
        //   第 1 天：臺東觀光夜市 11:12（週一二日休）…
        //   第 2 天：國立臺東生活美學館 08:30、臺東故事館 11:01（都是週二休）
        val storyHouse = "星期一: 11:00 – 19:00\n星期二: 休息\n星期三: 11:00 – 19:00\n星期四: 11:00 – 19:00\n" +
            "星期五: 11:00 – 19:00\n星期六: 11:00 – 19:00\n星期日: 11:00 – 19:00"
        val raw = mapOf(
            "臺東觀光夜市" to nightMarket,
            "國立臺東生活美學館" to museum,
            "臺東故事館" to storyHouse,
            "海濱公園" to "星期一: 24 小時營業\n星期二: 24 小時營業"
        )
        val dates = listOf(mon, tue)
        val byDay = listOf(
            listOf(stop("臺東觀光夜市"), stop("台東客來吃樂"), stop("海濱公園")),
            listOf(stop("國立臺東生活美學館", 2), stop("臺東故事館", 2), stop("利吉惡地", 2))
        )
        val r = DayPlanner.relocateClosed(byDay) { s, d ->
            raw[s.name]?.let { BusinessHours.closedOn(it, dates[d]) } ?: false
        }

        // 夜市兩天都休：剔除，不會再出現在行程裡
        assertEquals(listOf("臺東觀光夜市"), r.dropped.map { it.name })
        // 週二休的兩個場館：週一有開，換到第 1 天
        assertEquals(setOf("國立臺東生活美學館", "臺東故事館"), r.moved.map { it.first.name }.toSet())
        assertTrue(r.moved.all { it.second == 0 })
        assertEquals(
            listOf("台東客來吃樂", "海濱公園", "國立臺東生活美學館", "臺東故事館"),
            r.byDay[0].map { it.name }
        )
        // 第 2 天只剩沒有公休問題的
        assertEquals(listOf("利吉惡地"), r.byDay[1].map { it.name })
        // 沒有任何站還排在它公休的那天
        r.byDay.forEachIndexed { d, stops ->
            stops.forEach { s ->
                assertFalse("${s.name} 仍排在公休日", raw[s.name]?.let { BusinessHours.closedOn(it, dates[d]) } ?: false)
            }
        }
    }

    @Test
    fun `目的天收不下就不換而是剔除`() {
        // 實測 9/22–23：七里坡（餐廳）第 2 天公休，第 1 天雖有開但正餐額度已滿。
        // 換過去只會被餐飲上限移除，說明卻寫「改排第 1 天」——換日前要先問目的天收不收得下
        val day1Meals = listOf(stop("秘食-私廚"), stop("台東客來吃樂"))
        val byDay = listOf(day1Meals, listOf(stop("七里坡", 2), stop("卑南遺址", 2)))
        val r = DayPlanner.relocateClosed(
            byDay,
            isClosedOn = { s, d -> s.name == "七里坡" && d == 1 },
            canAccept = { s, _, current ->
                // 正餐額度 1：目的天已有兩家正餐，不再收
                !(s.name == "七里坡" && current.size >= 2)
            }
        )
        assertEquals(listOf("七里坡"), r.dropped.map { it.name })
        assertTrue(r.moved.isEmpty())
        assertEquals(listOf("秘食-私廚", "台東客來吃樂"), r.byDay[0].map { it.name })
    }

    @Test
    fun `目的天收得下時照常換日`() {
        val byDay = listOf(listOf(stop("A")), listOf(stop("七里坡", 2)))
        val r = DayPlanner.relocateClosed(
            byDay,
            isClosedOn = { s, d -> s.name == "七里坡" && d == 1 },
            canAccept = { _, _, _ -> true }
        )
        assertEquals(1, r.moved.size)
        assertEquals(listOf("A", "七里坡"), r.byDay[0].map { it.name })
    }

    @Test
    fun `多個候選天時 canAccept 只擋收不下的那天`() {
        val byDay = listOf(listOf(stop("A")), listOf(stop("X", 2)), listOf(stop("B", 3), stop("C", 3)))
        val r = DayPlanner.relocateClosed(
            byDay,
            isClosedOn = { s, d -> s.name == "X" && d == 1 },
            canAccept = { _, dayIdx, _ -> dayIdx != 0 }     // 第 1 天收不下
        )
        assertEquals(2, r.moved[0].second)     // 只剩第 3 天可去
    }

    @Test
    fun `多個候選天時挑站數最少的`() {
        val byDay = listOf(
            listOf(stop("A"), stop("B"), stop("X")),
            listOf(stop("C", 2), stop("D", 2), stop("E", 2)),
            listOf(stop("F", 3))
        )
        val r = DayPlanner.relocateClosed(byDay, isClosedOn = closedMap("X" to setOf(0)))
        assertEquals(2, r.moved[0].second)
    }
}
