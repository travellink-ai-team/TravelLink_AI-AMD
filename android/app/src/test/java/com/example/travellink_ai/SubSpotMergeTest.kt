package com.example.travellink_ai

import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.mergedFields
import com.example.travellink_ai.data.model.parseMergedSubSpots
import com.example.travellink_ai.ui.planning.SubSpotMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同一個大景區的小景點併成一站（測試回饋第 10 條），座標取自 local_places.json */
class SubSpotMergeTest {

    private fun stop(name: String, lat: Double?, lng: Double?, duration: Long = 55, type: String = "景點") =
        Stop(name = name, time = "10:00", desc = "", emoji = "📍", duration = duration, order = 1,
            stopType = type, lat = lat, lng = lng)

    private val all: (Stop) -> Boolean = { !it.isStation && it.stopType != "餐廳" }
    private val pos: (Stop) -> Pair<Double, Double>? = { s -> s.lat?.let { la -> s.lng?.let { la to it } } }

    private val egret = stop("鷺鷥湖", 22.77537, 121.14741)
    private val huoshui = stop("活水湖", 22.76521, 121.16477)
    private val storyHouse = stop("臺東故事館", 22.7932, 121.1236)
    private val library = stop("臺東縣立圖書館", 22.7929, 121.1259)      // 故事館約 240m
    private val artsCenter = stop("臺東藝文中心", 22.7925, 121.1264)     // 圖書館約 70m、故事館約 300m 內
    private val tiehua = stop("鐵花村音樂聚落", 22.7935, 121.1230)        // 故事館約 70m、圖書館約 305m

    @Test
    fun `森林公園的湖相距兩公里也併成一站，用大景區名`() {
        val r = SubSpotMerge.merge(listOf(egret, stop("富岡漁港", 22.79, 121.19), huoshui), all, pos)
        assertEquals(listOf("台東森林公園", "富岡漁港"), r.map { it.name })
        val park = r[0]
        assertEquals(listOf("鷺鷥湖", "活水湖"), park.mergedSubSpots)
        assertTrue(park.isMergedAttraction)
        assertEquals(85L, park.duration)                     // 最長 55 + 30
        assertTrue(park.mergedRadiusMeters in 1000..2500)    // 兩湖約 2.1 km，中心在中間
        assertEquals(2, park.mergedMemberCoords.size)
        assertEquals(egret.stopId, park.stopId)              // 沿用第一站的身分
    }

    @Test
    fun `大景區本身在行程裡就用它當主體`() {
        val park = stop("臺東森林公園", 22.76818, 121.15626)
        val r = SubSpotMerge.merge(listOf(huoshui, park), all, pos)
        assertEquals(1, r.size)
        assertEquals("臺東森林公園", r[0].name)
        assertEquals(listOf("活水湖"), r[0].mergedSubSpots)
        assertEquals(22.76818, r[0].lat!!, 1e-9)
    }

    @Test
    fun `不同名的景點三百公尺內併，但要和群組每一站都近，不會串成一整片`() {
        val r = SubSpotMerge.merge(listOf(storyHouse, library, artsCenter, tiehua), all, pos)
        // 故事館＋圖書館＋藝文中心互相都在 300m 內；鐵花村離圖書館 305m，自己一站
        assertEquals(listOf("臺東故事館", "鐵花村音樂聚落"), r.map { it.name })
        assertEquals(listOf("臺東縣立圖書館", "臺東藝文中心"), r[0].mergedSubSpots)
        assertEquals(115L, r[0].duration)                    // 55 + 30×2
    }

    @Test
    fun `停留時間上限 120 分`() {
        val r = SubSpotMerge.merge(listOf(egret.copy(duration = 100), huoshui, stop("琵琶湖", 22.75572, 121.16656)), all, pos)
        assertEquals(120L, r.single().duration)
    }

    @Test
    fun `餐廳和車站不併`() {
        val diner = stop("故事館旁小吃", 22.7931, 121.1238, type = "餐廳")
        val station = stop("臺東車站", 22.7931, 121.1237).copy(isStation = true)
        val r = SubSpotMerge.merge(listOf(storyHouse, diner, station), all, pos)
        assertEquals(3, r.size)
        assertFalse(r.any { it.isMergedAttraction })
    }

    @Test
    fun `沒座標的只靠對照表併`() {
        val r = SubSpotMerge.merge(listOf(stop("活水湖", null, null), stop("琵琶湖", null, null),
            stop("加路蘭", null, null)), all, pos)
        assertEquals(listOf("台東森林公園", "加路蘭"), r.map { it.name })
        assertEquals(0, r[0].mergedRadiusMeters)             // 沒座標畫不出範圍
    }

    @Test
    fun `補位時被併掉的小景點不能再補回來`() {
        val park = SubSpotMerge.merge(listOf(egret, huoshui), all, pos).single()
        assertTrue(SubSpotMerge.covers(park, "琵琶湖", 22.75572, 121.16656))   // 同一區
        assertTrue(SubSpotMerge.covers(park, "鷺鷥湖", 22.77537, 121.14741))   // 子景點
        assertFalse(SubSpotMerge.covers(park, "加路蘭", 22.81, 121.20))
        assertTrue(SubSpotMerge.covers(storyHouse, "臺東縣立圖書館", 22.7929, 121.1259))
    }

    @Test
    fun `對照表比對不分臺台`() {
        assertEquals("台東森林公園", SubSpotMerge.areaOf("臺東森林公園"))
        assertEquals("台東森林公園", SubSpotMerge.areaOf("活水湖"))
        assertEquals(null, SubSpotMerge.areaOf("加路蘭"))
    }

    @Test
    fun `寫回 Firestore 的欄位與網頁同名，網頁取消合併的不當成合併站`() {
        val park = SubSpotMerge.merge(listOf(egret, huoshui), all, pos).single()
        val m = mergedFields(park)
        assertEquals(true, m["isMergedAttraction"])
        assertEquals(listOf("鷺鷥湖", "活水湖"), m["mergedSubSpots"])
        assertEquals(false, mergedFields(egret)["isMergedAttraction"])
        assertEquals(emptyList<String>(), parseMergedSubSpots(mapOf("isMergedAttraction" to false, "mergedSubSpots" to listOf("x"))))
        assertEquals(listOf("鏡池"), parseMergedSubSpots(mapOf("isMergedAttraction" to true, "mergedSubSpots" to listOf("鏡池", ""))))
    }
}
