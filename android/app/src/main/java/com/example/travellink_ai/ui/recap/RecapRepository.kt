package com.example.travellink_ai.ui.recap

import android.content.Context
import android.util.Log
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.CostReference
import com.example.travellink_ai.ui.planning.estimateTripCost
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    @ApplicationContext private val context: Context,
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
            .filter(::isRecapStop)
            .map { s ->
                RecapStop(
                    stopId = s.stopId,
                    name = s.name,
                    lat = s.lat!!, lng = s.lng!!,
                    mode = mapMode(item.transportMode),
                    stayMin = s.duration.toInt(),
                    dayIndex = s.dayIndex,
                    arriveTime = hhmm(s.time)
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
     * 這趟的全部照片，用 stopIndex（在 [trip].stops 的位置），依「站 → 共同相簿順序」排。
     * 要送哪幾張由 [recapPhotosFor] 依節奏挑。只收網址（後端只抓 Firebase Storage），還沒上傳完的照片略過。
     * @param trip buildTrip 的結果（決定 stopIndex 與哪些站入列）
     */
    suspend fun buildPhotos(trip: RecapTrip, cloudTripId: String, roomTripId: String): List<RecapPhoto> {
        // 雲端：共同相簿（已依 manualOrder → 拍攝時間排好）；讀不到才退回本地快取
        val cloud = if (cloudTripId.isNotBlank()) photoRepo.loadMerged(cloudTripId) else emptyList()
        val byStopId: Map<String, List<Pair<String, String?>>> =
            if (cloud.isNotEmpty()) cloud.groupBy { it.stopId }.mapValues { (_, list) -> list.map { it.url to it.photoId.ifBlank { null } } }
            else loadRoomSpots(roomTripId).associate { s -> s.stopId to s.photos.map { it.url to null } }
        return trip.stops.flatMapIndexed { index, stop ->
            byStopId[stop.stopId].orEmpty().filter { (url, _) -> url.startsWith("http") }
                .map { (url, id) -> RecapPhoto(stopIndex = index, url = url, photoId = id) }
        }
    }

    /**
     * 攻略卡的每人花費：與行程頁費用卡同一套（[estimateTripCost]：真實門票／餐費優先，查不到用參考表）。
     * 預算等級只存在雲端行程文件，讀不到就用預設餐費。算不出來回 null（後端就不顯示）。
     */
    suspend fun costPerPerson(item: LocalItinerary, cloudTripId: String): Int? = runCatching {
        val budget = if (cloudTripId.isBlank()) "" else runCatching {
            db.collection("micro_trips").document(cloudTripId).get().await().getString("budget")
        }.getOrNull().orEmpty()
        val stops = item.stops
        if (stops.size < 2) return null
        val mins = item.transitTimesJson.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        val transit = List(stops.size - 1) { mins.getOrElse(it) { 0L } }
        val modes = List(stops.size - 1) { i -> segmentMode(stops[i], stops[i + 1], item.transportMode) }
        estimateTripCost(stops, transit, modes, budget, item.people, CostReference.get(context)).totalPerPerson.takeIf { it > 0 }
    }.onFailure { Log.w(tag, "每人花費估算失敗", it) }.getOrNull()

    /** 這段實際用的交通方式（與行程頁 segmentModeFor 相同：手動改走路、步行行程超過 1.5 公里改計程車） */
    private fun segmentMode(from: Stop, to: Stop, tripMode: String): String = when {
        from.walkNext -> "walking"
        tripMode == "walking" && from.lat != null && from.lng != null && to.lat != null && to.lng != null &&
            haversineKm(from.lat, from.lng, to.lat, to.lng) * 1000 > 1500 -> "taxi"
        else -> tripMode
    }

    /**
     * 取路線折線（跨端契約 §4）：優先讀 routeGeometry 快取（v2、complete、sig 相符）；沒命中就用本地
     * roadSegmentsEncoded 換算成回顧短片的站序；本機也沒有（沒開過地圖、重裝過）就現查 Directions。
     * 後兩種都寫回快取，讓下次兩端命中同一份。
     *
     * @return null 表示拿不到每一段的道路折線 → 後端畫直線。
     */
    suspend fun buildRoutePoints(
        trip: RecapTrip, item: LocalItinerary, cloudTripId: String
    ): List<List<List<Double>>>? {
        val expected = trip.stops.size - 1
        if (expected < 1) return null
        val sig = sigOf(trip)

        if (cloudTripId.isNotBlank()) {
            readRouteGeometry(cloudTripId, sig, expected)?.let {
                Log.d(tag, "🗺️ 回顧短片路線：用雲端快取（$expected 段）")
                return it
            }
        }
        val local = decodeRoadSegments(item.roadSegmentsEncoded)
            ?.let { recapSegmentsFrom(item.stops, it) }
            ?.takeIf { it.size == expected }
        val segments = local ?: fetchRoadSegments(trip, item)
        if (segments == null) {
            Log.w(tag, "🗺️ 回顧短片路線：有路段查不到道路，影片會畫直線")
            return null
        }
        if (cloudTripId.isNotBlank()) writeRouteGeometry(cloudTripId, sig, segments)
        Log.d(tag, "🗺️ 回顧短片路線：用${if (local != null) "本機折線" else "現查路線"}（$expected 段）")
        return segments
    }

    /**
     * 現查每段道路（與網頁 computeRecapRoutePoints 相同：每段一次 Directions、取 overview_polyline）。
     * 有任何一段沒查到就回 null：不寫快取，免得直線被兩端一直沿用。
     */
    private suspend fun fetchRoadSegments(trip: RecapTrip, item: LocalItinerary): List<List<List<Double>>>? {
        val key = BuildConfig.DIRECTIONS_API_KEY
        if (key.isBlank()) return null
        val byId = item.stops.associateBy { it.stopId }
        Log.d(tag, "🌐 [外部 API] 回顧短片現查路線：${trip.stops.size - 1} 段")
        val segs = withContext(Dispatchers.IO) {
            coroutineScope {
                trip.stops.zipWithNext().map { (a, b) ->
                    async {
                        val walk = byId[a.stopId]?.walkNext == true ||
                            (item.transportMode == "walking" && haversineKm(a, b) * 1000 <= 1500)
                        fetchRoadSegment(a, b, if (walk) "walking" else "driving", key)
                    }
                }.awaitAll()
            }
        }
        if (segs.any { it == null }) return null
        return segs.map { downsampleRoute(it!!, RECAP_ROUTE_MAX_POINTS) }
    }

    private fun fetchRoadSegment(a: RecapStop, b: RecapStop, mode: String, key: String): List<List<Double>>? = runCatching {
        val url = URL("https://maps.googleapis.com/maps/api/directions/json?origin=${a.lat},${a.lng}" +
            "&destination=${b.lat},${b.lng}&mode=$mode&key=${URLEncoder.encode(key, "UTF-8")}")
        val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        val json = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
        if (json.optString("status") != "OK") return null
        val enc = json.getJSONArray("routes").getJSONObject(0).getJSONObject("overview_polyline").getString("points")
        PolyUtil.decode(enc).map { listOf(it.latitude, it.longitude) }.takeIf { it.size >= 2 }
    }.onFailure { Log.w(tag, "回顧短片路線查詢失敗：${it.message}") }.getOrNull()

    // ── routeGeometry 讀寫（v2：Firestore 不收陣列裡的陣列，每段存 { points: [lat, lng, lat, lng, ...] }）──

    private suspend fun readRouteGeometry(
        cloudTripId: String, sig: String, expected: Int
    ): List<List<List<Double>>>? = runCatching {
        val snap = db.collection("micro_trips").document(cloudTripId).get().await()
        routeGeometryFromFirestore(snap.get("routeGeometry"), sig, expected)
    }.onFailure { Log.w(tag, "routeGeometry 讀取失敗", it) }.getOrNull()

    /** 寫回快取；沒寫權限（viewer／規則未部署）就吞掉，不擋生成。 */
    private suspend fun writeRouteGeometry(
        cloudTripId: String, sig: String, segments: List<List<List<Double>>>
    ) {
        runCatching {
            db.collection("micro_trips").document(cloudTripId).set(
                mapOf("routeGeometry" to routeGeometryToFirestore(sig, segments, System.currentTimeMillis())),
                SetOptions.merge()
            ).await()
        }.onFailure { Log.d(tag, "routeGeometry 寫回略過（可能無權限）：${it.message}") }
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

    /**
     * 每站的旅記備註（AI 寫字幕用）：stopId → 備註（最多 200 字）。雲端讀整個 memories 子集合，
     * 自己那份優先、旅伴的補空白的站（與網頁合併旅伴旅記的做法相同）；純本地行程讀 Room。
     */
    suspend fun loadNotes(trip: RecapTrip, cloudTripId: String, roomTripId: String): Map<String, String> {
        val spots: List<SpotMemory> = if (cloudTripId.isNotBlank()) runCatching {
            val uid = auth.currentUser?.uid
            val snaps = db.collection("micro_trips").document(cloudTripId).collection("memories").get().await()
            snaps.documents.sortedBy { if (it.id == uid) 0 else 1 }.flatMap { d ->
                d.data?.let { com.example.travellink_ai.data.model.TripMemory.fromFirestore(it) }
                    ?.spots?.map { (k, v) -> if (v.stopId.isBlank()) v.copy(stopId = k) else v }.orEmpty()
            }
        }.onFailure { Log.w(tag, "讀旅記備註失敗", it) }.getOrDefault(emptyList())
        else loadRoomSpots(roomTripId)
        val out = LinkedHashMap<String, String>()
        trip.stops.forEach { st ->
            spots.firstOrNull { (it.stopId == st.stopId || it.spotName == st.name) && it.note.isNotBlank() }
                ?.let { out[st.stopId] = it.note.trim().take(200) }
        }
        return out
    }

    // ── 照片來源（純本地行程：Room）─────────────────────────────

    private suspend fun loadRoomSpots(roomTripId: String): List<SpotMemory> = runCatching {
        val local = database.tripMemoryDao().getByTripId(roomTripId) ?: return emptyList()
        val type = TypeToken.getParameterized(Map::class.java, String::class.java, SpotMemory::class.java).type
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

    private fun haversineKm(a: RecapStop, b: RecapStop): Double = haversineKm(a.lat, a.lng, b.lat, b.lng)

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(s), sqrt(1 - s))
    }

    /** 站的時間（"09:30"、"9:30-10:00"…）取第一個時刻成 HH:MM；沒有就空字串 */
    private fun hhmm(time: String): String {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(time) ?: return ""
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else ""
    }
}

/** 要送後端的照片：第 1 版節奏每站第一張；Reels 每站多張（照後端上限挑，見 [capRecapPhotos]） */
fun recapPhotosFor(all: List<RecapPhoto>, templateId: String): List<RecapPhoto> =
    if (RecapTimelines.isAnchor(templateId)) capRecapPhotos(all) else all.distinctBy { it.stopIndex }

/** 後端收照片的上限（RECAP_IG_V2_ALIGNMENT.md 第 3 節）：總數 24、每站 10，重複網址只算一次 */
internal const val RECAP_MAX_PHOTOS = 24
internal const val RECAP_MAX_PHOTOS_PER_STOP = 10

/**
 * 照後端上限挑照片：重複網址只留第一張、每站最多 10 張；總數超過 24 時輪流取（先每站第一張、再每站第二張…），
 * 讓每站都盡量有照片。順序維持「站 → 相簿順序」。App 先挑好，後端就不會再刪，編輯器的時間軸才對得上影片。
 */
internal fun capRecapPhotos(photos: List<RecapPhoto>): List<RecapPhoto> {
    val seen = HashSet<String>()
    val byStop = photos.filter { seen.add(it.url) }.groupBy { it.stopIndex }
        .mapValues { it.value.take(RECAP_MAX_PHOTOS_PER_STOP) }.toSortedMap()
    if (byStop.values.sumOf { it.size } <= RECAP_MAX_PHOTOS) return byStop.values.flatten()
    val keep = byStop.mapValues { 0 }.toMutableMap()
    var left = RECAP_MAX_PHOTOS
    var rank = 0
    while (left > 0) {
        byStop.forEach { (stop, list) -> if (left > 0 && rank < list.size) { keep[stop] = rank + 1; left-- } }
        rank++
    }
    return byStop.flatMap { (stop, list) -> list.take(keep.getValue(stop)) }
}

// ── 路線折線（跨端契約 §4）──

/** 回顧短片放哪些站：非車站、有座標（buildTrip 與折線換算共用，兩邊站序才對得上） */
internal fun isRecapStop(s: Stop) = !s.isStation && s.lat != null && s.lng != null

/** 每段最多幾個點（與網頁 recapDownsample 相同） */
internal const val RECAP_ROUTE_MAX_POINTS = 120

/** 折線頭尾離站超過這個距離，就當作站被改過、折線是舊的 */
private const val RECAP_ROUTE_END_TOLERANCE_KM = 3.0

/** roadSegmentsEncoded（以 \n 分隔，與 item.stops 相鄰兩站一段）→ 每段 [[lat,lng],...]；舊的 `|` 格式不可靠，不解 */
internal fun decodeRoadSegments(encoded: String?): List<List<List<Double>>>? {
    if (encoded.isNullOrBlank() || !encoded.contains('\n')) return null
    // 不濾掉空字串：空的那段也佔一個位置，濾掉會讓後面每段都對錯站
    return runCatching {
        encoded.split('\n').map { seg -> PolyUtil.decode(seg).map { listOf(it.latitude, it.longitude) } }
    }.getOrNull()
}

/**
 * 整趟行程的折線換算成回顧短片的站序：車站、沒座標的站不在短片裡，跨過它們的幾段接成一段。
 * 段數對不上、有一段沒查到，或頭尾離站太遠（站後來被改過）就回 null，寧可畫直線也不畫錯路。
 */
internal fun recapSegmentsFrom(stops: List<Stop>, segments: List<List<List<Double>>>): List<List<List<Double>>>? {
    if (segments.size != stops.size - 1) return null
    val kept = stops.indices.filter { isRecapStop(stops[it]) }
    if (kept.size < 2) return null
    return kept.zipWithNext().map { (from, to) ->
        val merged = mutableListOf<List<Double>>()
        for (i in from until to) {
            val seg = segments[i]
            if (seg.size < 2) return null
            merged += if (merged.isNotEmpty() && merged.last() == seg.first()) seg.drop(1) else seg
        }
        val a = stops[from]; val b = stops[to]
        if (RecapTimelines.haversineKm(a.lat!!, a.lng!!, merged.first()[0], merged.first()[1]) > RECAP_ROUTE_END_TOLERANCE_KM ||
            RecapTimelines.haversineKm(b.lat!!, b.lng!!, merged.last()[0], merged.last()[1]) > RECAP_ROUTE_END_TOLERANCE_KM) return null
        downsampleRoute(merged, RECAP_ROUTE_MAX_POINTS)
    }
}

/** 均勻抽樣到最多 [max] 點，保留頭尾（與網頁 recapDownsample 相同，四捨五入取點） */
internal fun downsampleRoute(seg: List<List<Double>>, max: Int): List<List<Double>> {
    if (seg.size <= max) return seg
    val step = (seg.size - 1).toDouble() / (max - 1)
    return (0 until max).map { seg[Math.round(it * step).toInt().coerceIn(0, seg.size - 1)] }
}

/** 寫進 micro_trips.routeGeometry 的 v2 格式：每段攤平成 [lat, lng, lat, lng, ...] */
internal fun routeGeometryToFirestore(sig: String, segments: List<List<List<Double>>>, now: Long): Map<String, Any> = mapOf(
    "v" to 2,
    "sig" to sig,
    "complete" to true,
    "segments" to segments.map { seg -> mapOf("points" to seg.flatMap { listOf(it[0], it[1]) }) },
    "updatedAt" to now
)

/** 讀 routeGeometry：只採用 v2、complete、sig 相符、段數對、每段至少兩點的（與網頁 recapRouteFromFirestore 相同） */
internal fun routeGeometryFromFirestore(raw: Any?, sig: String, expected: Int): List<List<List<Double>>>? {
    val rg = raw as? Map<*, *> ?: return null
    if ((rg["v"] as? Number)?.toInt() != 2 || rg["complete"] != true || rg["sig"] != sig) return null
    val segs = rg["segments"] as? List<*> ?: return null
    if (segs.size != expected) return null
    val out = segs.map { seg ->
        val flat = ((seg as? Map<*, *>)?.get("points") as? List<*>).orEmpty().map { (it as? Number)?.toDouble() ?: Double.NaN }
        (0 until flat.size / 2).map { listOf(flat[it * 2], flat[it * 2 + 1]) }
    }
    return out.takeIf { segsOk -> segsOk.all { pts -> pts.size >= 2 && pts.all { p -> p.all { it.isFinite() } } } }
}
