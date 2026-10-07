package com.example.travellink_ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.travellink_ai.data.model.Stop

@Entity(tableName = "itineraries")
data class LocalItinerary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val aiTitle: String, // 🌟 新增
    val aiReply: String, // 🌟 新增
    val region: String,
    val days: String,     // 🌟 新增
    val people: String,   // 🌟 新增
    val stops: List<Stop>,
    val isSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,
    val firestoreDocId: String? = null,  // 🌟 v4：對應 Firestore 的 document ID
    val feedbackSubmitted: Boolean = false,  // 🌟 v5：是否已填寫回饋
    val overallRating: Int = 0,              // 🌟 v6：整體評分（1-5，0 表示未評）
    val joinPin: String? = null,             // 🌟 v7：協作邀請 PIN 碼
    val joinPinExpiresAt: Long = 0L,         // 🌟 v7：PIN 碼過期時間戳（ms）
    // 🌟 v8：生成當下算好的路線資料快取，供歷史行程重新開啟時直接重用，
    //        免去 Geocoding／Places／Directions 的重複呼叫。
    //        roadSegmentsEncoded：多段 polyline，用 "|" 分隔（對應 stops 相鄰兩兩之間的路線）
    //        transitTimesJson：每段車程分鐘數，用 "," 分隔，例如 "12,8,15"
    //        資料皆可能為 null（舊版行程或編碼失敗時），此時 loadFromHistory 會 fallback 重新查詢
    val roadSegmentsEncoded: String? = null,
    val transitTimesJson: String? = null,
    val userId: String = "",  // 🌟 v9：綁定 Firebase Auth UID，空字串代表舊資料（視為本機行程）
    val transportMode: String = "taxi"  // 🌟 v10：生成時選擇的交通方式（taxi/walking/driving）
)