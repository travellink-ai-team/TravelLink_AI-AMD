package com.example.travellink_ai

import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.VisualData
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 驗證 ItineraryStateHolder 的初始狀態與基本讀寫。
 * 不需要 Android Context，可在 JVM 直接執行。
 */
class ItineraryStateHolderTest {

    private lateinit var stateHolder: ItineraryStateHolder

    @Before
    fun setUp() {
        stateHolder = ItineraryStateHolder()
    }

    // ── 初始狀態 ────────────────────────────────────────────────

    @Test
    fun `初始行程為 null`() {
        assertNull(stateHolder.itinerary.value)
    }

    @Test
    fun `初始 firestoreDocId 為 null`() {
        assertNull(stateHolder.currentFirestoreDocId.value)
    }

    @Test
    fun `初始 lastGeneratedLocalId 為 -1`() {
        assertEquals(-1L, stateHolder.lastGeneratedLocalId)
    }

    @Test
    fun `初始 currentScreen 為 home`() {
        assertEquals("home", stateHolder.currentScreen)
    }

    @Test
    fun `初始 resultInitialView 為 map`() {
        assertEquals("map", stateHolder.resultInitialView)
    }

    @Test
    fun `初始 visualState 無 loading 也無 bitmap`() {
        val state = stateHolder.visualState.value
        assertFalse(state.isLoading)
        assertFalse(state.isFailed)
        assertNull(state.bitmap)
    }

    @Test
    fun `初始 stopLocations 為空`() {
        assertTrue(stateHolder.stopLocations.value.isEmpty())
    }

    @Test
    fun `初始 remoteHistory 為空`() {
        assertTrue(stateHolder.remoteHistory.value.isEmpty())
    }

    // ── 寫入後能正確讀回 ────────────────────────────────────────

    @Test
    fun `設定 currentScreen 後能讀回`() {
        stateHolder.currentScreen = "result"
        assertEquals("result", stateHolder.currentScreen)
    }

    @Test
    fun `設定 currentFirestoreDocId 後能讀回`() {
        stateHolder.currentFirestoreDocId.value = "doc_abc123"
        assertEquals("doc_abc123", stateHolder.currentFirestoreDocId.value)
    }

    @Test
    fun `設定 lastGeneratedLocalId 後能讀回`() {
        stateHolder.lastGeneratedLocalId = 42L
        assertEquals(42L, stateHolder.lastGeneratedLocalId)
    }

    @Test
    fun `設定 visualState 為 loading`() {
        stateHolder.visualState.value = VisualData(isLoading = true)
        assertTrue(stateHolder.visualState.value.isLoading)
    }

    @Test
    fun `設定 visualState 為 failed`() {
        stateHolder.visualState.value = VisualData(isFailed = true)
        assertTrue(stateHolder.visualState.value.isFailed)
        assertFalse(stateHolder.visualState.value.isLoading)
    }

    @Test
    fun `recentlyDeletedDocIds 可以新增與查詢`() {
        stateHolder.recentlyDeletedDocIds.add("doc_001")
        assertTrue("doc_001" in stateHolder.recentlyDeletedDocIds)
        assertFalse("doc_002" in stateHolder.recentlyDeletedDocIds)
    }

    @Test
    fun `recentlyDeletedCreatedAts 可以新增與查詢`() {
        val ts = System.currentTimeMillis()
        stateHolder.recentlyDeletedCreatedAts.add(ts)
        assertTrue(stateHolder.recentlyDeletedCreatedAts.any {
            kotlin.math.abs(it - ts) < 10_000L
        })
    }

    @Test
    fun `backgroundUrl 預設為 null，設定後可讀回`() {
        assertNull(stateHolder.backgroundUrl.value)
        stateHolder.backgroundUrl.value = "file:///storage/test.jpg"
        assertEquals("file:///storage/test.jpg", stateHolder.backgroundUrl.value)
    }

    @Test
    fun `isWorkflowRunning 初始為 false，可設為 true`() {
        assertFalse(stateHolder.isWorkflowRunning.get())
        stateHolder.isWorkflowRunning.set(true)
        assertTrue(stateHolder.isWorkflowRunning.get())
    }

    // ── 返回路徑 ────────────────────────────────────────────────

    @Test
    fun `我的微旅行進行中行程離開後，預覽返回回到我的微旅行`() {
        stateHolder.currentScreen = "history"
        stateHolder.currentScreen = "trip_progress"
        stateHolder.currentScreen = "stop_detail"
        stateHolder.currentScreen = "trip_progress"
        stateHolder.currentScreen = "preview"
        assertEquals("history", stateHolder.previewReturnScreen)
    }

    @Test
    fun `從預覽開始行程再離開，預覽返回維持原來源`() {
        stateHolder.currentScreen = "history"
        stateHolder.currentScreen = "preview"
        stateHolder.currentScreen = "trip_progress"
        stateHolder.currentScreen = "preview"
        assertEquals("history", stateHolder.previewReturnScreen)
    }
}
