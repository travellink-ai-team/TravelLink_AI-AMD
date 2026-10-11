package com.example.travellink_ai.ui.recap

import android.util.Log
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.repository.MyNameProvider
import com.example.travellink_ai.data.repository.TripPhotoRepository
import com.example.travellink_ai.ui.map.PolyUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 組出「回顧短片」要送後端的資料（trip / photos / routePoints）。
 * 全部從 App 既有資料組，欄位對齊組員交接契約。
 *
 * ★ routePoints 走 routeGeometry 兩端共用快取（契約 §3，決定兩端影片一不一樣）：
 *   讀 micro_trips/{tripId}.routeGeometry，sig 相符就用；沒命中就用本地折線現算並寫回。
 */
@Singleton
class RecapRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val database: AppDatabase,
    private val photoRepo: TripPhotoRepository,
    private val names: MyNameProvider
) {
    private val tag = "TravelLink_Recap"
    private val gson = Gson()

    /** 站點簽章：行程一改（增刪站、改順序）就變，用來判斷雲端影片要不要重生。 */
    fun sigOf(trip: RecapTrip): String = trip.stops.joinToString("|") { it.stopId }

    /** 組行程：非車站、有座標的景點依序，含總距離。 */
    fun buildTrip(item: LocalItinerary): RecapTrip {
        val stops = item.stops
            .filter { !it.isStation && it.lat != null && it.lng != null }
            .map { s ->
                RecapStop(
                    stopId = s.stopId,
                    name = s.name,
                    lat = s.lat!!, lng = s.lng!!,
                    mode = mapMode(item.transportMode),
                    stayMin = s.duration.toInt(),
                    dayIndex = s.dayIndex
                )
            }
        var distanceKm = 0.0
        for (i in 1 until stops.size) distanceKm += haversineKm(stops[i - 1], stops[i])
        return RecapTrip(
            title = item.title.ifBlank { item.aiTitle }.ifBlank { "旅程回顧" },
            region = item.region,
            dateLabel = item.days,      // 日期範圍字串（契約 dateLabel 為顯示用）
            people = item.people,
            distanceKm = distanceKm,
            transportMode = mapMode(item.transportMode),
            stops = stops
        )
    }

    /**
     * 到站要插入的照片：**每站最多一張，用 stopIndex（在 [trip].stops 的位置）**。
     * @param trip buildTrip 的結果（決定 stopIndex 與哪些站入列）
     */
    suspend fun buildPhotos(trip: RecapTrip, cloudTripId: String, roomTripId: String): List<RecapPhoto> {
        // 雲端：共同相簿（已依 manualOrder → 拍攝時間排好）取每站第一張；讀不到才退回本地快取
        val cloud = if (cloudTripId.isNotBlank()) photoRepo.loadMerged(cloudTripId) else emptyList()
        val firstUrlByStopId: Map<String, String> =
            if (cloud.isNotEmpty()) cloud.groupBy { it.stopId }.mapValues { it.value.first().url }
            else loadRoomSpots(roomTripId)
                .mapNotNull { s -> s.photos.firstOrNull { it.url.isNotBlank() }?.let { s.stopId to it.url } }
                .toMap()
        return trip.stops.mapIndexedNotNull { index, stop ->
            firstUrlByStopId[stop.stopId]?.let { RecapPhoto(stopIndex = index, url = it) }
        }
    }

    /**
     * 取路線折線（契約 §3）：優先讀 routeGeometry 快取（sig 相符）；沒命中就用本地
     * roadSegmentsEncoded 現算並寫回快取，讓下次兩端命中同一份。
     *
     * @return null 表示拿不到有效折線（段數不符等）→ 讓後端自行以座標算。
     */
    suspend fun buildRoutePoints(
        trip: RecapTrip, item: LocalItinerary, cloudTripId: String
    ): List<List<List<Double>>>? {
        val expected = trip.stops.size - 1
        if (expected < 1) return null
        val sig = sigOf(trip)

        if (cloudTripId.isNotBlank()) {
            readRouteGeometry(cloudTripId, sig, expected)?.let { return it }
        }
        // 沒命中 → 本地折線現算
        val segments = decodeLocalSegments(item)?.takeIf { it.size == expected } ?: return null
        if (cloudTripId.isNotBlank()) writeRouteGeometry(cloudTripId, sig, segments)
        return segments
    }

    // ── routeGeometry 讀寫 ──────────────────────────────────────

    private suspend fun readRouteGeometry(
        cloudTripId: String, sig: String, expected: Int
    ): List<List<List<Double>>>? = runCatching {
        val snap = db.collection("micro_trips").document(cloudTripId).get().await()
        @Suppress("UNCHECKED_CAST")
        val rg = snap.get("routeGeometry") as? Map<String, Any?> ?: return null
        if (rg["sig"] != sig) return null
        val segs = rg["segments"] as? List<*> ?: return null
        if (segs.size != expected) return null
        segs.map { seg ->
            (seg as List<*>).map { pt ->
                (pt as List<*>).map { (it as Number).toDouble() }
            }
        }
    }.onFailure { Log.w(tag, "routeGeometry 讀取失敗", it) }.getOrNull()

    /** 寫回快取；沒寫權限（viewer／規則未部署）就吞掉，不擋生成（契約 §3）。 */
    private suspend fun writeRouteGeometry(
        cloudTripId: String, sig: String, segments: List<List<List<Double>>>
    ) {
        runCatching {
            db.collection("micro_trips").document(cloudTripId).set(
                mapOf("routeGeometry" to mapOf(
                    "sig" to sig,
                    "segments" to segments,
                    "updatedAt" to System.currentTimeMillis()
                )),
                SetOptions.merge()
            ).await()
        }.onFailure { Log.d(tag, "routeGeometry 寫回略過（可能無權限）：${it.message}") }
    }

    /** roadSegmentsEncoded → 每段 [[lat,lng],...]，每段 downsample 到 ≤120 點（契約 §3）。 */
    private fun decodeLocalSegments(item: LocalItinerary): List<List<List<Double>>>? {
        val enc = item.roadSegmentsEncoded ?: return null
        if (!enc.contains('\n')) return null   // 舊 `|` 格式不可靠
        return runCatching {
            enc.split('\n').filter { it.isNotBlank() }.map { seg ->
                downsample(PolyUtil.decode(seg).map { listOf(it.latitude, it.longitude) }, 120)
            }
        }.getOrNull()
    }

    /** 均勻抽樣到最多 [max] 點，保留頭尾。 */
    private fun downsample(seg: List<List<Double>>, max: Int): List<List<Double>> {
        if (seg.size <= max) return seg
        val step = (seg.size - 1).toDouble() / (max - 1)
        return (0 until max).map { seg[(it * step).toInt().coerceIn(0, seg.size - 1)] }
    }

    // ── 雲端短片（Storage + Firestore recaps，兩端共用）──────────

    /**
     * 上傳 mp4 到雲端，寫 metadata。對齊網頁 uploadRecapToCloud。
     * @return videoUrl；失敗回 null（不擋流程，影片仍已存本機相簿）
     */
    suspend fun uploadRecap(cloudTripId: String, mp4: ByteArray, sig: String, title: String): String? {
        val uid = auth.currentUser?.uid ?: return null
        if (cloudTripId.isBlank()) return null
        return runCatching {
            val path = "recap-videos/$uid/$cloudTripId/recap.mp4"
            val ref = storage.reference.child(path)
            ref.putBytes(mp4, StorageMetadata.Builder().setContentType("video/mp4").build()).await()
            val url = ref.downloadUrl.await().toString()
            db.collection("micro_trips").document(cloudTripId)
                .collection("recaps").document(uid).set(
                    mapOf(
                        "tripId" to cloudTripId,
                        "ownerUid" to uid,
                        "ownerName" to names.myName(),
                        "videoUrl" to url,
                        "storagePath" to path,
                        "sig" to sig,
                        "title" to title,
                        "filename" to "recap.mp4",
                        "bytes" to mp4.size.toLong(),
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            url
        }.onFailure { Log.w(tag, "上傳雲端短片失敗", it) }.getOrNull()
    }

    /** 讀雲端已存的短片：自己那份優先，否則最新一支（旅伴/他機）。對齊網頁 readCloudRecap。 */
    suspend fun readCloudRecap(cloudTripId: String): CloudRecap? {
        if (cloudTripId.isBlank()) return null
        val uid = auth.currentUser?.uid
        return runCatching {
            val snap = db.collection("micro_trips").document(cloudTripId)
                .collection("recaps").get().await()
            var mine: CloudRecap? = null
            var newest: CloudRecap? = null
            snap.documents.forEach { d ->
                val url = d.getString("videoUrl")?.takeIf { it.isNotBlank() } ?: return@forEach
                val rec = CloudRecap(
                    videoUrl = url,
                    sig = d.getString("sig") ?: "",
                    title = d.getString("title") ?: "",
                    ownerName = d.getString("ownerName") ?: "",
                    updatedAt = d.getLong("updatedAt") ?: 0L
                )
                if (d.id == uid) mine = rec
                if (newest == null || rec.updatedAt > newest!!.updatedAt) newest = rec
            }
            mine ?: newest
        }.onFailure { Log.w(tag, "讀取雲端短片失敗", it) }.getOrNull()
    }

    // ── 照片來源（純本地行程：Room）─────────────────────────────

    private suspend fun loadRoomSpots(roomTripId: String): List<SpotMemory> = runCatching {
        val local = database.tripMemoryDao().getByTripId(roomTripId) ?: return emptyList()
        val type = object : TypeToken<Map<String, SpotMemory>>() {}.type
        val map: Map<String, SpotMemory> = gson.fromJson(local.spotsJson, type) ?: emptyMap()
        map.values.toList()
    }.onFailure { Log.w(tag, "本地照片讀取失敗（$roomTripId）", it) }.getOrDefault(emptyList())

    // ── 工具 ────────────────────────────────────────────────────

    /** App transportMode → 契約枚舉。taxi 直接用；walking→walk；driving→car。 */
    private fun mapMode(transportMode: String): String = when (transportMode) {
        "walking" -> "walk"
        "driving" -> "car"
        else -> transportMode   // taxi（契約枚舉含 taxi，直接用）
    }

    private fun haversineKm(a: RecapStop, b: RecapStop): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(s), sqrt(1 - s))
    }
}
