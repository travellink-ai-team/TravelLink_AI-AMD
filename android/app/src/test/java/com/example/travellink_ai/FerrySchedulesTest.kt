package com.example.travellink_ai

import com.example.travellink_ai.data.island.FerrySchedules
import com.example.travellink_ai.data.island.IslandRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * A5 Stage 5：船班靜態表。
 *
 * 這份資料是人工抄的，最大的風險是「抄錯或過期卻照樣顯示」。所以測試守兩件事：
 * 實際的 assets 檔要解析得出來且與 IslandRegistry 對得上，以及過期判定要成立。
 */
class FerrySchedulesTest {

    private val assetFile = File("src/main/assets/ferry_schedules.json")

    private fun realSchedules() = FerrySchedules.parse(assetFile.readText())

    private fun daysAgo(n: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -n)
    }.timeInMillis

    // ── 實際 assets 檔 ──────────────────────────────────────────

    @Test
    fun `assets 的船班表解析得出來`() {
        assertTrue("找不到 ferry_schedules.json", assetFile.exists())
        val s = realSchedules()
        assertTrue(s.dataAsOf.isNotBlank())
        assertTrue(s.disclaimer.isNotBlank())
    }

    @Test
    fun `每個離島都有對應航線且航程與設定檔一致`() {
        val s = realSchedules()
        IslandRegistry.all.forEach { island ->
            val route = s.routeForIsland(island.code)
            assertNotNull("${island.name} 缺少航線資料", route)
            // 兩邊都寫了航程，對不上就是其中一份抄錯
            assertEquals("${island.name} 的航程與 IslandProfile 不一致",
                island.sailingMins, route!!.sailingMins)
            assertEquals("${island.name} 的港口與 IslandProfile 不一致",
                island.port.name, route.islandPort)
        }
    }

    @Test
    fun `凱旋海運的綠島票價已填入`() {
        val route = realSchedules().routeForIsland("ludao")!!
        val fare = route.fareReference
        assertNotNull("綠島線應該要有可用票價", fare)
        assertEquals(660, fare!!.oneWayFull)
        assertEquals(1320, fare.roundTripFull)
        assertEquals(1120, fare.roundTripBooking)
        assertTrue("票價必須註明來源", fare.source.startsWith("http"))
    }

    // ── 綠島線班表（聯營共用班次）────────────────────────────────

    @Test
    fun `綠島線有每日固定班次`() {
        val route = realSchedules().routeForIsland("ludao")!!
        assertTrue(route.hasAnySchedule)
        val season = route.activeSeason!!
        assertEquals(listOf("07:30", "09:30", "11:30", "13:30", "15:30"),
            season.departuresFrom("mainland"))
        assertEquals(listOf("08:30", "10:30", "12:30", "14:30", "16:30"),
            season.departuresFrom("island"))
    }

    @Test
    fun `班表來源是代訂平台時必須標示`() {
        // 官方端（航港局、東管處、船公司官網）都沒有可抄的逐班時刻，這份來自
        // 飛魚船票網。可信度較低，UI 必須據此標示，不能當官方公告顯示。
        val season = realSchedules().routeForIsland("ludao")!!.activeSeason!!
        assertFalse("代訂平台的資料不可標成官方", season.isOfficial)
        assertEquals("third_party", season.sourceType)
        assertTrue(season.source.startsWith("http"))
        assertTrue("非官方來源必須寫明性質", season.sourceNote.isNotBlank())
    }

    @Test
    fun `找得到指定時間之後的下一班船`() {
        val season = realSchedules().routeForIsland("ludao")!!.activeSeason!!
        assertEquals("13:30", season.nextDeparture("mainland", "12:00"))
        assertEquals("13:30", season.nextDeparture("mainland", "13:30"))  // 剛好同時間算得上
        assertEquals("16:30", season.lastDeparture("island"))
    }

    @Test
    fun `超過末班船時回 null 而不是給隔天的班次`() {
        // 抓不到就不顯示，不要猜——與 A7 台鐵卡同一原則
        val season = realSchedules().routeForIsland("ludao")!!.activeSeason!!
        assertNull(season.nextDeparture("island", "17:00"))
    }

    @Test
    fun `蘭嶼線有旺季每日班次`() {
        val route = realSchedules().routeForIsland("lanyu")!!
        assertTrue(route.hasAnySchedule)
        val season = route.activeSeason!!
        assertEquals(listOf("07:00", "12:30"), season.departuresFrom("mainland"))
        assertEquals(listOf("09:30", "15:00"), season.departuresFrom("island"))
    }

    @Test
    fun `蘭嶼線班表同樣來自代訂平台必須標示`() {
        // 蘭嶼線與綠島線同樣沒有官方逐班時刻，資料來自飛魚船票網，可信度較低。
        val season = realSchedules().routeForIsland("lanyu")!!.activeSeason!!
        assertFalse("代訂平台的資料不可標成官方", season.isOfficial)
        assertEquals("third_party", season.sourceType)
        assertTrue(season.source.startsWith("http"))
        assertTrue("非官方來源必須寫明性質", season.sourceNote.isNotBlank())
    }

    @Test
    fun `蘭嶼線有可估算的船票（暫以優惠價填入）`() {
        // 官方全票尚未取得，fares 暫填飛魚網優惠價；來源必須標明，todo 必須註記缺口。
        val route = realSchedules().routeForIsland("lanyu")!!
        val fare = route.fareReference
        assertNotNull("蘭嶼線應該要有可用票價估算", fare)
        assertTrue("票價必須註明來源", fare!!.source.startsWith("http"))
        route.operators.forEach {
            assertTrue("${it.name} 的優惠價暫填必須在 todo 註記官方全票缺口", it.todo.isNotBlank())
        }
    }

    @Test
    fun `資料不完整的業者都要寫明缺什麼`() {
        realSchedules().routeForIsland("ludao")!!.operators
            .filter { it.fares == null || !it.hasSchedule }
            .forEach {
                assertTrue("${it.name} 資料不完整卻沒寫 todo", it.todo.isNotBlank())
            }
    }

    @Test
    fun `至少要有一家留得下聯絡電話`() {
        // 時刻不確定時的退路就是打電話問，一家都沒有的話這張卡毫無用處
        assertTrue(realSchedules().routeForIsland("ludao")!!.contactable.isNotEmpty())
    }

    // ── 過期判定 ────────────────────────────────────────────────

    @Test
    fun `資料在有效期內不算過期`() {
        val s = FerrySchedules.parse("""{"dataAsOf":"2026-08-11","staleAfterDays":90,"routes":[]}""")
        assertFalse(s.isStale(daysAgo(0)))
    }

    @Test
    fun `超過 staleAfterDays 就算過期`() {
        val s = FerrySchedules.parse("""{"dataAsOf":"2026-01-01","staleAfterDays":90,"routes":[]}""")
        assertTrue(s.isStale())
    }

    @Test
    fun `dataAsOf 壞掉時一律當過期`() {
        // 寧可退化成「請洽船公司」，也不要拿一份不知道多舊的班表當有效資料
        val s = FerrySchedules.parse("""{"dataAsOf":"不明","staleAfterDays":90,"routes":[]}""")
        assertTrue(s.isStale())
    }

    @Test
    fun `讀不到檔案時回傳空資料而不是拋例外`() {
        val s = FerrySchedules.parse("""{}""")
        assertNull(s.routeForIsland("ludao"))
        assertTrue(s.isStale())
    }
}
