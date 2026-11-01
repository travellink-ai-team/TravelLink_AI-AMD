package com.example.travellink_ai

import com.example.travellink_ai.data.model.PhotoClassifier
import com.example.travellink_ai.data.model.PhotoClassifier.Confidence
import com.example.travellink_ai.data.model.PhotoClassifier.Evidence
import com.example.travellink_ai.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PhotoClassifierTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private val day1 = LocalDate.of(2026, 10, 10)

    private fun stop(id: String, time: String, lat: Double, lng: Double, day: Int = 1, duration: Long = 60) =
        Stop(name = id, time = time, desc = "", emoji = "", duration = duration, order = 0, stopId = id, lat = lat, lng = lng, dayIndex = day)

    // 台東市區三站（座標取自本地景點資料）＋第二天一站
    private val stops = listOf(
        stop("museum", "10:00", 22.7586, 121.1456),        // 臺東美術館
        stop("story", "11:30", 22.7542, 121.1472, duration = 40), // 臺東故事館（約 500m 外）
        stop("sugar", "14:00", 22.7712, 121.1153),         // 台東糖廠
        stop("sanxian", "09:00", 23.1232, 121.4110, day = 2) // 第二天：三仙台
    )
    private val windows = PhotoClassifier.windowsOf(stops, day1, zone)

    private fun at(date: LocalDate, hh: Int, mm: Int) =
        date.atTime(hh, mm).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `時間與位置都對上：高信心，直接歸站`() {
        val r = PhotoClassifier.classify(Evidence(at(day1, 10, 20), lat = 22.7587, lng = 121.1457), windows, zone)
        assertEquals("museum", r.stopId)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.autoAssign)
    }

    @Test
    fun `沒有 GPS、只有時間：落在時間窗內為中信心`() {
        val r = PhotoClassifier.classify(Evidence(at(day1, 14, 30)), windows, zone)
        assertEquals("sugar", r.stopId)
        assertEquals(Confidence.MEDIUM, r.confidence)   // 0.45 ≥ 0.38
        assertTrue(r.autoAssign)
    }

    @Test
    fun `只有 GPS、時間在別天：仍以位置歸站`() {
        // 時間未知（null）只看位置：0.55 → 中信心
        val r = PhotoClassifier.classify(Evidence(null, lat = 22.7713, lng = 121.1152), windows, zone)
        assertEquals("sugar", r.stopId)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun `兩站很近時由時間決定`() {
        // 位置在美術館與故事館中間偏故事館，時間 11:45 落在故事館的窗
        val r = PhotoClassifier.classify(Evidence(at(day1, 11, 45), lat = 22.7550, lng = 121.1470), windows, zone)
        assertEquals("story", r.stopId)
    }

    @Test
    fun `只比同一天的站：第二天的照片不會歸到第一天`() {
        val r = PhotoClassifier.classify(Evidence(at(day1.plusDays(1), 9, 10), lat = 23.1230, lng = 121.4105), windows, zone)
        assertEquals("sanxian", r.stopId)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun `離所有站都遠、時間也對不上：不自動歸站`() {
        // 台北、行程隔週
        val r = PhotoClassifier.classify(Evidence(at(day1.plusDays(7), 10, 0), lat = 25.04, lng = 121.56), windows, zone)
        assertFalse(r.autoAssign)
        assertEquals(Confidence.NONE, r.confidence)
    }

    @Test
    fun `GPS 離所有站很遠、時間卻吻合：不自動歸站（拍攝地點是反證）`() {
        // 實測案例：台北拍的照片，時間落在糖廠的時間窗內
        val r = PhotoClassifier.classify(Evidence(at(day1, 14, 30), lat = 25.0330, lng = 121.5654), windows, zone)
        assertEquals("sugar", r.stopId)   // 仍給預設選項
        assertEquals(Confidence.LOW, r.confidence)
        assertFalse(r.autoAssign)
    }

    @Test
    fun `GPS 在站點附近 1 公里外、時間吻合：同樣要確認`() {
        // 糖廠往北約 1.2 公里（超過 3×350m 的位置計分範圍）
        val r = PhotoClassifier.classify(Evidence(at(day1, 14, 30), lat = 22.7820, lng = 121.1153), windows, zone)
        assertFalse(r.autoAssign)
    }

    @Test
    fun `完全沒有證據：沒有預設站`() {
        val r = PhotoClassifier.classify(Evidence(null), windows, zone)
        assertNull(r.stopId)
        assertFalse(r.autoAssign)
    }

    @Test
    fun `只有檔案修改時間：時間權重降到 0_2，要使用者確認`() {
        val r = PhotoClassifier.classify(Evidence(at(day1, 14, 30), weakTime = true), windows, zone)
        assertEquals("sugar", r.stopId)   // 仍給預設選項
        assertFalse(r.autoAssign)         // 0.2 → 低信心
    }

    @Test
    fun `時間窗外 45 分鐘：時間分數減半`() {
        // 糖廠 14:00–15:00，15:45 拍 → 時間分數 0.5 × 0.45 = 0.225 → 低信心
        val r = PhotoClassifier.classify(Evidence(at(day1, 15, 45)), windows, zone)
        assertEquals("sugar", r.stopId)
        assertEquals(Confidence.LOW, r.confidence)
        assertEquals(45, r.timeDistanceMinutes)
    }

    @Test
    fun `不知道出發日期：沒有時間窗，只靠位置`() {
        val noDate = PhotoClassifier.windowsOf(stops, null, zone)
        assertTrue(noDate.all { it.start == null && it.dayKey == null })
        val r = PhotoClassifier.classify(Evidence(at(day1, 10, 20), lat = 22.7587, lng = 121.1457), noDate, zone)
        assertEquals("museum", r.stopId)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test
    fun `時間字串容錯：帶區間或空白都取第一個 HH_mm`() {
        val w = PhotoClassifier.windowsOf(listOf(stop("a", " 9:05 - 10:00", 0.0, 0.0)), day1, zone).single()
        assertEquals(at(day1, 9, 5), w.start)
        assertEquals(at(day1, 10, 5), w.end)
    }

    @Test
    fun `GPS 0,0 視為沒有定位`() {
        assertFalse(PhotoClassifier.validCoords(0.0, 0.0))
        assertTrue(PhotoClassifier.validCoords(22.75, 121.14))
    }
}
