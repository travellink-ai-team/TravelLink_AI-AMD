package com.example.travellink_ai.ui.memory

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.local.LocalTripMemory
import com.example.travellink_ai.data.model.MemoryPhoto
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.TripMemory
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.model.legacyPhotos
import com.example.travellink_ai.data.model.mergeTripPhotos
import com.example.travellink_ai.data.model.reconcileSpotKeys
import com.example.travellink_ai.data.repository.MyNameProvider
import com.example.travellink_ai.data.repository.TripPhotoRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.time.ZoneId
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val database: AppDatabase,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val auth: FirebaseAuth,
    private val photoRepo: TripPhotoRepository,
    private val names: MyNameProvider
) : ViewModel() {

    private val tag = "TravelLink_Memory"

    // Room 的 spotsJson 同時存在兩代格式（photos 舊為 List<String>、新為 List<物件>），
    // Gson 反射模式吃不了這種型別變動，需自訂 deserializer 走 SpotMemory.fromMap 的相容路徑
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SpotMemory::class.java, SpotMemoryDeserializer())
        .create()

    private val dao get() = database.tripMemoryDao()

    /**
     * 自己那份回憶（唯一可編輯、會被寫回 Firestore/Room 的一份）。
     * 雲端行程的新照片不再放這裡（改寫 photos），這裡只剩短記與舊版照片。
     */
    private val _memory = MutableStateFlow(TripMemory())
    val memory: StateFlow<TripMemory> = _memory.asStateFlow()

    /** 同行旅伴的回憶（唯讀；短記與舊版照片）。 */
    private val _companions = MutableStateFlow<List<TripMemory>>(emptyList())
    val companions: StateFlow<List<TripMemory>> = _companions.asStateFlow()

    /** 共同相簿 `micro_trips/{tripId}/photos`（即時）。 */
    private val _cloudPhotos = MutableStateFlow<List<TripPhoto>>(emptyList())

    // 必須宣告在 photos 之前：photos 用 Eagerly stateIn，建構時就會同步執行一次 combine，
    // 讀到還沒初始化的欄位會是 null（isBlank NPE 閃退）
    private var currentTripId: String = ""

    /** 雲端文件 id（＝micro_trips docId）。純本地行程為空字串，代表只寫 Room。 */
    private var cloudTripId: String = ""

    /**
     * 畫面上的整本相簿：新版 photos ＋ 舊版 memories（自己與旅伴）裡新版還沒有的。
     * 以 storagePath／url 去重，與網頁 mergePhotos 同一套判斷。
     */
    val photos: StateFlow<List<TripPhoto>> =
        combine(_cloudPhotos, _memory, _companions) { cloud, mine, others ->
            val zone = ZoneId.systemDefault()
            val tripId = cloudTripId.ifBlank { currentTripId }
            val legacy = (listOf(mine) + others).flatMap { it.legacyPhotos(tripId, zone) }
            mergeTripPhotos(cloud, legacy)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 我是不是這趟的 owner/editor（方案 B：可刪除、重新分類別人的照片）。 */
    private val _canManageOthers = MutableStateFlow(false)
    val canManageOthers: StateFlow<Boolean> = _canManageOthers.asStateFlow()

    val myUid: String get() = auth.currentUser?.uid.orEmpty()

    /** 目前正在上傳照片的 stopId 集合（畫面顯示 loading）。 */
    private val _uploading = MutableStateFlow<Set<String>>(emptySet())
    val uploading: StateFlow<Set<String>> = _uploading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 目前這趟行程的排序（非車站站點），供畫面依序渲染
    private val _orderedStops = MutableStateFlow<List<Stop>>(emptyList())
    val orderedStops: StateFlow<List<Stop>> = _orderedStops.asStateFlow()

    private var photosJob: Job? = null

    /** tripId：優先 firestoreDocId，純本地行程用 local_{roomId}。皆為合法 path segment。 */
    fun tripIdOf(item: LocalItinerary): String =
        item.firestoreDocId?.takeIf { it.isNotBlank() } ?: "local_${item.id}"

    /** 自己這份回憶在 Firestore 的位置：micro_trips/{docId}/memories/{uid} */
    private fun myMemoryRef(uid: String) =
        db.collection("micro_trips").document(cloudTripId).collection("memories").document(uid)

    /**
     * 載入某趟行程的回憶。順序：先種下所有非車站站點的空殼 → 疊 Room 快取 →
     * 讀 micro_trips/{docId}/memories 整個子集合（自己那份疊上去，其餘存進 companions）→
     * 監聽共同相簿，並把自己舊版的照片搬進 photos。
     */
    fun load(item: LocalItinerary) {
        val tripId = tripIdOf(item)
        currentTripId = tripId
        cloudTripId = item.firestoreDocId?.takeIf { it.isNotBlank() } ?: ""
        val stops = item.stops.filter { !it.isStation }
        _orderedStops.value = stops
        val title = item.title.ifBlank { item.aiTitle }

        // 空殼：每個景點一個 SpotMemory，保留名稱
        val seed = stops.associate { it.stopId to SpotMemory(stopId = it.stopId, spotName = it.name) }
        _memory.value = TripMemory(
            tripId = tripId, tripTitle = title, region = item.region, spots = seed,
            ownerUid = myUid, ownerName = names.cachedOrFallback()
        )
        _companions.value = emptyList()
        _cloudPhotos.value = emptyList()
        _canManageOthers.value = false

        photosJob?.cancel()
        photosJob = null
        if (cloudTripId.isNotBlank() && auth.currentUser != null) {
            val listenId = cloudTripId
            photosJob = viewModelScope.launch {
                photoRepo.listen(listenId).collect { _cloudPhotos.value = it }
            }
            viewModelScope.launch { _canManageOthers.value = photoRepo.canManageOthers(listenId) }
        }

        viewModelScope.launch {
            // Room 快取（離線可用；只快取自己那份）
            runCatching {
                dao.getByTripId(tripId)?.let { local ->
                    mergeInto(parseSpots(local.spotsJson), title, item.region)
                }
            }.onFailure { Log.w(tag, "Room 讀取回憶失敗", it) }

            val uid = auth.currentUser?.uid ?: return@launch
            // 純本地行程沒有 micro_trips 文件可掛，維持 Room-only
            if (cloudTripId.isBlank()) return@launch

            // 一次取回整個子集合：自己那份 + 旅伴那幾份
            runCatching {
                val snaps = db.collection("micro_trips").document(cloudTripId)
                    .collection("memories").get().await()
                val mine = snaps.documents.firstOrNull { it.id == uid }
                _companions.value = snaps.documents
                    .filter { it.id != uid }
                    .mapNotNull { doc ->
                        doc.data?.let { data ->
                            val m = TripMemory.fromFirestore(data)
                            // 旅伴那份改不了，只在記憶體把脫落的 key 接回去供顯示
                            val fixedSpots =
                                reconcileSpotKeys(m.spots, stops, m.spotAliases).spots
                            val named =
                                // ownerName 舊文件可能沒寫，退回 doc id 前 4 碼避免空白
                                if (m.ownerName.isBlank()) m.copy(ownerName = "旅伴 ${doc.id.take(4)}")
                                else m
                            named.copy(ownerUid = doc.id, spots = fixedSpots)
                        }
                    }
                    .filter { it.hasContent }
                if (mine != null) {
                    val remote = TripMemory.fromFirestore(mine.data ?: emptyMap())
                    val newAliases = mergeInto(remote.spots, title, item.region, remote.spotAliases)
                    _memory.value = _memory.value.copy(migratedPhotoKeys = remote.migratedPhotoKeys)
                    // 有搶救到東西就立刻寫回，避免每次開啟都重算（key 換了，照片陣列要跟著搬）
                    if (newAliases.isNotEmpty()) persist(rewritePhotos = true)
                } else {
                    // 新路徑還沒有我的文件 → 看看舊路徑有沒有，有就搬過來
                    migrateLegacyMemory(uid, tripId, title, item.region)
                }
                Log.d(tag, "📸 回憶載入：自己=${mine != null}、旅伴 ${_companions.value.size} 份（$cloudTripId）")
            }.onFailure { Log.w(tag, "Firestore 讀取回憶失敗", it) }

            // 自己舊版的照片搬進共同相簿，每張只搬一次（memories 的照片陣列保留，舊版 App 的旅伴還讀得到）
            runCatching {
                val keys = photoRepo.migrateLegacy(
                    cloudTripId, _memory.value, photoRepo.fetch(cloudTripId),
                    stops.associateBy { it.stopId }
                )
                if (keys.isNotEmpty()) {
                    _memory.value = _memory.value.copy(
                        migratedPhotoKeys = (_memory.value.migratedPhotoKeys + keys).distinct()
                    )
                    myMemoryRef(uid).set(
                        mapOf("migratedPhotoKeys" to FieldValue.arrayUnion(*keys.toTypedArray())),
                        SetOptions.merge()
                    ).await()
                    Log.d(tag, "📦 舊照片搬進 photos：${keys.size} 張（$cloudTripId）")
                }
            }.onFailure { Log.w(tag, "舊照片搬移失敗（略過）", it) }
        }
    }

    /**
     * 舊路徑 `users/{uid}/memories/{tripId}` → 新路徑 `micro_trips/{docId}/memories/{uid}` 的一次性搬家。
     * 只在新路徑尚無自己的文件時執行；舊文件保留不刪（保險，且網頁端可能還在讀）。
     */
    private suspend fun migrateLegacyMemory(uid: String, tripId: String, title: String, region: String) {
        runCatching {
            val legacy = db.collection("users").document(uid)
                .collection("memories").document(tripId).get().await()
            if (!legacy.exists()) return
            val old = TripMemory.fromFirestore(legacy.data ?: emptyMap())
            if (!old.hasContent) return
            mergeInto(old.spots, title, region)
            persist(rewritePhotos = true)
            Log.d(tag, "📦 舊回憶已搬到共享路徑：$tripId（${old.photoCount} 張照片）")
        }.onFailure { Log.w(tag, "舊回憶搬家失敗（略過）", it) }
    }

    /**
     * 把來源 spots 疊到現有殼上（保留站點順序與名稱，僅補 note/photos）。
     *
     * 疊之前先做 key 重新對應——殼只有目前 stops 的 key，對不上的來源項目在這裡會被
     * 直接丟掉，所以搶救必須發生在覆蓋之前。回傳這次新解析出的 alias。
     */
    private fun mergeInto(
        source: Map<String, SpotMemory>,
        title: String,
        region: String,
        knownAliases: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val (reconciled, newAliases) =
            reconcileSpotKeys(source, _orderedStops.value, knownAliases)
        if (newAliases.isNotEmpty()) Log.d(tag, "🔗 回憶重新對應 ${newAliases.size} 站：$newAliases")
        val cur = _memory.value
        val merged = cur.spots.mapValues { (stopId, shell) ->
            val incoming = reconciled[stopId] ?: return@mapValues shell
            shell.copy(
                note = incoming.note,
                photos = incoming.photos,
                updatedAt = incoming.updatedAt
            )
        }
        _memory.value = cur.copy(
            tripTitle = title, region = region, spots = merged,
            spotAliases = cur.spotAliases + knownAliases + newAliases
        )
        return newAliases
    }

    fun setNote(stopId: String, note: String) {
        val cur = _memory.value
        val spot = cur.spots[stopId] ?: return
        _memory.value = cur.copy(
            spots = cur.spots + (stopId to spot.copy(note = note.take(500)))
        )
    }

    /**
     * 選好照片後逐張上傳到該站。
     * 雲端行程寫共同相簿 photos（監聽會自動帶回畫面）；純本地行程沒有 micro_trips 文件，
     * 維持寫進自己的回憶（Room）。
     */
    fun addPhotos(stopId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uid = auth.currentUser?.uid
        if (uid == null) { _error.value = "請先登入才能上傳照片"; return }
        val stop = _orderedStops.value.firstOrNull { it.stopId == stopId } ?: return
        viewModelScope.launch {
            _uploading.value = _uploading.value + stopId
            if (cloudTripId.isNotBlank()) {
                var failed = 0
                for (uri in uris) {
                    runCatching { photoRepo.upload(cloudTripId, stop, uri) }
                        .onFailure { Log.w(tag, "照片上傳失敗", it); failed++ }
                }
                if (failed > 0) _error.value = "有 $failed 張照片上傳失敗，請重試"
            } else {
                addLocalPhotos(uid, stopId, uris)
            }
            _uploading.value = _uploading.value - stopId
        }
    }

    private suspend fun addLocalPhotos(uid: String, stopId: String, uris: List<Uri>) {
        val added = mutableListOf<MemoryPhoto>()
        for (uri in uris) {
            runCatching {
                val bytes = photoRepo.compressForUpload(uri) ?: error("圖片讀取失敗")
                val photoId = UUID.randomUUID().toString()
                val path = "trip-photos/$uid/$currentTripId/$photoId.jpg"
                val ref = storage.reference.child(path)
                ref.putBytes(bytes, TripPhotoRepository.jpegStorageMetadata()).await()
                added += MemoryPhoto(
                    photoId = photoId,
                    url = ref.downloadUrl.await().toString(),
                    storagePath = path,
                    addedAt = System.currentTimeMillis(),
                    byUid = uid
                )
            }.onFailure {
                Log.w(tag, "照片上傳失敗", it)
                _error.value = "有照片上傳失敗，請重試"
            }
        }
        if (added.isEmpty()) return
        val cur = _memory.value
        val spot = cur.spots[stopId] ?: SpotMemory(stopId = stopId)
        _memory.value = cur.copy(
            spots = cur.spots + (stopId to spot.copy(photos = spot.photos + added))
        )
        persist()
    }

    /**
     * 刪除一張照片（方案 B：自己的，或我是 owner/editor）。
     * 自己的舊版照片同時從 memories 移除，否則雙讀會讓它再出現。
     */
    fun removePhoto(photo: TripPhoto) {
        val mine = photo.ownerUid == myUid
        viewModelScope.launch {
            if (!photo.legacy) {
                runCatching { photoRepo.delete(photo) }.onFailure {
                    Log.w(tag, "照片刪除失敗", it)
                    _error.value = "刪除失敗，可能沒有權限"
                    return@launch
                }
            }
            if (!mine) return@launch
            val removed = dropFromMyMemory(photo)
            if (removed.isEmpty()) return@launch
            if (photo.legacy) {
                runCatching {
                    val ref = photo.storagePath.takeIf { it.isNotBlank() }
                        ?.let { storage.reference.child(it) }
                        ?: storage.getReferenceFromUrl(photo.url)
                    ref.delete().await()
                }.onFailure { Log.w(tag, "照片檔案刪除失敗（略過）", it) }
            }
            // 平常存檔不碰照片陣列（見 persist），刪除要明確從 memories 拿掉
            val uid = auth.currentUser?.uid
            if (uid != null && cloudTripId.isNotBlank()) {
                runCatching {
                    removed.forEach { (stopId, urls) ->
                        myMemoryRef(uid).update(
                            FieldPath.of("spots", stopId, "photos"),
                            FieldValue.arrayRemove(*urls.toTypedArray())
                        ).await()
                    }
                }.onFailure { Log.w(tag, "memories 移除舊照片失敗", it) }
            }
            persist()
        }
    }

    /** 從自己的 memories 移除同一張（storagePath 或 url 相符）；回傳 stopId → 被移掉的網址。 */
    private fun dropFromMyMemory(photo: TripPhoto): Map<String, List<String>> {
        val cur = _memory.value
        val removed = mutableMapOf<String, List<String>>()
        val spots = cur.spots.mapValues { (stopId, spot) ->
            val (gone, kept) = spot.photos.partition {
                it.url == photo.url ||
                    (photo.storagePath.isNotBlank() && it.storagePath == photo.storagePath)
            }
            if (gone.isEmpty()) spot
            else { removed[stopId] = gone.map { it.url }; spot.copy(photos = kept) }
        }
        if (removed.isNotEmpty()) _memory.value = cur.copy(spots = spots)
        return removed
    }

    /** 把照片改到另一站（只有新版 photos 的照片能改；舊版等搬進 photos 後才行）。 */
    fun movePhoto(photo: TripPhoto, stopId: String) {
        if (photo.legacy || photo.stopId == stopId) return
        val stop = _orderedStops.value.firstOrNull { it.stopId == stopId } ?: return
        viewModelScope.launch {
            runCatching { photoRepo.moveToStop(photo, stop) }.onFailure {
                Log.w(tag, "照片改站失敗", it)
                _error.value = "移動失敗，可能沒有權限"
            }
        }
    }

    /** 存檔：寫 Firestore + Room。畫面離開或按儲存時呼叫；照片異動已自動呼叫。 */
    fun save() {
        viewModelScope.launch { persist() }
    }

    /**
     * @param rewritePhotos true＝整份覆寫（含照片陣列）。只在剛從雲端讀回、要把 key 重新對應
     *   或從舊路徑搬家時用；平常存檔一律 false，只合併短記等欄位、不碰照片陣列——
     *   否則畫面開著期間照片被別人刪掉（Function 已從 memories 清掉網址），
     *   按儲存就會把手上的舊網址寫回去，照片在兩端「復活」成破圖。
     */
    private suspend fun persist(rewritePhotos: Boolean = false) {
        val now = System.currentTimeMillis()
        val cur = _memory.value
        // 只保留有內容的站；coverUrl 依站點順序取整本相簿的第一張
        val nonEmpty = cur.spots.filterValues { !it.isEmpty }
            .mapValues { it.value.copy(updatedAt = now) }
        val album = photos.value
        val cover = _orderedStops.value
            .firstNotNullOfOrNull { s -> album.firstOrNull { it.stopId == s.stopId }?.url } ?: ""
        val uid = auth.currentUser?.uid
        val toSave = cur.copy(
            spots = nonEmpty, coverUrl = cover, updatedAt = now,
            ownerUid = uid.orEmpty(), ownerName = names.myName()
        )
        _memory.value = _memory.value.copy(coverUrl = cover, updatedAt = now)

        // Room（離線）
        runCatching {
            dao.upsert(
                LocalTripMemory(
                    tripId = toSave.tripId,
                    userId = uid ?: "",
                    tripTitle = toSave.tripTitle,
                    region = toSave.region,
                    coverUrl = cover,
                    updatedAt = now,
                    spotsJson = gson.toJson(nonEmpty)
                )
            )
        }.onFailure { Log.w(tag, "Room 寫入回憶失敗", it) }

        // Firestore（線上同步）：寫進行程底下自己那份，旅伴讀得到但改不了
        if (uid != null && cloudTripId.isNotBlank()) {
            runCatching {
                if (rewritePhotos) {
                    myMemoryRef(uid).set(toSave.toFirestoreMap()).await()
                } else {
                    myMemoryRef(uid).set(mergeFieldsOf(toSave, cur), SetOptions.merge()).await()
                }
            }.onFailure { Log.w(tag, "Firestore 寫入回憶失敗", it) }
        }
    }

    /**
     * 一般存檔要合併的欄位：每一站（含已清空的，短記才清得掉）的短記與名稱。
     * 照片陣列只在本地也沒有照片時寫 `[]`（確保欄位存在，網頁讀得到）；本地有照片就不寫，
     * 以雲端為準。migratedPhotoKeys 另外用 arrayUnion 寫，這裡也不碰。
     */
    private fun mergeFieldsOf(toSave: TripMemory, all: TripMemory): Map<String, Any> {
        val base = toSave.toFirestoreMap() - "spots" - "migratedPhotoKeys"
        val spots = all.spots.mapValues { (_, s) ->
            buildMap<String, Any> {
                put("stopId", s.stopId)
                put("spotName", s.spotName)
                put("note", s.note)
                put("updatedAt", toSave.updatedAt)
                if (s.photos.isEmpty()) put("photos", emptyList<String>())
            }
        }
        return base + ("spots" to spots)
    }

    fun clearError() { _error.value = null }

    private fun parseSpots(json: String): Map<String, SpotMemory> = runCatching {
        val type = object : TypeToken<Map<String, SpotMemory>>() {}.type
        gson.fromJson<Map<String, SpotMemory>>(json, type) ?: emptyMap()
    }.getOrElse { emptyMap() }

    /**
     * Room 的 spotsJson 有兩代格式：photos 舊為 `["url", ...]`、新為物件陣列。
     * 走 [SpotMemory.fromMap] 的相容路徑，順便沿用它的 UUID 補號。
     */
    private class SpotMemoryDeserializer : JsonDeserializer<SpotMemory> {
        override fun deserialize(
            json: JsonElement, typeOfT: Type, context: JsonDeserializationContext
        ): SpotMemory {
            val o = json.asJsonObject
            val photos: List<Any> = o.get("photos")?.asJsonArray?.mapNotNull { el ->
                when {
                    el.isJsonPrimitive -> el.asString
                    el.isJsonObject -> el.asJsonObject.entrySet().associate { (k, v) ->
                        k to when {
                            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asLong
                            v.isJsonNull -> ""
                            else -> v.asString
                        }
                    }
                    else -> null
                }
            } ?: emptyList()
            return SpotMemory.fromMap(
                mapOf(
                    "stopId" to (o.get("stopId")?.asString ?: ""),
                    "spotName" to (o.get("spotName")?.asString ?: ""),
                    "note" to (o.get("note")?.asString ?: ""),
                    "photos" to photos,
                    "updatedAt" to (o.get("updatedAt")?.asLong ?: 0L)
                )
            )
        }
    }
}
