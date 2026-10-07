package com.example.travellink_ai.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.TripMemory
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.model.canEditTripDoc
import com.example.travellink_ai.data.model.dayKeyOf
import com.example.travellink_ai.data.model.legacyPhotos
import com.example.travellink_ai.data.model.mergeTripPhotos
import com.example.travellink_ai.data.model.parseExifDateTime
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 共同相簿（`micro_trips/{tripId}/photos`）的讀寫，與網頁同一份資料。
 *
 * 新照片只寫 photos，不再寫進 `memories.spots.photos`（兩邊都寫會讓網頁雙讀時出現兩次）。
 * 舊版 memories 的照片仍一起讀進來（[loadMerged]），以 storagePath／url 去重。
 *
 * 刪除權限採方案 B：自己的照片、或行程 owner/editor 可刪他人的照片。
 * 刪別人的照片只刪得掉 Firestore 文件（Storage 規則只准本人刪檔），
 * 殘留的檔案由 Cloud Function `cleanup_deleted_trip_photo` 清掉。
 */
@Singleton
class TripPhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth,
    private val names: MyNameProvider
) {
    private val tag = "TravelLink_Photos"

    private fun photosRef(tripId: String) =
        db.collection("micro_trips").document(tripId).collection("photos")

    /** 即時監聽整本相簿；讀不到（非成員、離線無快取）送空清單，不中斷。 */
    fun listen(tripId: String): Flow<List<TripPhoto>> = callbackFlow {
        val reg = photosRef(tripId).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.w(tag, "相簿監聽失敗（$tripId）：${e.message}")
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().mapNotNull { d ->
                d.data?.let { TripPhoto.fromFirestore(d.id, it) }
            })
        }
        awaitClose { reg.remove() }
    }

    suspend fun fetch(tripId: String): List<TripPhoto> = runCatching {
        photosRef(tripId).get().await().documents.mapNotNull { d ->
            d.data?.let { TripPhoto.fromFirestore(d.id, it) }
        }
    }.onFailure { Log.w(tag, "相簿讀取失敗（$tripId）", it) }.getOrDefault(emptyList())

    /** 新版 photos ＋ 舊版 memories（所有成員）合併後的完整相簿，給九宮格／短片用。 */
    suspend fun loadMerged(tripId: String): List<TripPhoto> {
        auth.currentUser ?: return emptyList()
        val photos = fetch(tripId)
        val legacy = runCatching {
            db.collection("micro_trips").document(tripId).collection("memories").get().await()
                .documents.mapNotNull { d ->
                    d.data?.let { TripMemory.fromFirestore(it).copy(ownerUid = d.id) }
                }
                .flatMap { it.legacyPhotos(tripId, ZoneId.systemDefault()) }
        }.onFailure { Log.w(tag, "舊版回憶照片讀取失敗（$tripId）", it) }.getOrDefault(emptyList())
        return mergeTripPhotos(photos, legacy)
    }

    /** 我是不是這趟的 owner/editor（可管理別人的照片）。讀不到就當不是，交給 Rules 把關。 */
    suspend fun canManageOthers(tripId: String): Boolean {
        val user = auth.currentUser ?: return false
        return runCatching {
            val data = db.collection("micro_trips").document(tripId).get().await().data
                ?: return false
            canEditTripDoc(data, user.uid, user.email.orEmpty())
        }.getOrDefault(false)
    }

    /**
     * 上傳一張照片到某一站：讀原檔（算 hash、讀 EXIF 時間）→ 轉正壓縮 →
     * Storage `trip-photos/{uid}/{tripId}/{photoId}.jpg` → 寫 photos 文件。
     */
    suspend fun upload(tripId: String, stop: Stop, uri: Uri): TripPhoto {
        val uid = auth.currentUser?.uid ?: error("未登入")
        val zone = ZoneId.systemDefault()
        val original = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: error("圖片讀取失敗")
        // 與網頁同格式 `sha256:<小寫 hex>`，對壓縮前的原始檔算
        val hash = "sha256:" + sha256Hex(original)
        val capturedAt = withContext(Dispatchers.IO) { readCapturedAt(original, uri, zone) }
        val jpeg = withContext(Dispatchers.Default) { compressToJpeg(original) } ?: error("圖片解碼失敗")

        val photoId = UUID.randomUUID().toString()
        val path = "trip-photos/$uid/$tripId/$photoId.jpg"
        val ref = storage.reference.child(path)
        ref.putBytes(jpeg, jpegStorageMetadata()).await()
        val url = ref.downloadUrl.await().toString()

        val now = System.currentTimeMillis()
        val photo = TripPhoto(
            photoId = photoId,
            tripId = tripId,
            stopId = stop.stopId,
            stopName = stop.name,
            dayKey = dayKeyOf(capturedAt, zone),
            capturedAt = capturedAt,
            ownerUid = uid,
            ownerName = names.myName(),
            url = url,
            storagePath = path,
            capturedTimezone = zone.id,
            hash = hash,
            createdAt = now,
            uploadedAt = now,
            updatedAt = now
        )
        try {
            photosRef(tripId).document(photoId).set(photo.toFirestoreMap()).await()
        } catch (e: Exception) {
            // 文件寫不進去就把剛上傳的檔案收掉，免得留下沒人引用的孤兒檔
            runCatching { ref.delete().await() }
            throw e
        }
        return photo
    }

    /**
     * 刪除一張照片。先刪文件（Rules 判斷權限）；自己的照片順便刪 Storage 檔案，
     * 別人的檔案交給 Cloud Function 清。
     */
    suspend fun delete(photo: TripPhoto) {
        val uid = auth.currentUser?.uid ?: error("未登入")
        photosRef(photo.tripId).document(photo.photoId).delete().await()
        if (photo.ownerUid == uid && photo.storagePath.isNotBlank()) {
            runCatching { storage.reference.child(photo.storagePath).delete().await() }
                .onFailure {
                    // Function 可能已先刪掉 → 404 屬正常
                    if ((it as? StorageException)?.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND)
                        Log.w(tag, "照片檔案刪除失敗（${photo.storagePath}）", it)
                }
        }
    }

    /** 改到另一站（自己的照片，或 owner/editor 協助整理）。 */
    suspend fun moveToStop(photo: TripPhoto, stop: Stop) {
        photosRef(photo.tripId).document(photo.photoId).update(
            mapOf(
                "stopId" to stop.stopId,
                "stopName" to stop.name,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    /**
     * 把自己舊版 memories 的照片搬進 photos，每張只搬一次。
     * - `memories` 的照片陣列保留：還在用舊版 App 的旅伴只讀得到那邊
     * - 搬過的記在 [TripMemory.migratedPhotoKeys]：之後照片文件被刪了也不會再搬回來
     *   （不必等 Cloud Function 把 memories 的網址清掉）
     * - doc id 由 storagePath 推導，兩台裝置同時開也不會搬出兩份
     * - storagePath／url 原樣保留，網頁的 mergePhotos 才對得上
     * @return 新確認「已搬過」的 key（這次搬的＋先前已在 photos 但還沒記錄的），呼叫端要寫回 memories
     */
    suspend fun migrateLegacy(
        tripId: String,
        mine: TripMemory,
        existing: List<TripPhoto>,
        stopsById: Map<String, Stop>
    ): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val done = mine.migratedPhotoKeys.toSet()
        val legacy = mine.copy(ownerUid = uid).legacyPhotos(tripId, zone).filter { it.dedupKey !in done }
        if (legacy.isEmpty()) return emptyList()
        val pending = mergeTripPhotos(existing, legacy).filter { it.legacy }
        val pendingKeys = pending.map { it.dedupKey }.toSet()
        // 新版已經有的（以前搬過、但那時還沒記錄）直接補記
        val confirmed = legacy.map { it.dedupKey }.filter { it !in pendingKeys }.toMutableList()
        if (pending.isEmpty()) return confirmed.distinct()
        val name = names.myName()
        val now = System.currentTimeMillis()
        for (p in pending) {
            val photoId = "legacy_" + sha256Hex(p.dedupKey.toByteArray()).take(32)
            val doc = p.copy(
                photoId = photoId,
                stopName = stopsById[p.stopId]?.name ?: p.stopName,
                ownerUid = uid,
                ownerName = name,
                capturedTimezone = zone.id,
                createdAt = p.createdAt.takeIf { it > 0 } ?: p.capturedAt,
                uploadedAt = p.createdAt.takeIf { it > 0 } ?: p.capturedAt,
                updatedAt = now,
                legacy = false
            )
            runCatching { photosRef(tripId).document(photoId).set(doc.toFirestoreMap()).await() }
                .onSuccess { confirmed += p.dedupKey }
                .onFailure { Log.w(tag, "舊照片搬移失敗（${p.dedupKey}）", it) }
        }
        return confirmed.distinct()
    }

    // ── 本機處理 ─────────────────────────────────────────────

    /**
     * 拍攝時間：EXIF 0x9003 → 0x9004 → 0x0132（有 OffsetTimeOriginal 就照它，否則裝置時區），
     * 都沒有就退到媒體庫的拍攝／修改時間，再沒有就用現在。
     */
    private fun readCapturedAt(bytes: ByteArray, uri: Uri, zone: ZoneId): Long {
        runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val offset = exif.getAttribute("OffsetTimeOriginal") ?: exif.getAttribute("OffsetTime")
            listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME
            ).firstNotNullOfOrNull { tag -> parseExifDateTime(exif.getAttribute(tag), offset, zone) }
        }.getOrNull()?.let { return it }
        return queryLong(uri, MediaStore.MediaColumns.DATE_TAKEN)
            ?: queryLong(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: System.currentTimeMillis()
    }

    private fun queryLong(uri: Uri, column: String): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    /** 純本地行程（寫 Room，不進 photos）也走同一套壓縮。 */
    suspend fun compressForUpload(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(::compressToJpeg)
    }

    /** 解碼 → 依 EXIF 轉正 → 長邊 ≤1920 → JPEG 壓縮到 <5MB（Storage 規則上限）。 */
    private fun compressToJpeg(bytes: ByteArray): ByteArray? {
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotated = applyExifRotation(bytes, original)
        val scaled = downscale(rotated, 1920)
        var quality = 85
        var out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        while (out.size() > 4_800_000 && quality > 40) {
            quality -= 10
            out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return out.toByteArray()
    }

    private fun applyExifRotation(bytes: ByteArray, bmp: Bitmap): Bitmap = runCatching {
        val degrees = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bmp else {
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }.getOrDefault(bmp)

    private fun downscale(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width; val h = bmp.height
        val longSide = maxOf(w, h)
        if (longSide <= maxDim) return bmp
        val ratio = maxDim.toFloat() / longSide
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /** 純本地行程（沒有 micro_trips 文件）上傳時也要帶 contentType，規則才會放行。 */
        fun jpegStorageMetadata(): StorageMetadata =
            StorageMetadata.Builder().setContentType(TripPhoto.MIME_JPEG).build()
    }
}
