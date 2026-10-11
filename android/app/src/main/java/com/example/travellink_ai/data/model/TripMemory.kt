package com.example.travellink_ai.data.model

import androidx.annotation.Keep
import com.google.firebase.firestore.IgnoreExtraProperties
import java.net.URLDecoder
import java.util.UUID

/**
 * W4 C6 旅遊回憶（★App 端先定 schema，2026-07-21，待網頁端對齊）。
 *
 * 照片綁在「景點」層（以 [Stop.stopId] 為 key），對齊網頁端 visitedSpots 每元素帶 photos 的結構。
 * 儲存位置：
 *   - 照片本體 → Firebase Storage `trip-photos/{uid}/{tripId}/{stopId}_{ts}.jpg`（JPEG，<5MB）
 *   - 中繼資料 → Firestore `micro_trips/{tripId}/memories/{uid}`（★2026-08-03 改為行程共享）
 *   - 離線快取 → Room `trip_memories`（spots 以 JSON 存，只快取自己那份）
 *
 * ★共享模型：一趟行程底下每位成員各一份文件（doc id = 該成員 uid）。同行的人**互相讀得到**、
 * 但只能寫自己那份，避免多人同時整理照片時互相覆蓋。舊路徑 `users/{uid}/memories/{tripId}`
 * 僅保留作為讀取來源，載入時會自動搬到新路徑（見 MemoryViewModel.migrateLegacyMemory）。
 *
 * tripId 取用規則：優先 [firestoreDocId]（如 `my_1783...`），純本地行程用 `local_{roomId}`，
 * 皆為合法的 Storage/Firestore path segment（不含斜線）。純本地行程沒有 micro_trips 文件，
 * 只寫 Room（無旅伴可共享）。
 */
/**
 * 一張回憶照片。
 *
 * ★2026-08-07：從裸 URL 字串改為帶不可變 [photoId] 的物件（旅程故事計畫 §3.2）。
 * 九宮格與回顧短片的 renderer 需要穩定引用某一張照片，而陣列索引、檔名與 URL
 * 都不可作長期 ID——URL 帶 token 會換、索引會因刪除而位移。
 *
 * 舊格式（`photos: List<String>`）由 [fromAny] 就地相容並補上 UUID。
 */
@Keep
@IgnoreExtraProperties
data class MemoryPhoto(
    val photoId: String = "",
    val url: String = "",          // Storage download URL（含 token，僅供顯示）
    val storagePath: String = "",  // Storage 物件路徑，刪除與後端取用走這個
    val addedAt: Long = 0L,
    val byUid: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "photoId" to photoId,
        "url" to url,
        "storagePath" to storagePath,
        "addedAt" to addedAt,
        "byUid" to byUid
    )

    companion object {
        /** 相容兩種格式：物件（新）與裸 URL 字串（舊，補 UUID 與解析路徑）。 */
        fun fromAny(value: Any?, ownerUid: String = ""): MemoryPhoto? = when (value) {
            is String -> value.takeIf { it.isNotBlank() }?.let {
                MemoryPhoto(
                    photoId = UUID.randomUUID().toString(),
                    url = it,
                    storagePath = storagePathFromUrl(it),
                    byUid = ownerUid
                )
            }
            is Map<*, *> -> {
                val url = value["url"] as? String ?: ""
                if (url.isBlank()) null else MemoryPhoto(
                    photoId = (value["photoId"] as? String)?.takeIf { it.isNotBlank() }
                        ?: UUID.randomUUID().toString(),
                    url = url,
                    storagePath = (value["storagePath"] as? String)?.takeIf { it.isNotBlank() }
                        ?: storagePathFromUrl(url),
                    addedAt = (value["addedAt"] as? Number)?.toLong() ?: 0L,
                    byUid = value["byUid"] as? String ?: ownerUid
                )
            }
            else -> null
        }

        /**
         * 從 Firebase download URL 反推 Storage 物件路徑。
         * 格式：`.../o/{urlEncodedPath}?alt=media&token=...`
         */
        fun storagePathFromUrl(url: String): String = runCatching {
            val encoded = url.substringAfter("/o/", "").substringBefore("?")
            if (encoded.isBlank()) "" else URLDecoder.decode(encoded, "UTF-8")
        }.getOrDefault("")
    }
}

@Keep
@IgnoreExtraProperties
data class SpotMemory(
    val stopId: String = "",
    val spotName: String = "",
    val note: String = "",
    val photos: List<MemoryPhoto> = emptyList(),
    val updatedAt: Long = 0L
) {
    val isEmpty: Boolean get() = note.isBlank() && photos.isEmpty()

    fun toMap(): Map<String, Any> = mapOf(
        "stopId" to stopId,
        "spotName" to spotName,
        "note" to note,
        // 寫成裸 URL 字串陣列（對齊網頁讀取與對齊文件的 `photos: string[]`）。
        // 網頁讀 memories 時把每個元素當 URL 字串檢查 https://，寫物件會被它整批跳過 →
        // App 照片在網頁看不到。App 端讀取用 MemoryPhoto.fromAny 兼容字串（storagePath
        // 從 url 反推、去重用 url），所以改寫字串不丟功能。
        "photos" to photos.map { it.url },
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, ownerUid: String = ""): SpotMemory = SpotMemory(
            stopId = map["stopId"] as? String ?: "",
            spotName = map["spotName"] as? String ?: "",
            note = map["note"] as? String ?: "",
            photos = (map["photos"] as? List<*>)?.mapNotNull { MemoryPhoto.fromAny(it, ownerUid) }
                ?: emptyList(),
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

/** [reconcileSpotKeys] 的結果：重新對應後的 spots，以及這次新解析出的 alias。 */
data class SpotKeyReconcileResult(
    val spots: Map<String, SpotMemory>,
    val newAliases: Map<String, String>
)

/**
 * 把已脫落的 spot key 接回目前的站（旅程故事計畫 §3.1 的搶救路徑）。
 *
 * 景點改名或換順序會讓 [deterministicStopId] 推導出不同的 id，而 `spots` 正是以
 * stopId 為 key → 該站的照片與短記會整段消失。對每個對不上的 key 依序嘗試：
 *   1. alias 表已記錄過的對應
 *   2. [SpotMemory.spotName] 與目前某站同名（換了順序但沒改名）
 *   3. 從 `web_{order}_` 反推的 order 對上目前某站（改了名但沒換順序）
 *
 * 都對不上就原封不動保留，**絕不刪除**——寧可留著孤兒等後續處理，也不能弄丟使用者的照片。
 * 目標站已有內容時採合併（照片以 photoId 去重、短記換行相接），不覆蓋。
 */
fun reconcileSpotKeys(
    spots: Map<String, SpotMemory>,
    stops: List<Stop>,
    knownAliases: Map<String, String> = emptyMap()
): SpotKeyReconcileResult {
    val validIds = stops.map { it.stopId }.toSet()
    val orphans = spots.filterKeys { it !in validIds }
    if (orphans.isEmpty()) return SpotKeyReconcileResult(spots, emptyMap())

    val byName = stops.associateBy { it.name }
    val byOrder = stops.associateBy { it.order }
    val resolved = spots.toMutableMap()
    val newAliases = mutableMapOf<String, String>()

    for ((orphanKey, orphan) in orphans) {
        val target = knownAliases[orphanKey]?.takeIf { it in validIds }
            ?: byName[orphan.spotName]?.stopId
            ?: orderFromDeterministicStopId(orphanKey)?.let { byOrder[it]?.stopId }
        if (target == null || target == orphanKey) continue

        val existing = resolved[target]
        resolved[target] = if (existing == null || existing.isEmpty) {
            orphan.copy(stopId = target)
        } else {
            existing.copy(
                note = listOf(existing.note, orphan.note)
                    .filter { it.isNotBlank() }.joinToString("\n").take(500),
                photos = (existing.photos + orphan.photos).distinctBy { it.photoId },
                updatedAt = maxOf(existing.updatedAt, orphan.updatedAt)
            )
        }
        resolved.remove(orphanKey)
        newAliases[orphanKey] = target
    }
    return SpotKeyReconcileResult(resolved, newAliases)
}

@Keep
@IgnoreExtraProperties
data class TripMemory(
    val tripId: String = "",
    val tripTitle: String = "",
    val region: String = "",
    val coverUrl: String = "",            // 第一張照片，供歷史頁/回憶牆縮圖
    val updatedAt: Long = 0L,
    val spots: Map<String, SpotMemory> = emptyMap(),  // key = stopId
    /** 這份回憶屬於誰（doc id 同值）。共享模型下用來標示照片作者。 */
    val ownerUid: String = "",
    val ownerName: String = "",
    /**
     * 舊 spot key → 目前 stopId 的對照（旅程故事計畫 §3.1 的 alias map）。
     * 景點改名／換序會讓推導出來的 stopId 變動，靠這張表把已脫落的回憶接回去；
     * 一旦解析成功就記下來，之後不必再重算。
     */
    val spotAliases: Map<String, String> = emptyMap(),
    /**
     * 已搬進共同相簿 photos 的舊照片（storagePath，舊資料沒有就用 url）。
     * 這些照片以 photos 那份為準：photos 文件被刪了就是刪了，不能再從 memories 讀回來或重新搬。
     * 網頁不讀這個欄位，網頁那邊靠 Cloud Function 把 memories 的網址清掉。
     */
    val migratedPhotoKeys: List<String> = emptyList()
) {
    /** 是否有任何一站填了內容（決定歷史頁是否顯示「回憶」標記）。 */
    val hasContent: Boolean get() = spots.values.any { !it.isEmpty }

    val photoCount: Int get() = spots.values.sumOf { it.photos.size }

    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "tripId" to tripId,
        "tripTitle" to tripTitle,
        "region" to region,
        "coverUrl" to coverUrl,
        "updatedAt" to updatedAt,
        "spots" to spots.mapValues { it.value.toMap() },
        "ownerUid" to ownerUid,
        "ownerName" to ownerName,
        "spotAliases" to spotAliases,
        "migratedPhotoKeys" to migratedPhotoKeys
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromFirestore(map: Map<String, Any?>): TripMemory {
            val owner = map["ownerUid"] as? String ?: ""
            val rawSpots = map["spots"] as? Map<String, Any?> ?: emptyMap()
            val spots = rawSpots.mapNotNull { (k, v) ->
                (v as? Map<String, Any?>)?.let { k to SpotMemory.fromMap(it, owner) }
            }.toMap()
            val aliases = (map["spotAliases"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val value = v as? String ?: return@mapNotNull null
                    key to value
                }?.toMap() ?: emptyMap()
            return TripMemory(
                tripId = map["tripId"] as? String ?: "",
                tripTitle = map["tripTitle"] as? String ?: "",
                region = map["region"] as? String ?: "",
                coverUrl = map["coverUrl"] as? String ?: "",
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
                spots = spots,
                ownerUid = owner,
                ownerName = map["ownerName"] as? String ?: "",
                spotAliases = aliases,
                migratedPhotoKeys = (map["migratedPhotoKeys"] as? List<*>)
                    ?.filterIsInstance<String>().orEmpty()
            )
        }
    }
}
