package com.example.travellink_ai.ui.recap

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.ai.AiQuota
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.transit.ParkingDirectory
import com.example.travellink_ai.ui.planning.CostReference
import com.example.travellink_ai.ui.poster.PhotoBitmapCache
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * 回顧短片：收集 → 選節奏、看／改片段（推薦版時間軸）→ 呼叫後端 render → 輪詢 → 下載 mp4 → 存相簿。
 * 渲染在後端；時間軸的算法見 [RecapTimelines]（與網頁同一套），編輯結果存在這台裝置（[RecapEditorStore]）。
 */
@HiltViewModel
class RecapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RecapRepository,
    private val api: RecapApiClient,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val generator: RecapGenerator
) : ViewModel() {
    private val tag = "TravelLink_Recap"

    enum class Phase { IDLE, COLLECTING, RENDERING, DOWNLOADING, DONE, ERROR }

    /** 影片節奏與片段編輯器 */
    data class EditorState(
        val templateId: String = RecapTimelines.DEFAULT_TEMPLATE_ID,
        val timeline: RecapTimeline? = null,
        val rows: List<RecapTimelines.Row> = emptyList(),
        val totalSec: Double = 0.0,
        val photoClips: Int = 0,
        val canUndo: Boolean = false,
        val aiBusy: Boolean = false,
        /** 行程或照片變了，舊的編輯不適用、已重排推薦版 */
        val resetNotice: Boolean = false,
        /** 不是打字造成的改變（AI、復原、還原、換節奏）就 +1：字幕輸入框要換成新內容 */
        val revision: Int = 0,
        /** Reels：長度（all／15）、主角景點、封面（stopId；null＝依規則挑／行程插圖） */
        val isReels: Boolean = false,
        val lengthMode: String = "all",
        val heroStopId: String? = null,
        val coverStopId: String? = null,
        /** 封面站的照片（photoId → 網址，依相簿順序）與目前用的那張 */
        val coverPhotos: List<Pair<String, String>> = emptyList(),
        val coverPhotoId: String? = null,
        /** 有照片的站（主角、封面的選項） */
        val photoStops: List<PhotoStop> = emptyList(),
        /** photoId → 網址：照片片段卡片的縮圖 */
        val photoUrls: Map<String, String> = emptyMap()
    ) {
        val edited: Boolean get() = timeline?.edited == true
    }

    data class PhotoStop(val stopId: String, val name: String, val count: Int)

    data class UiState(
        val loading: Boolean = true,
        val configured: Boolean = false,
        val tripTitle: String = "",
        val region: String = "",
        val dateLabel: String = "",
        val stopCount: Int = 0,
        val photoCount: Int = 0,
        val phase: Phase = Phase.IDLE,
        val progress: Int = 0,
        /** 影片位置（雲端已存的、或剛生成的本機），供下方預覽播放。 */
        val videoUri: Uri? = null,
        /** 進頁時讀到的雲端短片；outdated 用它的 sig 比對當前行程。 */
        val existingRecap: CloudRecap? = null,
        val currentSig: String = "",
        val message: String? = null,
        val editor: EditorState = EditorState(),
        /** 一次性的提示（Toast） */
        val toast: String? = null,
        /** 配樂建議（影片做好後顯示；同一份節奏／站／照片會沿用這台裝置存的） */
        val music: RecapMusic.Suggestion? = null,
        val musicBusy: Boolean = false,
        val musicError: String? = null,
        /** 旅程結束自動產生（C7）：這個帳號有沒有開；這趟自動產生有沒有失敗 */
        val autoEnabled: Boolean = false,
        val autoFailed: Boolean = false
    ) {
        val busy: Boolean get() = phase == Phase.COLLECTING || phase == Phase.RENDERING || phase == Phase.DOWNLOADING
        /** 有影片可看（雲端或剛生成）。 */
        val hasVideo: Boolean get() = videoUri != null
        /** 雲端影片是舊的（行程已改，站點簽章不符）。 */
        val outdated: Boolean get() = existingRecap != null && existingRecap.sig != currentSig
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val store = RecapEditorStore(context)
    private val autoStore = AutoRecapStore(context)
    // 編輯器的素材：與送後端的 trip／photos 同一份，秒數才對得上
    private var trip: RecapTrip? = null
    private var allPhotos: List<RecapPhoto> = emptyList()
    /** 送後端的照片：依節奏挑（第 1 版每站一張、Reels 每站多張） */
    private val photos: List<RecapPhoto> get() = recapPhotosFor(allPhotos, store.templateId)
    private val settings: RecapSettings get() = store.settings(tripKey)
    private var tripKey = ""
    private var cloudId = ""
    private var roomId = ""
    private var editorSig: String? = null
    private var undo: RecapTimeline? = null
    private var captionEditing: String? = null
    private var notes: Map<String, String>? = null
    private var facts: Map<String, String>? = null
    private var item: LocalItinerary? = null
    private var musicHints: Map<String, RecapMusic.StopHint> = emptyMap()

    fun load(item: LocalItinerary) {
        viewModelScope.launch {
            val trip = repo.buildTrip(item)
            val cloud = item.firestoreDocId?.takeIf { it.isNotBlank() }.orEmpty()
            val room = cloud.ifBlank { "local_${item.id}" }
            val all = runCatching { repo.buildPhotos(trip, cloud, room) }.getOrDefault(emptyList())
            // 進頁讀雲端已存的短片，離開再回來就直接看得到
            val cloudRecap = if (cloud.isNotBlank())
                runCatching { repo.readCloudRecap(cloud) }.getOrNull() else null
            val currentSig = repo.sigOf(trip)
            this@RecapViewModel.trip = trip
            allPhotos = all
            cloudId = cloud; roomId = room; tripKey = room
            editorSig = null; undo = null; notes = null; facts = null
            this@RecapViewModel.item = item
            musicHints = RecapMusic.hints(item)
            _ui.value = UiState(
                loading = false,
                configured = api.isConfigured,
                tripTitle = trip.title,
                region = trip.region,
                dateLabel = trip.dateLabel,
                stopCount = trip.stops.size,
                photoCount = photos.size,
                existingRecap = cloudRecap,
                currentSig = currentSig,
                videoUri = cloudRecap?.videoUrl?.let { Uri.parse(it) },
                phase = if (cloudRecap != null) Phase.DONE else Phase.IDLE,
                music = store.loadMusic(room, RecapTimelines.signature(store.templateId, trip, photos)),
                autoEnabled = autoStore.isEnabled(auth.currentUser?.uid),
                autoFailed = cloudRecap == null && autoStore.hasFailed(room),
                message = when {
                    cloudRecap != null && cloudRecap.sig != currentSig -> "行程有更新，可按「重新生成」更新短片"
                    cloudRecap != null -> "這是先前生成的短片"
                    else -> null
                }
            )
            publish(ensureTimeline(), external = true)
        }
    }

    // ── 編輯器 ──────────────────────────────────────────────

    /** 目前適用的時間軸：記憶體裡的 → 這台裝置存的編輯版 → 新排的推薦版。景點不到兩站回 null */
    private fun ensureTimeline(): RecapTimeline? {
        val t = trip ?: return null
        if (t.stops.size < 2) return null
        val s = settings
        val ph = photos
        val sig = s.signature(t, ph)
        val cur = _ui.value.editor.timeline
        if (cur != null && editorSig == sig) return cur
        editorSig = sig
        val (saved, stale) = store.load(tripKey, sig)
        if (stale) _ui.value = _ui.value.copy(editor = _ui.value.editor.copy(resetNotice = true))
        return saved?.let { s.withCover(it, t, ph) } ?: s.recommended(t, ph)
    }

    private fun publish(timeline: RecapTimeline?, external: Boolean) {
        val e = _ui.value.editor
        val t = trip
        val s = settings
        val ph = photos
        _ui.value = _ui.value.copy(photoCount = ph.size, editor = e.copy(
            templateId = s.templateId,
            timeline = timeline,
            rows = timeline?.let { RecapTimelines.summarize(it) }.orEmpty(),
            totalSec = if (timeline != null && t != null) Math.round(RecapTimelines.totalMs(t, timeline, ph) / 100.0) / 10.0 else 0.0,
            photoClips = timeline?.clips?.count { it.type == "photo" } ?: 0,
            canUndo = undo != null,
            revision = if (external) e.revision + 1 else e.revision,
            isReels = s.isReels,
            lengthMode = s.lengthMode,
            heroStopId = t?.let { s.hero(it) },
            coverStopId = t?.let { s.coverIndex(it, ph) }?.let { t.stops[it].stopId },
            coverPhotos = t?.let { s.coverIndex(it, ph) }?.let { idx ->
                ph.filter { it.stopIndex == idx }.mapNotNull { p -> p.photoId?.let { it to p.url } }
            }.orEmpty(),
            coverPhotoId = t?.let { s.coverPhoto(it, ph) }?.photoId,
            photoStops = t?.let { tr ->
                ph.groupBy { it.stopIndex }.toSortedMap().map { (i, list) -> PhotoStop(tr.stops[i].stopId, tr.stops[i].name, list.size) }
            }.orEmpty(),
            photoUrls = ph.mapNotNull { p -> p.photoId?.let { it to p.url } }.toMap()
        ))
    }

    /** 每次修改：先留一份給「一步復原」，再存檔、更新畫面 */
    private fun commit(next: RecapTimeline) {
        undo = _ui.value.editor.timeline
        captionEditing = null
        store.save(tripKey, editorSig ?: "", next)
        publish(next, external = true)
    }

    fun dismissResetNotice() { _ui.value = _ui.value.copy(editor = _ui.value.editor.copy(resetNotice = false)) }

    /** 換影片節奏：重排推薦版（改過的秒數與字幕會清掉，畫面先問過使用者） */
    fun setTemplate(id: String) {
        if (!RecapTimelines.hasTemplate(id) || id == store.templateId) return
        store.templateId = id
        rebuild()
    }

    /** Reels 的長度（放進全部照片／固定 15 秒）：重排推薦版（同上，畫面先問過） */
    fun setLengthMode(mode: String) {
        if (mode !in RecapTimelines.LENGTH_MODES || mode == store.lengthMode) return
        store.lengthMode = mode
        rebuild()
    }

    /** 主角景點（null＝依規則挑）：重排推薦版（同上，畫面先問過） */
    fun setHero(stopId: String?) {
        if (stopId == store.heroStopId(tripKey)) return
        store.setHeroStopId(tripKey, stopId)
        rebuild()
    }

    /** 封面（null＝行程插圖）：只換開場片段，改過的秒數與字幕保留 */
    fun setCover(stopId: String?) {
        if (stopId == store.coverStopId(tripKey)) return
        store.setCoverStopId(tripKey, stopId)
        store.setCoverPhotoId(tripKey, null)   // 換站就從那站第一張開始
        val t = trip ?: return
        val cur = _ui.value.editor.timeline ?: return
        val next = settings.withCover(cur, t, photos)
        store.save(tripKey, editorSig ?: "", next)
        publish(next, external = false)
    }

    /** 封面站裡換一張：時間軸不變（後端依送出的照片順序取封面），只更新畫面 */
    fun setCoverPhoto(photoId: String) {
        if (photoId == store.coverPhotoId(tripKey)) return
        store.setCoverPhotoId(tripKey, photoId)
        publish(_ui.value.editor.timeline, external = false)
    }

    /** 設定變了（節奏、長度、主角）：清掉這趟的編輯、重排推薦版 */
    private fun rebuild() {
        store.clear(tripKey)
        undo = null
        captionEditing = null
        editorSig = null
        _ui.value = _ui.value.copy(editor = _ui.value.editor.copy(timeline = null))
        publish(ensureTimeline(), external = true)
    }

    fun stepClip(clipId: String, deltaMs: Long) {
        val cur = _ui.value.editor.timeline ?: return
        val clip = cur.clips.firstOrNull { it.clipId == clipId } ?: return
        commit(RecapTimelines.setDuration(cur, clipId, clip.durationMs + deltaMs))
    }

    /** 打字：不重設輸入框；「復原」記錄每次開始改這一則之前的版本 */
    fun captionClip(clipId: String, text: String) {
        val cur = _ui.value.editor.timeline ?: return
        if (captionEditing != clipId) { captionEditing = clipId; undo = cur }
        val next = RecapTimelines.setCaption(cur, clipId, text)
        store.save(tripKey, editorSig ?: "", next)
        publish(next, external = false)
    }

    fun removeClip(clipId: String) {
        val cur = _ui.value.editor.timeline ?: return
        commit(RecapTimelines.removePhoto(cur, clipId))
    }

    /** 一站多張的照片片段移除其中一張（只剩一張時＝移除整個片段） */
    fun removeClipPhoto(clipId: String, index: Int) {
        val cur = _ui.value.editor.timeline ?: return
        commit(RecapTimelines.removeClipPhoto(cur, clipId, index))
    }

    fun undo() {
        val t = trip ?: return
        val prev = undo?.let { settings.withCover(it, t, photos) } ?: return
        undo = null
        captionEditing = null
        store.save(tripKey, editorSig ?: "", prev)
        publish(prev, external = true)
    }

    fun resetTimeline() {
        val t = trip ?: return
        commit(settings.recommended(t, photos))
    }

    /** AI 寫字幕：只填字幕與建議照片長短，不新增片段、不改順序；使用者打好的字幕保留（全部都有字幕時才整份改寫） */
    fun aiCaptions() {
        val t = trip ?: return
        val base = _ui.value.editor.timeline ?: return
        if (_ui.value.editor.aiBusy) return
        _ui.value = _ui.value.copy(editor = _ui.value.editor.copy(aiBusy = true))
        viewModelScope.launch {
            try {
                val n = notes ?: withContext(Dispatchers.IO) { repo.loadNotes(t, cloudId, roomId) }.also { notes = it }
                val f = facts ?: withContext(Dispatchers.IO) { buildFacts(t) }.also { facts = it }
                val prompt = RecapTimelines.captionPrompt(t, base, t.title, n, f)
                val r = functions.getHttpsCallable("generate_content").call(hashMapOf(
                    "prompt" to prompt,
                    "model" to "gemini-3-flash-preview",
                    "temperature" to 0.6,
                    "responseMimeType" to "application/json",
                    "thinkingBudget" to 0,
                    "maxOutputTokens" to 2048
                )).await()
                val text = (r.data as? Map<*, *>)?.get("result") as? String ?: error("AI 沒有回應")
                val json = text.substringAfter("{", "").let { if (it.isEmpty()) "" else "{" + it.substringBeforeLast("}") + "}" }
                val parsed = runCatching { JSONObject(json) }.getOrNull() ?: error("AI 回傳格式不正確，請再試一次")
                val latest = _ui.value.editor.timeline ?: base
                val hasEmpty = latest.clips.any { it.type in RecapTimelines.CAPTION_TYPES && it.caption.isNullOrEmpty() }
                commit(RecapTimelines.applyScriptSuggestion(latest, parsed, keepExisting = hasEmpty))
                Log.d(tag, "✨ AI 字幕：${n.size} 站有旅記備註、${f.size} 站有實用資訊，${latest.clips.size} 段")
                _ui.value = _ui.value.copy(toast = "✨ 已填入 AI 字幕，可以直接修改")
            } catch (e: Exception) {
                Log.w(tag, "AI 字幕失敗：${e.message}")
                _ui.value = _ui.value.copy(toast = AiQuota.exceededMessage(e) ?: e.message?.takeIf { it.contains("AI") } ?: "AI 字幕失敗，請再試一次")
            } finally {
                _ui.value = _ui.value.copy(editor = _ui.value.editor.copy(aiBusy = false))
            }
        }
    }

    fun consumeToast() { _ui.value = _ui.value.copy(toast = null) }

    // 縣府＋OSM 停車場（assets/parking_taitung.json），與規劃時找停車場同一份；只用來推估步行分鐘
    private val parkingLots by lazy {
        runCatching {
            ParkingDirectory.usable(
                ParkingDirectory.parseLocal(context.assets.open("parking_taitung.json").bufferedReader().use { it.readText() }),
                emptyList()
            )
        }.onFailure { Log.w(tag, "停車場清單讀取失敗：${it.message}") }.getOrDefault(emptyList())
    }

    /** AI 字幕的實用資訊（[RecapFacts]）：停車只在開車／機車行程算，直線推估、12 分鐘內才給 */
    private fun buildFacts(t: RecapTrip): Map<String, String> {
        val src = item ?: return emptyMap()
        val costs = CostReference.get(context)
        val walk: ((Double, Double) -> Int?)? = if (t.transportMode == "car" || t.transportMode == "scooter") { lat, lng ->
            ParkingDirectory.candidates(parkingLots, lat, lng)
                .map { lot -> ParkingDirectory.estimateWalkMins(lot, lat, lng) }
                .firstOrNull { m -> m <= ParkingDirectory.MAX_WALK_MINS }
                ?.coerceAtLeast(1)
        } else null
        return RecapFacts.build(src, t, { name, lat, lng -> costs.lookup(name, lat, lng) }, walk)
    }

    /** 分享到 Reels 時複製的說明文案（見 [RecapShare]） */
    fun shareCaption(): String = trip?.let { RecapShare.caption(it) }.orEmpty()

    // ── 配樂建議 ────────────────────────────────────────────

    /**
     * 配樂建議：先看這台裝置存的（節奏、站、照片都沒變就沿用），沒有或 [force] 才問 AI。
     * 附每站一張代表照；看照片那次失敗（額度用完除外）就只用景點類型＋節奏再問一次。
     */
    fun suggestMusic(force: Boolean = false) {
        val t = trip ?: return
        if (_ui.value.musicBusy) return
        val templateId = store.templateId
        val sig = RecapTimelines.signature(templateId, t, photos)
        if (!force) store.loadMusic(tripKey, sig)?.let { _ui.value = _ui.value.copy(music = it, musicError = null); return }
        val picked = RecapMusic.pickPhotos(photos)
        val key = tripKey
        _ui.value = _ui.value.copy(musicBusy = true, musicError = null)
        viewModelScope.launch {
            try {
                val images = withContext(Dispatchers.IO) { encodePhotos(picked) }
                val s = try {
                    askMusic(RecapMusic.prompt(t, templateId, musicHints, images.size), images)
                } catch (e: Exception) {
                    if (images.isEmpty() || AiQuota.exceededMessage(e) != null) throw e
                    Log.w(tag, "配樂建議（看照片）失敗，改只用景點類型：${e.message}")
                    askMusic(RecapMusic.prompt(t, templateId, musicHints, 0), emptyList())
                }
                store.saveMusic(key, sig, s)
                Log.d(tag, "🎵 配樂建議：${s.mood}（看了 ${images.size} 張照片）")
                _ui.value = _ui.value.copy(music = s)
            } catch (e: Exception) {
                Log.w(tag, "配樂建議失敗：${e.message}")
                _ui.value = _ui.value.copy(musicError = AiQuota.exceededMessage(e) ?: "配樂建議暫時取不到，請再試一次")
            } finally {
                _ui.value = _ui.value.copy(musicBusy = false)
            }
        }
    }

    private suspend fun askMusic(prompt: String, images: List<Map<String, String>>): RecapMusic.Suggestion {
        val args = hashMapOf<String, Any>(
            "prompt" to prompt,
            "model" to "gemini-3-flash-preview",
            "temperature" to 0.7,
            "responseMimeType" to "application/json",
            "thinkingBudget" to 0,
            "maxOutputTokens" to 512
        )
        if (images.isNotEmpty()) args["images"] = images
        val r = functions.getHttpsCallable("generate_content").call(args).await()
        val text = (r.data as? Map<*, *>)?.get("result") as? String ?: error("AI 沒有回應")
        return RecapMusic.parse(text) ?: error("AI 回傳格式不正確")
    }

    /** 代表照縮成長邊 512px 的 JPEG（base64）；載不到的略過 */
    private suspend fun encodePhotos(picked: List<RecapPhoto>): List<Map<String, String>> {
        if (picked.isEmpty()) return emptyList()
        val cache = PhotoBitmapCache(context, RecapMusic.PHOTO_EDGE_PX)
        try {
            return picked.mapNotNull { p ->
                val bmp = cache.load(p.url) ?: return@mapNotNull null
                val scale = RecapMusic.PHOTO_EDGE_PX.toFloat() / maxOf(bmp.width, bmp.height)
                val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp,
                    (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true) else bmp
                val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                if (out !== bmp) out.recycle()
                mapOf("mimeType" to "image/jpeg", "data" to Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        } finally {
            cache.release()
        }
    }

    // ── 產生影片 ────────────────────────────────────────────

    fun generate(item: LocalItinerary) {
        val cur = _ui.value
        if (cur.busy) return
        if (!api.isConfigured) {
            _ui.value = cur.copy(message = "回顧短片功能尚未啟用（後端未設定）")
            return
        }
        if (auth.currentUser == null) {
            _ui.value = cur.copy(message = "請先登入再產生回顧短片")
            return
        }

        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(phase = Phase.COLLECTING, progress = 0, message = null)
                val out = generator.render(item, settings, timelineFor = { trip, _, all ->
                    // 進頁之後行程或照片變了（旅伴剛上傳）：用最新的素材重排，編輯器才不會和影片對不上
                    this@RecapViewModel.trip = trip
                    allPhotos = all
                    ensureTimeline().also { publish(it, external = true) }
                }) { stage, progress ->
                    _ui.value = _ui.value.copy(
                        phase = if (stage == RecapGenerator.Stage.RENDERING) Phase.RENDERING else Phase.DOWNLOADING,
                        progress = progress
                    )
                }
                autoStore.clearFailed(tripKey)
                _ui.value = _ui.value.copy(
                    phase = Phase.DONE,
                    videoUri = out.localUri ?: out.cloudUrl?.let { Uri.parse(it) },
                    existingRecap = out.cloudUrl?.let {
                        CloudRecap(it, out.sig, out.trip.title, "", System.currentTimeMillis())
                    } ?: _ui.value.existingRecap,
                    currentSig = out.sig,
                    photoCount = out.photos.size,
                    autoFailed = false,
                    message = out.saveMessage
                )
                suggestMusic()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(phase = Phase.ERROR, message = e.message ?: "產生失敗")
            }
        }
    }

    /** 「旅程結束後自動做回顧短片」開關（與側欄同一個設定） */
    fun setAutoEnabled(on: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        autoStore.setEnabled(uid, on)
        _ui.value = _ui.value.copy(autoEnabled = on)
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
