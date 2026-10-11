package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.PlaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「這兩筆是不是同一個地方」的判斷。
 *
 * 回歸來源：實測行程 my_1786535964243（台東兩天一夜）。第 2 天 10:01 排了
 * 卑南遺址公園、10:54 排了卑南遺址——相距 430 公尺的同一個考古遺址，
 * 等於同一個地方逛兩次。候選池裡還同時躺著台東天后宮／臺東天后宮、
 * 台東森林公園／臺東森林公園。
 *
 * 舊的判斷只看「名稱完全相同或 80 公尺內」，這些全都漏掉。
 *
 * 這組測試原本複製了一份規則（註解自己警告兩邊分歧就會失效）；現在直接測
 * [PlaceIdentity]，ItineraryViewModel 也委派給它，不再有兩份。
 */
class SamePlaceDedupTest {

    private data class P(val lat: Double, val lng: Double)

    private fun isSamePlace(nameA: String, a: P, nameB: String, b: P) =
        PlaceIdentity.isSame(nameA, a.lat, a.lng, nameB, b.lat, b.lng)

    // 座標取自 assets/local_places.json
    private val 卑南遺址 = P(22.79150, 121.12000)
    private val 卑南遺址公園 = P(22.79187, 121.11582)
    private val 台東天后宮 = P(22.75218, 121.16177)
    private val 臺東天后宮 = P(22.75685, 121.15548)
    private val 台東森林公園 = P(22.75718, 121.16589)
    private val 臺東森林公園 = P(22.76818, 121.15626)
    private val 寶町 = P(22.75814, 121.15322)
    private val 榕樹下米苔目 = P(22.75472, 121.15338)
    private val 榕樹下小滿雨生 = P(22.76278, 121.14914)

    @Test
    fun `卑南遺址與卑南遺址公園算同一個地方`() {
        assertTrue(isSamePlace("卑南遺址", 卑南遺址, "卑南遺址公園", 卑南遺址公園))
    }

    @Test
    fun `台與臺的寫法差異算同一個地方`() {
        assertTrue(isSamePlace("台東天后宮", 台東天后宮, "臺東天后宮", 臺東天后宮))
        assertTrue(isSamePlace("台東森林公園", 台東森林公園, "臺東森林公園", 臺東森林公園))
    }

    @Test
    fun `英文副標與星號後綴不算不同地方`() {
        assertTrue(isSamePlace("寶町藝文中心", 寶町, "寶町藝文中心 Boting Art Center", 寶町))
        assertTrue(isSamePlace("榕樹下米苔目", 榕樹下米苔目, "榕樹下米苔目*小滿雨生", 榕樹下小滿雨生))
    }

    @Test
    fun `名字像但相隔很遠的不算同一個`() {
        // 多良火車站與山里火車站都是景觀車站，相距數十公里
        val 多良 = P(22.50752, 120.95885)
        val 山里 = P(22.86188, 121.13782)
        assertFalse(isSamePlace("多良火車站", 多良, "山里火車站", 山里))
        // 同名但跨鄉鎮的天后宮：關山天后宮距臺東市 30 公里以上
        val 關山天后宮 = P(23.04906, 121.16281)
        assertFalse(isSamePlace("臺東天后宮", 臺東天后宮, "關山天后宮", 關山天后宮))
    }

    @Test
    fun `短前綴不能吃掉不同的景點`() {
        // 「綠島小」是一筆名稱被截斷的資料，不能把「綠島小長城」判成同一個
        val a = P(22.6600, 121.4900)
        val b = P(22.6650, 121.4950)
        assertFalse(isSamePlace("綠島小", a, "綠島小長城", b))
    }

    @Test
    fun `括號裡的別名也算數`() {
        // 實測 my_1786537113715：14:42 小野柳遊客中心、15:28 富岡地質公園 (小野柳)，
        // 相距 220 公尺、是同一個園區的入口與本體，等於在同一地待 95 分鐘
        val 富岡地質公園 = P(22.7947736, 121.1981802)
        val 小野柳遊客中心 = P(22.7956565, 121.1962458)
        assertTrue(isSamePlace("富岡地質公園 (小野柳)", 富岡地質公園,
                               "小野柳遊客中心", 小野柳遊客中心))
    }

    @Test
    fun `括號裡的營業說明不能當別名`() {
        // 兩家不相干的店都在括號裡寫店休說明，不能因此被湊成同一個地方
        val a = P(22.7604753, 121.1568874)
        val b = P(22.7545696, 121.1459322)
        assertFalse(isSamePlace("蔥薑蒜料理小廚 （最新消息請看FB、IG）", a,
                                "台東客來吃樂（店休不固定，請查詢粉絲專頁））", b))
        // 「(最後點餐kitchen last call 14:00/19:45)」含英數，也不該成為別名
        assertFalse(isSamePlace("Caravan 卡騯· 異國料理(最後點餐kitchen last call 14:00/19:45)", a,
                                "秘食-私廚(最後點餐kitchen last call 14:00/19:45)", b))
    }

    @Test
    fun `五公里外一律不算同一個`() {
        val a = P(22.7554, 121.1418)
        val b = P(22.8154, 121.1384)   // 相距約 6.7 公里
        assertFalse(isSamePlace("同名地點", a, "同名地點", b))
    }

    @Test
    fun `八十公尺內一律視為同一個`() {
        val a = P(22.75814, 121.15322)
        val b = P(22.75818, 121.15325)
        assertTrue(isSamePlace("完全不同的名字", a, "另一個名字", b))
    }

    // ── 這次補的漏洞 ─────────────────────────────────────────────

    @Test
    fun `短名加通用地名後綴算同一個地方`() {
        // 實測 9/21 行程第 2 天同時排了「加路蘭遊憩區」與「加路蘭」：兩筆資料相距 1.2 公里，
        // 「加路蘭」只有 3 字，舊規則要求主名前綴至少 4 字，所以被放過
        val 加路蘭 = P(22.812171, 121.187132)
        val 加路蘭遊憩區 = P(22.8063665, 121.19699739)
        assertTrue(isSamePlace("加路蘭", 加路蘭, "加路蘭遊憩區", 加路蘭遊憩區))
        assertTrue(isSamePlace("加路蘭遊憩區", 加路蘭遊憩區, "加路蘭", 加路蘭))
    }

    @Test
    fun `多出來的是專名而不是通用後綴就不算同一個`() {
        // 「綠島小」+「長城」：長城是專名，不是「遊憩區」「公園」這類通用詞
        val a = P(22.6600, 121.4900)
        val b = P(22.6650, 121.4950)
        assertFalse(isSamePlace("三仙", a, "三仙台", b))          // 2 字不夠
        assertFalse(isSamePlace("加路蘭", a, "加路蘭大草原", b))  // 「大草原」不是通用後綴
    }

    @Test
    fun `通用後綴規則仍受兩公里距離限制`() {
        val a = P(22.7500, 121.1500)
        val far = P(22.7500, 121.1800)    // 約 3 公里
        assertFalse(isSamePlace("加路蘭", a, "加路蘭遊憩區", far))
    }

    @Test
    fun `富岡地質公園與帶括號的同名寫法算同一個`() {
        // 實測第 2 天 09:29 富岡地質公園、11:10 富岡地質公園 (小野柳)：補位只比對
        // 名稱完全相同，抓不到這組
        val a = P(22.7947736, 121.1981802)
        assertTrue(isSamePlace("富岡地質公園", a, "富岡地質公園 (小野柳)", a))
    }

    // ── dedupe：把重複項合併成一筆 ─────────────────────────────────

    private data class Item(val name: String, val pos: P?, val day: Int = 1)

    private fun dedupe(
        items: List<Item>,
        prefer: (Item, Item) -> Boolean = { _, _ -> false },
        dropped: MutableList<String> = mutableListOf()
    ) = PlaceIdentity.dedupe(
        items,
        name = { it.name },
        position = { it.pos?.let { p -> p.lat to p.lng } },
        prefer = prefer,
        onDuplicate = { d, _ -> dropped += d.name }
    )

    @Test
    fun `dedupe 保留先出現的並回報被合併的`() {
        val dropped = mutableListOf<String>()
        val r = dedupe(listOf(
            Item("加路蘭遊憩區", P(22.8063665, 121.19699739)),
            Item("烏龍院家庭食堂", P(22.7553, 121.1467)),
            Item("加路蘭", P(22.812171, 121.187132))
        ), dropped = dropped)
        assertEquals(listOf("加路蘭遊憩區", "烏龍院家庭食堂"), r.map { it.name })
        assertEquals(listOf("加路蘭"), dropped)
    }

    @Test
    fun `dedupe 可依偏好留下後來的那筆`() {
        // 實測：AI 把海濱公園排了兩次（第 1 天下午、第 2 天日出）。日出那天的要留下
        val 海濱 = P(22.7518661, 121.1638748)
        val r = dedupe(
            listOf(Item("海濱公園", 海濱, day = 1), Item("琵琶湖", P(22.7557, 121.1665), day = 1),
                   Item("海濱公園", 海濱, day = 2)),
            prefer = { candidate, existing -> candidate.day == 2 && existing.day != 2 }
        )
        assertEquals(2, r.size)
        assertEquals(2, r.first { it.name == "海濱公園" }.day)
    }

    @Test
    fun `dedupe 沒有座標時只有主名相同才合併`() {
        val r = dedupe(listOf(
            Item("海濱公園", null), Item("臺東海濱公園", null), Item("海濱公園 (日出)", null)
        ))
        // 「海濱公園」與「海濱公園 (日出)」主名相同 → 合併；「臺東海濱公園」主名不同，
        // 沒座標時不冒險合併
        assertEquals(listOf("海濱公園", "臺東海濱公園"), r.map { it.name })
    }

    @Test
    fun `dedupe 不同的地方不受影響`() {
        val items = listOf(
            Item("卑南遺址", P(22.79150, 121.12000)),
            Item("台東森林公園", P(22.75718, 121.16589)),
            Item("海濱公園", P(22.7518661, 121.1638748))
        )
        assertEquals(items, dedupe(items))
    }
}
