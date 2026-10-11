package com.example.travellink_ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候選池不足時，個人化去重要一趟一趟放寬。
 *
 * 回歸來源：實測行程 my_1786545289305（綠島兩天一夜）。綠島 6km 內只有 50 個 POI，
 * 排掉最近三趟等於少一半——候選池 49 → 23，類型配額只填得出 17 席（上限 25），
 * 第 1 天要加站時整個池子只剩一個非餐飲候選可補。
 *
 * 而且會自我惡化：池子越小 → 每趟排的站越集中 → 下一趟排掉的比例越高。
 *
 * 放寬的順序是「先放最久以前那一趟」——寧可重複三趟前去過的地方，也不要重複
 * 上一趟才剛去的。這裡複製 fetchNearbyVerifiedPOIs 的放寬迴圈，修改時請同步。
 */
class DedupRelaxationTest {

    private val MIN_POOL_AFTER_DEDUP = 38

    /**
     * @param excludeByTrip 最近的一趟在前
     * @return 實際採用的趟數 to 排除後剩下的候選
     */
    private fun relax(pool: List<String>, excludeByTrip: List<Set<String>>): Pair<Int, List<String>> {
        var tripsUsed = excludeByTrip.size
        var excludeNorm: Set<String> = emptySet()
        while (tripsUsed > 0) {
            excludeNorm = excludeByTrip.take(tripsUsed).flatten().toSet()
            val remaining = pool.count { it !in excludeNorm }
            if (remaining >= MIN_POOL_AFTER_DEDUP) break
            tripsUsed--
        }
        if (tripsUsed == 0) excludeNorm = emptySet()
        return tripsUsed to pool.filter { it !in excludeNorm }
    }

    private fun trip(prefix: String, n: Int) = (1..n).map { "$prefix$it" }.toSet()

    @Test
    fun `本島這種大池子三趟全排也綽綽有餘`() {
        // 台東市 8km 內約 115 個 POI，去重前約 78
        val pool = (1..78).map { "台東$it" }
        val trips = listOf(
            setOf("台東1", "台東2", "台東3", "台東4", "台東5", "台東6", "台東7", "台東8"),
            setOf("台東9", "台東10", "台東11", "台東12", "台東13"),
            setOf("台東14", "台東15", "台東16", "台東17", "台東18")
        )
        val (used, remaining) = relax(pool, trips)
        assertEquals("池子夠大就不放寬", 3, used)
        assertEquals(78 - 18, remaining.size)
    }

    @Test
    fun `綠島這種小池子會放寬到剩下的趟數`() {
        // 實測條件：池子 49，三趟共排除 26 → 只剩 23，低於門檻 38
        val pool = (1..49).map { "綠島$it" }
        val trips = listOf(trip("綠島", 9), (10..18).map { "綠島$it" }.toSet(),
                           (19..26).map { "綠島$it" }.toSet())
        val (used, remaining) = relax(pool, trips)
        assertTrue("必須放寬", used < 3)
        assertTrue("放寬後要達到門檻（實得 ${remaining.size}）", remaining.size >= MIN_POOL_AFTER_DEDUP)
    }

    @Test
    fun `放寬時先放最久以前那一趟`() {
        val pool = (1..45).map { "P$it" }
        val 上一趟 = setOf("P1", "P2", "P3", "P4")
        val 上上趟 = setOf("P5", "P6", "P7", "P8")
        val 三趟前 = setOf("P9", "P10", "P11", "P12")
        val (used, remaining) = relax(pool, listOf(上一趟, 上上趟, 三趟前))
        assertEquals("45 − 12 = 33 < 38，放掉一趟後 45 − 8 = 37 仍不足，再放一趟 45 − 4 = 41", 1, used)
        assertTrue("上一趟的仍被排除", 上一趟.none { it in remaining })
        assertTrue("三趟前的已放回", 三趟前.all { it in remaining })
        assertTrue("上上趟的也放回了", 上上趟.all { it in remaining })
    }

    @Test
    fun `池子小到怎麼放都不夠時全部放回`() {
        val pool = (1..20).map { "小$it" }
        val trips = listOf(setOf("小1", "小2"), setOf("小3"), setOf("小4"))
        val (used, remaining) = relax(pool, trips)
        assertEquals("放到 0 趟為止", 0, used)
        assertEquals("不會因為湊不到門檻就把池子清空", 20, remaining.size)
    }

    @Test
    fun `沒有歷史行程時不做任何事`() {
        val pool = (1..10).map { "P$it" }
        val (used, remaining) = relax(pool, emptyList())
        assertEquals(0, used)
        assertEquals(10, remaining.size)
    }
}
