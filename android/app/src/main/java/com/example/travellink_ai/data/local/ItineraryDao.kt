package com.example.travellink_ai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.travellink_ai.data.model.Stop
import kotlinx.coroutines.flow.Flow

@Dao
interface ItineraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItinerary(itinerary: LocalItinerary): Long

    @Query("UPDATE itineraries SET isSynced = :synced WHERE id = :id")
    suspend fun updateSyncStatus(id: Int, synced: Boolean)

    @Delete
    suspend fun deleteItinerary(itinerary: LocalItinerary)
    @Query("UPDATE itineraries SET imageUrl = :url WHERE id = :id")
    suspend fun updateImageUrl(id: Long, url: String)
    @Query("SELECT * FROM itineraries ORDER BY createdAt DESC")
    fun getAllItineraries(): Flow<List<LocalItinerary>>

    // 🌟 v9：依 userId 過濾（登入後只顯示自己的行程）
    @Query("SELECT * FROM itineraries WHERE userId = :userId ORDER BY createdAt DESC")
    fun getAllItinerariesByUser(userId: String): Flow<List<LocalItinerary>>

    @Query("SELECT * FROM itineraries WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getAllItinerariesSyncByUser(userId: String): List<LocalItinerary>

    // 🌟 新增：根據 ID 取得特定行程
    @Query("SELECT * FROM itineraries WHERE id = :id")
    suspend fun getItineraryById(id: Long): LocalItinerary?

    // 🌟 新增：刪除超過指定時間戳的行程（用於兩週自動清理）
    @Query("DELETE FROM itineraries WHERE createdAt < :threshold AND (userId = :userId OR userId = '')")
    suspend fun deleteOlderThan(threshold: Long, userId: String)

    // 🌟 新增：更新景點列表（編輯行程用）
    @Query("UPDATE itineraries SET stops = :stops WHERE id = :id")
    suspend fun updateStops(id: Long, stops: List<Stop>)

    // 🌟 新增：批次刪除（歷史頁多選刪除用）
    @Query("DELETE FROM itineraries WHERE id IN (:ids)")
    suspend fun deleteItinerariesByIds(ids: List<Long>)

    // 🌟 新增：寫入 Firestore docId（生成行程後補寫用）
    @Query("UPDATE itineraries SET firestoreDocId = :docId WHERE id = :id")
    suspend fun updateFirestoreDocId(id: Long, docId: String)

    // 🌟 新增：標記已填寫回饋（v5）
    @Query("UPDATE itineraries SET feedbackSubmitted = :submitted WHERE id = :id")
    suspend fun updateFeedbackSubmitted(id: Long, submitted: Boolean)

    // 🌟 新增：寫入整體評分（v6）
    @Query("UPDATE itineraries SET overallRating = :rating WHERE id = :id")
    suspend fun updateOverallRating(id: Long, rating: Int)

    // 🌟 新增：寫入邀請 PIN 碼與過期時間（v7）
    @Query("UPDATE itineraries SET joinPin = :pin, joinPinExpiresAt = :expiresAt WHERE id = :id")
    suspend fun updateJoinPin(id: Long, pin: String, expiresAt: Long)

    // 行程頁切換主要交通工具
    @Query("UPDATE itineraries SET transportMode = :mode WHERE id = :id")
    suspend fun updateTransportMode(id: Long, mode: String)

    // 改名（我的微旅行「📝 改名」；純 UPDATE，不動 schema）
    @Query("UPDATE itineraries SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    // 🌟 新增：依 Firestore docId 查詢行程（供 PIN 離線讀取用）
    @Query("SELECT * FROM itineraries WHERE firestoreDocId = :firestoreDocId LIMIT 1")
    suspend fun getItineraryByFirestoreDocId(firestoreDocId: String): LocalItinerary?

    // 🌟 新增：一次性取得所有行程（供個人回饋提示查詢用）
    @Query("SELECT * FROM itineraries ORDER BY createdAt DESC")
    suspend fun getAllItinerariesSync(): List<LocalItinerary>

    // 🌟 v8：寫入地圖資料快取（座標已內嵌於 stops，連同路線折線與車程一併存入）
    // 用途：歷史行程重新開啟時可直接重用，避免重複呼叫 Geocoding／Places／Directions
    @Query("UPDATE itineraries SET stops = :stops, roadSegmentsEncoded = :segmentsEncoded, transitTimesJson = :transitTimesJson WHERE id = :id")
    suspend fun updateMapCache(id: Long, stops: List<Stop>, segmentsEncoded: String?, transitTimesJson: String?)
}