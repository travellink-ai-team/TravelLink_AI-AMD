package com.example.travellink_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停留時間上限的分類規則。
 *
 * 案例取自實測行程 my_1786455778457（綠島一日）：AI 把「一塊岩石／一個小洞／
 * 只能在門口拍照的監獄」各排了 30–50 分鐘，因為 prompt 要求「把時間排滿」而
 * duration 是唯一沒有上限的變數。
 *
 * 這裡複製 ItineraryViewModel.visitDurationCap 的規則（該函式為 private，
 * 且分類邏輯是純字串判斷，獨立驗證比開放內部 API 更合適）。兩邊若分歧，
 * 這組測試會失效——修改規則時請同步。
 */
class VisitDurationCapTest {

    private fun cap(stopType: String, name: String): Int {
        when {
            stopType.contains("餐廳") -> return 75
            stopType.contains("咖啡") -> return 60
            stopType.contains("小吃") || stopType.contains("冰") ||
                stopType.contains("美食") -> return 35
        }
        val has = { keys: List<String> -> keys.any { name.contains(it) } }
        val isExperience = has(listOf("浮潛", "潛水", "溫泉", "體驗", "獨木舟", "SUP"))
        val isTrail      = has(listOf("步道", "古道", "健行", "登山", "長城"))
        val isFacadeOnly = has(listOf("監獄", "看守所"))
        val isSpotObject = has(listOf("岩", "洞", "窟", "礁", "碑", "柱", "橋", "門"))
        val isLookout    = has(listOf("觀景", "瞭望", "制高", "眺望"))
        val isWaterSpot  = has(listOf("沙灘", "潮池", "潟湖", "湖", "灣", "白沙"))
        val isReligious  = has(listOf("廟", "宮", "寺", "佛堂", "教會", "神社", "宗祠", "教堂"))
        val isGrass      = has(listOf("草原", "公園", "廣場"))
        return when {
            isExperience -> 100
            isTrail      -> 70
            isFacadeOnly -> 30
            isSpotObject -> 20
            isLookout    -> 25
            isWaterSpot  -> 40
            isReligious  -> 30
            isGrass      -> 50
            stopType.contains("博物館") || stopType.contains("文化") ||
                stopType.contains("藝廊") || stopType.contains("展覽") -> 90
            stopType.contains("園區") || stopType.contains("農場") ||
                stopType.contains("牧場") || stopType.contains("遊樂") -> 100
            stopType.contains("市場") || stopType.contains("夜市") -> 65
            else -> 45
        }
    }

    // ── 實測案例：這些是被灌水的那批 ──────────────────────────────

    @Test
    fun `單一定點景物上限 20 分`() {
        assertEquals(20, cap("景點", "烏油窟"))       // 實測被排 45
        assertEquals(20, cap("景點", "哈巴狗岩"))     // 實測被排 30
        assertEquals(20, cap("景點", "觀音洞"))       // 實測被排 35
        assertEquals(20, cap("景點", "綠島樓門岩"))
    }

    @Test
    fun `僅能外觀參觀的上限 30 分`() {
        assertEquals(30, cap("景點", "綠島監獄"))     // 實測被排 50，實際進不去
    }

    @Test
    fun `步道類仍給足時間`() {
        // 這批是真的要走，不能一起壓低
        assertEquals(70, cap("景點", "過山古道"))
        assertEquals(70, cap("景點", "小長城"))
        assertEquals(70, cap("公園/步道", "帆船鼻步道"))
    }

    @Test
    fun `體驗型維持最寬鬆`() {
        assertEquals(100, cap("景點", "柴口浮潛區"))
        assertEquals(100, cap("景點", "朝日溫泉"))
    }

    // ── 分類邊界 ────────────────────────────────────────────────

    @Test
    fun `餐飲以型別優先判斷不被名稱特徵搶走`() {
        // 「石屋咖啡」含「石」但不是一塊石頭；「橋chiao」是餐廳不是橋
        assertEquals(60, cap("咖啡廳", "石屋咖啡"))
        assertEquals(75, cap("餐廳", "橋chiao"))
        assertEquals(35, cap("小吃", "綠島芡粿小吃"))
    }

    @Test
    fun `體驗優先於定點景物`() {
        // 名稱同時含「潛」與「區」時要走體驗，不能被當成岩洞類壓到 20
        assertTrue(cap("景點", "石朗潛水區") > 60)
    }

    @Test
    fun `水域景點給 40 分`() {
        assertEquals(40, cap("景點", "柚子湖"))
        assertEquals(40, cap("景點", "大白沙"))
    }

    @Test
    fun `草原公園給 50 分`() {
        assertEquals(50, cap("景點", "帆船鼻大草原"))
    }

    @Test
    fun `一般景點預設 45 而非舊值 85`() {
        // 舊的 maxStayForType 對未知型別一律給 85，是灌水的主要來源
        assertEquals(45, cap("景點", "海參坪"))
        assertEquals(45, cap("", "某個沒特徵的地方"))
    }

    @Test
    fun `展館類仍可較長`() {
        assertEquals(90, cap("博物館/文化館", "白色恐怖綠島紀念園區"))
    }
}
