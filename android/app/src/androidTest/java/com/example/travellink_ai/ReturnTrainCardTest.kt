package com.example.travellink_ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.travellink_ai.data.transit.ReturnTrainPlan
import com.example.travellink_ai.data.transit.TraDeparture
import com.example.travellink_ai.ui.planning.ReturnTrainCard
import com.example.travellink_ai.ui.trip.LiveReturnTrainCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A7 ② 回程班次卡版面測試。
 *
 * 全部用寫死的假資料，不打網路、不碰 ViewModel、不經過登入牆，也不會觸發 AI 生成費用。
 * 目的是驗證「資料長這樣時，畫面該顯示什麼」，與 TraServiceTest（驗真實 API）互補。
 */
@RunWith(AndroidJUnit4::class)
class ReturnTrainCardTest {

    @get:Rule
    val compose = createComposeRule()

    // ── 測試資料 ─────────────────────────────────────────────────────

    /** OD 模式：有指定最終目的地，帶抵達時間與乘車時長 */
    private val odPlan = ReturnTrainPlan(
        stationName = "台東車站",
        stationId = "6000",
        arriveTime = "16:27",
        finalDestination = "臺北",
        departures = listOf(
            TraDeparture(
                trainNo = "439", trainType = "自強(3000)", departureTime = "16:55",
                destinationName = "樹林", direction = 1,
                arrivalTime = "21:34", durationMins = 279
            ),
            TraDeparture(
                trainNo = "441", trainType = "自強(3000)", departureTime = "17:46",
                destinationName = "樹林", direction = 1,
                arrivalTime = "21:38", durationMins = 232
            )
        )
    )

    /** 車站模式：未指定目的地，南下北上都列 */
    private val stationPlan = ReturnTrainPlan(
        stationName = "台東車站",
        stationId = "6000",
        arriveTime = "16:27",
        finalDestination = "",
        departures = listOf(
            TraDeparture("3038", "區間快", "17:00", "新左營", direction = 0),
            TraDeparture("386", "自強(3000)", "17:30", "臺中", direction = 0),
            TraDeparture("4547", "區間", "16:49", "玉里", direction = 1),
            TraDeparture("439", "自強(3000)", "16:55", "樹林", direction = 1)
        )
    )

    // ── 預覽頁卡片 ───────────────────────────────────────────────────

    @Test
    fun 預覽頁_OD模式顯示抵達時間與乘車時長() {
        compose.setContent { ReturnTrainCard(odPlan) }

        compose.onNodeWithText("回程班次").assertIsDisplayed()
        // 摘要行：預計幾點抵達哪一站、要往哪裡
        compose.onNodeWithText("預計 16:27 抵達台東車站，往臺北").assertIsDisplayed()

        // 每一班的發車時間、車種車次
        compose.onNodeWithText("16:55").assertIsDisplayed()
        compose.onNodeWithText("自強(3000) 439").assertIsDisplayed()
        // OD 模式獨有：抵達時間與乘車分鐘數
        compose.onNodeWithText("往樹林 · 21:34 抵達（279 分）").assertIsDisplayed()

        compose.onNodeWithText("17:46").assertIsDisplayed()
        compose.onNodeWithText("往樹林 · 21:38 抵達（232 分）").assertIsDisplayed()

        // 資料來源標註（觀光/交通開放資料的使用規範要求標示）
        compose.onNodeWithText("班次資料來源：交通部 TDX，已預留 10 分鐘進站時間")
            .assertIsDisplayed()
    }

    @Test
    fun 預覽頁_車站模式分成南下北上兩組() {
        compose.setContent { ReturnTrainCard(stationPlan) }

        // 未指定目的地時，摘要行不應出現「往…」
        compose.onNodeWithText("預計 16:27 抵達台東車站").assertIsDisplayed()

        // 兩個方向的分組標題都要在
        compose.onNodeWithText("南下").assertIsDisplayed()
        compose.onNodeWithText("北上").assertIsDisplayed()

        // 四班車都要顯示
        listOf("17:00", "17:30", "16:49", "16:55").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }

        // 車站模式沒有抵達時間，敘述只有「往…」
        compose.onNodeWithText("往新左營").assertIsDisplayed()
        compose.onNodeWithText("往玉里").assertIsDisplayed()
    }

    @Test
    fun 預覽頁_無誤點資料時不顯示誤點或準點標籤() {
        compose.setContent { ReturnTrainCard(odPlan) }
        // delayMins 為 null（預覽頁通常還沒查即時誤點）→ 兩種標籤都不該出現
        compose.onAllNodesWithText("準點").assertCountEquals(0)
        compose.onAllNodesWithText("誤點 5 分").assertCountEquals(0)
    }

    // ── 行程進行中卡片 ───────────────────────────────────────────────

    @Test
    fun 進行中_顯示依目前進度推估的抵達時間() {
        compose.setContent { LiveReturnTrainCard(odPlan) }

        compose.onNodeWithText("回程班次").assertIsDisplayed()
        // 進行中的措辭與預覽頁不同，強調是「依目前進度」動態推估的
        compose.onNodeWithText("依目前進度，約 16:27 抵達台東車站，往臺北").assertIsDisplayed()
        compose.onNodeWithText("誤點資訊每分鐘更新・來源 交通部 TDX").assertIsDisplayed()
    }

    @Test
    fun 進行中_誤點與準點分別顯示() {
        val withDelays = odPlan.copy(
            departures = listOf(
                odPlan.departures[0].copy(delayMins = 12),  // 誤點
                odPlan.departures[1].copy(delayMins = 0)    // 準點
            )
        )
        compose.setContent { LiveReturnTrainCard(withDelays) }

        compose.onNodeWithText("誤點 12 分").assertIsDisplayed()
        compose.onNodeWithText("準點").assertIsDisplayed()
    }

    @Test
    fun 進行中_最多只顯示三班避免卡片過長() {
        val many = odPlan.copy(
            departures = (1..6).map {
                TraDeparture(
                    trainNo = "T$it", trainType = "自強", departureTime = "1$it:00",
                    destinationName = "樹林", direction = 1,
                    arrivalTime = "2$it:00", durationMins = 300
                )
            }
        )
        compose.setContent { LiveReturnTrainCard(many) }

        // 前三班在
        listOf("T1", "T2", "T3").forEach {
            compose.onNodeWithText("自強 $it").assertIsDisplayed()
        }
        // 第四班之後不顯示
        listOf("T4", "T5", "T6").forEach {
            compose.onAllNodesWithText("自強 $it").assertCountEquals(0)
        }
    }

    @Test
    fun 進行中_行程落後時抵達時間與班次同步後移() {
        // 模擬打卡落後 40 分：ViewModel 會用 deriveTripState 推估的新時間重查班次
        val delayed = odPlan.copy(
            arriveTime = "17:07",
            departures = listOf(
                TraDeparture("386", "自強(3000)", "17:30", "臺中", 0, "21:00", 210),
                TraDeparture("441", "自強(3000)", "17:46", "樹林", 1, "21:38", 232)
            )
        )
        compose.setContent { LiveReturnTrainCard(delayed) }

        compose.onNodeWithText("依目前進度，約 17:07 抵達台東車站，往臺北").assertIsDisplayed()
        compose.onNodeWithText("17:30").assertIsDisplayed()
        // 原本推薦的 16:55 那班已經趕不上，不該再出現
        compose.onAllNodesWithText("16:55").assertCountEquals(0)
    }
}
