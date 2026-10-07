package com.example.travellink_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 午餐跳點為何被移除，以及等待罰則的新形狀。
 *
 * **跳點**（回歸來源 my_1786549499252）：原規則是「非餐廳景點的抵達落在 11:30–13:30、
 * 且後方還有餐廳時，把游標推到 13:30」，用意是別佔著午餐時段。但它推到的是午餐窗的
 * **結尾**，而餐廳排在這一站後面——推完之後餐廳只會更晚，永遠落在 13:30 之後。
 * 這條規則在它自己設定的目標上結構性地不可能成立。
 *
 * 實測代價：帆船鼻 10:51 收工、加車程後 11:31 抵達哈巴狗岩，比 11:30 晚一分鐘就觸發，
 * 哈巴狗岩被推到 13:30，硓宅食堂因此落在 14:06——而它 14:30 打烊。
 *
 * **等待罰則**（回歸來源 my_1786549898159）：原本等待一律 ×3、車程 ×1，等於
 * 「為了少等 1 分鐘願意多騎 3 分鐘車」。第 1 天被朝日溫泉（16:00 才開）釘住尾巴，
 * 優化器拿騎車去消耗時間，東西岸來回穿三次、總車程 151 分（環島一圈才 40 分）。
 */
class LunchSkipTest {

    private val LUNCH_START = 11 * 60 + 30
    private val LUNCH_END = 13 * 60 + 30
    private val FREE_WAIT_MINS = 30

    private fun hhmm(h: Int, m: Int) = h * 60 + m

    /** 舊規則：抵達落在午餐窗且後方有餐廳 → 推到 13:30 */
    private fun oldSkip(arrival: Int, hasLaterRestaurant: Boolean): Int =
        if (hasLaterRestaurant && arrival in LUNCH_START until LUNCH_END) LUNCH_END else arrival

    private fun waitPenalty(wait: Int) =
        minOf(wait, FREE_WAIT_MINS) + (wait - FREE_WAIT_MINS).coerceAtLeast(0) * 3

    // ── 跳點為何不可能成立 ──────────────────────────────────────────

    @Test
    fun `跳點之後餐廳一定落在午餐窗之外`() {
        // 跳到 13:30 之後，還要加上這一站的停留與到餐廳的車程，
        // 所以餐廳的抵達必然 > 13:30 = 午餐窗結尾
        val 停留 = 20
        val 車程 = 16
        val 餐廳抵達 = LUNCH_END + 停留 + 車程
        assertTrue("餐廳抵達 ${餐廳抵達 / 60}:${餐廳抵達 % 60} 已過午餐窗", 餐廳抵達 > LUNCH_END)
        // 連停留 0、車程 0 的極端情況也只是剛好等於窗尾，容不下任何用餐時間
        assertEquals(LUNCH_END, LUNCH_END + 0 + 0)
    }

    @Test
    fun `一分鐘的越界就會換來兩小時空白`() {
        val 抵達 = hhmm(11, 31)   // 實測值：比 11:30 晚一分鐘
        val 跳點後 = oldSkip(抵達, hasLaterRestaurant = true)
        assertEquals(LUNCH_END, 跳點後)
        assertEquals("憑空多出 119 分鐘", 119, 跳點後 - 抵達)
    }

    @Test
    fun `跳點會把餐廳推過打烊時間`() {
        // 硓宅食堂 11:30-14:30、17:30-20:30，停留 60
        val 午餐窗結束 = hhmm(14, 30)
        val 餐廳抵達 = LUNCH_END + 20 + 16   // 14:06
        assertEquals(hhmm(14, 6), 餐廳抵達)
        assertTrue("14:06 + 60 分已超過 14:30 打烊", 餐廳抵達 + 60 > 午餐窗結束)
    }

    @Test
    fun `不跳點時這一站仍在午餐窗內結束餐廳趕得上`() {
        // 移除規則後：哈巴狗岩 11:31 開始、停留 20 → 11:51 結束，
        // 加車程 16 → 餐廳 12:07 抵達，完全在午餐窗內
        val 抵達 = hhmm(11, 31)
        val 不跳 = oldSkip(抵達, hasLaterRestaurant = false)
        assertEquals(抵達, 不跳)
        val 餐廳抵達 = 不跳 + 20 + 16
        assertEquals(hhmm(12, 7), 餐廳抵達)
        assertTrue("餐廳落在午餐窗內", 餐廳抵達 in LUNCH_START until LUNCH_END)
    }

    // ── 等待罰則 ────────────────────────────────────────────────────

    @Test
    fun `三十分鐘內的等待與車程等價`() {
        assertEquals(0, waitPenalty(0))
        assertEquals(20, waitPenalty(20))
        assertEquals(30, waitPenalty(30))
    }

    @Test
    fun `超過三十分鐘才加重`() {
        assertEquals(30 + 30 * 3, waitPenalty(60))
        assertEquals(30 + 57 * 3, waitPenalty(87))
    }

    @Test
    fun `新罰則不會比舊的更重`() {
        (0..180 step 5).forEach {
            assertTrue("wait=$it", waitPenalty(it) <= it * 3)
        }
    }

    @Test
    fun `罰則單調遞增長等待仍然要付代價`() {
        var prev = -1
        (0..240 step 10).forEach {
            val v = waitPenalty(it)
            assertTrue("wait=$it 應比前一級高", v > prev)
            prev = v
        }
    }

    @Test
    fun `不再單方面偏好多騎車`() {
        // 用 20 分鐘的等待換掉 20 分鐘的車程
        val 多騎車 = 130 + waitPenalty(0)
        val 多等待 = 110 + waitPenalty(20)
        assertTrue("新權重下不再嚴格偏好多騎車（$多等待 <= $多騎車）", 多等待 <= 多騎車)
        assertTrue("舊權重（一律 ×3）會嚴格偏好多騎車", 110 + 20 * 3 > 130 + 0 * 3)
    }
}
