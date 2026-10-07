package com.example.travellink_ai.ui.trip

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.parseIsStation
import com.example.travellink_ai.data.model.stableStopId
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import com.example.travellink_ai.data.repository.NotificationRepository
import com.example.travellink_ai.data.util.IdentityKeys
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject

// ══════════════════════════════════════════════════════════════
// 行程進行中（W3：C1 + C2）資料模型
// 儲存位置：micro_trips/{docId}.tripProgress（獨立巢狀 map，
// 不動 stops 陣列，避免與網頁端的整包覆蓋相容問題）
// ══════════════════════════════════════════════════════════════

/** 單站打卡紀錄（tripProgress.checkIns.{stopId}） */
data class CheckInRecord(
    val uid: String = "",
    val displayName: String = "旅人",
    val at: Long = 0L
)

/** 實際停車紀錄（tripProgress.parking.{stopId}）：行程進行中以 GPS 記錄的停車點 */
data class ParkingRecord(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 0f,        // GPS 水平精度（公尺）
    val note: String = "",           // 選填：「B2 紅區 12 號柱」
    val at: Long = 0L,
    val uid: String = "",
    val displayName: String = "旅人",
    val releasedAt: Long = 0L        // >0 = 已取車
) {
    val isActive: Boolean get() = releasedAt == 0L
    val latLng: LatLng get() = LatLng(lat, lng)
}

/**
 * 使用者自己調的「預計離開時間」（tripProgress.leaveAt.{stopId}）。
 * 只影響後續各站的預計時間；原規劃的停留時間（Stop.duration）不改寫，
 * 所以「原定停留」「預計何時走」「實際何時到」三者分開保存。
 */
data class LeaveRecord(
    val at: Long = 0L,                    // 預計離開的時刻（epoch ms）
    val uid: String = "",
    val displayName: String = "旅人"
)

/** 整趟行程的進行狀態 */
data class TripProgress(
    val status: String = "",              // "" | "ongoing" | "completed"
    val startedAt: Long = 0L,
    val completedAt: Long = 0L,
    val checkIns: Map<String, CheckInRecord> = emptyMap(),
    val skips: Set<String> = emptySet(),
    val parking: Map<String, ParkingRecord> = emptyMap(),  // key = stopId
    val leaveAt: Map<String, LeaveRecord> = emptyMap()     // key = stopId
) {
    val isOngoing: Boolean get() = status == "ongoing"
    val isCompleted: Boolean get() = status == "completed"
    fun isDone(stopId: String): Boolean = stopId in checkIns || stopId in skips

    /** 目前生效的停車點（最近一次、尚未取車）→ UI 只 highlight 這一筆 */
    val activeParking: Pair<String, ParkingRecord>?
        get() = parking.entries
            .filter { it.value.isActive }
            .maxByOrNull { it.value.at }
            ?.let { it.key to it.value }
}

@HiltViewModel
class TripProgressViewModel @Inject constructor(
    application: Application,
    private val stateHolder: ItineraryStateHolder,
    private val db: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth,
    private val notificationRepo: NotificationRepository,
    private val visitedRepo: com.example.travellink_ai.data.repository.VisitedSpotsRepository
) : AndroidViewModel(application) {

    /** 「📌 我去過了」的景點（與網頁共用 users/{uid}.visitedSpots），以正規化名稱比對 */
    val visitedKeys: kotlinx.coroutines.flow.StateFlow<Set<String>> = visitedRepo.observeNames()
        .map { names -> names.map { com.example.travellink_ai.data.repository.VisitedSpotsRepository.nameKey(it) }.toSet() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 切換去過了；回傳 true＝已標記、false＝已取消、null＝失敗 */
    fun toggleVisited(stop: com.example.travellink_ai.data.model.Stop, onResult: (Boolean?) -> Unit) {
        val itin = stateHolder.itinerary.value
        viewModelScope.launch {
            val r = visitedRepo.toggle(
                name = stop.name,
                emoji = stop.emoji,
                region = itin?.region.orEmpty(),
                tripId = stateHolder.currentFirestoreDocId.value?.takeIf { it != "current" }.orEmpty(),
                tripTitle = itin?.let { it.title.ifBlank { it.aiTitle } }.orEmpty(),
                gpsVerified = null   // 手動標記（網頁：null＝手動）
            )
            onResult(r)
        }
    }

    private val tag = "TravelLink_Debug"
    private val prefs = application.getSharedPreferences("trip_progress", Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow(TripProgress())
    val progress = _progress.asStateFlow()

    /** 站點詳細頁目前檢視的 stopId（由時間軸頁點擊設入） */
    var selectedStopId by mutableStateOf<String?>(null)

    /**
     * 🧪 Debug-only 假 GPS（demo 用）。放在 ViewModel 而非畫面的 remember，
     * 是因為 MainActivity 用簡易的 currentScreen 切換畫面，進站點詳細頁再切回來，
     * TripInProgressScreen 會被整個重組，畫面內的 remember/rememberSaveable 與其
     * 掛的 coroutine 都會被砍掉——模擬走到一半會直接斷掉。掛在這裡的 viewModelScope
     * 撐得過切畫面，只有離開整個行程進行中流程（ViewModel 被清除）才會停止。
     */
    private var _demoModeOn by mutableStateOf(false)
    /** demo 模式：假 GPS＋demo 時鐘。關掉時時鐘快轉歸零，回到手機時間 */
    var demoModeOn: Boolean
        get() = _demoModeOn
        set(v) {
            _demoModeOn = v
            if (!v) com.example.travellink_ai.debug.DemoClock.reset()
        }
    val locationSimulator = com.example.travellink_ai.debug.LocationSimulator(viewModelScope)

    /**
     * 最後一次收到的真實 GPS 座標（由行程進行中頁的定位訂閱鏡射進來）。
     * 站點詳細頁沒有自己的 GPS 訂閱，打卡時的距離檢查靠這份值，不用另外再接一次 FusedLocationProviderClient。
     */
    var lastKnownLocation by mutableStateOf<com.google.android.gms.maps.model.LatLng?>(null)

    /** 進地圖頁的意圖："plain" | "parking"（聚焦停車點）| "toilets"（預設開廁所圖層） */
    var mapMode by mutableStateOf("plain")

    // ── 第 6 項：到預計離開時間的提醒 ─────────────────────────────
    // 目前站或預計離開時間一變就重排（下一站打卡、調整時間、通知上按延長、旅伴改動都會經過這裡），
    // 行程結束或重設就取消。掛在 ViewModel 而不是畫面，是因為打卡後會跳到景點資訊頁，
    // 行程頁不在畫面上時也要能重排。
    private var reminderKey: String? = null

    init {
        viewModelScope.launch {
            // demo 時鐘快轉時也要重排：鬧鐘的真實觸發時間＝預計離開－快轉量
            kotlinx.coroutines.flow.combine(
                _progress, stateHolder.itinerary, com.example.travellink_ai.debug.DemoClock.offsetMs
            ) { p, itin, _ -> p to itin?.stops.orEmpty() }
                .collect { (p, stops) -> syncLeaveReminder(p, stops) }
        }
    }

    private fun syncLeaveReminder(p: TripProgress, stops: List<Stop>) {
        val ctx = getApplication<Application>()
        val derived = deriveTripState(stops, p, emptyList())
        val here = derived.activeHere(p)
        val checkInAt = here?.let { p.checkIns[it.stop.stopId]?.at }
        if (here == null || checkInAt == null) {
            if (reminderKey != null) { LeaveReminder.cancel(ctx); reminderKey = null }
            return
        }
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = checkInAt
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val leaveAt = dayStart + here.leaveMin * 60_000L
        val key = "${here.stop.stopId}@$leaveAt@${com.example.travellink_ai.debug.DemoClock.offsetMs.value}"
        if (key == reminderKey) return
        reminderKey = key
        val nextName = derived.currentStop?.name ?: stops.lastOrNull { it.isStation }?.name
        LeaveReminder.schedule(ctx, realDocId, here.stop.stopId, here.stop.name, nextName, leaveAt)
    }

    private var listener: ListenerRegistration? = null
    private var attachedDocId: String? = null
    /** 目前 progress 對應的儲存 key，切換行程時用來偵測並重置狀態 */
    private var loadedKey: String? = null

    private val myUid: String get() = firebaseAuth.currentUser?.uid ?: ""
    private val myName: String
        get() = firebaseAuth.currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: firebaseAuth.currentUser?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "旅人"

    /**
     * 真實 Firestore docId（只排除 "current" 占位值）。
     * 注意：App 生成的行程 docId 本來就是 "my_{createdAt}" 格式且真實存在於
     * micro_trips，不可用前綴排除（曾誤判導致 tripProgress 只存本地、雙機不同步）。
     * loadForFeedback 對無 docId 的舊行程也會合成 my_ id，那種文件不存在，
     * update() 會 NOT_FOUND 被 catch，無害。
     */
    private val realDocId: String?
        get() = stateHolder.currentFirestoreDocId.value
            ?.takeIf { it.isNotBlank() && it != "current" }

    /** 本地快取 key：雲端行程用 docId，純本地行程用 Room id */
    private val progressKey: String
        get() = realDocId ?: "local_${stateHolder.lastGeneratedLocalId}"

    // ── 載入 / 監聽 ───────────────────────────────────────────

    /**
     * 進入預覽頁時呼叫：先讀本地快取（立即可用），再向 Firestore 補一次最新狀態。
     * 換了行程（key 改變）時先重置，避免上一趟的進度殘留。
     */
    fun refreshStatus() {
        val key = progressKey
        if (loadedKey != key) {
            detach()
            _progress.value = loadFromLocal(key)
            loadedKey = key
        }
        val docId = realDocId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = db.collection("micro_trips").document(docId).get().await()
                val remote = mergeWebProgress(snap, parseProgress(snap.get("tripProgress") as? Map<*, *>))
                if (progressKey == key && (remote.status.isNotBlank() || remote.checkIns.isNotEmpty())) {
                    _progress.value = remote
                    persistLocal()
                }
            } catch (e: Exception) {
                Log.w(tag, "⚠️ tripProgress 讀取失敗（沿用本地快取）：${e.message}")
            }
        }
    }

    /** 進入進行中頁面時呼叫：建立即時監聽，其他成員的打卡/跳過即時同步 */
    fun attach() {
        val docId = realDocId ?: return
        if (attachedDocId == docId) return
        detach()
        attachedDocId = docId
        listener = db.collection("micro_trips").document(docId)
            .addSnapshotListener { doc, error ->
                if (attachedDocId != docId) return@addSnapshotListener
                if (error != null || doc?.exists() != true) return@addSnapshotListener
                // 重設進度要分兩次寫（規則不讓 tripProgress 與 root 欄位同一筆），
                // 中間那個快照還是「進行中」，忽略掉以免畫面閃回去
                if (resetInFlight) return@addSnapshotListener
                val remote = mergeWebProgress(doc, parseProgress(doc.get("tripProgress") as? Map<*, *>))
                // 本地樂觀更新在前、echo 在後，值相同不觸發重組
                if (remote != _progress.value) {
                    _progress.value = remote
                    persistLocal()
                }
            }
    }

    fun detach() {
        listener?.remove()
        listener = null
        attachedDocId = null
    }

    // ── 動作 ─────────────────────────────────────────────────

    /** 開始（或重新開始）行程；已完成的行程重新開始時清掉舊進度 */
    fun startTrip() {
        val cur = _progress.value
        if (cur.isOngoing) { attach(); return }
        val now = com.example.travellink_ai.debug.DemoClock.now()
        _progress.value = if (cur.isCompleted) {
            TripProgress(status = "ongoing", startedAt = now)
        } else {
            cur.copy(status = "ongoing", startedAt = if (cur.startedAt > 0) cur.startedAt else now, completedAt = 0L)
        }
        persistLocal()
        if (cur.isCompleted) {
            // 重新開始：整包覆蓋 tripProgress，清掉舊打卡
            pushSet(
                mapOf(
                    "status" to "ongoing",
                    "startedAt" to now,
                    "checkIns" to emptyMap<String, Any>(),
                    "skips" to emptyMap<String, Any>(),
                    "parking" to emptyMap<String, Any>()
                )
            )
        } else {
            pushUpdate(
                mapOf(
                    "tripProgress.status" to "ongoing",
                    "tripProgress.startedAt" to _progress.value.startedAt
                )
            )
        }
        mirrorWebProgress(resetAll = cur.isCompleted)
        attach()
    }

    /** 打卡（stopId 已經過 stableStopId 消毒，可安全用於 field path） */
    fun checkIn(stopId: String) {
        val rec = CheckInRecord(uid = myUid, displayName = myName, at = com.example.travellink_ai.debug.DemoClock.now())
        val cur = _progress.value
        _progress.value = cur.copy(
            checkIns = cur.checkIns + (stopId to rec),
            skips = cur.skips - stopId
        )
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.checkIns.$stopId" to mapOf(
                    "uid" to rec.uid, "displayName" to rec.displayName, "at" to rec.at
                ),
                "tripProgress.skips.$stopId" to FieldValue.delete()
            )
        )
        mirrorWebProgress()
    }

    /** 取消打卡（誤按救援） */
    fun undoCheckIn(stopId: String) {
        val cur = _progress.value
        _progress.value = cur.copy(checkIns = cur.checkIns - stopId)
        persistLocal()
        pushUpdate(mapOf("tripProgress.checkIns.$stopId" to FieldValue.delete()))
        mirrorWebProgress(clearStopIds = setOf(stopId))
    }

    /** 跳過此站（不刪站、可恢復；後續站時間估算會前移） */
    fun skipStop(stopId: String) {
        val cur = _progress.value
        _progress.value = cur.copy(
            skips = cur.skips + stopId,
            checkIns = cur.checkIns - stopId
        )
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.skips.$stopId" to true,
                "tripProgress.checkIns.$stopId" to FieldValue.delete()
            )
        )
        mirrorWebProgress(clearStopIds = setOf(stopId))
    }

    /** 恢復被跳過的站 */
    fun unskipStop(stopId: String) {
        val cur = _progress.value
        _progress.value = cur.copy(skips = cur.skips - stopId)
        persistLocal()
        pushUpdate(mapOf("tripProgress.skips.$stopId" to FieldValue.delete()))
        mirrorWebProgress()
    }

    /**
     * 調整目前所在站的預計離開時間（epoch ms）。後續各站的預計時間由 deriveTripState 順延，
     * 不動 Stop.duration。所有成員都能調（和打卡一樣只寫 tripProgress）。
     */
    fun setLeaveAt(stopId: String, atMs: Long) {
        val rec = LeaveRecord(at = atMs, uid = myUid, displayName = myName)
        val cur = _progress.value
        _progress.value = cur.copy(leaveAt = cur.leaveAt + (stopId to rec))
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.leaveAt.$stopId" to mapOf(
                    "at" to rec.at, "uid" to rec.uid, "displayName" to rec.displayName
                )
            )
        )
    }

    /**
     * 把「目前站」的預計離開設成當日第 [minutes] 分鐘：不能早於到達；
     * 調回「到達＋原定停留」就等於恢復原定，直接清掉。
     */
    internal fun adjustLeave(here: HereStop, minutes: Int) {
        val target = minutes.coerceAtLeast(here.arrivedMin)
        if (target == here.defaultLeaveMin) {
            clearLeaveAt(here.stop.stopId)
            return
        }
        // 以打卡那天的 00:00 為基準換回 epoch ms（當日分鐘數可能超過 24:00，照樣加上去）
        val checkInAt = _progress.value.checkIns[here.stop.stopId]?.at ?: return
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = checkInAt
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        setLeaveAt(here.stop.stopId, dayStart + target * 60_000L)
    }

    /**
     * 剛打卡的站：行程頁打卡後自動跳到景點資訊頁，由那頁跳出「拍張照」提示後清掉。
     * 放在 ViewModel 是因為打卡和提示在不同畫面。
     */
    var justCheckedInStopId by mutableStateOf<String?>(null)

    /** 衝突提醒按「先不用」的 key（ScheduleConflict.key）。只存在這次使用期間，不同步給旅伴 */
    var dismissedConflicts by mutableStateOf(emptySet<String>())
        private set
    internal fun dismissConflict(key: String) { dismissedConflicts = dismissedConflicts + key }

    /** 恢復原定：清掉這站的預計離開時間，改回「到達時間＋原定停留」 */
    fun clearLeaveAt(stopId: String) {
        val cur = _progress.value
        if (stopId !in cur.leaveAt) return
        _progress.value = cur.copy(leaveAt = cur.leaveAt - stopId)
        persistLocal()
        pushUpdate(mapOf("tripProgress.leaveAt.$stopId" to FieldValue.delete()))
    }

    @Volatile private var resetInFlight = false

    /**
     * 重設進度：退回規劃中，清掉打卡、跳過、停車與預計離開時間（對齊網頁 resetTripProgress）。
     * tripProgress 與網頁的 root 欄位要分兩次寫（editor 白名單不含 tripProgress），
     * 期間暫停套用快照，避免中途的「進行中」快照把畫面拉回去。
     */
    fun resetProgress() {
        _progress.value = TripProgress()
        persistLocal()
        val docId = realDocId ?: return
        resetInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection("micro_trips").document(docId)
                    .update("tripProgress", emptyMap<String, Any>()).await()
                mirrorWebProgressNow(resetAll = true)
            } catch (e: Exception) {
                Log.w(tag, "⚠️ 重設進度同步失敗（本地已重設）：${e.message}")
            } finally {
                resetInFlight = false
            }
        }
    }

    /** 記錄停車點（覆蓋同站舊紀錄）。stopId 已過 stableStopId 消毒，可安全用於 field path */
    fun setParking(stopId: String, lat: Double, lng: Double, accuracy: Float, note: String = "") {
        val now = com.example.travellink_ai.debug.DemoClock.now()
        val rec = ParkingRecord(
            lat = lat, lng = lng, accuracy = accuracy, note = note,
            at = now, uid = myUid, displayName = myName, releasedAt = 0L
        )
        val cur = _progress.value
        _progress.value = cur.copy(parking = cur.parking + (stopId to rec))
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.parking.$stopId" to mapOf(
                    "lat" to lat, "lng" to lng, "accuracy" to accuracy, "note" to note,
                    "at" to now, "uid" to rec.uid, "displayName" to rec.displayName, "releasedAt" to 0L
                )
            )
        )
    }

    /** 微調停車點座標與備註（保留原 at 停車時間） */
    fun updateParking(stopId: String, lat: Double, lng: Double, note: String) {
        val cur = _progress.value
        val rec = cur.parking[stopId] ?: return
        val updated = rec.copy(lat = lat, lng = lng, note = note)
        _progress.value = cur.copy(parking = cur.parking + (stopId to updated))
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.parking.$stopId.lat" to lat,
                "tripProgress.parking.$stopId.lng" to lng,
                "tripProgress.parking.$stopId.note" to note
            )
        )
    }

    /** 已取車：releasedAt = now（保留紀錄供日後眾包，不刪除） */
    fun releaseParking(stopId: String) {
        val cur = _progress.value
        val rec = cur.parking[stopId] ?: return
        val now = com.example.travellink_ai.debug.DemoClock.now()
        _progress.value = cur.copy(parking = cur.parking + (stopId to rec.copy(releasedAt = now)))
        persistLocal()
        pushUpdate(mapOf("tripProgress.parking.$stopId.releasedAt" to now))
    }

    /**
     * 完成行程。回饋提醒改為「完成行程時」觸發（原本是行程結束時間的 AlarmManager 排程）：
     * @param notifyFeedback true = 立即發「填寫回饋」通知（使用者選「完成並回首頁」時）；
     *                       false = 不發（使用者直接進回饋頁填寫）
     */
    fun finishTrip(notifyFeedback: Boolean = true) {
        val now = com.example.travellink_ai.debug.DemoClock.now()
        _progress.value = _progress.value.copy(status = "completed", completedAt = now)
        persistLocal()
        pushUpdate(
            mapOf(
                "tripProgress.status" to "completed",
                "tripProgress.completedAt" to now
            )
        )
        mirrorWebProgress()
        if (notifyFeedback) sendFeedbackNotification()
        broadcastTripCompletedToFriends()
    }

    /**
     * 完成整趟行程時通知已成立好友（friend_trip_completed），對齊網頁端 markTripAsCompleted。
     * nid＝['friend_done', tripId, 我的 identityKey]（同一趟同一人重送會撞同 doc→靜默）。
     * 純本地行程（無 realDocId）好友無法開啟，直接略過。
     */
    private fun broadcastTripCompletedToFriends() {
        val myEmail = firebaseAuth.currentUser?.email ?: return
        val tripId = realDocId ?: return
        val title = stateHolder.itinerary.value?.title ?: ""
        viewModelScope.launch {
            val friends = notificationRepo.fetchAcceptedFriendEmails(myEmail)
            if (friends.isEmpty()) return@launch
            val myKey = IdentityKeys.identityKey(myEmail)
            val id = notificationRepo.nid(listOf("friend_done", tripId, myKey))
            notificationRepo.pushToMany(
                emails = friends,
                idFor = { id },
                type = "friend_trip_completed",
                fromName = myName,
                tripId = tripId,
                tripTitle = title
            )
        }
    }

    /** 完成行程當下發出「填寫回饋」通知（走既有 FeedbackNotificationReceiver） */
    private fun sendFeedbackNotification() {
        try {
            val context = getApplication<Application>()
            val title = stateHolder.itinerary.value?.let { it.title.ifBlank { it.aiTitle } } ?: "您的行程"
            val intent = android.content.Intent(
                context, com.example.travellink_ai.FeedbackNotificationReceiver::class.java
            ).apply {
                action = com.example.travellink_ai.FeedbackNotificationReceiver.ACTION_OPEN_FEEDBACK
                putExtra(com.example.travellink_ai.FeedbackNotificationReceiver.EXTRA_TITLE, title)
                putExtra(com.example.travellink_ai.FeedbackNotificationReceiver.EXTRA_DOC_ID,
                    stateHolder.currentFirestoreDocId.value)
                putExtra(com.example.travellink_ai.FeedbackNotificationReceiver.EXTRA_LOCAL_ID,
                    stateHolder.lastGeneratedLocalId)
            }
            context.sendBroadcast(intent)
            Log.d(tag, "🔔 行程完成，已發出回饋提醒通知")
        } catch (e: Exception) {
            Log.w(tag, "⚠️ 回饋通知發送失敗（非致命）：${e.message}")
        }
    }

    // ── 網頁端 schema 鏡像 ─────────────────────────────────────
    // 網頁端（WanderAI）的旅程進行狀態存在文件 root：
    //   status('planning'/'ongoing'/'completed') + currentStopIndex + startedAt(ms)
    //   + stops[].checkedInAt（打卡時間寫進 stops 陣列元素）
    // App 打卡時鏡像寫入這組欄位，網頁端才看得到「⚡進行中」與打卡進度。
    // 鏡像與 tripProgress 寫入「分開送」：viewer 對 root 欄位無權限（規則 editor 白名單），
    // 鏡像被拒不影響打卡本體（網頁本來也擋唯讀成員操作進度）。

    /**
     * @param clearStopIds 取消打卡的站：checkedInAt 清為 null
     * @param resetAll     重新開始行程：全部 checkedInAt 清空
     */
    private fun mirrorWebProgress(clearStopIds: Set<String> = emptySet(), resetAll: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) { mirrorWebProgressNow(clearStopIds, resetAll) }
    }

    private suspend fun mirrorWebProgressNow(clearStopIds: Set<String> = emptySet(), resetAll: Boolean = false) {
        val docId = realDocId ?: return
        try {
            val p = _progress.value
            val snap = db.collection("micro_trips").document(docId).get().await()
            val rawStops = (snap.get("stops") as? List<*>)
                ?.filterIsInstance<Map<String, Any?>>() ?: return
            if (rawStops.isEmpty()) return

            // 逐站合併：只動 checkedInAt，網頁端其他欄位原樣保留
            var currentIdx = -1
            val ids = com.example.travellink_ai.data.model.stableStopIds(rawStops)
            val merged = rawStops.mapIndexed { i, s ->
                val sid = ids[i]
                val m = s.toMutableMap()
                val appAt = p.checkIns[sid]?.at
                when {
                    resetAll      -> m["checkedInAt"] = null
                    appAt != null -> m["checkedInAt"] = appAt
                    sid in clearStopIds -> m["checkedInAt"] = null
                }
                // currentStopIndex＝完整陣列中第一個「未完成的非車站」（跳過的站略過）
                if (currentIdx == -1 && !parseIsStation(s) && appAt == null && sid !in p.skips) {
                    currentIdx = i
                }
                m
            }
            if (currentIdx == -1) currentIdx = rawStops.size - 1  // 全完成 → 指向回程站

            val status = when {
                p.isCompleted -> "completed"
                p.isOngoing   -> "ongoing"
                else          -> "planning"
            }
            val updates = hashMapOf<String, Any?>(
                "stops"  to merged,
                "status" to status,
                "currentStopIndex" to if (status == "planning") -1 else currentIdx
            )
            if (p.startedAt > 0) updates["startedAt"] = p.startedAt
            else if (status == "planning") updates["startedAt"] = null  // 重設進度（網頁也清成 null）
            db.collection("micro_trips").document(docId).update(updates).await()
            Log.d(tag, "🔁 web 進度鏡像已同步（status=$status idx=$currentIdx）")
        } catch (e: Exception) {
            Log.w(tag, "⚠️ web 進度鏡像失敗（viewer 無權限屬預期）：${e.message}")
        }
    }

    /**
     * 把網頁端 schema 的進度併入 TripProgress（讀取方向的相容層）：
     * - 網頁打卡的站（stops[].checkedInAt 有值、App checkIns 沒有）補一筆，打卡者顯示「旅伴」
     * - 網頁端開始/完成的行程（root status），tripProgress 尚無狀態時沿用
     */
    private fun mergeWebProgress(doc: DocumentSnapshot, base: TripProgress): TripProgress {
        var merged = base
        val stopsRaw = doc.get("stops") as? List<*>
        val webCheckIns = mutableMapOf<String, CheckInRecord>()
        val ids = com.example.travellink_ai.data.model.stableStopIds(stopsRaw.orEmpty())
        stopsRaw?.forEachIndexed { i, raw ->
            val s = raw as? Map<*, *> ?: return@forEachIndexed
            val at = (s["checkedInAt"] as? Number)?.toLong() ?: return@forEachIndexed
            if (parseIsStation(s)) return@forEachIndexed
            val sid = ids[i]
            if (sid !in base.checkIns) {
                webCheckIns[sid] = CheckInRecord(uid = "", displayName = "旅伴", at = at)
            }
        }
        if (webCheckIns.isNotEmpty()) merged = merged.copy(checkIns = merged.checkIns + webCheckIns)
        if (merged.status.isBlank()) {
            when (doc.getString("status")) {
                "ongoing"   -> merged = merged.copy(
                    status = "ongoing",
                    startedAt = (doc.get("startedAt") as? Number)?.toLong() ?: 0L
                )
                "completed" -> merged = merged.copy(status = "completed")
            }
        }
        return merged
    }

    // ── Firestore 寫入 ────────────────────────────────────────

    private fun pushUpdate(updates: Map<String, Any>) {
        val docId = realDocId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection("micro_trips").document(docId).update(updates).await()
            } catch (e: Exception) {
                // 觀看者可能被規則拒絕；本地狀態已更新，不阻斷操作
                Log.w(tag, "⚠️ tripProgress 同步失敗（本地已保留）：${e.message}")
            }
        }
    }

    private fun pushSet(progressMap: Map<String, Any>) {
        val docId = realDocId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.collection("micro_trips").document(docId)
                    .update("tripProgress", progressMap).await()
            } catch (e: Exception) {
                Log.w(tag, "⚠️ tripProgress 重置同步失敗：${e.message}")
            }
        }
    }

    // ── 本地快取（SharedPreferences JSON；覆蓋安裝後仍在）──────

    private fun persistLocal() {
        val p = _progress.value
        val key = loadedKey ?: progressKey
        try {
            val json = JSONObject().apply {
                put("status", p.status)
                put("startedAt", p.startedAt)
                put("completedAt", p.completedAt)
                put("checkIns", JSONObject().apply {
                    p.checkIns.forEach { (id, r) ->
                        put(id, JSONObject().apply {
                            put("uid", r.uid); put("displayName", r.displayName); put("at", r.at)
                        })
                    }
                })
                put("skips", JSONObject().apply { p.skips.forEach { put(it, true) } })
                put("parking", JSONObject().apply {
                    p.parking.forEach { (id, r) ->
                        put(id, JSONObject().apply {
                            put("lat", r.lat); put("lng", r.lng); put("accuracy", r.accuracy.toDouble())
                            put("note", r.note); put("at", r.at); put("uid", r.uid)
                            put("displayName", r.displayName); put("releasedAt", r.releasedAt)
                        })
                    }
                })
                put("leaveAt", JSONObject().apply {
                    p.leaveAt.forEach { (id, r) ->
                        put(id, JSONObject().apply {
                            put("at", r.at); put("uid", r.uid); put("displayName", r.displayName)
                        })
                    }
                })
            }
            prefs.edit().putString(key, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(tag, "⚠️ tripProgress 本地快取寫入失敗：${e.message}")
        }
    }

    private fun loadFromLocal(key: String): TripProgress {
        val raw = prefs.getString(key, null) ?: return TripProgress()
        return try {
            val json = JSONObject(raw)
            val checkIns = mutableMapOf<String, CheckInRecord>()
            json.optJSONObject("checkIns")?.let { c ->
                c.keys().forEach { id ->
                    val r = c.getJSONObject(id)
                    checkIns[id] = CheckInRecord(
                        uid = r.optString("uid"),
                        displayName = r.optString("displayName", "旅人"),
                        at = r.optLong("at")
                    )
                }
            }
            val skips = mutableSetOf<String>()
            json.optJSONObject("skips")?.keys()?.forEach { skips.add(it) }
            val parking = mutableMapOf<String, ParkingRecord>()
            json.optJSONObject("parking")?.let { pk ->
                pk.keys().forEach { id ->
                    val r = pk.getJSONObject(id)
                    parking[id] = ParkingRecord(
                        lat = r.optDouble("lat"),
                        lng = r.optDouble("lng"),
                        accuracy = r.optDouble("accuracy").toFloat(),
                        note = r.optString("note"),
                        at = r.optLong("at"),
                        uid = r.optString("uid"),
                        displayName = r.optString("displayName", "旅人"),
                        releasedAt = r.optLong("releasedAt")
                    )
                }
            }
            val leaveAt = mutableMapOf<String, LeaveRecord>()
            json.optJSONObject("leaveAt")?.let { la ->
                la.keys().forEach { id ->
                    val r = la.getJSONObject(id)
                    leaveAt[id] = LeaveRecord(
                        at = r.optLong("at"),
                        uid = r.optString("uid"),
                        displayName = r.optString("displayName", "旅人")
                    )
                }
            }
            TripProgress(
                status = json.optString("status"),
                startedAt = json.optLong("startedAt"),
                completedAt = json.optLong("completedAt"),
                checkIns = checkIns,
                skips = skips,
                parking = parking,
                leaveAt = leaveAt
            )
        } catch (e: Exception) {
            TripProgress()
        }
    }

    private fun parseProgress(raw: Map<*, *>?): TripProgress {
        if (raw == null) return TripProgress()
        val checkIns = mutableMapOf<String, CheckInRecord>()
        (raw["checkIns"] as? Map<*, *>)?.forEach { (k, v) ->
            val id = k as? String ?: return@forEach
            val m = v as? Map<*, *> ?: return@forEach
            checkIns[id] = CheckInRecord(
                uid = m["uid"] as? String ?: "",
                displayName = m["displayName"] as? String ?: "旅人",
                at = (m["at"] as? Number)?.toLong() ?: 0L
            )
        }
        val skips = (raw["skips"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            if (v == true) k as? String else null
        }?.toSet() ?: emptySet()
        val parking = mutableMapOf<String, ParkingRecord>()
        (raw["parking"] as? Map<*, *>)?.forEach { (k, v) ->
            val id = k as? String ?: return@forEach
            val m = v as? Map<*, *> ?: return@forEach
            parking[id] = ParkingRecord(
                lat = (m["lat"] as? Number)?.toDouble() ?: 0.0,
                lng = (m["lng"] as? Number)?.toDouble() ?: 0.0,
                accuracy = (m["accuracy"] as? Number)?.toFloat() ?: 0f,
                note = m["note"] as? String ?: "",
                at = (m["at"] as? Number)?.toLong() ?: 0L,
                uid = m["uid"] as? String ?: "",
                displayName = m["displayName"] as? String ?: "旅人",
                releasedAt = (m["releasedAt"] as? Number)?.toLong() ?: 0L
            )
        }
        val leaveAt = mutableMapOf<String, LeaveRecord>()
        (raw["leaveAt"] as? Map<*, *>)?.forEach { (k, v) ->
            val id = k as? String ?: return@forEach
            val m = v as? Map<*, *> ?: return@forEach
            val at = (m["at"] as? Number)?.toLong() ?: return@forEach
            leaveAt[id] = LeaveRecord(
                at = at,
                uid = m["uid"] as? String ?: "",
                displayName = m["displayName"] as? String ?: "旅人"
            )
        }
        return TripProgress(
            status = raw["status"] as? String ?: "",
            startedAt = (raw["startedAt"] as? Number)?.toLong() ?: 0L,
            completedAt = (raw["completedAt"] as? Number)?.toLong() ?: 0L,
            checkIns = checkIns,
            skips = skips,
            parking = parking,
            leaveAt = leaveAt
        )
    }

    override fun onCleared() {
        super.onCleared()
        detach()
    }
}

// ══════════════════════════════════════════════════════════════
// 時間推估（純函式，供時間軸頁與詳細頁共用）
// ══════════════════════════════════════════════════════════════

/** 從 "09:00" 或 "2026/05/18 09:00" 解析為當日分鐘數；失敗回 null */
internal fun parseClockToMinutes(timeStr: String): Int? {
    val t = timeStr.trim().let { if (it.contains(" ")) it.substringAfterLast(" ") else it }
    val parts = t.split(":")
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].take(2).toIntOrNull() ?: return null
    return h * 60 + m
}

/** epoch ms → 當日分鐘數（裝置時區） */
internal fun minutesOfDay(epochMs: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
}

/** 分鐘數 → "HH:mm"（超過 24h 取模） */
internal fun fmtClock(mins: Int): String {
    val m = ((mins % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

/** 時間軸推導結果 */
internal data class TripDerived(
    val playable: List<Stop>,          // 不含車站的景點
    val currentStop: Stop?,            // 第一個未打卡且未跳過的景點
    val doneCount: Int,
    val totalCount: Int,
    val delayMin: Int,                 // 後續站相對原排定的位移（含調過的預計離開）；正=落後，負=超前
    val estimatedTimes: Map<String, Int>, // stopId → 順延後預估抵達（當日分鐘數）；只含未完成站
    val allDone: Boolean,
    val currentDayIndex: Int = 1,      // 目前進行到第幾天（1-based）
    val dayCount: Int = 1,             // 這趟共幾天；1 = 單日行程
    val here: HereStop? = null         // 使用者目前所在的站（最後打卡的景點）
)

/**
 * 目前站：最後一次打卡的景點（打卡＝到達，要等下一站打卡才往前移），
 * 也就是可以調整預計離開時間的那一站。時間都是當日分鐘數。
 */
internal data class HereStop(
    val stop: Stop,
    val arrivedMin: Int,               // 實際到達（打卡時間）
    val defaultLeaveMin: Int,          // 到達 + 原定停留
    val leaveMin: Int,                 // 目前採用的預計離開（有調過就是調過的值）
    val isAdjusted: Boolean            // 使用者是否調過
)

/**
 * 畫面上要顯示成「目前站」的那一站。多日行程過夜後、今天還沒打卡時不算——
 * 調昨天最後一站的離開時間只影響昨天，沒有意義，這時改顯示「今天第一站」。
 */
internal fun TripDerived.activeHere(progress: TripProgress): HereStop? =
    here?.takeIf { progress.isOngoing && (allDone || it.stop.dayIndex == currentDayIndex) }

/**
 * 依打卡實況推導行程狀態與各站順延預估：
 * - delay = 目前所在站的預計離開 − 原排定離開（沒調過離開時間時＝打卡時間 − 排定時間）
 * - 跳過的站把「停留 + 該站之後路段車程」的時間讓給後續站（提前）
 *
 * **多日行程（A5 Stage 3）：落後與省下的時間都不跨夜。**
 * delay 是拿當日分鐘數相減算的，若讓它跨日繼承，昨天晚收工 40 分會讓今天
 * 每一站都平白多 40 分；反過來昨天提早結束也會讓今天的預估過度樂觀。
 * 中間隔了一整晚睡眠，隔天本來就是重新開始，所以錨點不在同一天時一律歸零。
 */
internal fun deriveTripState(
    stops: List<Stop>,
    progress: TripProgress,
    transitTimes: List<Long>
): TripDerived {
    val playable = stops.filter { !it.isStation }
    val doneCount = playable.count { progress.isDone(it.stopId) }
    val currentStop = playable.firstOrNull { !progress.isDone(it.stopId) }
    val allDone = playable.isNotEmpty() && currentStop == null

    // 最後一次打卡（依實際時間）決定 delay 與推估錨點
    val lastCheckIn = progress.checkIns
        .filterKeys { id -> stops.any { it.stopId == id } }
        .maxByOrNull { it.value.at }
    var delay = 0
    var anchorIndex = -1
    var here: HereStop? = null
    if (lastCheckIn != null) {
        val anchorStop = stops.firstOrNull { it.stopId == lastCheckIn.key }
        val scheduled = anchorStop?.let { parseClockToMinutes(it.time) }
        if (anchorStop != null && scheduled != null) {
            // 後續各站的位移 = 預計離開 − 原排定離開。沒調過時預計離開 = 到達 + 原定停留，
            // 位移就等於「到達落後多少」，和原本只看打卡的算法一致
            val arrived = minutesOfDay(lastCheckIn.value.at)
            val defaultLeave = arrived + anchorStop.duration.toInt()
            val adjusted = progress.leaveAt[anchorStop.stopId]?.let { minutesOfDay(it.at) }
                ?.coerceAtLeast(arrived)
            val leave = adjusted ?: defaultLeave
            delay = leave - (scheduled + anchorStop.duration.toInt())
            anchorIndex = stops.indexOf(anchorStop)
            here = HereStop(anchorStop, arrived, defaultLeave, leave, adjusted != null)
        }
    }

    // 多日：錨點與各站的「第幾天」。跨日一律不繼承落後與省下的時間
    val dayCount = stops.maxOfOrNull { it.dayIndex }?.coerceAtLeast(1) ?: 1
    val anchorDay = if (anchorIndex >= 0) stops[anchorIndex].dayIndex else 1
    val currentDayIndex = currentStop?.dayIndex
        ?: playable.lastOrNull()?.dayIndex ?: 1
    fun delayFor(stop: Stop) = if (stop.dayIndex == anchorDay) delay else 0

    // 各未完成站的預估：排定 + delay − 錨點之後被跳過站省下的時間
    val estimates = mutableMapOf<String, Int>()
    var savings = 0
    var savingsDay = anchorDay
    stops.forEachIndexed { i, stop ->
        // 換日 → 昨天跳過的站省下的時間不該讓今天提前
        if (stop.dayIndex != savingsDay) { savings = 0; savingsDay = stop.dayIndex }
        if (i > anchorIndex && !stop.isStation && !progress.isDone(stop.stopId)) {
            val scheduled = parseClockToMinutes(stop.time)
            if (scheduled != null) estimates[stop.stopId] = scheduled + delayFor(stop) - savings
        }
        // 跳過的站（在錨點之後）省下停留 + 其後路段車程，之後的站全部前移
        if (i > anchorIndex && !stop.isStation && stop.stopId in progress.skips) {
            savings += stop.duration.toInt() + (transitTimes.getOrNull(i) ?: 15L).toInt()
        }
    }
    // 回程車站也給預估（讓「預計幾點回到車站」看得到）
    stops.lastOrNull()?.takeIf { it.isStation }?.let { ret ->
        val scheduled = parseClockToMinutes(ret.time)
        if (scheduled != null && anchorIndex >= 0) {
            estimates[ret.stopId] = scheduled + delayFor(ret) - savings
        }
    }

    return TripDerived(
        playable = playable,
        currentStop = currentStop,
        doneCount = doneCount,
        totalCount = playable.size,
        // 顯示用的落後同樣以「今天」為準：昨天的落後過了一夜就不算數
        delayMin = if (anchorDay == currentDayIndex) delay else 0,
        estimatedTimes = estimates,
        allDone = allDone,
        currentDayIndex = currentDayIndex,
        dayCount = dayCount,
        here = here
    )
}
