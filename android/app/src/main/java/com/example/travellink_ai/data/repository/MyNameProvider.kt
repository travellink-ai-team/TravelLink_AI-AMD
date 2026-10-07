package com.example.travellink_ai.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片／短片上掛的作者名稱（ownerName）。
 *
 * 對齊網頁 a92fb4b：優先用 App 內設定的暱稱（`users/{uid}.name`），而不是 Firebase Auth 的
 * displayName——後者多半是空的，會退到 email 前綴，畫面上出現一串帳號字串。
 * 這只是顯示標籤，身分一律看 ownerUid；撞名在顯示時處理（見 ownerLabels）。
 */
@Singleton
class MyNameProvider @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {
    @Volatile private var cached: Pair<String, String>? = null   // uid → 暱稱

    /** 暱稱 → displayName → email 前綴 → 旅人。每次都重讀，使用者改了暱稱才會跟上。 */
    suspend fun myName(): String {
        val user = auth.currentUser ?: return "旅人"
        val nickname = withTimeoutOrNull(3_000) {
            runCatching {
                db.collection("users").document(user.uid).get().await().getString("name")
            }.getOrNull()
        }?.trim()?.takeIf { it.isNotBlank() }
        if (nickname != null) cached = user.uid to nickname
        return nickname ?: cachedName() ?: fallback()
    }

    /** 不能等網路時用：上次讀到的暱稱，沒有就走後備。 */
    fun cachedName(): String? =
        cached?.takeIf { it.first == auth.currentUser?.uid }?.second

    fun cachedOrFallback(): String = cachedName() ?: fallback()

    private fun fallback(): String {
        val user = auth.currentUser ?: return "旅人"
        return user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "旅人"
    }
}
