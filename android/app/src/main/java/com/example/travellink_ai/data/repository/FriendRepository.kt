package com.example.travellink_ai.data.repository

import com.example.travellink_ai.data.model.FriendProfile
import com.example.travellink_ai.data.model.Friendship
import com.example.travellink_ai.data.model.InviteResult
import com.example.travellink_ai.data.util.IdentityKeys
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 好友資料層，1:1 對照網頁端 friends.js（版本 20260722-friends2）。
 *
 * 集合：
 *  - user_profiles/{uid}                 本人私人檔案
 *  - user_profiles_by_email/{idKey}      好友精確搜尋索引（只允許 get）
 *  - friendships/{docId}                 好友關係（pending → accepted）
 *
 * ★schema 與網頁端對齊：identityKey＝email UTF-8 hex；docId＝兩端 idKey 排序後 '__' 相接；
 *   createdAt/acceptedAt 一律 serverTimestamp（rules 驗 ==request.time）。
 */
@Singleton
class FriendRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    private fun idKey(email: String) = IdentityKeys.identityKey(email)
    private fun legacyKey(email: String) = IdentityKeys.legacyMemberKey(email)

    /** 好友關係文件 id：兩端 idKey 排序後相接，A→B 與 B→A 天生撞同一 doc。 */
    fun friendshipId(emailA: String, emailB: String): String =
        listOf(idKey(emailA), idKey(emailB)).sorted().joinToString("__")

    // ── user_profiles：登入/註冊/改檔時同步 ──
    /** 同步本人公開檔案；好友搜尋靠 user_profiles_by_email。索引未部署時靜默略過，下次登入補寫。 */
    suspend fun syncMyProfile(uid: String, email: String, name: String, emoji: String) {
        if (uid.isBlank() || email.isBlank()) return
        val safeName = name.ifBlank { email }
        db.collection("user_profiles").document(uid).set(
            mapOf(
                "uid" to uid,
                "email" to email,
                "emailLower" to email.trim().lowercase(),
                "name" to safeName,
                "emoji" to emoji,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
        try {
            db.collection("user_profiles_by_email").document(idKey(email)).set(
                mapOf(
                    "email" to email,
                    "name" to safeName,
                    "emoji" to emoji,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        } catch (_: Exception) {
            // Rules 部署前的短暫期間可能寫不進，登入流程下次會補；不影響本人檔案。
        }
    }

    /** 以 email 精確查公開檔案；找不到回 null（對方需登入過才有檔案）。 */
    suspend fun findProfileByEmail(email: String): FriendProfile? {
        val q = email.trim().lowercase()
        if (q.isEmpty()) return null
        val snap = db.collection("user_profiles_by_email").document(idKey(q)).get().await()
        if (!snap.exists()) return null
        return FriendProfile(
            email = snap.getString("email") ?: q,
            name = snap.getString("name") ?: q,
            emoji = snap.getString("emoji") ?: "🌟"
        )
    }

    // ── friendships：邀請 → 接受 ──
    /**
     * 送出好友邀請。先以 array-contains 找既有關係（相容舊 emailKey 文件，也避免對不存在文件
     * get 被 rules 擋），已存在則丟出精確訊息；否則以固定 docId 建立 pending。
     */
    suspend fun sendInvite(me: FriendProfile, target: FriendProfile): InviteResult {
        if (me.email.isBlank()) throw IllegalStateException("請先登入。")
        if (target.email.isBlank()) throw IllegalStateException("找不到對方資料。")
        if (idKey(me.email) == idKey(target.email)) throw IllegalStateException("不能加自己為好友。")

        val related = db.collection("friendships")
            .whereArrayContains("emails", me.email).get().await()
        related.documents.firstOrNull { doc ->
            (doc.get("emails") as? List<*>)?.any {
                (it as? String)?.lowercase() == target.email.lowercase()
            } == true
        }?.let { doc ->
            if (doc.getString("status") == "accepted") throw IllegalStateException("你們已經是好友了。")
            throw IllegalStateException(
                if (doc.getString("fromEmail") == me.email) "已送出過邀請，等待對方確認中。"
                else "對方已邀請過你，請到「待確認邀請」接受。"
            )
        }

        val id = friendshipId(me.email, target.email)
        val cycleId = newCycleId()
        // 同時寫 identityKey 與 legacy key 兩份名稱，Android／舊 Web 皆能顯示。
        val names = mapOf(
            idKey(me.email) to me.name.ifBlank { me.email },
            idKey(target.email) to target.name.ifBlank { target.email },
            legacyKey(me.email) to me.name.ifBlank { me.email },
            legacyKey(target.email) to target.name.ifBlank { target.email }
        )
        db.collection("friendships").document(id).set(
            mapOf(
                "emails" to listOf(me.email, target.email),
                "fromEmail" to me.email,
                "toEmail" to target.email,
                "names" to names,
                "status" to "pending",
                "cycleId" to cycleId,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return InviteResult(id, cycleId)
    }

    /** 接受邀請（只有 toEmail 本人；rules 限 pending→accepted 且只動 status/acceptedAt）。 */
    suspend fun acceptInvite(friendshipDocId: String) {
        db.collection("friendships").document(friendshipDocId).update(
            mapOf(
                "status" to "accepted",
                "acceptedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    /** 刪除好友關係：拒絕邀請／取消已送邀請／解除好友（rules 允許任一當事人）。 */
    suspend fun removeFriendship(friendshipDocId: String) {
        db.collection("friendships").document(friendshipDocId).delete().await()
    }

    /** 訂閱與我有關的所有好友關係（array-contains 我的 email）；呼叫端自行分三堆。 */
    fun observeFriendships(myEmail: String): Flow<List<Friendship>> = callbackFlow {
        if (myEmail.isBlank()) {
            trySend(emptyList()); close(); return@callbackFlow
        }
        val reg = db.collection("friendships")
            .whereArrayContains("emails", myEmail)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.documents?.mapNotNull { it.toFriendship() } ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    private fun newCycleId(): String {
        val ts = System.currentTimeMillis().toString(36)
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val rand = buildString { repeat(8) { append(chars[Random.nextInt(chars.length)]) } }
        return "${ts}_$rand"   // ~17 字元，落在 rules 要求的 [8,64]
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toFriendship(): Friendship? {
        val emails = (get("emails") as? List<*>)?.filterIsInstance<String>() ?: return null
        val status = getString("status") ?: return null
        val names = (get("names") as? Map<*, *>)?.entries
            ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> key to (v as? String ?: "") } }
            ?.toMap() ?: emptyMap()
        return Friendship(
            id = id,
            emails = emails,
            fromEmail = getString("fromEmail") ?: "",
            toEmail = getString("toEmail") ?: "",
            names = names,
            status = status,
            cycleId = getString("cycleId"),
            createdAt = get("createdAt") as? Timestamp,
            acceptedAt = get("acceptedAt") as? Timestamp
        )
    }
}
