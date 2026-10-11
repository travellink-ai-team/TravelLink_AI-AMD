package com.example.travellink_ai.data.repository

import com.example.travellink_ai.data.model.FriendNotification
import com.example.travellink_ai.data.util.IdentityKeys
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 站內通知資料層，1:1 對照網頁端 notifications.js（版本 20260722-friends2）。
 *
 * user_notifications/{identityKey(toEmail)}/items/{nid}
 *   { type, fromEmail, toEmail, fromName, tripId, tripTitle, message, read:false, createdAt }
 *
 * nid 為確定性 id（同事件重送會撞同一 doc；rules 只允許 create，第二次寫入被拒→靜默＝天生防打擾）。
 * type 白名單：collab_invite / friend_invite / friend_accept / trip_renamed / trip_regenerated / friend_trip_completed
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {

    private fun idKey(email: String) = IdentityKeys.identityKey(email)

    /** 確定性 nid：每段淨化（非 A-Za-z0-9_- 換 '_'）後以 '__' 相接，上限 300。 */
    fun nid(parts: List<String>): String =
        parts.map { it.replace(Regex("[^A-Za-z0-9_-]"), "_") }
            .filter { it.isNotEmpty() }
            .joinToString("__")
            .take(300)

    /** 寫一則通知給 toEmail。失敗（rules 未部署／已存在／未登入）一律靜默回 false。 */
    suspend fun push(
        toEmail: String,
        id: String,
        type: String,
        fromName: String,
        tripId: String = "",
        tripTitle: String = "",
        message: String = ""
    ): Boolean {
        val sender = auth.currentUser?.email ?: return false
        if (toEmail.isBlank() || id.isBlank()) return false
        if (idKey(toEmail) == idKey(sender)) return false   // 不通知自己
        return try {
            db.collection("user_notifications").document(idKey(toEmail))
                .collection("items").document(id).set(
                    mapOf(
                        "type" to type,
                        "fromEmail" to sender,
                        "toEmail" to toEmail.trim(),
                        "fromName" to fromName.ifBlank { sender }.take(100),
                        "tripId" to tripId.take(150),
                        "tripTitle" to tripTitle.take(200),
                        "message" to message.take(200),
                        "read" to false,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            true
        } catch (_: Exception) {
            false   // 確定性 docId 已存在或 rules 未部署 → 靜默
        }
    }

    /** 群發（排除自己）。idFor 依收件人 email 產生 nid。 */
    suspend fun pushToMany(
        emails: List<String>,
        idFor: (String) -> String,
        type: String,
        fromName: String,
        tripId: String = "",
        tripTitle: String = "",
        message: String = ""
    ) {
        val myKey = auth.currentUser?.email?.let { idKey(it) } ?: return
        emails.filter { it.isNotBlank() && idKey(it) != myKey }
            .forEach { push(it, idFor(it), type, fromName, tripId, tripTitle, message) }
    }

    /** 訂閱自己的通知（新到舊，最多 50 則）。 */
    fun observeNotifications(myEmail: String): Flow<List<FriendNotification>> = callbackFlow {
        if (myEmail.isBlank()) {
            trySend(emptyList()); close(); return@callbackFlow
        }
        val reg = db.collection("user_notifications").document(idKey(myEmail))
            .collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                trySend(snap?.documents?.mapNotNull { it.toNotification() } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    suspend fun markRead(myEmail: String, itemId: String) {
        if (myEmail.isBlank() || itemId.isBlank()) return
        try {
            db.collection("user_notifications").document(idKey(myEmail))
                .collection("items").document(itemId).update("read", true).await()
        } catch (_: Exception) { /* 靜默 */ }
    }

    suspend fun markAllRead(myEmail: String, items: List<FriendNotification>) {
        if (myEmail.isBlank()) return
        val unread = items.filter { !it.read }.take(50)
        if (unread.isEmpty()) return
        try {
            val batch = db.batch()
            val col = db.collection("user_notifications").document(idKey(myEmail)).collection("items")
            unread.forEach { batch.update(col.document(it.id), "read", true) }
            batch.commit().await()
        } catch (_: Exception) { /* 靜默 */ }
    }

    /** 取「已成立好友」的 email 清單（完成行程通知好友用）；status 過濾在 client 端做。 */
    suspend fun fetchAcceptedFriendEmails(myEmail: String): List<String> {
        if (myEmail.isBlank()) return emptyList()
        return try {
            val snap = db.collection("friendships")
                .whereArrayContains("emails", myEmail).get().await()
            val out = LinkedHashSet<String>()
            snap.documents.forEach { doc ->
                if (doc.getString("status") != "accepted") return@forEach
                (doc.get("emails") as? List<*>)?.forEach { e ->
                    val email = e as? String ?: return@forEach
                    if (email != myEmail) out.add(email)
                }
            }
            out.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun DocumentSnapshot.toNotification(): FriendNotification? {
        val type = getString("type") ?: return null
        return FriendNotification(
            id = id,
            type = type,
            fromEmail = getString("fromEmail") ?: "",
            toEmail = getString("toEmail") ?: "",
            fromName = getString("fromName") ?: "",
            tripId = getString("tripId") ?: "",
            tripTitle = getString("tripTitle") ?: "",
            message = getString("message") ?: "",
            read = getBoolean("read") ?: false,
            createdAt = get("createdAt") as? Timestamp
        )
    }
}
