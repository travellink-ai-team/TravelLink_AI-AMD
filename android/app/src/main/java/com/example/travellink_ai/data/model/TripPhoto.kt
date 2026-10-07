package com.example.travellink_ai.data.model

import com.example.travellink_ai.data.util.IdentityKeys
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 共同相簿的一張照片：`micro_trips/{tripId}/photos/{photoId}`（與網頁同一份）。
 *
 * ★ Rules 用 hasOnly＋hasAll 鎖死欄位，多寫一個欄位整筆會被拒——[toFirestoreMap] 只能輸出
 *   白名單內的 18 欄（見 firestore.rules 的 validPhotoShape）。
 * ★ 身分一律看 [ownerUid]；[ownerName] 只是顯示標籤，撞名交給 [ownerLabels] 處理。
 * ★ Storage 檔名 `trip-photos/{uid}/{tripId}/{photoId}.jpg`；景點歸屬只看 [stopId] 欄位，
 *   不從檔名反推（照片可被重新分類）。
 *
 * [legacy]＝來自舊版 `memories/{uid}.spots.*.photos` 的照片（雙讀相容用），只在記憶體裡，
 * 不會被寫進 photos。
 */
data class TripPhoto(
    val photoId: String,
    val tripId: String,
    val stopId: String,
    val stopName: String,
    val dayKey: String,
    val capturedAt: Long,
    val manualOrder: Long? = null,
    val ownerUid: String,
    val ownerName: String,
    val url: String,
    val storagePath: String,
    val status: String = STATUS_SYNCED,
    val capturedTimezone: String = "",
    val mimeType: String = MIME_JPEG,
    val hash: String = "",
    val createdAt: Long = 0L,
    val uploadedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val legacy: Boolean = false
) {
    /** 跨端去重用的 key：storagePath 優先，舊資料沒有就用 url。 */
    val dedupKey: String get() = storagePath.ifBlank { url }

    fun toFirestoreMap(): Map<String, Any?> = buildMap {
        put("photoId", photoId)
        put("tripId", tripId)
        put("stopId", stopId)          // 未分類寫空字串，不可省略
        put("stopName", stopName)
        put("dayKey", dayKey)
        put("capturedAt", capturedAt)
        put("manualOrder", manualOrder) // 沒有就寫 null，不可省略
        put("ownerUid", ownerUid)
        put("ownerName", ownerName)
        put("url", url)
        put("storagePath", storagePath)
        put("status", STATUS_SYNCED)   // 寫進 Firestore 的一律是 synced
        // 選填欄位：沒有值就不寫
        if (capturedTimezone.isNotBlank()) put("capturedTimezone", capturedTimezone)
        if (mimeType.isNotBlank()) put("mimeType", mimeType)
        if (hash.isNotBlank()) put("hash", hash)
        if (createdAt > 0) put("createdAt", createdAt)
        if (uploadedAt > 0) put("uploadedAt", uploadedAt)
        if (updatedAt > 0) put("updatedAt", updatedAt)
    }

    companion object {
        const val STATUS_SYNCED = "synced"
        const val MIME_JPEG = "image/jpeg"

        /** Rules validPhotoShape 的欄位白名單（測試用來確認沒有多寫）。 */
        val ALLOWED_FIELDS = setOf(
            "photoId", "tripId", "stopId", "stopName", "dayKey", "capturedAt",
            "manualOrder", "ownerUid", "ownerName", "url", "storagePath", "status",
            "capturedTimezone", "mimeType", "hash", "createdAt", "uploadedAt", "updatedAt"
        )
        val REQUIRED_FIELDS = setOf(
            "photoId", "tripId", "stopId", "stopName", "dayKey", "capturedAt",
            "manualOrder", "ownerUid", "ownerName", "url", "storagePath", "status"
        )

        fun fromFirestore(id: String, map: Map<String, Any?>): TripPhoto? {
            val url = map["url"] as? String
            if (url.isNullOrBlank()) return null
            return TripPhoto(
                photoId = (map["photoId"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                tripId = map["tripId"] as? String ?: "",
                stopId = map["stopId"] as? String ?: "",
                stopName = map["stopName"] as? String ?: "",
                dayKey = map["dayKey"] as? String ?: "",
                capturedAt = (map["capturedAt"] as? Number)?.toLong() ?: 0L,
                manualOrder = (map["manualOrder"] as? Number)?.toLong(),
                ownerUid = map["ownerUid"] as? String ?: "",
                ownerName = map["ownerName"] as? String ?: "",
                url = url,
                storagePath = map["storagePath"] as? String ?: "",
                status = map["status"] as? String ?: STATUS_SYNCED,
                capturedTimezone = map["capturedTimezone"] as? String ?: "",
                mimeType = map["mimeType"] as? String ?: "",
                hash = map["hash"] as? String ?: "",
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                uploadedAt = (map["uploadedAt"] as? Number)?.toLong() ?: 0L,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}

// ── 拍攝時間（對齊網頁：EXIF 0x9003 → 0x9004 → 0x0132，無時區資訊時以裝置時區解讀）──

private val EXIF_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * EXIF 日期字串 → epoch 毫秒。
 * 有 OffsetTimeOriginal（0x9011，如 `+08:00`）就照它；沒有才以 [zone]（裝置時區）解讀。
 * 格式不對或是相機沒設時間的 `0000:00:00 00:00:00` 回 null。
 */
fun parseExifDateTime(dateTime: String?, offset: String?, zone: ZoneId): Long? {
    val raw = dateTime?.trim()?.takeIf { it.length >= 19 }?.substring(0, 19) ?: return null
    val local = runCatching { LocalDateTime.parse(raw, EXIF_DATE_FORMAT) }.getOrNull() ?: return null
    val zoneOffset = offset?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
    return if (zoneOffset != null) OffsetDateTime.of(local, zoneOffset).toInstant().toEpochMilli()
    else local.atZone(zone).toInstant().toEpochMilli()
}

/** capturedAt 在 [zone] 的日期 → `YYYY-MM-DD`。 */
fun dayKeyOf(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(DAY_KEY_FORMAT)

/** 舊版 App 檔名 `{stopId}_{timestamp}.jpg` 內的上傳時間；網頁舊檔名 `{timestamp}.jpg` 也適用。 */
fun timestampFromStoragePath(path: String): Long? =
    Regex("""(?:^|[_/])(\d{13})\.jpg$""").find(path)?.groupValues?.get(1)?.toLongOrNull()

// ── 顯示標籤：只有真的撞名才加 uid 尾四碼（對齊網頁 a92fb4b）──

/** ownerUid → 顯示名稱。不同 uid 用了同一個名字時加 ` #尾四碼`，沒撞到的維持原名。 */
fun ownerLabels(photos: List<TripPhoto>): Map<String, String> {
    val nameByUid = photos.filter { it.ownerUid.isNotBlank() }
        .groupBy { it.ownerUid }
        .mapValues { (uid, list) ->
            list.firstNotNullOfOrNull { it.ownerName.takeIf { n -> n.isNotBlank() } } ?: "旅伴 ${uid.take(4)}"
        }
    val collided = nameByUid.entries.groupBy({ it.value }, { it.key })
        .filterValues { it.size > 1 }.keys
    return nameByUid.mapValues { (uid, name) ->
        if (name in collided) "$name #${uid.takeLast(4)}" else name
    }
}

// ── 新舊雙讀合併 ──

/**
 * 舊版 memories 的照片 → [TripPhoto]（legacy=true）。
 * 舊資料沒有拍攝時間，退回檔名裡的上傳時間，再退回加入時間。
 * 已搬進 photos 的（[TripMemory.migratedPhotoKeys]）不算：以 photos 那份為準，被刪了就不再出現。
 */
fun TripMemory.legacyPhotos(tripId: String, zone: ZoneId): List<TripPhoto> {
    val migrated = migratedPhotoKeys.toSet()
    return spots.flatMap { (stopId, spot) ->
        spot.photos.filter { it.url.isNotBlank() && it.storagePath.ifBlank { it.url } !in migrated }.map { p ->
            val at = timestampFromStoragePath(p.storagePath) ?: p.addedAt.takeIf { it > 0 } ?: updatedAt
            TripPhoto(
                photoId = p.photoId,
                tripId = tripId,
                stopId = stopId,
                stopName = spot.spotName,
                dayKey = if (at > 0) dayKeyOf(at, zone) else "",
                capturedAt = at,
                ownerUid = ownerUid,
                ownerName = ownerName,
                url = p.url,
                storagePath = p.storagePath,
                createdAt = p.addedAt,
                legacy = true
            )
        }
    }
}

/**
 * 新版 photos 為主，舊版只補「新版沒有的」（storagePath 或 url 任一撞到就視為同一張）。
 * 依 manualOrder → capturedAt 排序。
 */
fun mergeTripPhotos(photos: List<TripPhoto>, legacy: List<TripPhoto>): List<TripPhoto> {
    val seen = HashSet<String>()
    photos.forEach { p -> seen += p.url; if (p.storagePath.isNotBlank()) seen += p.storagePath }
    val extra = legacy
        .filter { it.url !in seen && (it.storagePath.isBlank() || it.storagePath !in seen) }
        .distinctBy { it.dedupKey }
    return (photos + extra).sortedWith(
        compareBy<TripPhoto>({ it.manualOrder ?: Long.MAX_VALUE }, { it.capturedAt })
    )
}

// ── 權限（對齊 firestore.rules 的 isOwner／isEditor）──

/**
 * 是否為行程 owner 或 editor：ownerUid／ownerEmail／userEmail 為本人，
 * 或 editorEmails 含本人，或 members 裡 role=editor 的舊格式成員。
 */
fun canEditTripDoc(data: Map<String, Any?>, uid: String, email: String): Boolean {
    val isOwner = (uid.isNotBlank() && data["ownerUid"] == uid) ||
        (email.isNotBlank() && (
            (data["ownerEmail"] as? String).equals(email, ignoreCase = true) ||
            (data["userEmail"] as? String).equals(email, ignoreCase = true)))
    if (isOwner) return true
    if (email.isBlank()) return false
    val inEditors = (data["editorEmails"] as? List<*>)
        ?.any { (it as? String).equals(email, ignoreCase = true) } == true
    val legacyEditor = ((data["members"] as? Map<*, *>)
        ?.get(IdentityKeys.legacyMemberKey(email)) as? Map<*, *>)?.get("role") == "editor"
    return inEditors || legacyEditor
}
