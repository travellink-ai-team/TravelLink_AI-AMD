package com.example.travellink_ai

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.local.UserPreferencesManager
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.transit.ReturnTrainPlan
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A7 ② 回程班次的整合流程測試：行程資料 → ViewModel → 真實 TDX → returnTrains 狀態。
 *
 * 與另外兩支的分工：
 * - `TraServiceTest`：資料層（TDX 解析、快取、過濾）
 * - `ReturnTrainCardTest`：畫面（給定資料時該顯示什麼）
 * - **本檔**：中間那段——把一份行程餵進 ViewModel，驗證它正確找出回程車站、
 *   算出進站緩衝、選對查詢模式，並在行程落後時把班次往後推
 *
 * 設計取捨：
 * - **用 `loadFromHistory()` 灌入行程**，那是使用者開啟歷史行程時的真實路徑，
 *   不必為了測試在正式程式碼開後門
 * - **不含 AI 生成**。AI 每次排出的景點與結束時間都不同，斷言只能寫得很寬鬆，
 *   且回程地點若不是車站卡片根本不會出現，會造成無意義的 flaky 失敗。
 *   生成本身是機率性的，不適合寫成通過/失敗的測試
 * - 透過 MainActivity 取得 Hilt 提供的 ViewModel。未登入時畫面會停在 LoginScreen，
 *   但這不影響我們要驗的 ViewModel 狀態
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReturnTrainFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: ItineraryViewModel
    private lateinit var prefs: UserPreferencesManager

    /** 行程結束、抵達回程車站的時間 */
    private val arriveTime = "16:27"
    /** 進站緩衝 10 分鐘後，最早可搭的時間 */
    private val boardAfter = "16:37"

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[ItineraryViewModel::class.java]
        }
        prefs = UserPreferencesManager(context)
    }

    @After
    fun tearDown() {
        prefs.finalDestinationStation = ""
        scenario.close()
    }

    // ── 測試資料 ─────────────────────────────────────────────────────

    /** 行程日期用今天，OD 時刻表才查得到（TDX 只提供近期日期的每日時刻表）。 */
    private fun todayDays(): String {
        val d = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).format(Date())
        return "$d 09:00 - $d $arriveTime"
    }

    /**
     * 一份台東一日遊：出發車站 → 兩個景點 → 回程車站。
     * [returnPlace] 可換成非車站地點，用來驗證「對不上車站就不顯示班次」。
     */
    private fun makeTrip(returnPlace: String = "台東車站") = LocalItinerary(
        title = "A7 流程測試行程",
        aiTitle = "台東一日遊",
        aiReply = "",
        region = "台東",
        days = todayDays(),
        people = "2人",
        stops = listOf(
            Stop(name = "台東車站", time = "09:00", desc = "出發車站", emoji = "🚉",
                duration = 0, order = 0, isStation = true),
            Stop(name = "鐵花村", time = "09:40", desc = "音樂聚落", emoji = "🎵",
                duration = 90, order = 1),
            Stop(name = "台東美術館", time = "13:00", desc = "美術館", emoji = "🎨",
                duration = 120, order = 2),
            Stop(name = returnPlace, time = arriveTime, desc = "回程地點", emoji = "🚉",
                duration = 0, order = 3, isStation = true)
        )
    )

    // ── 等待工具 ─────────────────────────────────────────────────────

    /** 輪詢直到 [block] 回傳非 null，逾時則失敗。用於等非同步的 StateFlow 更新。 */
    private fun <T> waitFor(what: String, timeoutMs: Long = 25_000, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("等待「$what」逾時（${timeoutMs}ms）")
    }

    /** 灌入行程並等它進到 ViewModel 狀態。 */
    private fun loadTrip(returnPlace: String = "台東車站") {
        val trip = makeTrip(returnPlace)
        scenario.onActivity { vm.loadFromHistory(trip) }
        waitFor("行程載入") {
            vm.itinerary.value?.takeIf { it.stops.size == 4 && it.title == "A7 流程測試行程" }
        }
    }

    private fun loadTrains(arriveOverride: String? = null): ReturnTrainPlan? {
        scenario.onActivity { vm.loadReturnTrains(arriveOverride) }
        // 查詢可能回 null（例如回程地點不是車站），所以不能無條件等非 null；
        // 給固定時間讓協程跑完再讀最終狀態
        Thread.sleep(6_000)
        return vm.returnTrains.value
    }

    // ── 測試 ─────────────────────────────────────────────────────────

    @Test
    fun f01_車站模式_正確辨識回程車站並套用進站緩衝() {
        prefs.finalDestinationStation = ""   // 未指定最終目的地
        loadTrip()
        val plan = loadTrains()

        assertNotNull("回程地點是台東車站，應查到班次", plan)
        plan!!

        assertEquals("回程站名應取自行程", "台東車站", plan.stationName)
        assertEquals("台東車站應對到臺東站 6000", "6000", plan.stationId)
        assertEquals("抵達時間應取自回程站的排定時間", arriveTime, plan.arriveTime)
        assertEquals("未指定最終目的地時應為空字串", "", plan.finalDestination)
        assertTrue("應查到班次", plan.departures.isNotEmpty())

        // 🔑 進站緩衝：16:27 抵達 + 10 分鐘 = 16:37 之後的班次才推薦，
        // 否則會推薦一班下車就要立刻衝月台的車
        plan.departures.forEach {
            assertTrue(
                "不應推薦 $boardAfter 之前的班次（${it.trainNo} 於 ${it.departureTime}）",
                it.departureTime >= boardAfter
            )
        }

        // 車站模式不提供抵達時間（那是 OD 模式才有的）
        plan.departures.forEach {
            assertNull("車站模式不應有抵達時間", it.arrivalTime)
        }

        // 終點為本站的到站列車不可出現（TraServiceTest 已驗過資料層，這裡驗整條鏈路）
        plan.departures.forEach {
            assertTrue("不應出現終點為臺東的列車（${it.trainNo}）", it.destinationName != "臺東")
        }
    }

    @Test
    fun f02_OD模式_設定最終目的地後改查精準班次() {
        prefs.finalDestinationStation = "臺北"
        loadTrip()
        val plan = loadTrains()

        assertNotNull("設定最終目的地後應查到臺東→臺北的班次", plan)
        plan!!

        assertEquals("應記錄最終目的地", "臺北", plan.finalDestination)
        assertTrue("應查到班次", plan.departures.isNotEmpty())

        plan.departures.forEach {
            // OD 模式的價值：知道幾點到得了臺北
            assertNotNull("OD 模式應有抵達時間（${it.trainNo}）", it.arrivalTime)
            assertNotNull("OD 模式應有乘車時長（${it.trainNo}）", it.durationMins)
            assertTrue(
                "臺東到臺北乘車時間應在 3–7 小時，實際 ${it.durationMins} 分",
                it.durationMins!! in 180..420
            )
            assertTrue(
                "不應推薦 $boardAfter 之前的班次（${it.trainNo} 於 ${it.departureTime}）",
                it.departureTime >= boardAfter
            )
        }
    }

    @Test
    fun f03_行程落後時建議班次同步後移() {
        prefs.finalDestinationStation = ""
        loadTrip()

        // 準時的情況
        val onTime = loadTrains()
        assertNotNull("準時情況應查到班次", onTime)
        val onTimeFirst = onTime!!.departures.minByOrNull { it.departureTime }!!.departureTime

        // 模擬打卡落後 40 分鐘：TripInProgressScreen 會把 deriveTripState 重新推估的
        // 抵達時間傳進來，班次應該跟著往後推
        val delayed = loadTrains(arriveOverride = "17:07")
        assertNotNull("落後後仍應查到班次", delayed)
        delayed!!

        assertEquals("抵達時間應更新為推估值", "17:07", delayed.arriveTime)

        val newBoardAfter = "17:17"   // 17:07 + 10 分鐘緩衝
        delayed.departures.forEach {
            assertTrue(
                "落後後不應再推薦 $newBoardAfter 之前的班次（${it.trainNo} 於 ${it.departureTime}）",
                it.departureTime >= newBoardAfter
            )
        }

        // 🔑 這是整個功能的核心價值：落後之後，原本趕得上的那班已經趕不上了
        val delayedFirst = delayed.departures.minByOrNull { it.departureTime }!!.departureTime
        assertTrue(
            "落後 40 分後的首班車（$delayedFirst）應晚於準時情況的首班車（$onTimeFirst）",
            delayedFirst > onTimeFirst
        )
    }

    @Test
    fun f04_回程地點不是車站時不顯示班次() {
        prefs.finalDestinationStation = ""
        // 先載入正常行程讓 returnTrains 有值，確認接下來的 null 是真的被清掉而非從未設定
        loadTrip()
        assertNotNull("前置：正常行程應先查到班次", loadTrains())

        // 使用者把回程地點填成非車站的地方
        loadTrip(returnPlace = "鐵花村")
        val plan = loadTrains()

        assertNull(
            "「鐵花村」不是台鐵車站，應不顯示班次卡（寧可不顯示，也不能對到錯的車站）",
            plan
        )
    }
}
