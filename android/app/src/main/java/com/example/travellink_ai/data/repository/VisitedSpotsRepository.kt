package com.example.travellink_ai.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「📌 我去過了」造訪紀錄，與網頁共用 `users/{uid}.visitedSpots`（網頁 toggleVisitedPlace）。
 *
 * 陣列元素是網頁格式的 map：{name, region, visitDate, tripId, tripTitle, emoji, gpsVerified, photos, note}，
 * 其中 photos／note 由網頁旅記寫入。App 只新增／移除整筆，**既有元素原樣保留**（不重建 map），
 * 否則會洗掉網頁的照片與備註。
 *
 * 比對以名稱為準：去空白、轉小寫（網頁 visitedPlaceNameKey）。
 * 寫入走交易讀最新陣列再整包寫回——網頁也是整包寫，這是同一個欄位唯一安全的寫法。
 */
@Singleton
class VisitedSpotsRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage
) {
    private fun ref() = auth.currentUser?.uid?.let { db.collection("users").document(it) }

    /** 已去過的景點名稱（原始名稱，未正規化） */
    fun observeNames(): Flow<Set<String>> = callbackFlow {
        val r = ref()
        if (r == null) { trySend(emptySet()); close(); return@callbackFlow }
        val reg = r.addSnapshotListener { snap, _ ->
            trySend(((snap?.get("visitedSpots") as? List<*>) ?: emptyList<Any?>()).mapNotNull { elementName(it) }.toSet())
        }
        awaitClose { reg.remove() }
    }

    /** 一次性讀取（生成行程時用來排除） */
    suspend fun fetchNames(): Set<String> = try {
        ((ref()?.get()?.await()?.get("visitedSpots") as? List<*>) ?: emptyList<Any?>()).mapNotNull { elementName(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    /**
     * 切換「去過了」：沒有就新增、有就移除（移除時一併刪掉網頁旅記上傳的照片，與網頁相同）。
     * @return true＝現在是去過；false＝已取消；null＝失敗（未登入或寫入被拒）
     */
    suspend fun toggle(
        name: String,
        emoji: String,
        region: String,
        tripId: String,
        tripTitle: String,
        gpsVerified: Boolean?
    ): Boolean? {
        val r = ref() ?: return null
        val key = nameKey(name)
        return try {
            var removedPhotoPaths = emptyList<String>()
            val added = db.runTransaction { tx ->
                // 原始陣列整份保留（含非 map 的舊格式元素），只動命中的那一筆
                val list = ((tx.get(r).get("visitedSpots") as? List<*>) ?: emptyList<Any?>()).toMutableList()
                val idx = list.indexOfFirst { nameKey(elementName(it)) == key }
                if (idx >= 0) {
                    val removed = list.removeAt(idx) as? Map<*, *>
                    removedPhotoPaths = (removed?.get("photos") as? List<*>)
                        ?.mapNotNull { (it as? Map<*, *>)?.get("path") as? String }.orEmpty()
                } else {
                    list.add(mapOf(
                        "name" to name,
                        "region" to region,
                        "visitDate" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        "tripId" to tripId,
                        "tripTitle" to tripTitle,
                        "emoji" to emoji.ifBlank { "📍" },
                        "gpsVerified" to gpsVerified,
                        "photos" to emptyList<Any>(),
                        "note" to ""
                    ))
                }
                tx.set(r, mapOf("visitedSpots" to list), SetOptions.merge())
                idx < 0
            }.await()
            removedPhotoPaths.forEach { path ->
                runCatching { storage.reference.child(path).delete() }   // 失敗靜默，孤兒檔可容忍（同網頁）
            }
            added
        } catch (e: Exception) {
            null
        }
    }

    /** 陣列元素的名稱：網頁格式是 map，App 舊格式可能是純字串 */
    private fun elementName(e: Any?): String? = (e as? Map<*, *>)?.get("name") as? String ?: e as? String


    companion object {
        fun nameKey(name: String?): String = name.orEmpty().replace(Regex("\\s"), "").lowercase()
    }
}
