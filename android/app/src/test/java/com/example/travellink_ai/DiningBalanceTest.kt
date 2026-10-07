package com.example.travellink_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 餐飲佔比的兩道防線。
 *
 * 回歸來源：實測行程 my_1786503167974（台東兩天一夜）。候選池跑出
 * {景點=8, 餐廳=17}，AI 只能從看得到的東西挑，8 站裡 5 站是餐廳，
 * 第 2 天更是「台東客來吃樂 → 香琪鴨肉 → 好漁日」三家連排。
 *
 * 成因是本地資料只有「景點／餐廳」兩種 typeName，QUOTA 其餘 8 個類型全部落空，
 * 15 個自由席次整批被餐廳按分數拿走（餐廳有評分、景點常常沒有）。
 *
 * 這裡複製 ItineraryViewModel 的兩段規則（皆為 private 的純判斷邏輯，
 * 獨立驗證比開放內部 API 合適）。兩邊若分歧，這組測試會失效——修改時請同步。
 */
class DiningBalanceTest {

    private val diningNameKeywords = listOf(
        "餐廳", "食堂", "小吃", "咖啡", "早午餐", "餐酒", "廚房", "料理", "小館", "飯館",
        // 「吃」「食」單字看似寬鬆，實測反例（台東客來吃樂、回家食間）都靠它們攔下，
        // 而景點名稱幾乎不會出現這兩字（夜市刻意不列入，它在本專案算景點）
        "吃", "食",
        "麵", "米粉", "水餃", "便當", "火鍋", "燒肉", "燒烤", "鵝肉", "鴨肉", "豬排",
        "牛排", "速食", "披薩", "壽司", "拉麵", "剉冰", "冰品", "甜點", "蛋糕", "茶飲"
    )

    private fun isDiningType(stopType: String, name: String = ""): Boolean {
        if (stopType.contains("餐廳") || stopType.contains("咖啡") ||
            stopType.contains("小吃") || stopType.contains("冰") ||
            stopType.contains("美食") || stopType.contains("甜點")) return true
        return diningNameKeywords.any { name.contains(it) }
    }

    private val LUNCH_START = 11 * 60 + 30
    private val LUNCH_END = 13 * 60 + 30
    private val DINNER_START = 17 * 60 + 30
    private val DINNER_END = 20 * 60

    private fun mealSlotsInWindow(dayStartMins: Int, dayEndMins: Int): Int {
        val enough = 45
        fun fits(s: Int, e: Int) = (minOf(dayEndMins, e) - maxOf(dayStartMins, s)) >= enough
        return listOf(LUNCH_START to LUNCH_END, DINNER_START to DINNER_END)
            .count { (s, e) -> fits(s, e) }
            .coerceAtLeast(1)
    }

    /** 與呼叫端同一條算式 */
    private fun maxDiningSlots(dayCount: Int) = (2 * dayCount + 3).coerceIn(5, 10)

    private data class Poi(val name: String, val typeName: String)

    /** 複製 Step 3b 的自由席次填法：餐飲達上限就跳過，非餐飲不夠再往下撈 */
    private fun fillPool(
        guaranteed: List<Poi>,
        remaining: List<Poi>,
        deeper: List<Poi>,
        totalSlots: Int,
        maxDining: Int
    ): List<Poi> {
        var diningPicked = guaranteed.count { isDiningType(it.typeName) }
        val filled = mutableListOf<Poi>()
        val freeSlots = (totalSlots - guaranteed.size).coerceAtLeast(0)
        for (poi in remaining) {
            if (filled.size >= freeSlots) break
            if (isDiningType(poi.typeName)) {
                if (diningPicked >= maxDining) continue
                diningPicked++
            }
            filled.add(poi)
        }
        if (filled.size < freeSlots) {
            val already = (guaranteed + filled).map { it.name }.toSet()
            filled.addAll(
                deeper.asSequence()
                    .filter { it.name !in already && !isDiningType(it.typeName) }
                    .take(freeSlots - filled.size)
            )
        }
        return guaranteed + filled
    }

    /** 複製每日餐飲上限：正餐看用餐時段數、點心固定一家 */
    private fun capDining(dayStops: List<Poi>, dayStart: Int, dayEnd: Int): List<Poi> {
        val mealCap = mealSlotsInWindow(dayStart, dayEnd)
        val (meals, snacks) = dayStops
            .filter { isDiningType(it.typeName, it.name) }
            .partition { it.typeName.contains("餐廳") }
        val keep = (meals.take(mealCap) + snacks.take(1)).toSet()
        return dayStops.filter { !isDiningType(it.typeName, it.name) || it in keep }
    }

    // ── 候選池 ──────────────────────────────────────────────────────

    @Test
    fun `台東兩天一夜的池子不再變成餐廳佔三分之二`() {
        // 重建實測條件：保障席 景點 5 + 餐廳 5，自由席 15 全是高分餐廳排在前面
        val guaranteed = (1..5).map { Poi("景點$it", "景點") } +
            (1..5).map { Poi("餐廳$it", "餐廳") }
        val remaining = (6..30).map { Poi("餐廳$it", "餐廳") } +
            (6..30).map { Poi("景點$it", "景點") }

        val pool = fillPool(guaranteed, remaining, remaining, totalSlots = 25, maxDining = maxDiningSlots(2))
        val dining = pool.count { isDiningType(it.typeName) }

        assertEquals("池子仍要填滿 25 席", 25, pool.size)
        assertEquals("兩天一夜的餐飲上限是 7", 7, dining)
        assertTrue("非餐飲要過半，AI 才有景點可挑", pool.size - dining > dining)
    }

    @Test
    fun `非餐飲不夠時往深處撈而不是用餐廳湊數`() {
        val guaranteed = listOf(Poi("景點1", "景點"), Poi("餐廳1", "餐廳"))
        // 前 40 名之內只剩餐廳
        val remaining = (2..30).map { Poi("餐廳$it", "餐廳") }
        // 更深處還有景點
        val deeper = remaining + (2..30).map { Poi("深景點$it", "景點") }

        val pool = fillPool(guaranteed, remaining, deeper, totalSlots = 25, maxDining = maxDiningSlots(1))

        assertEquals(25, pool.size)
        assertEquals("單日上限 5", 5, pool.count { isDiningType(it.typeName) })
        assertTrue("空出來的席次由深處的景點遞補", pool.any { it.name.startsWith("深景點") })
    }

    @Test
    fun `餐飲上限隨天數成長但有天花板`() {
        assertEquals(5, maxDiningSlots(1))
        assertEquals(7, maxDiningSlots(2))
        assertEquals(9, maxDiningSlots(3))
        assertEquals(10, maxDiningSlots(4))
        assertEquals("再長也不必無限增加", 10, maxDiningSlots(9))
    }

    // ── 每日上限 ────────────────────────────────────────────────────

    @Test
    fun `第二天的三家餐廳連排會被砍到一家`() {
        // 實測第 2 天原始內容，時間窗 09:00–15:00 只塞得下午餐
        val day2 = listOf(
            Poi("台東客來吃樂", "餐廳"),
            Poi("香琪鴨肉", "餐廳"),
            Poi("好漁日鬼頭刀魚主題餐廳", "餐廳")
        )
        val capped = capDining(day2, 9 * 60, 15 * 60)
        assertEquals(listOf("台東客來吃樂"), capped.map { it.name })
    }

    @Test
    fun `晚餐窗吃不完整就只准排一頓正餐`() {
        // 實測 my_1786505477939 第 1 天 11:00–18:00：晚餐窗只剩 30 分鐘
        assertEquals(1, mealSlotsInWindow(11 * 60, 18 * 60))
        val day1 = listOf(
            Poi("寶町藝文中心", "景點"),
            Poi("回家食間-台東拿手菜", "餐廳"),
            Poi("四方鵝肉", "餐廳"),
            Poi("台東國際地標", "景點")
        )
        val capped = capDining(day1, 11 * 60, 18 * 60)
        assertEquals(
            listOf("寶町藝文中心", "回家食間-台東拿手菜", "台東國際地標"),
            capped.map { it.name }
        )
    }

    @Test
    fun `排得下晚餐的一天可以有兩頓正餐`() {
        assertEquals(2, mealSlotsInWindow(9 * 60, 20 * 60))
        val day = listOf(Poi("午餐店", "餐廳"), Poi("景點A", "景點"), Poi("晚餐店", "餐廳"))
        assertEquals(day, capDining(day, 9 * 60, 20 * 60))
    }

    @Test
    fun `點心每天最多一家且不佔正餐名額`() {
        val day = listOf(
            Poi("某冰店", "冰品"),
            Poi("某咖啡", "咖啡廳"),
            Poi("午餐店", "餐廳")
        )
        val capped = capDining(day, 9 * 60, 15 * 60)
        assertEquals(listOf("某冰店", "午餐店"), capped.map { it.name })
    }

    @Test
    fun `沒超標的日子原封不動`() {
        val day = listOf(
            Poi("鯉魚山", "景點"),
            Poi("午餐店", "餐廳"),
            Poi("圖書館", "景點")
        )
        assertEquals(day, capDining(day, 9 * 60, 15 * 60))
    }

    @Test
    fun `砍餐廳不會動到景點的順序`() {
        val day = listOf(
            Poi("景點A", "景點"),
            Poi("餐廳A", "餐廳"),
            Poi("景點B", "景點"),
            Poi("餐廳B", "餐廳"),
            Poi("景點C", "景點")
        )
        val capped = capDining(day, 9 * 60, 15 * 60)
        assertEquals(listOf("景點A", "餐廳A", "景點B", "景點C"), capped.map { it.name })
    }

    // ── 型別標錯的餐廳 ──────────────────────────────────────────────

    @Test
    fun `被標成景點的餐廳照樣算餐飲`() {
        // local_places.json 的實際內容：這三筆 typeName 都是「景點」
        assertTrue(isDiningType("景點", "SP夏帕義大利麵 台東正氣店"))
        assertTrue(isDiningType("景點", "藍蜻蜓速食專賣店"))
        assertTrue(isDiningType("景點", "台東客來吃樂"))
    }

    @Test
    fun `真正的景點不會被誤判成餐飲`() {
        // 「台東糖廠」含糖、「馬蘭車站」含蘭，都不能被關鍵字掃到
        listOf(
            "台東糖廠", "馬蘭車站", "台東森林公園", "琵琶湖", "活水湖",
            "台東國際地標", "寶町藝文中心", "公東高工聖堂大樓", "臺東觀光夜市",
            "綠島監獄", "帆船鼻大草原", "朝日溫泉"
        ).forEach {
            assertTrue("「$it」不該被當成餐飲", !isDiningType("景點", it))
        }
    }
}
