package com.example.travellink_ai.data.model

import com.google.firebase.Timestamp

/** 好友精確搜尋回傳的最小公開檔案（user_profiles_by_email）。 */
data class FriendProfile(
    val email: String,
    val name: String,
    val emoji: String = "🌟"
)

/** friendships/{docId} 一筆好友關係。docId＝兩端 identityKey 排序後以 '__' 相接。 */
data class Friendship(
    val id: String,
    val emails: List<String>,
    val fromEmail: String,
    val toEmail: String,
    val names: Map<String, String>,
    val status: String,          // "pending" | "accepted"
    val cycleId: String? = null,
    val createdAt: Timestamp? = null,
    val acceptedAt: Timestamp? = null
)

/** 送出邀請後回傳，供組通知 nid 使用。 */
data class InviteResult(val id: String, val cycleId: String)

/** user_notifications/{identityKey}/items/{nid} 一則站內通知。 */
data class FriendNotification(
    val id: String,
    val type: String,
    val fromEmail: String,
    val toEmail: String,
    val fromName: String,
    val tripId: String = "",
    val tripTitle: String = "",
    val message: String = "",
    val read: Boolean = false,
    val createdAt: Timestamp? = null
)
