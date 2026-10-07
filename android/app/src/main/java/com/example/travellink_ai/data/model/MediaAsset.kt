package com.example.travellink_ai.data.model

import androidx.annotation.Keep

/**
 * 九宮格與回顧短片的素材引用（旅程故事計畫 §3.2）。
 *
 * Web 與 Android 用同一種引用格式，renderer 才能吃兩端產生的 timeline：
 * ```
 * { kind: "memory_photo", stopId, photoId }
 * { kind: "media", mediaId }
 * ```
 *
 * 刻意**只存 ID 不存 URL**：download URL 帶 token 會換、陣列索引會因刪除而位移，
 * 兩者都不能當長期引用。實際的 URL 由 renderer 依 ID 當場解析。
 *
 * 目前只有 [MemoryPhotoRef] 有資料來源（回憶照片，已上線）；
 * [MediaRef] 對應 `micro_trips/{tripId}/media/{mediaId}`，等 Phase 2 影片上傳才會有內容。
 */
@Keep
sealed class MediaAsset {

    /** 回憶照片：綁在某一站底下的某一張。 */
    data class MemoryPhotoRef(val stopId: String, val photoId: String) : MediaAsset()

    /** 使用者上傳的影片（Phase 2）。 */
    data class MediaRef(val mediaId: String) : MediaAsset()

    fun toMap(): Map<String, Any> = when (this) {
        is MemoryPhotoRef -> mapOf("kind" to KIND_MEMORY_PHOTO, "stopId" to stopId, "photoId" to photoId)
        is MediaRef       -> mapOf("kind" to KIND_MEDIA, "mediaId" to mediaId)
    }

    companion object {
        const val KIND_MEMORY_PHOTO = "memory_photo"
        const val KIND_MEDIA = "media"

        fun fromMap(map: Map<*, *>?): MediaAsset? {
            if (map == null) return null
            return when (map["kind"] as? String) {
                KIND_MEMORY_PHOTO -> {
                    val stopId = map["stopId"] as? String ?: return null
                    val photoId = map["photoId"] as? String ?: return null
                    if (stopId.isBlank() || photoId.isBlank()) null
                    else MemoryPhotoRef(stopId, photoId)
                }
                KIND_MEDIA -> (map["mediaId"] as? String)
                    ?.takeIf { it.isNotBlank() }?.let { MediaRef(it) }
                else -> null
            }
        }

        /** 把一趟行程的回憶攤平成可輸出的素材清單，依站點順序排列。 */
        fun fromTripMemory(memory: TripMemory, orderedStopIds: List<String>): List<MemoryPhotoRef> =
            orderedStopIds.flatMap { stopId ->
                memory.spots[stopId]?.photos.orEmpty().map { MemoryPhotoRef(stopId, it.photoId) }
            }
    }
}

/** 由引用解析回實際照片；找不到回 null（素材可能已被刪除）。 */
fun TripMemory.resolve(ref: MediaAsset.MemoryPhotoRef): MemoryPhoto? =
    spots[ref.stopId]?.photos?.firstOrNull { it.photoId == ref.photoId }
