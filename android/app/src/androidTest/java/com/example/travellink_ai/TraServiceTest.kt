package com.example.travellink_ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.travellink_ai.data.transit.TraService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A7 ② 台鐵班次資料層測試。
 *
 * 設計取捨：
 * - **刻意打真實 TDX API**。這支測試的存在意義就是驗證「在真實裝置上連得到 TDX、
 *   解析得出班次」，用假資料就失去意義了。改跑在 Firebase Test Lab 的真機上，
 *   本機不需要模擬器。
 * - **把網路查詢集中在少數幾個 test method**，因為 TDX 免費帳號 rate limit 很緊
 *   （實測約十幾次呼叫就會被擋）。同一次查詢結果拿來做多個斷言，而不是每個斷言查一次。
 * - 方法名用 t01/t02… 前綴並固定執行順序，讓不需網路的離線測試先跑完，
 *   萬一 rate limit 被擋，至少離線部分的結果是可信的。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TraServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(Date())

    // ── 離線部分（不打網路，只讀 assets）─────────────────────────────

    @Test
    fun t01_車站清單能從assets載入且涵蓋全台() {
        val stations = TraService.stations(context)
        assertEquals("assets/tra_stations.json 應有 245 站", 245, stations.size)

        // 臺東縣 14 站是 A7 的主戰場，逐一確認都在
        val taitung = TraService.taitungStations(context).map { it.name }
        assertEquals("臺東縣應有 14 個台鐵車站", 14, taitung.size)
        listOf("臺東", "知本", "池上", "關山", "太麻里", "大武").forEach {
            assertTrue("臺東縣車站清單應包含 $it", taitung.contains(it))
        }
    }

    @Test
    fun t02_站名比對能吃各種寫法且不誤判非車站地點() {
        // 使用者可能填的各種寫法都要對到臺東站 6000
        listOf("台東車站", "臺東車站", "台東火車站", "臺東火車站", "台東站", "臺東")
            .forEach { assertEquals("「$it」應對到臺東站", "6000", TraService.findStationId(context, it)) }

        // 其他縣市的車站
        assertEquals("1000", TraService.findStationId(context, "臺北"))
        assertEquals("7000", TraService.findStationId(context, "花蓮"))
        assertEquals("5230", TraService.findStationId(context, "知本車站"))

        // 非車站地點必須回 null。寧可不顯示班次，也不能對到錯的車站
        listOf("台東大學", "鐵花村", "臺東轉運站", "初鹿牧場", "").forEach {
            assertNull("「$it」不是台鐵車站，應回 null", TraService.findStationId(context, it))
        }
    }

    @Test
    fun t03_站名反查() {
        assertEquals("臺東", TraService.stationName(context, "6000"))
        assertNull(TraService.stationName(context, "9999"))
    }

    // ── 線上部分（打真實 TDX API）────────────────────────────────────

    @Test
    fun t10_車站時刻表能取得且不含不可搭乘的到站列車() = runBlocking {
        // 一天當中最早的時間，確保一定查得到後續班次（不受測試執行時間影響）
        val departures = TraService.stationDepartures(context, "6000", "00:00", perDirection = 5)

        assertTrue(
            "應查到臺東站的班次；若為空請確認裝置有網路、TDX 金鑰有效、且未被 rate limit 擋下",
            departures.isNotEmpty()
        )

        // 🔴 這是實作時抓到的真實 bug 的回歸測試：
        // 臺東是端點站，定期時刻表中有 41% 的班次「終點就是臺東」，那是到站不是發車，
        // 不可搭乘。曾經會推薦「17:23 往臺東」給人在臺東的使用者。
        departures.forEach {
            assertTrue(
                "不可推薦終點為本站的列車（車次 ${it.trainNo} 往 ${it.destinationName}）",
                it.destinationName != "臺東"
            )
        }

        // 南下北上都要有，使用者才能自己選方向
        val directions = departures.map { it.direction }.toSet()
        assertEquals("應同時提供南下與北上兩個方向", setOf(0, 1), directions)

        // 每個方向最多 perDirection 班
        departures.groupBy { it.direction }.forEach { (dir, list) ->
            assertTrue("方向 $dir 的班次數 ${list.size} 不應超過 5", list.size <= 5)
        }

        // 欄位完整性：時間格式 HH:mm、車次與車種非空
        departures.forEach {
            assertTrue("發車時間格式應為 HH:mm，實際 ${it.departureTime}",
                it.departureTime.matches(Regex("\\d{2}:\\d{2}")))
            assertTrue("車次不應為空", it.trainNo.isNotBlank())
            assertTrue("車種不應為空", it.trainType.isNotBlank())
            assertTrue("終點站不應為空", it.destinationName.isNotBlank())
        }

        // 方向標籤
        departures.forEach {
            assertEquals(
                if (it.direction == 1) "北上" else "南下",
                it.directionLabel
            )
        }
    }

    @Test
    fun t11_車站時刻表會濾掉指定時間之前的班次() = runBlocking {
        val after = "18:00"
        val departures = TraService.stationDepartures(context, "6000", after, perDirection = 5)
        assertTrue("18:00 之後臺東站應仍有班次", departures.isNotEmpty())
        departures.forEach {
            assertTrue(
                "不應回傳 $after 之前的班次（車次 ${it.trainNo} 於 ${it.departureTime}）",
                it.departureTime >= after
            )
        }
    }

    @Test
    fun t12_磁碟快取會建立且第二次查詢不再打網路() = runBlocking {
        val cacheFile = File(File(context.filesDir, "tra_cache"), "station_6000.json")
        // 前面的測試已經查過臺東站，快取檔應該已經在了
        assertTrue("查詢後應建立磁碟快取 ${cacheFile.absolutePath}", cacheFile.exists())
        assertTrue("快取內容不應為空", cacheFile.length() > 0)

        // 第二次查詢應該明顯更快（走磁碟不走網路）。用寬鬆門檻避免機房網路波動造成 flaky
        val elapsed = System.currentTimeMillis().let { start ->
            TraService.stationDepartures(context, "6000", "00:00")
            System.currentTimeMillis() - start
        }
        assertTrue("快取命中時查詢應在 2 秒內完成，實際 ${elapsed}ms", elapsed < 2000)
    }

    @Test
    fun t20_OD時刻表能取得抵達時間與乘車時長() = runBlocking {
        // 臺東(6000) → 臺北(1000)，這是最常見的回程需求
        val trips = TraService.odDepartures(
            context, fromId = "6000", toId = "1000", date = today(), afterHHmm = "00:00", limit = 4
        )

        assertTrue(
            "應查到臺東→臺北的班次；若為空請確認網路與 TDX 額度",
            trips.isNotEmpty()
        )
        assertTrue("limit=4 時不應回傳超過 4 班", trips.size <= 4)

        trips.forEach {
            // OD 模式的價值就在這兩個欄位，車站模式沒有
            assertNotNull("OD 模式應提供抵達時間（車次 ${it.trainNo}）", it.arrivalTime)
            assertNotNull("OD 模式應提供乘車時長（車次 ${it.trainNo}）", it.durationMins)
            assertTrue(
                "臺東到臺北的乘車時間應在 3–7 小時之間，實際 ${it.durationMins} 分",
                it.durationMins!! in 180..420
            )
            assertTrue("抵達時間格式應為 HH:mm", it.arrivalTime!!.matches(Regex("\\d{2}:\\d{2}")))
        }

        // 必須依發車時間排序，UI 才能直接「第一筆＝最快能搭的那班」
        val times = trips.map { it.departureTime }
        assertEquals("班次應依發車時間排序", times.sorted(), times)
    }

    @Test
    fun t21_OD時刻表會濾掉指定時間之前的班次() = runBlocking {
        val after = "16:37"   // 模擬行程 16:27 結束 + 10 分鐘進站緩衝
        val trips = TraService.odDepartures(
            context, fromId = "6000", toId = "1000", date = today(), afterHHmm = after, limit = 4
        )
        trips.forEach {
            assertTrue(
                "不應回傳 $after 之前發車的班次（車次 ${it.trainNo} 於 ${it.departureTime}）",
                it.departureTime >= after
            )
        }
    }

    @Test
    fun t30_即時誤點查詢不會崩潰且格式正確() = runBlocking {
        // 深夜或離峰時段 LiveBoard 可能真的沒有資料，所以不斷言「一定有內容」，
        // 只驗證「呼叫得通、回傳結構正確、不拋例外」——誤點是加值資訊，
        // 查不到時 UI 本來就設計成不顯示。
        val delays = TraService.liveDelays("6000")
        assertNotNull("liveDelays 不應回 null（查不到應回空 map）", delays)
        delays.forEach { (trainNo, mins) ->
            assertTrue("車次號不應為空", trainNo.isNotBlank())
            assertTrue("誤點分鐘數不應為負，實際 $mins", mins >= 0)
        }
    }

    @Test
    fun t31_誤點併入班次清單時只影響有對應車次的項目() = runBlocking {
        val departures = TraService.stationDepartures(context, "6000", "00:00", perDirection = 3)
        val fakeDelays = departures.firstOrNull()?.let { mapOf(it.trainNo to 7) } ?: emptyMap()

        val merged = TraService.withDelays(departures, fakeDelays)
        assertEquals("併入誤點不應改變班次數量", departures.size, merged.size)

        if (fakeDelays.isNotEmpty()) {
            assertEquals("有對應車次者應帶入誤點分鐘", 7, merged.first().delayMins)
            // 其餘班次維持 null（無即時資料），UI 才知道要不要顯示誤點標籤
            merged.drop(1).forEach {
                assertNull("無對應誤點資料的班次 delayMins 應維持 null", it.delayMins)
            }
        }
    }
}
