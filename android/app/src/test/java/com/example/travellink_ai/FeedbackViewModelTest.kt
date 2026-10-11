package com.example.travellink_ai

import android.content.Context
import app.cash.turbine.test
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.ItineraryDao
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import com.example.travellink_ai.ui.feedback.FeedbackViewModel
import com.example.travellink_ai.ui.feedback.FeedbackState
import com.example.travellink_ai.ui.feedback.StopFeedbackItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var stateHolder: ItineraryStateHolder
    private lateinit var viewModel: FeedbackViewModel

    // Mocks
    private val context: Context = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val dao: ItineraryDao = mockk(relaxed = true)
    private val firestore: FirebaseFirestore = mockk(relaxed = true)
    private val firebaseAuth: FirebaseAuth = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stateHolder = ItineraryStateHolder()

        // DAO 回傳空 Flow，避免 StateFlow 初始化失敗
        every { database.itineraryDao() } returns dao
        every { dao.getAllItineraries() } returns flowOf(emptyList())

        viewModel = FeedbackViewModel(context, stateHolder, database, firestore, firebaseAuth)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 初始狀態 ────────────────────────────────────────────────

    @Test
    fun `初始 feedbackState 為預設值`() {
        val state = viewModel.feedbackState.value
        assertEquals(0, state.overallRating)
        assertEquals("", state.highlight)
        assertEquals("", state.suggestions)
        assertTrue(state.stopFeedbacks.isEmpty())
        assertTrue(state.wantToVisitAgainStops.isEmpty())
        assertNull(state.aiScheduleAccurate)
    }

    @Test
    fun `初始 isFeedbackSubmitting 為 false`() {
        assertFalse(viewModel.isFeedbackSubmitting.value)
    }

    @Test
    fun `初始 feedbackSubmitSuccess 為 false`() {
        assertFalse(viewModel.feedbackSubmitSuccess.value)
    }

    // ── 整體評分 ─────────────────────────────────────────────────

    @Test
    fun `updateFeedbackOverallRating 正確更新評分`() = runTest {
        viewModel.feedbackState.test {
            awaitItem() // 消費初始值

            viewModel.updateFeedbackOverallRating(4)
            val updated = awaitItem()
            assertEquals(4, updated.overallRating)
        }
    }

    @Test
    fun `評分範圍 1 到 5 都能設定`() {
        (1..5).forEach { rating ->
            viewModel.updateFeedbackOverallRating(rating)
            assertEquals(rating, viewModel.feedbackState.value.overallRating)
        }
    }

    // ── 文字欄位 ─────────────────────────────────────────────────

    @Test
    fun `updateFeedbackHighlight 正確更新 highlight`() {
        viewModel.updateFeedbackHighlight("最喜歡池上便當！")
        assertEquals("最喜歡池上便當！", viewModel.feedbackState.value.highlight)
    }

    @Test
    fun `updateFeedbackSuggestions 正確更新 suggestions`() {
        viewModel.updateFeedbackSuggestions("希望多安排海邊景點")
        assertEquals("希望多安排海邊景點", viewModel.feedbackState.value.suggestions)
    }

    // ── AI 準確度 ─────────────────────────────────────────────────

    @Test
    fun `updateAiScheduleAccurate 設定 true`() {
        viewModel.updateAiScheduleAccurate(true)
        assertTrue(viewModel.feedbackState.value.aiScheduleAccurate == true)
    }

    @Test
    fun `updateAiScheduleAccurate 設定 false`() {
        viewModel.updateAiScheduleAccurate(false)
        assertFalse(viewModel.feedbackState.value.aiScheduleAccurate == true)
    }

    @Test
    fun `updateAiTransitAccurate 設定後能讀回`() {
        viewModel.updateAiTransitAccurate(true)
        assertEquals(true, viewModel.feedbackState.value.aiTransitAccurate)
    }

    @Test
    fun `updateAiHoursAccurate 設定後能讀回`() {
        viewModel.updateAiHoursAccurate(false)
        assertEquals(false, viewModel.feedbackState.value.aiHoursAccurate)
    }

    // ── 景點清單 ─────────────────────────────────────────────────

    @Test
    fun `updateWantToVisitAgainStops 正確更新清單`() {
        viewModel.updateWantToVisitAgainStops(listOf("池上便當", "鹿野高台"))
        assertEquals(
            listOf("池上便當", "鹿野高台"),
            viewModel.feedbackState.value.wantToVisitAgainStops
        )
    }

    @Test
    fun `updateDontWantToVisitAgainStops 正確更新清單`() {
        viewModel.updateDontWantToVisitAgainStops(listOf("某景點"))
        assertEquals(
            listOf("某景點"),
            viewModel.feedbackState.value.dontWantToVisitAgainStops
        )
    }

    // ── 單站回饋 ─────────────────────────────────────────────────

    @Test
    fun `updateStopVisited 設定景點已造訪`() {
        viewModel.updateStopVisited("池上便當", visited = true)
        assertTrue(viewModel.feedbackState.value.stopFeedbacks["池上便當"]?.visited == true)
    }

    @Test
    fun `updateStopVisited 設定景點未造訪`() {
        viewModel.updateStopVisited("某景點", visited = false)
        assertFalse(viewModel.feedbackState.value.stopFeedbacks["某景點"]?.visited == true)
    }

    @Test
    fun `updateStopRating 設定景點評分`() {
        viewModel.updateStopRating("鹿野高台", 5)
        assertEquals(5, viewModel.feedbackState.value.stopFeedbacks["鹿野高台"]?.rating)
    }

    @Test
    fun `updateStopDuration 設定停留時間回饋`() {
        viewModel.updateStopDuration("池上便當", "太短")
        assertEquals("太短", viewModel.feedbackState.value.stopFeedbacks["池上便當"]?.durationFeedback)
    }

    @Test
    fun `多次更新同一景點只保留最新值`() {
        viewModel.updateStopRating("池上便當", 3)
        viewModel.updateStopRating("池上便當", 5)
        assertEquals(5, viewModel.feedbackState.value.stopFeedbacks["池上便當"]?.rating)
    }

    @Test
    fun `不同景點的回饋互不影響`() {
        viewModel.updateStopRating("池上便當", 5)
        viewModel.updateStopRating("鹿野高台", 3)
        assertEquals(5, viewModel.feedbackState.value.stopFeedbacks["池上便當"]?.rating)
        assertEquals(3, viewModel.feedbackState.value.stopFeedbacks["鹿野高台"]?.rating)
    }

    // ── resetFeedback ────────────────────────────────────────────

    @Test
    fun `resetFeedback 清除所有狀態`() {
        // 先設定一些狀態
        viewModel.updateFeedbackOverallRating(5)
        viewModel.updateFeedbackHighlight("很棒的旅程！")
        viewModel.updateStopRating("池上便當", 5)

        viewModel.resetFeedback()

        val state = viewModel.feedbackState.value
        assertEquals(0, state.overallRating)
        assertEquals("", state.highlight)
        assertTrue(state.stopFeedbacks.isEmpty())
        assertFalse(viewModel.feedbackSubmitSuccess.value)
    }

    // ── FeedbackState 不可變性 ───────────────────────────────────

    @Test
    fun `每次 update 都產生新的 FeedbackState 實例`() {
        val before = viewModel.feedbackState.value
        viewModel.updateFeedbackOverallRating(3)
        val after = viewModel.feedbackState.value
        assertFalse(before === after) // 不同實例
        assertEquals(3, after.overallRating)
        assertEquals(0, before.overallRating) // 舊值不受影響
    }
}
