package com.example.travellink_ai.ui.poster

import android.util.Log
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.repository.TripPhotoRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 讀取某趟行程的回憶照片，供九宮格選片用。
 *   - 雲端行程 → 共同相簿 `micro_trips/{cloudTripId}/photos` ＋ 舊版 memories（兩端共用，含旅伴）
 *   - 純本地行程 → Room `trip_memories`（只有自己）
 */
@Singleton
class MemoryGridRepository @Inject constructor(
    private val photoRepo: TripPhotoRepository,
    private val database: AppDatabase
) {
    private val tag = "TravelLink_Poster"
    private val gson = Gson()

    /**
     * @param cloudTripId firestoreDocId；空字串＝純本地行程
     * @param roomTripId  Room 的 key（cloudTripId 或 local_{roomId}）
     */
    suspend fun loadPhotos(cloudTripId: String, roomTripId: String): List<PhotoRef> {
        val fromCloud = if (cloudTripId.isNotBlank()) loadFromCloud(cloudTripId) else emptyList()
        // 雲端讀到就用雲端（含旅伴）；否則退回本地快取（離線或純本地行程）
        return fromCloud.ifEmpty { loadFromRoom(roomTripId) }
    }

    private suspend fun loadFromCloud(cloudTripId: String): List<PhotoRef> =
        photoRepo.loadMerged(cloudTripId)
            .map { PhotoRef(photoId = it.photoId.ifBlank { it.url }, url = it.url) }
            .distinctBy { it.photoId }

    private suspend fun loadFromRoom(roomTripId: String): List<PhotoRef> = runCatching {
        val local = database.tripMemoryDao().getByTripId(roomTripId) ?: return emptyList()
        val type = object : TypeToken<Map<String, SpotMemory>>() {}.type
        val spots: Map<String, SpotMemory> = gson.fromJson(local.spotsJson, type) ?: emptyMap()
        spots.values.toPhotoRefs()
    }.onFailure { Log.w(tag, "本地照片讀取失敗（$roomTripId）", it) }
        .getOrDefault(emptyList())

    private fun Collection<SpotMemory>.toPhotoRefs(): List<PhotoRef> =
        flatMap { it.photos }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.photoId.ifBlank { it.url } }
            .map { PhotoRef(photoId = it.photoId.ifBlank { it.url }, url = it.url) }
}
