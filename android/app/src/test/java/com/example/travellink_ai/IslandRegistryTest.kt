package com.example.travellink_ai

import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.estimateTripCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 Stage 4：離島守門員與跨海段費用。
 *
 * 最關鍵的是守門方向會翻轉——本島行程的行為必須與改動前一模一樣
 * （離島座標一律拒絕），離島行程才反過來只收島上的。
 */
class IslandRegistryTest {

    // 實際座標：三仙台在本島、朝日溫泉在綠島、野銀部落在蘭嶼
    private val sanxiantai = 23.1206 to 121.4028
    private val chaoRi     = 22.6486 to 121.5010
    private val yeYin      = 22.0389 to 121.5661

    // ── 目的地判定 ──────────────────────────────────────────────

    @Test
    fun `目的地寫綠島時辨識為離島`() {
        assertEquals("ludao", IslandRegistry.byDestination("綠島")?.code)
        assertEquals("ludao", IslandRegistry.byDestination("台東綠島")?.code)
        assertEquals("ludao", IslandRegistry.byDestination("綠島鄉")?.code)
    }

    @Test
    fun `蘭嶼的別名也能辨識`() {
        assertEquals("lanyu", IslandRegistry.byDestination("蘭嶼")?.code)
        assertEquals("lanyu", IslandRegistry.byDestination("紅頭嶼")?.code)
    }

    @Test
    fun `本島目的地不會被誤判為離島`() {
        assertNull(IslandRegistry.byDestination("台東"))
        assertNull(IslandRegistry.byDestination("成功鎮"))
        assertNull(IslandRegistry.byDestination(""))
        assertNull(IslandRegistry.byDestination(null))
    }

    // ── 守門方向翻轉 ────────────────────────────────────────────

    @Test
    fun `本島行程照舊拒絕離島座標`() {
        // 這是改動前的行為，不能因為支援離島就鬆掉
        assertFalse(IslandRegistry.accepts(null, chaoRi.first, chaoRi.second))
        assertFalse(IslandRegistry.accepts(null, yeYin.first, yeYin.second))
        assertTrue(IslandRegistry.accepts(null, sanxiantai.first, sanxiantai.second))
    }

    @Test
    fun `綠島行程只收綠島座標`() {
        val ludao = IslandRegistry.ludao
        assertTrue(IslandRegistry.accepts(ludao, chaoRi.first, chaoRi.second))
        // 本島景點被排進綠島行程 → 擋掉（過去這種幻覺會靜默通過）
        assertFalse(IslandRegistry.accepts(ludao, sanxiantai.first, sanxiantai.second))
        // 另一個離島的景點同樣擋掉
        assertFalse(IslandRegistry.accepts(ludao, yeYin.first, yeYin.second))
    }

    @Test
    fun `蘭嶼行程只收蘭嶼座標`() {
        val lanyu = IslandRegistry.lanyu
        assertTrue(IslandRegistry.accepts(lanyu, yeYin.first, yeYin.second))
        assertFalse(IslandRegistry.accepts(lanyu, chaoRi.first, chaoRi.second))
    }

    @Test
    fun `港口座標必須落在自己的島範圍內`() {
        // 設定檔寫錯（例如把本島富岡漁港的座標填進來）會讓整趟行程錨在海上
        IslandRegistry.all.forEach { p ->
            assertTrue("${p.name} 的 ${p.port.name} 不在島範圍內",
                p.contains(p.port.lat, p.port.lng))
            assertTrue("${p.name} 的中心點不在島範圍內",
                p.contains(p.centerLat, p.centerLng))
        }
    }

    @Test
    fun `兩島的範圍不重疊`() {
        val l = IslandRegistry.ludao
        assertFalse(IslandRegistry.lanyu.contains(l.centerLat, l.centerLng))
    }

    // ── 跨海段費用 ──────────────────────────────────────────────

    @Test
    fun `跨海段不套每公里車資`() {
        val port = Stop(name = "南寮漁港", time = "08:50", desc = "", emoji = "⛴️",
            duration = 0L, order = 0L, isStation = true, isFerry = true,
            lat = 22.659485, lng = 121.473767)
        val spot = Stop(name = "朝日溫泉", time = "10:00", desc = "", emoji = "♨️",
            duration = 90L, order = 1L, stopType = "景點",
            lat = chaoRi.first, lng = chaoRi.second)
        // 港口→景點這一段若被當成計程車，光起跳價就有 85 元
        val withFerry = estimateTripCost(
            listOf(port, spot), transitTimes = listOf(20L),
            segmentModes = listOf("taxi"), budget = "適中", people = "2人"
        )
        assertEquals(0, withFerry.transportPerPerson)
    }

    @Test
    fun `一般路段的車資計算不受影響`() {
        val a = Stop(name = "A", time = "09:00", desc = "", emoji = "📍",
            duration = 60L, order = 1L, lat = 22.7563, lng = 121.1440)
        val b = Stop(name = "B", time = "11:00", desc = "", emoji = "📍",
            duration = 60L, order = 2L, lat = 22.8000, lng = 121.2000)
        val cost = estimateTripCost(
            listOf(a, b), transitTimes = listOf(20L),
            segmentModes = listOf("taxi"), budget = "適中", people = "2人"
        )
        assertTrue("一般路段仍應算出車資", cost.transportPerPerson > 0)
    }
}
