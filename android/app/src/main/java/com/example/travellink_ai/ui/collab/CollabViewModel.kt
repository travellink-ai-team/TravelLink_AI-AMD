package com.example.travellink_ai.ui.collab

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.CollabIdentityManager
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.CollabMemberInfo
import com.example.travellink_ai.data.model.EditingLock
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.PresenceInfo
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.generateInviteCode
import com.example.travellink_ai.data.model.isWebCollabDoc
import com.example.travellink_ai.data.model.normalizeDaysField
import com.example.travellink_ai.data.model.parseDayIndex
import com.example.travellink_ai.data.model.parseWalkNext
import com.example.travellink_ai.data.model.parseManualMin
import com.example.travellink_ai.data.model.parseIsStation
import com.example.travellink_ai.data.model.sanitizeEmailKey
import com.example.travellink_ai.data.model.stableStopId
import com.example.travellink_ai.data.model.VisualData
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import com.example.travellink_ai.data.repository.RemoteTripMeta
import com.example.travellink_ai.data.repository.NotificationRepository
import com.example.travellink_ai.data.util.IdentityKeys
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class CollabViewModel @Inject constructor(
    private val stateHolder: ItineraryStateHolder,
    private val database: AppDatabase,
    val collabIdentity: CollabIdentityManager,
    private val db: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth,
    private val notificationRepo: NotificationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val tag = "TravelLink_Debug"
    private val itineraryDao = database.itineraryDao()

    val currentUserUid: String get() = firebaseAuth.currentUser?.uid ?: ""
    private val currentUid: String get() = currentUserUid
    private val currentEmail: String get() = firebaseAuth.currentUser?.email ?: ""

    // 目前用戶資訊（joinItinerary/startPresenceHeartbeat 時由外部設入）
    private var currentUserName  = "旅人"
    private var currentUserEmoji = "🌟"

    // ── PIN 本地快取（SharedPreferences，重啟後立刻可讀）──────────
    private val pinPrefs by lazy {
        context.getSharedPreferences("collab_pins", Context.MODE_PRIVATE)
    }
    private fun getCachedPin(docId: String): String? {
        val pin     = pinPrefs.getString("pin_$docId", null) ?: return null
        val expires = pinPrefs.getLong("pin_expires_$docId", 0L)
        return if (expires > System.currentTimeMillis()) pin else null
    }
    private fun cachePin(docId: String, pin: String, expiresAtMs: Long) {
        pinPrefs.edit()
            .putString("pin_$docId", pin)
            .putLong("pin_expires_$docId", expiresAtMs)
            .apply()
    }

    private var itineraryListener: ListenerRegistration? = null
    private var presenceListener: ListenerRegistration? = null
    private var presenceHeartbeatJob: Job? = null
    // 預覽頁專用的獨立 presence（不引入地圖頁 loadItinerary 的行程 listener 副作用）
    private var previewPresenceListener: ListenerRegistration? = null
    private var previewPresenceHeartbeatJob: Job? = null
    private var previewPresenceDocId: String? = null
    private var realCollabDocId: String? = null
        set(value) { field = value; _hasActiveGroup.value = value != null }

    private val _hasActiveGroup = MutableStateFlow(false)
    val hasActiveGroup: StateFlow<Boolean> = _hasActiveGroup.asStateFlow()

    // ── Collab StateFlows ─────────────────────────────────────
    private val _editingLocks = MutableStateFlow<Map<String, EditingLock>>(emptyMap())
    val editingLocks: StateFlow<Map<String, EditingLock>> = _editingLocks.asStateFlow()

    private val _onlineMembers = MutableStateFlow<List<PresenceInfo>>(emptyList())
    val onlineMembers: StateFlow<List<PresenceInfo>> = _onlineMembers.asStateFlow()

    data class GroupProfile(
        val interests: List<String> = emptyList(),
        val pace: String = "平衡",
        val memberCount: Int = 0,
        val budget: String = "",  // 多數決；成員都沒填時為空
        val desiredSpots: List<String> = emptyList()   // 各成員「想去的景點」聯集，生成時列為必排
    )
    private val _groupProfile = MutableStateFlow(GroupProfile())
    val groupProfile: StateFlow<GroupProfile> = _groupProfile.asStateFlow()

    // ── 成員偏好：micro_trips/{id}/member_prefs/{emailKey}（與網頁 collab.js setMemberPrefs 同一處）──
    // 過去 App 只讀寫主文件的 members.{key}.prefs，網頁只讀寫這個子集合，兩端互相看不到
    // 對方成員的興趣、節奏、預算與想去的景點。現在以子集合為準，主文件的 prefs 只當舊資料的退路。
    private var memberPrefsListener: ListenerRegistration? = null
    private var memberPrefsByKey: Map<String, Map<*, *>> = emptyMap()   // emailKey → member_prefs 文件
    private var lastMembersRaw: Map<*, *> = emptyMap<String, Any?>()

    /** 主文件 members 或 member_prefs 任一變動就重算群組偏好 */
    private fun rebuildGroupProfile() {
        val membersRaw = lastMembersRaw
        if (membersRaw.isEmpty()) { _groupProfile.value = GroupProfile(); return }
        val allInterests = mutableSetOf<String>()
        val paceVotes = mutableMapOf<String, Int>()
        val budgetVotes = mutableMapOf<String, Int>()
        val allSpots = linkedSetOf<String>()
        membersRaw.forEach { (k, v) ->
            val mv = v as? Map<*, *> ?: return@forEach
            val mapKey = k as? String ?: return@forEach
            val memberEmail = mv["email"] as? String ?: ""
            // 子集合那筆的 email 要與成員相符才採用（同網頁 loadTrip 的 sameEmail 檢查）
            val sub = (memberPrefsByKey[mapKey]
                ?: memberEmail.takeIf { it.isNotBlank() }?.let { memberPrefsByKey[sanitizeEmailKey(it)] })
                ?.takeIf { (it["email"] as? String).orEmpty().equals(memberEmail, ignoreCase = true) }
            // 退路：網頁建立時寫在主文件 prefs 巢狀 map；舊版 App 是平鋪欄位
            val prefs = sub?.get("prefs") as? Map<*, *> ?: mv["prefs"] as? Map<*, *>
            fun pick(key: String) = if (sub != null) prefs?.get(key) else mv[key] ?: prefs?.get(key)
            val memberInterests = (pick("interests") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            // 舊 App 成員可能存 emoji 前綴的 14 標籤值，正規化成網頁對齊後的 6 標籤再聚合
            allInterests.addAll(com.example.travellink_ai.data.model.normalizeInterests(memberInterests))
            // 正規化節奏值（網頁端與 App 舊版值可能混雜在同一群組）
            val memberPace = com.example.travellink_ai.data.local.normalizePace(pick("pace") as? String)
            paceVotes[memberPace] = (paceVotes[memberPace] ?: 0) + 1
            val memberBudget = (pick("budget") as? String).orEmpty()
            if (memberBudget.isNotBlank()) budgetVotes[memberBudget] = (budgetVotes[memberBudget] ?: 0) + 1
            // 想去的景點：網頁 normalizePrefs 存字串；保險起見陣列也收
            when (val raw = pick("desiredSpots")) {
                is String -> allSpots.addAll(com.example.travellink_ai.ui.planning.WishedSpots.parse(raw))
                is List<*> -> allSpots.addAll(raw.filterIsInstance<String>().map { it.trim() }.filter { it.length >= 2 })
            }
        }
        _groupProfile.value = GroupProfile(
            interests    = allInterests.toList(),
            pace         = paceVotes.maxByOrNull { it.value }?.key ?: "平衡",
            memberCount  = membersRaw.size,
            budget       = budgetVotes.maxByOrNull { it.value }?.key ?: "",
            desiredSpots = allSpots.toList()
        )
    }

    private fun stopMemberPrefsListener() {
        memberPrefsListener?.remove(); memberPrefsListener = null
        memberPrefsByKey = emptyMap()
        lastMembersRaw = emptyMap<String, Any?>()
    }

    // 目前使用者的角色：不在 members map 裡代表是 owner
    private val _myRole = MutableStateFlow("owner")
    val myRole: StateFlow<String> = _myRole.asStateFlow()

    // 成員面板完整清單（含角色 + 在線狀態）
    private val _collabMembers = MutableStateFlow<List<CollabMemberInfo>>(emptyList())
    val collabMembers: StateFlow<List<CollabMemberInfo>> = _collabMembers.asStateFlow()

    // 目前行程已是成員的 email（小寫），供「邀請好友」清單顯示「已加入」
    private val _memberEmails = MutableStateFlow<Set<String>>(emptySet())
    val memberEmails: StateFlow<Set<String>> = _memberEmails.asStateFlow()

    /**
     * 成員目錄：members map 的實際 key ↔ uid ↔ email 對照。
     * App 格式的 key 是 uid，網頁端格式的 key 是 email 消毒字串，
     * 退出／踢人／改角色都要先透過這裡解析出正確的 map key。
     */
    private data class MemberKeyEntry(val mapKey: String, val uid: String, val email: String)
    private var memberDirectory: List<MemberKeyEntry> = emptyList()

    /** 由 uid 或 map key 解析出 members map 的實際 key */
    private fun resolveMemberKey(uidOrKey: String): String =
        memberDirectory.firstOrNull { it.uid == uidOrKey || it.mapKey == uidOrKey }
            ?.mapKey ?: uidOrKey

    /**
     * 依目前的 presence 名單重算 collabMembers 的 isOnline。
     *
     * 不能只比 uid：「邀請好友共編」寫進 members 的項目沒有 uid 欄位（邀請當下不知道對方
     * Firebase uid），解析時會退回 emailKey，而 presence 的 doc id 是真 uid → 永遠對不上，
     * 面板會一直顯示「離線」。因此改成 uid 或 emailKey 任一對上即視為在線。
     *
     * 每次改寫 _collabMembers 之後都要呼叫，否則成員清單載入慢於 presence 快照時會把旗標蓋掉。
     */
    private fun applyOnlineFlags() {
        val presence   = _onlineMembers.value
        val onlineIds  = presence.map { it.uid }.toSet()
        val onlineKeys = presence.mapNotNull { p ->
            p.email.takeIf { it.isNotBlank() }?.let { sanitizeEmailKey(it) }
        }.toSet()
        _collabMembers.value = _collabMembers.value.map { m ->
            val entry   = memberDirectory.firstOrNull { it.uid == m.uid || it.mapKey == m.uid }
            val myKey   = entry?.mapKey ?: m.uid
            val myEmailKey = entry?.email?.takeIf { it.isNotBlank() }?.let { sanitizeEmailKey(it) }
            m.copy(isOnline = m.uid in onlineIds || myKey in onlineKeys || myEmailKey in onlineKeys)
        }
    }

    private fun emailForMemberKey(mapKey: String): String =
        memberDirectory.firstOrNull { it.mapKey == mapKey }?.email ?: ""

    /** 預覽路徑（無 listener）一次性讀到的成員清單所屬 docId，供角色管理寫入時定位文件 */
    private var previewMembersDocId: String? = null

    /**
     * 一次性讀取 members map，供「從本地歷史開啟」的預覽畫面顯示成員面板。
     * 不開 listener（維持 fetchPinForPreview 不觸發地圖重算的設計）；
     * presence 未載入，isOnline 一律 false（面板此路徑僅供名單檢視與角色管理）。
     * 進行中的共編 session（realCollabDocId 相同）由 listener 維護，這裡直接跳過。
     */
    private fun refreshMembersForPreview(docId: String) {
        if (docId == realCollabDocId) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = db.collection("micro_trips").document(docId).get().await()
                if (!doc.exists()) {
                    // 雲端文件已被刪、只剩本地紀錄 → 必須清掉上一場共編的殘留名單，
                    // 否則預覽頁「N 人在線」徽章會誤顯示，presence 也會寫進這個舊 docId
                    withContext(Dispatchers.Main) {
                        if (docId != realCollabDocId) {
                            memberDirectory = emptyList()
                            previewMembersDocId = docId
                            _memberEmails.value  = emptySet()
                            _collabMembers.value = emptyList()
                        }
                    }
                    return@launch
                }
                val membersRaw = doc.get("members") as? Map<*, *> ?: emptyMap<Any, Any>()
                val directory = mutableListOf<MemberKeyEntry>()
                val list = mutableListOf<CollabMemberInfo>()
                membersRaw.forEach { (k, v) ->
                    val mv = v as? Map<*, *> ?: return@forEach
                    val mapKey = k as? String ?: return@forEach
                    val uid = mv["uid"] as? String ?: mapKey
                    directory.add(MemberKeyEntry(mapKey, uid, mv["email"] as? String ?: ""))
                    list.add(CollabMemberInfo(
                        uid         = uid,
                        displayName = mv["displayName"] as? String ?: mv["name"] as? String ?: "旅人",
                        emoji       = mv["emoji"] as? String ?: "🌟",
                        role        = mv["role"] as? String ?: "viewer",
                        isOnline    = false
                    ))
                }
                withContext(Dispatchers.Main) {
                    // 再次確認 listener 沒有在這期間接手同一份文件
                    if (docId != realCollabDocId) {
                        memberDirectory = directory
                        previewMembersDocId = docId
                        _memberEmails.value = directory
                            .mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }.toSet()
                        _collabMembers.value = list.sortedWith(
                            compareBy({ if (it.role == "owner") 0 else 1 }, { it.uid })
                        )
                        // 名單可能晚於 presence 快照抵達，補算一次以免 isOnline 被蓋成 false
                        applyOnlineFlags()
                        Log.d(tag, "👥 [Preview] members 一次性載入：${list.size} 位（$docId）")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "⚠️ [Preview] members 讀取失敗：${e.message}")
            }
        }
    }

    private val _isCollabMode = MutableStateFlow(false)
    val isCollabMode: StateFlow<Boolean> = _isCollabMode.asStateFlow()

    private val _pendingJoinDocId = MutableStateFlow<String?>(null)
    val pendingJoinDocId: StateFlow<String?> = _pendingJoinDocId.asStateFlow()

    private val _joinPin = MutableStateFlow("")
    val joinPin: StateFlow<String> = _joinPin.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    // 委派 StateHolder
    val remoteHistory: StateFlow<List<LocalItinerary>> = stateHolder.remoteHistory.asStateFlow()
    val remoteTripMeta: StateFlow<Map<String, RemoteTripMeta>> = stateHolder.remoteTripMeta.asStateFlow()
    val isLoadingRemoteHistory: StateFlow<Boolean> = stateHolder.isLoadingRemoteHistory.asStateFlow()
    val collabDocIds: StateFlow<Set<String>> = stateHolder.collabDocIds.asStateFlow()

    init {
        // 當 ItineraryViewModel 生成新行程並寫入 Firestore 後，ownerJoinPin 會更新
        // 在 Firestore listener 尚未啟動時，直接用此 PIN 顯示給 owner
        viewModelScope.launch {
            stateHolder.ownerJoinPin.collect { pin ->
                if (pin.isNotBlank()) _joinPin.value = pin
            }
        }
    }

    /**
     * 專供 ItineraryPreviewScreen 使用的輕量 PIN 取得函式。
     * 只做三層查找（SharedPreferences → Room → Firestore 一次性 get），
     * 不設定 Snapshot Listener，不觸發 triggerMapReload，
     * 避免意外引發 loadMapData / recalculateStopTimes 導致營業時間衝突通知重複產生。
     */
    fun fetchPinForPreview(docId: String) {
        if (docId.isBlank() || docId == "current") return

        // 成員面板入口改為依「成員數」常駐顯示：預覽路徑也需要成員清單
        // （PIN 快取命中會提前 return，所以 members 載入獨立於 PIN 流程）
        refreshMembersForPreview(docId)

        // 預覽的不是進行中的共編文件（無 listener session）→ 這是自己本地/歷史的行程，
        // 重置前一場共編殘留的角色，否則 owner 開自己的新行程會看到「觀看者」橫幅
        if (docId != realCollabDocId && _myRole.value != "owner") {
            Log.d(tag, "👤 [Preview] 非共編 session（$docId），重置殘留角色 ${_myRole.value} → owner")
            _myRole.value = "owner"
        }

        // ① SharedPreferences 快取（同步，立即）
        val cached = getCachedPin(docId)
        if (cached != null) {
            _joinPin.value = cached
            Log.d(tag, "🔑 [Preview] PIN 從 SharedPreferences 恢復：$cached")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // ② Room 備援
            val record = itineraryDao.getItineraryByFirestoreDocId(docId)
            val roomPin     = record?.joinPin
            val roomExpires = record?.joinPinExpiresAt ?: 0L
            if (!roomPin.isNullOrBlank() && roomExpires > System.currentTimeMillis()) {
                Log.d(tag, "🔑 [Preview] PIN 從 Room 恢復：$roomPin")
                withContext(Dispatchers.Main) {
                    if (_joinPin.value.isBlank()) _joinPin.value = roomPin
                }
                return@launch
            }

            // ③ Firestore 一次性 get（非 Listener，不觸發任何地圖更新）
            try {
                val doc = db.collection("micro_trips").document(docId).get().await()
                if (!doc.exists()) return@launch
                val pinStr     = doc.getString("joinPin") ?: ""
                val pinExpires = doc.getTimestamp("pinExpiresAt")?.toDate()?.time ?: 0L
                val inviteCode = doc.getString("inviteCode") ?: ""
                val ownerUid   = doc.getString("ownerUid") ?: doc.getString("createdBy") ?: ""
                val myUid      = currentUid
                if (pinStr.isNotBlank() && pinExpires > System.currentTimeMillis()) {
                    cachePin(docId, pinStr, pinExpires)
                    withContext(Dispatchers.Main) {
                        if (_joinPin.value.isBlank()) _joinPin.value = pinStr
                    }
                    Log.d(tag, "🔑 [Preview] PIN 從 Firestore 恢復：$pinStr")
                } else if (inviteCode.isNotBlank()) {
                    // 網頁端文件：顯示 inviteCode，不生成 joinPin 寫回
                    withContext(Dispatchers.Main) {
                        if (_joinPin.value.isBlank()) _joinPin.value = inviteCode
                    }
                    Log.d(tag, "🔑 [Preview] 網頁端 inviteCode：$inviteCode")
                } else if (myUid == ownerUid) {
                    // 無邀請碼的舊 App 文件（或 PIN 已過期）且本裝置是 Owner
                    // → 補發長期有效的邀請碼（統一分享機制，不再發 6 位 PIN）
                    val newCode = issueInviteCode(docId)
                    if (newCode != null) {
                        withContext(Dispatchers.Main) { _joinPin.value = newCode }
                        Log.d(tag, "🔑 [Preview] 邀請碼補發：$newCode")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "⚠️ [Preview] fetchPinForPreview 失敗：${e.message}")
            }
        }
    }

    // ── 編輯鎖 ────────────────────────────────────────────────

    suspend fun acquireEditingLock(stopId: String): Boolean {
        val docId = realCollabDocId ?: return true
        val myUid = currentUid
        val existing = _editingLocks.value[stopId]
        if (existing != null && existing.uid != myUid && !existing.isExpired) return false
        return try {
            val expiresAt = Timestamp(Date(System.currentTimeMillis() + 60_000L))
            val lockData = mapOf(
                "uid"         to myUid,
                "displayName" to currentUserName,
                "expiresAt"   to expiresAt
            )
            db.collection("micro_trips").document(docId)
                .update("editingLocks.$stopId", lockData).await()
            true
        } catch (e: Exception) {
            Log.w(tag, "⚠️ 取得鎖失敗（非致命）: ${e.message}")
            true
        }
    }

    fun releaseEditingLock(stopId: String) {
        val docId = realCollabDocId ?: return
        db.collection("micro_trips").document(docId)
            .update("editingLocks.$stopId", FieldValue.delete())
            .addOnFailureListener { Log.w(tag, "⚠️ 釋放鎖失敗：${it.message}") }
    }

    fun updateMemberRole(uid: String, newRole: String) {
        // 預覽路徑（無 listener）用一次性載入時記下的 docId
        val docId = realCollabDocId ?: previewMembersDocId ?: return
        val key = resolveMemberKey(uid)
        val updates = mutableMapOf<String, Any>("members.$key.role" to newRole)
        // 線上 Firestore 規則以 editorEmails 陣列判定編輯權（isEditor），
        // 只改 members.role 的話被升級者實際上仍無法寫入行程，必須同步維護
        val email = emailForMemberKey(key)
        if (email.isNotBlank()) {
            updates["editorEmails"] =
                if (newRole == "editor") FieldValue.arrayUnion(email)
                else FieldValue.arrayRemove(email)
        }
        db.collection("micro_trips").document(docId)
            .update(updates)
            .addOnFailureListener { Log.w(tag, "⚠️ 更新角色失敗：${it.message}") }
        // 樂觀更新本地清單：預覽路徑沒有 listener echo，面板要立即反映新角色
        _collabMembers.value = _collabMembers.value.map {
            if (it.uid == uid) it.copy(role = newRole) else it
        }
    }

    // ── 邀請好友加入共編（owner 直接把好友加成 viewer 成員；通知在下一步接上）──
    private val _inviteFriendResult = MutableStateFlow<String?>(null)
    val inviteFriendResult: StateFlow<String?> = _inviteFriendResult.asStateFlow()
    fun clearInviteFriendResult() { _inviteFriendResult.value = null }

    /**
     * owner 邀請好友加入本行程共編。兩步：
     *  1) 把好友 email 加進 members(role=viewer)＋memberEmails，讓對方通過線上規則、有權開啟行程。
     *  2) 發 collab_invite 站內通知（nid=['collab_invite', tripId, 我的 identityKey]，同人同行程重送會撞同 doc→靜默）。
     * 通知失敗（rules 未部署／已存在）不影響加入結果，只是對方少一則提示。
     */
    fun inviteFriendToCollab(friendEmail: String, friendName: String, tripTitle: String) {
        val docId = realCollabDocId ?: previewMembersDocId ?: run {
            _inviteFriendResult.value = "行程尚未儲存，無法邀請好友"
            return
        }
        val email = friendEmail.trim()
        if (email.isBlank()) { _inviteFriendResult.value = "好友 email 無效"; return }
        if (email.equals(currentEmail, ignoreCase = true)) {
            _inviteFriendResult.value = "不能邀請自己"; return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val emailKey = sanitizeEmailKey(email)
            val memberData = mapOf(
                "email"       to email,
                "name"        to friendName.ifBlank { email },
                "joinedAt"    to System.currentTimeMillis(),
                "role"        to "viewer",
                "ready"       to false,
                "invitedBy"   to currentEmail,
                "displayName" to friendName.ifBlank { email },
                "emoji"       to "🌟"
            )
            try {
                db.collection("micro_trips").document(docId).update(
                    mapOf(
                        "members.$emailKey" to memberData,
                        "memberEmails"      to FieldValue.arrayUnion(email)
                    )
                ).await()
            } catch (e: Exception) {
                Log.e(tag, "❌ 邀請好友 memberEmails 寫入失敗：${e.message}")
                _inviteFriendResult.value = "❌ 邀請失敗：${e.message}"
                return@launch
            }
            // 發 collab_invite 通知（best-effort）
            val nid = notificationRepo.nid(
                listOf("collab_invite", docId, IdentityKeys.identityKey(currentEmail))
            )
            val notified = notificationRepo.push(
                toEmail = email,
                id = nid,
                type = "collab_invite",
                fromName = currentUserName,
                tripId = docId,
                // 新群組行程尚未命名時 title 為空，通知會顯示「邀請你共編「」」→ 補預設名
                tripTitle = tripTitle.ifBlank { "未命名共編行程" }
            )
            Log.d(tag, "✅ 已邀請好友 $email 共編 $docId（通知寫入=$notified, nid=$nid）")
            _memberEmails.value = _memberEmails.value + email.lowercase()
            _inviteFriendResult.value = "✅ 已邀請 ${friendName.ifBlank { email }} 加入共編"
        }
    }

    /** 退出共編（非 owner 自行離開）：從 members map 移除自己 */
    fun leaveCollab(onComplete: () -> Unit) {
        val docId = realCollabDocId ?: return
        val myUid = currentUid
        // 網頁端群組裡自己的 key 是 email 消毒字串，先用 uid → 再用 email 對出來
        val myKey = memberDirectory.firstOrNull {
            it.uid == myUid ||
                (currentEmail.isNotBlank() && it.email.equals(currentEmail, ignoreCase = true))
        }?.mapKey ?: myUid
        val myEmail = emailForMemberKey(myKey).ifBlank { currentEmail }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updates = mutableMapOf<String, Any>(
                    "members.$myKey" to FieldValue.delete(),
                    "memberUids"     to FieldValue.arrayRemove(myUid)
                )
                if (myEmail.isNotBlank())
                    updates["memberEmails"] = FieldValue.arrayRemove(myEmail)
                db.collection("micro_trips").document(docId).update(updates).await()
                Log.d(tag, "✅ 已退出共編：$docId")
                withContext(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                Log.e(tag, "❌ 退出共編失敗：${e.message}")
            }
        }
    }

    /** 踢出成員（owner 專用）：從 members map 移除指定成員 */
    fun removeMember(targetUid: String) {
        val docId = realCollabDocId ?: previewMembersDocId ?: return
        val targetKey = resolveMemberKey(targetUid)
        val targetEmail = emailForMemberKey(targetKey)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updates = mutableMapOf<String, Any>(
                    "members.$targetKey" to FieldValue.delete(),
                    "memberUids"         to FieldValue.arrayRemove(targetUid)
                )
                if (targetEmail.isNotBlank())
                    updates["memberEmails"] = FieldValue.arrayRemove(targetEmail)
                db.collection("micro_trips").document(docId).update(updates).await()
                // 樂觀更新本地清單（預覽路徑沒有 listener echo）
                withContext(Dispatchers.Main) {
                    _collabMembers.value = _collabMembers.value.filterNot { it.uid == targetUid }
                }
                Log.d(tag, "✅ 已移除成員：$targetKey")
            } catch (e: Exception) {
                Log.e(tag, "❌ 移除成員失敗：${e.message}")
            }
        }
    }

    // ── 分享 / 加入 ───────────────────────────────────────────

    fun getShareLink(): String {
        val docId = realCollabDocId ?: stateHolder.currentFirestoreDocId.value ?: return ""
        return "travellink://join/$docId"
    }

    fun setPendingJoinDocId(docId: String) { _pendingJoinDocId.value = docId }
    fun clearPendingJoinDocId() { _pendingJoinDocId.value = null }
    fun clearJoinError() { _joinError.value = null }

    // ── 邀請碼（App 全行程統一使用，取代舊 6 位 PIN）─────────────

    /**
     * 邀請碼註冊到 invites 表。網頁 joinByCode 查「正規化碼」（去連字號大寫）；
     * 線上 Firestore 規則的加入分支卻用「原始 inviteCode」（帶連字號）查
     * exists(/invites/..)，所以兩種 id 都要註冊，缺一邊就會 PERMISSION_DENIED。
     */
    private suspend fun registerInvite(docId: String, inviteCode: String) {
        val inviteData = mapOf(
            "tripId"    to docId,
            "active"    to true,
            "createdBy" to currentEmail.ifBlank { null },
            "createdAt" to Timestamp.now()
        )
        listOf(
            com.example.travellink_ai.data.model.normalizeInviteCode(inviteCode),
            inviteCode
        ).distinct().forEach { codeId ->
            db.collection("invites").document(codeId).set(inviteData).await()
        }
    }

    /** listener 補發邀請碼進行中旗標：多次 snapshot 觸發時避免重複補發 */
    private var inviteCodeIssueInFlight = false

    /** 為沒有邀請碼的舊 App 文件補發邀請碼（寫回文件 + 註冊 invites），回傳新碼 */
    private suspend fun issueInviteCode(docId: String): String? {
        return try {
            val code = com.example.travellink_ai.data.model.generateInviteCode()
            db.collection("micro_trips").document(docId).update("inviteCode", code).await()
            registerInvite(docId, code)
            Log.d(tag, "🔑 已補發邀請碼：$code（$docId）")
            code
        } catch (e: Exception) {
            Log.w(tag, "⚠️ 邀請碼補發失敗：${e.message}")
            null
        }
    }

    // ── 網頁端格式群組建立（先建群組 → 填偏好 → 再生成）──────────

    private fun generateShareToken(): String =
        (1..20).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")

    /** 本次 session 由 createCollabGroup 建立的群組 docId（避免重複建立）*/
    private var groupSetupDocId: String? = null

    /**
     * 建立網頁端格式的共編群組文件（無 stops，status=planning），
     * 建立後啟動即時監聽並回傳 docId。若本 session 已有進行中的群組則直接重用。
     */
    fun createCollabGroup(name: String, emoji: String, onReady: (String) -> Unit) {
        val myUid = currentUid
        val myEmail = currentEmail
        currentUserName  = name
        currentUserEmoji = emoji
        viewModelScope.launch(Dispatchers.IO) {
            // ── 重用：以 Firestore 為準確認上一團「仍存在且還沒生成行程」──────────
            // 用意是避免使用者進 group_setup 又返回、再點一次時留下多份空群組文件。
            // 判準與 openCollabTrip / joinItinerary 的導向邏輯一致（stops 空 = 還沒生成）。
            //
            // 刻意不用記憶體狀態判斷：stateHolder.itinerary 會被上一份行程、或尚未抵達的
            // snapshot 影響而不可靠（誤判成「已生成」就會每點一次多開一團空的）；
            // groupSetupDocId 指向的文件也可能已被 abandonGroupSetupIfEmpty 刪掉。
            // 只有在確實有前一團時才多讀這一次 Firestore。
            groupSetupDocId?.let { existing ->
                val reusable = try {
                    val snap = db.collection("micro_trips").document(existing).get().await()
                    snap.exists() && (snap.get("stops") as? List<*>).isNullOrEmpty()
                } catch (e: Exception) {
                    Log.w(tag, "⚠️ 舊群組狀態查詢失敗，改建新群組：${e.message}")
                    false
                }
                if (reusable) {
                    Log.d(tag, "♻️ 重用尚未生成行程的群組：$existing")
                    withContext(Dispatchers.Main) {
                        stateHolder.currentFirestoreDocId.value = existing
                        loadItinerary(existing, name, emoji)
                        onReady(existing)
                    }
                    return@launch
                }
                groupSetupDocId = null   // 已生成行程或已不存在 → 放掉，往下建新的
            }
            try {
                val localPrefs = com.example.travellink_ai.data.local.UserPreferencesManager(context)
                val interests = localPrefs.interests.toList()
                val pace      = localPrefs.pace
                val docId      = "my_${System.currentTimeMillis()}"
                val inviteCode = generateInviteCode()
                val memberKey  = if (myEmail.isNotBlank()) sanitizeEmailKey(myEmail) else myUid
                val member = mapOf(
                    "email"    to myEmail,
                    "name"     to name,
                    "joinedAt" to System.currentTimeMillis(),
                    "role"     to "owner",
                    "ready"    to false,
                    "prefs"    to mapOf(
                        "interests"    to interests,
                        "pace"         to pace,
                        "budget"       to "",
                        "avoid"        to "",
                        "avoidTags"    to emptyList<String>(),
                        "desiredSpots" to ""
                    ),
                    // App 端輔助欄位
                    "uid"         to myUid,
                    "displayName" to name,
                    "emoji"       to emoji,
                    "interests"   to interests,
                    "pace"        to pace
                )
                val docMap = hashMapOf<String, Any?>(
                    "id"              to docId,
                    "title"           to "未命名共編行程",
                    "region"          to "台東",
                    "days"            to "8小時",
                    "people"          to "1人",
                    "status"          to "planning",
                    "collab"          to true,
                    "tripMode"        to "collab",
                    "guestReadable"   to true,
                    "maxMembers"      to 10,
                    "inviteCode"      to inviteCode,
                    "shareToken"      to generateShareToken(),
                    "ownerUid"        to myUid,
                    "ownerEmail"      to myEmail,
                    "ownerName"       to name,
                    "userEmail"       to myEmail,
                    "memberEmails"    to if (myEmail.isNotBlank()) listOf(myEmail) else emptyList(),
                    "editorEmails"    to emptyList<String>(),   // 線上規則以此陣列判定編輯權，與網頁 createSharedTrip 對齊
                    "members"         to mapOf(memberKey to member),
                    "stops"           to emptyList<Any>(),
                    "collabCreatedAt" to Timestamp.now(),
                    "createdAt"       to Timestamp.now(),
                    "updatedAt"       to Timestamp.now()
                )
                db.collection("micro_trips").document(docId).set(docMap).await()
                // 對齊網頁 collab.js createSharedTrip：邀請碼必須同步註冊到 invites 表
                registerInvite(docId, inviteCode)
                Log.d(tag, "✅ 共編群組已建立: $docId（邀請碼: $inviteCode，invites 雙 id 註冊完成）")
                withContext(Dispatchers.Main) {
                    groupSetupDocId = docId
                    stateHolder.currentFirestoreDocId.value = docId
                    _joinPin.value = inviteCode
                    loadItinerary(docId, name, emoji)
                    onReady(docId)
                }
            } catch (e: Exception) {
                Log.e(tag, "❌ 共編群組建立失敗: ${e.message}")
            }
        }
    }

    /**
     * 刪除一份群組文件與它的邀請碼註冊。
     * 先刪 invites 再刪本體：反過來的話中途失敗會留下指向不存在行程的邀請碼。
     */
    private suspend fun deleteGroupDoc(docId: String, inviteCode: String?) {
        inviteCode?.takeIf { it.isNotBlank() }?.let { code ->
            for (id in listOf(
                com.example.travellink_ai.data.model.normalizeInviteCode(code), code
            ).distinct()) {
                try { db.collection("invites").document(id).delete().await() }
                catch (e: Exception) { Log.w(tag, "⚠️ invites/$id 刪除失敗：${e.message}") }
            }
        }
        db.collection("micro_trips").document(docId).delete().await()
        stateHolder.recentlyDeletedDocIds.add(docId)
    }

    /**
     * 離開群組設定頁時呼叫：這一團若從頭到尾沒生成行程、也沒有其他人加入，就直接刪掉，
     * 不要留一張「未命名共編行程」的空卡片在歷史頁（cleanupAbandonedGroups 的 24 小時
     * 掃描只作為 App 被強制關閉等情況的補網）。
     *
     * 以 Firestore 現況為準判斷，避免記憶體狀態落後造成誤刪；
     * 加入者（非 owner）與已有成員加入的群組一律不動。
     */
    fun abandonGroupSetupIfEmpty() {
        val docId = groupSetupDocId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = db.collection("micro_trips").document(docId).get().await()
                if (!snap.exists()) { groupSetupDocId = null; return@launch }
                val isOwner     = snap.getString("ownerUid") == currentUid
                val stopsEmpty  = (snap.get("stops") as? List<*>).isNullOrEmpty()
                val memberCount = (snap.get("members") as? Map<*, *>)?.size ?: 0
                val emailCount  = (snap.get("memberEmails") as? List<*>)?.size ?: 0
                if (!isOwner || !stopsEmpty || memberCount > 1 || emailCount > 1) {
                    Log.d(tag, "↩️ 群組 $docId 不符合放棄條件，保留（owner=$isOwner, stopsEmpty=$stopsEmpty, members=$memberCount）")
                    return@launch
                }
                deleteGroupDoc(docId, snap.getString("inviteCode"))
                Log.d(tag, "🧹 離開設定頁未生成行程，已刪除空群組：$docId")
                withContext(Dispatchers.Main) {
                    if (groupSetupDocId == docId) groupSetupDocId = null
                    // listener 還掛在已刪除的文件上會持續收到 null snapshot，一併收掉
                    if (realCollabDocId == docId) {
                        itineraryListener?.remove(); itineraryListener = null
                        presenceListener?.remove(); stopMemberPrefsListener()
                        presenceHeartbeatJob?.cancel()
                        realCollabDocId = null
                        stateHolder.currentFirestoreDocId.value = null
                        stateHolder.itinerary.value = null
                        _joinPin.value = ""
                    }
                    stateHolder.remoteHistory.value =
                        stateHolder.remoteHistory.value.filterNot { it.firestoreDocId == docId }
                }
            } catch (e: Exception) {
                Log.w(tag, "⚠️ 空群組刪除失敗（非致命）：${e.message}")
            }
        }
    }

    /**
     * 儲存我的偏好到 member_prefs/{emailKey}（與網頁 setMemberPrefs 同一處，兩端都讀這裡）。
     * 主文件 members.{myKey} 的 prefs 與平鋪欄位照寫一份，給還沒更新的舊版 App 讀。
     */
    fun saveMyPrefs(interests: List<String>, pace: String, budget: String, desiredSpots: String) {
        val docId = realCollabDocId ?: return
        val myUid = currentUid
        val myKey = memberDirectory.firstOrNull {
            it.uid == myUid ||
                (currentEmail.isNotBlank() && it.email.equals(currentEmail, ignoreCase = true))
        }?.mapKey ?: if (currentEmail.isNotBlank()) sanitizeEmailKey(currentEmail) else myUid
        // 欄位與網頁 normalizePrefs 相同；desiredSpots 一律字串
        val prefs = mapOf(
            "interests"    to interests,
            "pace"         to pace,
            "budget"       to budget,
            "avoid"        to "",
            "avoidTags"    to emptyList<String>(),
            "desiredSpots" to desiredSpots
        )
        viewModelScope.launch(Dispatchers.IO) {
            // 規則要求 email 與登入帳號相符；沒有 email 就只能寫主文件
            if (currentEmail.isNotBlank()) try {
                db.collection("micro_trips").document(docId)
                    .collection("member_prefs").document(sanitizeEmailKey(currentEmail))
                    .set(mapOf(
                        "email"     to currentEmail,
                        "prefs"     to prefs,
                        "ready"     to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ), com.google.firebase.firestore.SetOptions.merge()).await()
                Log.d(tag, "✅ 我的偏好已存到 member_prefs（${sanitizeEmailKey(currentEmail)}）")
            } catch (e: Exception) {
                Log.e(tag, "❌ member_prefs 儲存失敗: ${e.message}")
            }
            try {
                db.collection("micro_trips").document(docId).update(
                    mapOf(
                        "members.$myKey.prefs" to prefs,
                        "members.$myKey.ready"     to true,
                        "members.$myKey.interests" to interests,
                        "members.$myKey.pace"      to pace,
                        "members.$myKey.budget"    to budget,
                        "updatedAt"                to Timestamp.now()
                    )
                ).await()
                Log.d(tag, "✅ 我的偏好已儲存（$myKey）")
            } catch (e: Exception) {
                Log.e(tag, "❌ 偏好儲存失敗: ${e.message}")
            }
        }
    }

    fun joinItinerary(docId: String, name: String, emoji: String) {
        val myUid = currentUid
        currentUserName  = name
        currentUserEmoji = emoji
        viewModelScope.launch(Dispatchers.IO) {
            // 從本地 SharedPreferences 讀取偏好（不再依賴 Firestore profile）
            val localPrefs = com.example.travellink_ai.data.local.UserPreferencesManager(context)
            val userInterests: List<String> = localPrefs.interests.toList()
            val userPace = localPrefs.pace

            // 先讀目標文件，判斷是 App 格式（uid key）還是網頁端格式（email key + prefs 巢狀），
            // 用對方的格式寫入成員資料，否則網頁端看不到這位成員
            val targetDoc = try {
                db.collection("micro_trips").document(docId).get().await()
            } catch (e: Exception) { null }
            val myEmail = currentEmail
            // 已是成員（重新加入）→ 跳過寫入。線上規則的「加入」分支要求 memberEmails
            // 恰好 +1，重複寫入會被 PERMISSION_DENIED
            val alreadyMember = myEmail.isNotBlank() &&
                ((targetDoc?.get("memberEmails") as? List<*>)?.contains(myEmail) == true)

            var joinWriteError: String? = null
            if (!alreadyMember) {
                if (myEmail.isNotBlank()) {
                    // 統一用網頁格式（email key ＋ memberEmails）寫入，App／網頁文件皆適用：
                    // 線上規則的加入分支只允許同時動 members＋memberEmails 兩個欄位，
                    // 所以這裡不能再碰 memberUids
                    val emailKey = sanitizeEmailKey(myEmail)
                    val memberData = mapOf(
                        "email"    to myEmail,
                        "name"     to name,
                        "joinedAt" to System.currentTimeMillis(),   // 網頁端用 epoch ms
                        "role"     to "viewer",
                        "ready"    to true,   // 偏好已隨加入一併提供
                        "prefs"    to mapOf(
                            "interests"    to userInterests,
                            "pace"         to userPace,
                            "budget"       to "",
                            "avoid"        to "",
                            "avoidTags"    to emptyList<String>(),
                            "desiredSpots" to "",
                            "ready"        to true
                        ),
                        // App 端輔助欄位（網頁端忽略）：presence 在線比對與身分識別用
                        "uid"         to myUid,
                        "displayName" to name,
                        "emoji"       to emoji,
                        "interests"   to userInterests,
                        "pace"        to userPace
                    )
                    try {
                        db.collection("micro_trips").document(docId).update(
                            mapOf(
                                "members.$emailKey" to memberData,
                                "memberEmails"      to FieldValue.arrayUnion(myEmail)
                            )
                        ).await()
                    } catch (e: Exception) {
                        Log.e(tag, "❌ 加入共編失敗: ${e.message}")
                        joinWriteError = e.message ?: "未知錯誤"
                    }
                } else {
                    // 帳號無 email 的舊路徑（uid key）
                    val memberData = mapOf(
                        "uid"         to myUid,
                        "displayName" to name,
                        "emoji"       to emoji,
                        "role"        to "viewer",
                        "joinedAt"    to Timestamp.now(),
                        "interests"   to userInterests,
                        "pace"        to userPace
                    )
                    try {
                        db.collection("micro_trips").document(docId).update(
                            mapOf(
                                "members.$myUid" to memberData,
                                "memberUids"     to FieldValue.arrayUnion(myUid)
                            )
                        ).await()
                    } catch (e: Exception) {
                        Log.e(tag, "❌ 加入共編失敗: ${e.message}")
                        joinWriteError = e.message ?: "未知錯誤"
                    }
                }
            }

            // 寫入失敗（多半是 Firestore 規則 PERMISSION_DENIED）→ 浮出錯誤、不進入行程，
            // 避免「看似加入成功但成員清單沒有自己」的誤導狀態
            if (joinWriteError != null) {
                withContext(Dispatchers.Main) {
                    _joinError.value = if (joinWriteError.contains("PERMISSION_DENIED"))
                        "加入失敗：資料庫拒絕寫入成員資料，請確認邀請碼仍有效，或請擁有者檢查安全規則"
                    else "加入失敗：$joinWriteError"
                }
                return@launch
            }
            _pendingJoinDocId.value = null
            // 立即寫入 presence，讓 owner 端第一時間看到新成員在線
            try {
                db.collection("micro_trips").document(docId)
                    .collection("presence").document(myUid)
                    .set(mapOf(
                        "displayName" to name,
                        "emoji"       to emoji,
                        "lastSeen"    to Timestamp.now()
                    )).await()
            } catch (e: Exception) { /* 非致命 */ }
            // 導向：群組尚無景點（籌備中，網頁剛建立的群組可能連 status 欄位都沒有）
            // → 群組設定頁填偏好；已有插圖 → 地圖/插圖頁；否則 → 行程編輯頁
            val targetScreen = try {
                val snap = db.collection("micro_trips").document(docId).get().await()
                val imageUrl = snap.getString("imageUrl") ?: ""
                val stopsEmpty = (snap.get("stops") as? List<*>).isNullOrEmpty()
                val tripOngoing = ((snap.get("tripProgress") as? Map<*, *>)
                    ?.get("status") as? String) == "ongoing"
                when {
                    stopsEmpty  -> "group_setup"
                    tripOngoing -> "trip_progress"   // 行程進行中 → 直接進時間軸（W3）
                    imageUrl.startsWith("http") || imageUrl.startsWith("file://") -> "result"
                    else -> "preview"
                }
            } catch (e: Exception) { "preview" }
            withContext(Dispatchers.Main) {
                loadItinerary(docId)
                stateHolder.currentScreen = targetScreen
            }
        }
    }

    fun joinItineraryByPin(pin: String, name: String, emoji: String) {
        _joinError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = pin.trim()
                var tripDocId: String? = null

                // ① 主路徑：invites/{code} 索引表（發碼時 registerInvite 以「正規化碼」
                //    與「原始碼（帶連字號）」雙格式註冊）。線上規則允許以 doc id 直接 get，
                //    但「不」允許非成員對 micro_trips 做 whereEqualTo 查詢（會 PERMISSION_DENIED），
                //    所以查詢式路徑只能當離線/舊文件的備援
                val normalized = com.example.travellink_ai.data.model.normalizeInviteCode(input)
                val dashCandidate = if (normalized.length == 8)
                    "${normalized.take(4)}-${normalized.drop(4)}" else null
                for (codeId in listOfNotNull(normalized, dashCandidate, input.uppercase()).distinct()) {
                    try {
                        val inv = db.collection("invites").document(codeId).get().await()
                        if (inv.exists() && inv.getBoolean("active") != false) {
                            tripDocId = inv.getString("tripId")?.takeIf { it.isNotBlank() }
                            if (tripDocId != null) break
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ invites/$codeId 查詢失敗：${e.message}")
                    }
                }

                // ② 備援：舊 6 位 joinPin / 未註冊 invites 的舊文件（collection 查詢，
                //    線上規則多半拒絕，故各自 try-catch 靜默略過）
                if (tripDocId == null) {
                    val now = System.currentTimeMillis()
                    try {
                        val pinResult = db.collection("micro_trips")
                            .whereEqualTo("joinPin", input)
                            .get().await()
                        tripDocId = pinResult.documents.firstOrNull { snap ->
                            val exp = snap.getTimestamp("pinExpiresAt")?.toDate()?.time ?: 0L
                            exp > now
                        }?.id
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ joinPin 查詢被拒（線上規則不允許，略過）：${e.message}")
                    }
                }
                if (tripDocId == null) {
                    val code = input.uppercase()
                    val candidates = if (code.length == 8 && '-' !in code)
                        listOf(code, "${code.take(4)}-${code.drop(4)}")
                    else listOf(code)
                    for (c in candidates) {
                        try {
                            val r = db.collection("micro_trips")
                                .whereEqualTo("inviteCode", c)
                                .get().await()
                            tripDocId = r.documents.firstOrNull()?.id
                            if (tripDocId != null) break
                        } catch (e: Exception) {
                            Log.w(tag, "⚠️ inviteCode 查詢被拒（線上規則不允許，略過）：${e.message}")
                        }
                    }
                }

                val found = tripDocId
                if (found == null) {
                    withContext(Dispatchers.Main) { _joinError.value = "密碼無效或已過期，請確認後重試" }
                    return@launch
                }
                withContext(Dispatchers.Main) { joinItinerary(found, name, emoji) }
            } catch (e: Exception) {
                Log.e(tag, "❌ joinItineraryByPin 失敗：${e.message}")
                withContext(Dispatchers.Main) { _joinError.value = "查詢失敗，請稍後再試" }
            }
        }
    }

    // ── Firestore Snapshot Listener ────────────────────────────

    fun loadItinerary(docId: String, userName: String = currentUserName, userEmoji: String = currentUserEmoji) {
        currentUserName  = userName
        currentUserEmoji = userEmoji
        if (docId == "current") {
            Log.d(tag, "🔄 current 行程但無真實 docId，跳過載入")
            return
        }
        // realCollabDocId 是 listener 實際監聽的 docId；
        // stateHolder.currentFirestoreDocId 可能被 loadFromHistory 提前改成新 docId，
        // 不能用來判斷「listener 是否已在正確文件上」
        if (realCollabDocId == docId && itineraryListener != null) {
            Log.d(tag, "🔄 Listener 已啟動（$docId），跳過重複載入")
            return
        }

        itineraryListener?.remove()
        presenceListener?.remove(); stopMemberPrefsListener()
        presenceHeartbeatJob?.cancel()

        // 在指派前先判斷是否為切換到不同文件（指派後 realCollabDocId 已等於 docId，無法再比對）
        val isNewDoc = realCollabDocId != docId
        if (isNewDoc) {
            stateHolder.ownerJoinPin.value = ""
            // 換文件必須重定位本地 Room 紀錄：lastGeneratedLocalId 若沿用上一趟行程，
            // (1) 下方 listener 的「首次有內容 → insert」條件永不成立，新共編行程
            //     不會寫入 Room（歷史行程／首頁最近行程都看不到）
            // (2) PIN 與 stops 會誤寫進上一趟行程的紀錄
            stateHolder.lastGeneratedLocalId = -1L
            // 換文件也要清掉上一份行程的共用車程，避免錯配
            stateHolder.remoteTransitMins = null
            // 行程本體：openCollabTrip 是「loadItinerary 後立刻切畫面」，而 listener 的第一份
            // snapshot 要等一個網路來回才到。不清的話這段空窗期預覽頁會用「上一次開過的那份
            // 行程」渲染，看起來就像點 A 卻開出 B。
            //
            // 但只在「記憶體裡那份確實是別的行程」時才清：剛生成完的行程會由
            // switch.kt 的 LaunchedEffect 呼叫本函式啟動 listener，此時 currentFirestoreDocId
            // 已經是這份新行程，_itinerary 也正是它——清掉會讓剛生成的結果頁瞬間變空白。
            if (stateHolder.currentFirestoreDocId.value != docId) {
                stateHolder.itinerary.value = null
            }
            viewModelScope.launch(Dispatchers.IO) {
                val record = itineraryDao.getItineraryByFirestoreDocId(docId)
                if (record != null) {
                    withContext(Dispatchers.Main) {
                        // 確認 listener 沒有在查詢期間切換到其他文件
                        if (realCollabDocId == docId && stateHolder.lastGeneratedLocalId < 0) {
                            stateHolder.lastGeneratedLocalId = record.id
                            Log.d(tag, "📌 lastGeneratedLocalId 重定位：docId=$docId → localId=${record.id}")
                        }
                    }
                }
            }
        }
        stateHolder.currentFirestoreDocId.value = docId
        realCollabDocId = docId

        // 抓取優先順序：SharedPreferences 快取 → Room → Firestore snapshot（最終來源）
        val cached = getCachedPin(docId)
        if (cached != null) {
            _joinPin.value = cached
            Log.d(tag, "🔑 PIN 從 SharedPreferences 快取恢復：$cached（docId=$docId）")
        } else {
            // SharedPreferences 無快取：若切換文件先清空，再非同步查 Room 作為離線備援
            if (isNewDoc) _joinPin.value = ""
            viewModelScope.launch(Dispatchers.IO) {
                val record = itineraryDao.getItineraryByFirestoreDocId(docId)
                val roomPin     = record?.joinPin
                val roomExpires = record?.joinPinExpiresAt ?: 0L
                if (!roomPin.isNullOrBlank() && roomExpires > System.currentTimeMillis()) {
                    Log.d(tag, "🔑 PIN 從 Room 恢復：$roomPin（docId=$docId）")
                    withContext(Dispatchers.Main) {
                        // Firestore snapshot 若已先回來並填入值就不覆蓋，避免以舊蓋新
                        if (_joinPin.value.isBlank()) _joinPin.value = roomPin
                    }
                }
            }
        }
        // 同文件重載（isNewDoc = false）且有快取：保留目前顯示的 PIN，避免閃爍

        if (stateHolder.itinerary.value == null) {
            // 載入現有共編行程時用「載入中」文字，避免誤顯示「AI 正在規劃行程」動畫
            stateHolder.visualState.value = VisualData(isLoading = true, loadingPhase = "正在載入行程…")
        }

        var isFirstLoad = true
        itineraryListener = db.collection("micro_trips").document(docId)
            .addSnapshotListener { doc, error ->
                // 防止過期 callback：remove() 不保證立即取消已排隊的回調，
                // 若 realCollabDocId 已切換到其他文件，直接捨棄此次回調
                if (realCollabDocId != docId) {
                    Log.d(tag, "⚠️ 捨棄過期 snapshot callback（docId=$docId，currentDoc=$realCollabDocId）")
                    return@addSnapshotListener
                }
                if (error != null) {
                    Log.e(tag, "Firestore 監聽錯誤: ${error.message}")
                    stateHolder.visualState.value = stateHolder.visualState.value.copy(isLoading = false)
                    return@addSnapshotListener
                }
                if (doc?.exists() != true) {
                    stateHolder.visualState.value = stateHolder.visualState.value.copy(isLoading = false)
                    return@addSnapshotListener
                }
                try {
                    val title       = doc.getString("title")   ?: ""
                    val aiTitle     = doc.getString("aiTitle") ?: ""
                    val aiReply     = doc.getString("aiReply") ?: ""
                    val days        = resolveDaysField(doc)
                    val people      = doc.getString("people")  ?: ""
                    val existingUrl = doc.getString("imageUrl")

                    val stopsRaw = doc.get("stops") as? List<*> ?: emptyList<Any>()
                    // id 一次算整個陣列（缺 id 的站要用陣列位置推導、碰撞要加後綴，與網頁同一套）
                    val stopIds = com.example.travellink_ai.data.model.stableStopIds(stopsRaw)
                    val parsedStops = stopsRaw.mapIndexedNotNull { i, raw -> (raw as? Map<*, *>)?.let { i to it } }.map { (i, s) ->
                        val toiletsRaw = s["nearbyToiletLocations"] as? List<*> ?: emptyList<Any>()
                        Stop(
                            name          = s["name"]  as? String ?: "",
                            time          = s["time"]  as? String ?: "",
                            desc          = s["desc"]  as? String ?: "",
                            emoji         = s["emoji"] as? String ?: "",
                            // 網頁端格式相容：duration 缺失時退回 stayMin
                            duration      = (s["duration"] as? Number)?.toLong()
                                            ?: (s["stayMin"] as? Number)?.toLong() ?: 0L,
                            order         = (s["order"]    as? Number)?.toLong() ?: 0L,
                            businessHours = s["businessHours"] as? String ?: "未提供",
                            nearbyToiletLocations = toiletsRaw.filterIsInstance<Map<String, String>>(),
                            searchKeyword = s["searchKeyword"] as? String ?: "",
                            placeId       = s["placeId"] as? String ?: "",
                            stopId        = stopIds[i],
                            isStation     = parseIsStation(s),
                            isLodging     = s["isLodging"] as? Boolean ?: false,
                            bestTime      = s["bestTime"]  as? String ?: "",
                            stopType      = s["stopType"]  as? String ?: "",
                            // 🌟 v8：讀回座標快取，避免共編快照覆寫掉本地已合併的 lat/lng
                            lat           = (s["lat"] as? Number)?.toDouble(),
                            lng           = (s["lng"] as? Number)?.toDouble(),
                            dayIndex      = parseDayIndex(s),
                            walkNext      = parseWalkNext(s),
                            manualStartMin = parseManualMin(s, "manualStartMin"),
                            manualEndMin   = parseManualMin(s, "manualEndMin")
                        )
                    }.sortedBy { it.order }

                    // 共編省 API：擁有者/編輯者寫回的各段車程。sig（stopId 順序串接）
                    // 必須與目前 stops 相符才沿用——網頁端改了 stops 但沒更新 transitMins 時，
                    // sig 對不上會自動失效，成員端退回自查 Directions，不會用到錯誤配對
                    val tmRaw = doc.get("transitMins") as? Map<*, *>
                    val stopsSigNow = parsedStops.joinToString("|") { it.stopId }
                    stateHolder.remoteTransitMins =
                        if (tmRaw != null && tmRaw["sig"] == stopsSigNow)
                            (tmRaw["mins"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
                        else null

                    val now = System.currentTimeMillis()
                    val locksRaw = doc.get("editingLocks") as? Map<*, *> ?: emptyMap<Any, Any>()
                    _editingLocks.value = locksRaw.entries.mapNotNull { (k, v) ->
                        val stopKey = k as? String ?: return@mapNotNull null
                        val lv = v as? Map<*, *> ?: return@mapNotNull null
                        // Timestamp（App 格式）與 epoch ms（網頁端可能寫入）都要認得
                        val expiresAt = (lv["expiresAt"] as? Timestamp)?.toDate()?.time
                            ?: (lv["expiresAt"] as? Number)?.toLong() ?: 0L
                        if (expiresAt < now) return@mapNotNull null
                        stopKey to EditingLock(
                            uid         = lv["uid"] as? String ?: "",
                            displayName = lv["displayName"] as? String ?: "旅伴",
                            expiresAt   = expiresAt
                        )
                    }.toMap()

                    val pinStr     = doc.getString("joinPin") ?: ""
                    val pinExpires = doc.getTimestamp("pinExpiresAt")?.toDate()?.time ?: 0L
                    val inviteCode = doc.getString("inviteCode") ?: ""
                    val ownerUid   = doc.getString("ownerUid") ?: doc.getString("createdBy") ?: ""
                    val myUid      = currentUid

                    // 從 Firestore 同步交通方式，確保加入者與 owner 一致
                    // 相容兩種格式：root-level transportMode（本 app 格式）或 wizardData.transportMode（組員格式）
                    @Suppress("UNCHECKED_CAST")
                    val wizardData = doc.get("wizardData") as? Map<String, Any>
                    val firestoreTransportMode = doc.getString("transportMode")
                        ?: wizardData?.get("transportMode") as? String
                        ?: "taxi"
                    if (stateHolder.transportMode.value != firestoreTransportMode) {
                        stateHolder.transportMode.value = firestoreTransportMode
                        Log.d(tag, "🚗 transportMode 從 Firestore 同步：$firestoreTransportMode")
                    }

                    // effectivePin / effectivePinExpires：本次 snapshot 確定的 PIN 值，
                    // 用於同步寫入 Room（不論 Owner 或訪客，有 PIN 就存）
                    val effectivePin: String
                    val effectivePinExpires: Long

                    if (pinStr.isNotBlank() && pinExpires > System.currentTimeMillis()) {
                        // PIN 有效：直接使用並快取
                        effectivePin = pinStr
                        effectivePinExpires = pinExpires
                        _joinPin.value = pinStr
                        cachePin(docId, pinStr, pinExpires)
                    } else if (inviteCode.isNotBlank()) {
                        // 網頁端文件：以 inviteCode 作為分享碼顯示，
                        // 不生成 joinPin 寫回（避免污染網頁端格式）
                        effectivePin = ""
                        effectivePinExpires = 0L
                        _joinPin.value = inviteCode
                    } else if (myUid == ownerUid) {
                        // 無邀請碼的舊 App 文件（或 PIN 已過期）且本裝置是 Owner
                        // → 補發長期有效的邀請碼；寫回後 snapshot 會再觸發一次，
                        //   走上面的 inviteCode 分支顯示，這裡先顯示載入中
                        effectivePin = ""
                        effectivePinExpires = 0L
                        _joinPin.value = ""
                        if (!inviteCodeIssueInFlight) {
                            inviteCodeIssueInFlight = true
                            viewModelScope.launch(Dispatchers.IO) {
                                try { issueInviteCode(docId) }
                                finally { inviteCodeIssueInFlight = false }
                            }
                        }
                    } else {
                        // 訪客裝置：PIN 不存在時不覆寫，等 Owner 更新
                        effectivePin = ""
                        effectivePinExpires = 0L
                        _joinPin.value = ""
                    }

                    // 有效 PIN 同步寫入 Room（已有本地 ID 才更新，避免純雲端行程無 ID 的情況）
                    if (effectivePin.isNotBlank()) {
                        val localId = stateHolder.lastGeneratedLocalId
                        if (localId >= 0) {
                            viewModelScope.launch(Dispatchers.IO) {
                                itineraryDao.updateJoinPin(localId, effectivePin, effectivePinExpires)
                            }
                        }
                    }

                    val membersRaw = doc.get("members") as? Map<*, *> ?: emptyMap<Any, Any>()
                    _isCollabMode.value = membersRaw.isNotEmpty()

                    // ── 群組偏好彙整 + 角色解析 ──────────────────────────
                    val myUidNow = currentUid
                    val onlineUids = (_onlineMembers.value.map { it.uid } + currentUid).toSet()
                    // 偏好彙整另由 rebuildGroupProfile()：還要併入 member_prefs 子集合（見該函式）
                    lastMembersRaw = membersRaw
                    rebuildGroupProfile()
                    if (membersRaw.isNotEmpty()) {
                        val memberInfoList = mutableListOf<CollabMemberInfo>()
                        val directory = mutableListOf<MemberKeyEntry>()
                        membersRaw.forEach { (k, v) ->
                            val mv = v as? Map<*, *> ?: return@forEach
                            val mapKey = k as? String ?: return@forEach
                            // 網頁端成員沒有 uid 欄位，key 是 email 消毒字串（如 001_gmail_com）
                            val uid = mv["uid"] as? String ?: mapKey
                            val memberEmail = mv["email"] as? String ?: ""
                            directory.add(MemberKeyEntry(mapKey, uid, memberEmail))
                            val role = mv["role"] as? String ?: "viewer"
                            memberInfoList.add(CollabMemberInfo(
                                uid         = uid,
                                // 網頁端用 name，App 端用 displayName
                                displayName = mv["displayName"] as? String
                                              ?: mv["name"] as? String ?: "旅人",
                                emoji       = mv["emoji"] as? String ?: "🌟",
                                role        = role,
                                isOnline    = uid in onlineUids
                            ))
                            // 網頁端成員只能用 email 對出自己
                            if (uid == myUidNow ||
                                (memberEmail.isNotBlank() && memberEmail.equals(currentEmail, ignoreCase = true))
                            ) _myRole.value = role
                        }
                        memberDirectory = directory
                        _memberEmails.value = directory
                            .mapNotNull { it.email.takeIf { e -> e.isNotBlank() }?.lowercase() }.toSet()
                        // owner 固定排第一，其餘依加入時間穩定排序
                        _collabMembers.value = memberInfoList.sortedWith(
                            compareBy({ if (it.role == "owner") 0 else 1 }, { it.uid })
                        )
                        applyOnlineFlags()
                    } else {
                        // members 為空代表只有 owner，重置角色
                        memberDirectory = emptyList()
                        _memberEmails.value = emptySet()
                        _myRole.value = "owner"
                        _collabMembers.value = emptyList()
                    }

                    val finalItinerary = Itinerary(title, aiTitle, aiReply, "台東", parsedStops, days, people)
                    // 比對 stopId|name|time|duration|walkNext，確保改名稱/停留時間/逐段交通也能觸發同步
                    fun Stop.syncKey() = "$stopId|$name|$time|$duration|$walkNext"
                    val prevStopKeys = stateHolder.itinerary.value?.stops?.map { it.syncKey() }
                    val newStopKeys  = parsedStops.map { it.syncKey() }
                    val stopsChanged = prevStopKeys != newStopKeys
                    Log.d(tag, "🔄 Firestore 景點比對：prev=${prevStopKeys?.size}個, new=${newStopKeys.size}個, changed=$stopsChanged, isFromServer=${!doc.metadata.hasPendingWrites()}")

                    stateHolder.itinerary.value    = finalItinerary
                    stateHolder.backgroundUrl.value = existingUrl
                    // 有真實圖片（file:// 或 https://）→ 進入插圖頁；否則進入行程編輯頁
                    stateHolder.resultInitialView = if (existingUrl?.let {
                        it.startsWith("file://") || it.startsWith("http")
                    } == true) "image" else "map"

                    // 同時加入 hasPendingWrites 判斷：純遠端更新（非本裝置 echo）一律觸發地圖重載
                    val isRemoteUpdate = !doc.metadata.hasPendingWrites()
                    val wasFirstLoad = isFirstLoad
                    if (wasFirstLoad || stopsChanged || isRemoteUpdate) {
                        if (wasFirstLoad) {
                            isFirstLoad = false
                        }
                        // 有行程內容時（stops 不為空）才寫入本地 DB，確保加入者也能存到歷史
                        if (parsedStops.isNotEmpty()) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val existingLocal = itineraryDao.getItineraryByFirestoreDocId(docId)
                                // 若查到的舊紀錄是空行程（title 為空），先刪除，視同不存在
                                val validExisting = existingLocal?.takeIf { it.title.isNotBlank() }
                                if (existingLocal != null && validExisting == null) {
                                    itineraryDao.deleteItinerary(existingLocal)
                                    Log.d(tag, "🗑️ 刪除空行程紀錄：id=${existingLocal.id}，docId=$docId")
                                }
                                if (validExisting == null && stateHolder.lastGeneratedLocalId < 0) {
                                    // 首次有內容 → insert（owner 或加入者皆適用）
                                    val localId = itineraryDao.insertItinerary(
                                        LocalItinerary(
                                            title            = title,
                                            aiTitle          = aiTitle,
                                            aiReply          = aiReply,
                                            region           = "台東",
                                            days             = days,
                                            people           = people,
                                            stops            = parsedStops,
                                            firestoreDocId   = docId,
                                            joinPin          = effectivePin.ifBlank { null },
                                            joinPinExpiresAt = effectivePinExpires,
                                            userId           = currentUid
                                        )
                                    )
                                    stateHolder.lastGeneratedLocalId = localId
                                    Log.d(tag, "✅ 共編行程已存入本地 DB：id=$localId，docId=$docId")
                                } else if (validExisting != null && stopsChanged) {
                                    // 行程已存在但 stops 有更新 → 同步更新 Room
                                    itineraryDao.updateStops(validExisting.id, parsedStops)
                                    if (stateHolder.lastGeneratedLocalId < 0) {
                                        stateHolder.lastGeneratedLocalId = validExisting.id
                                    }
                                    Log.d(tag, "✅ 共編行程景點已更新本地 DB：id=${existingLocal.id}")
                                }
                            }
                        }
                        // 通知 ItineraryViewModel 重新載入地圖
                        viewModelScope.launch {
                            stateHolder.triggerMapReload.emit(parsedStops)
                        }
                        if (wasFirstLoad) {
                            stateHolder.visualState.value = VisualData(isLoading = false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Firestore 解析錯誤: ${e.message}")
                }
                if (!stateHolder.isWorkflowRunning.get()) {
                    stateHolder.visualState.value = stateHolder.visualState.value.copy(isLoading = false)
                }
            }

        // 成員偏好子集合（網頁與 App 共用）：任一成員更新偏好就重算群組偏好
        memberPrefsListener?.remove()
        memberPrefsByKey = emptyMap()
        memberPrefsListener = db.collection("micro_trips").document(docId)
            .collection("member_prefs")
            .addSnapshotListener { snapshot, error ->
                if (realCollabDocId != docId) return@addSnapshotListener
                if (error != null) { Log.w(tag, "member_prefs 監聽失敗：${error.message}"); return@addSnapshotListener }
                memberPrefsByKey = snapshot?.documents.orEmpty().associate { it.id to (it.data ?: emptyMap<String, Any?>()) }
                rebuildGroupProfile()
            }

        presenceListener = db.collection("micro_trips").document(docId)
            .collection("presence")
            .addSnapshotListener { snapshot, _ ->
                if (realCollabDocId != docId) return@addSnapshotListener
                val now = System.currentTimeMillis()
                val fromFirestore = snapshot?.documents?.mapNotNull { presDoc ->
                    val name     = presDoc.getString("displayName") ?: return@mapNotNull null
                    val lastSeen = presDoc.getTimestamp("lastSeen")?.toDate()?.time ?: 0L
                    if (now - lastSeen > 60_000L) return@mapNotNull null
                    PresenceInfo(presDoc.id, name, lastSeen, presDoc.getString("email") ?: "")
                } ?: emptyList()
                // 確保自己永遠被列為在線（heartbeat 還未寫入 Firestore 時也適用）
                val selfAlreadyIncluded = fromFirestore.any { it.uid == currentUid }
                _onlineMembers.value = if (selfAlreadyIncluded) fromFirestore
                else fromFirestore + PresenceInfo(currentUid, currentUserName, now, currentEmail)

                // presence 寫的是 presence 子集合，不會觸發主文件的 members listener，
                // 故在這裡同步更新成員面板的在線狀態，讓「幾人在線」與面板即時一致。
                applyOnlineFlags()
            }

        startPresenceHeartbeat(docId)
    }

    private fun startPresenceHeartbeat(docId: String) {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            val myUid = currentUid
            val presRef = db.collection("micro_trips").document(docId)
                .collection("presence").document(myUid)
            while (isActive) {
                try {
                    presRef.set(mapOf(
                        "displayName" to currentUserName,
                        "emoji"       to currentUserEmoji,
                        "lastSeen"    to Timestamp.now(),
                        // 供成員面板以 email 對上沒有 uid 欄位的 members 項目（規則已允許此欄位）
                        "email"       to currentEmail
                    )).await()
                } catch (e: Exception) { /* 非致命 */ }
                delay(30_000L)
            }
        }
    }

    /**
     * 預覽頁專用：只啟動 presence（心跳＋監聽），不掛行程 snapshot listener，
     * 讓「幾人在線」在行程預覽頁也準確，同時避免 loadItinerary 觸發地圖重載等副作用。
     * 與地圖頁的 loadItinerary presence 各自獨立（自己的 listener/心跳/guard）。
     */
    fun startPreviewPresence(docId: String, userName: String, userEmoji: String) {
        if (docId.isBlank() || docId == "current") return
        if (previewPresenceDocId == docId && previewPresenceHeartbeatJob?.isActive == true) return
        stopPreviewPresence()
        currentUserName  = userName
        currentUserEmoji = userEmoji
        previewPresenceDocId = docId

        previewPresenceListener = db.collection("micro_trips").document(docId)
            .collection("presence")
            .addSnapshotListener { snapshot, _ ->
                if (previewPresenceDocId != docId) return@addSnapshotListener
                val now = System.currentTimeMillis()
                val fromFirestore = snapshot?.documents?.mapNotNull { p ->
                    val name     = p.getString("displayName") ?: return@mapNotNull null
                    val lastSeen = p.getTimestamp("lastSeen")?.toDate()?.time ?: 0L
                    if (now - lastSeen > 60_000L) return@mapNotNull null
                    PresenceInfo(p.id, name, lastSeen, p.getString("email") ?: "")
                } ?: emptyList()
                val selfIncluded = fromFirestore.any { it.uid == currentUid }
                _onlineMembers.value = if (selfIncluded) fromFirestore
                else fromFirestore + PresenceInfo(currentUid, currentUserName, now, currentEmail)
                applyOnlineFlags()
            }

        previewPresenceHeartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            val presRef = db.collection("micro_trips").document(docId)
                .collection("presence").document(currentUid)
            while (isActive) {
                try {
                    presRef.set(mapOf(
                        "displayName" to currentUserName,
                        "emoji"       to currentUserEmoji,
                        "lastSeen"    to Timestamp.now(),
                        "email"       to currentEmail
                    )).await()
                } catch (e: Exception) { /* 非致命 */ }
                delay(30_000L)
            }
        }
    }

    /** 離開預覽頁時收掉 preview presence，並移除自己的在場紀錄。 */
    fun stopPreviewPresence() {
        previewPresenceListener?.remove(); previewPresenceListener = null
        previewPresenceHeartbeatJob?.cancel(); previewPresenceHeartbeatJob = null
        val docId = previewPresenceDocId
        previewPresenceDocId = null
        // 若地圖頁 session（realCollabDocId）仍在同一文件上，就不刪 presence（它自己的心跳會維持）
        if (docId != null && realCollabDocId != docId) {
            val myUid = currentUid
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    db.collection("micro_trips").document(docId)
                        .collection("presence").document(myUid).delete().await()
                } catch (e: Exception) { /* 非致命 */ }
            }
        }
    }

    /** 開啟共編行程（歷史行程點入時使用）：建立 Firestore 即時監聽後導向對應頁面 */
    fun openCollabTrip(docId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val snap = try {
                db.collection("micro_trips").document(docId).get().await()
            } catch (e: Exception) {
                Log.w(tag, "⚠️ openCollabTrip 讀取失敗：${e.message}")
                null
            }
            val imageUrl = snap?.getString("imageUrl") ?: ""
            val hasImage = imageUrl.startsWith("http") || imageUrl.startsWith("file://")
            val stopsEmpty = (snap?.get("stops") as? List<*>).isNullOrEmpty()
            val tripOngoing = ((snap?.get("tripProgress") as? Map<*, *>)
                ?.get("status") as? String) == "ongoing"
            withContext(Dispatchers.Main) {
                // 先清掉 loading，避免顯示「AI 正在規劃」動畫
                stateHolder.visualState.value = VisualData(isLoading = false)
                loadItinerary(docId)
                // 群組尚無景點 → 群組設定頁；行程進行中 → 時間軸（W3）；否則照插圖有無導向
                stateHolder.currentScreen = when {
                    stopsEmpty  -> "group_setup"
                    tripOngoing -> "trip_progress"
                    hasImage    -> "result"
                    else        -> "preview"
                }
            }
        }
    }

    // ── 雲端歷史行程 ──────────────────────────────────────────

    /**
     * 解析 micro_trips 的 createdAt，回傳毫秒；無法判讀時回 0L。
     *
     * App 寫入的是 Firestore Timestamp，但網頁端可能寫成 JS `Date.now()` 數字或 ISO 字串，
     * serverTimestamp() 尚未 resolve 時讀回來也會是 null。舊版只認 Timestamp，其餘一律
     * fallback 成 System.currentTimeMillis()，導致這些文件**每次載入都變成「剛剛建立」**，
     * 在依 createdAt 遞減排序的歷史頁永遠浮在最頂端，而且每次刷新位置都變。
     *
     * 判讀不出來時刻意回 0L（沉到清單底部）而非「現在」：時間未知的項目排到最後，
     * 誤導性遠小於偽裝成最新的一筆。
     */
    /**
     * 取行程的 days 字串，網頁端多日文件會由 wizardData 補成 App 格式。
     * 純邏輯在 [normalizeDaysField]（可單元測試），這裡只負責從文件取欄位。
     */
    private fun resolveDaysField(doc: com.google.firebase.firestore.DocumentSnapshot): String {
        val raw = doc.getString("days") ?: ""
        val result = normalizeDaysField(
            appDays = doc.getString("appDays"),
            rawDays = raw,
            wizard  = doc.get("wizardData") as? Map<*, *>
        )
        if (result != raw) Log.d(tag, "📅 days 正規化：「$raw」→「$result」")
        return result
    }

    private fun parseCreatedAtMs(value: Any?): Long = when (value) {
        is Timestamp   -> value.toDate().time
        is java.util.Date -> value.time
        // 秒 / 毫秒兩種都可能（1e11 毫秒 ≈ 1973 年，早於任何合理資料，可安全當分界）
        is Number      -> value.toLong().let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        is String      -> try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            catch (e2: Exception) { 0L }
        }
        else           -> 0L
    }

    /** 空群組視為「已放棄」的年齡門檻：小於這個時間的仍可能是使用者正在籌備中的那一團 */
    private val ABANDONED_GROUP_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * 清理「建立了群組但從沒生成行程」的殘留文件。
     *
     * createCollabGroup 會先建一份 status=planning、stops=[] 的空文件，使用者若在
     * group_setup 中途離開就再也不會回來——舊版的重用守衛會在同一 session 內接回這份文件，
     * 但只要中間開過別的行程（或重開 App）就接不回來，於是空文件永久留在 Firestore
     * 並以「未命名共編行程」的空卡片出現在歷史頁。
     *
     * 直接沿用 loadHistoryFromFirebase already 抓回來的文件判斷，不額外產生讀取。
     * 刪除條件從嚴，只清「確定沒人要的」：
     *   ① 自己是 owner        ② stops 為空        ③ status 仍是 planning
     *   ④ 沒有其他成員加入     ⑤ 建立超過 24 小時   ⑥ 不是當前正在操作的那一份
     * 連同 invites 註冊表一起刪，避免留下指向已刪行程的邀請碼。
     */
    private fun cleanupAbandonedGroups(docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        val activeDocIds = setOfNotNull(realCollabDocId, groupSetupDocId, stateHolder.currentFirestoreDocId.value)
        val now = System.currentTimeMillis()
        val abandoned = docs.filter { doc ->
            val isOwner     = doc.getString("ownerUid") == currentUid
            val stopsEmpty  = (doc.get("stops") as? List<*>).isNullOrEmpty()
            val isPlanning  = doc.getString("status") == "planning"
            val memberCount = (doc.get("members") as? Map<*, *>)?.size ?: 0
            val emailCount  = (doc.get("memberEmails") as? List<*>)?.size ?: 0
            val createdMs   = parseCreatedAtMs(doc.get("createdAt"))
            // 判讀不出建立時間（createdMs == 0）一律不刪，寧可留著也不誤刪
            val isStale     = createdMs > 0 && now - createdMs > ABANDONED_GROUP_AGE_MS
            isOwner && stopsEmpty && isPlanning && memberCount <= 1 && emailCount <= 1 &&
                isStale && doc.id !in activeDocIds
        }
        if (abandoned.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (doc in abandoned) {
                try {
                    deleteGroupDoc(doc.id, doc.getString("inviteCode"))
                    Log.d(tag, "🧹 已清理未使用的空群組：${doc.id}")
                } catch (e: Exception) {
                    Log.w(tag, "⚠️ 空群組清理失敗（非致命）：${doc.id} ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                val removed = abandoned.map { it.id }.toSet()
                stateHolder.remoteHistory.value =
                    stateHolder.remoteHistory.value.filterNot { it.firestoreDocId in removed }
            }
            Log.d(tag, "🧹 空群組清理完成：${abandoned.size} 筆")
        }
    }

    /**
     * 我的微旅行卡片用的狀態與權限。
     * status 以頂層 `status` 為準（兩端開始／結束行程都會寫）；舊文件退回 tripProgress.status。
     * canEdit 對齊 rules 的 isEditor：擁有者（ownerUid / ownerEmail / userEmail）、
     * editorEmails 成員、或 members 裡 role=editor 的舊格式成員。
     */
    private fun tripMetaOf(doc: com.google.firebase.firestore.DocumentSnapshot): RemoteTripMeta {
        val status = doc.getString("status")?.takeIf { it.isNotBlank() }
            ?: ((doc.get("tripProgress") as? Map<*, *>)?.get("status") as? String).orEmpty()
        return RemoteTripMeta(
            status = status,
            canEdit = com.example.travellink_ai.data.model.canEditTripDoc(
                doc.data.orEmpty(), currentUid, currentEmail
            ),
            titleVersion = (doc.get("titleVersion") as? Number)?.toLong() ?: 0L
        )
    }

    /** 改名結果；Conflict＝共編時別人先改了（titleVersion 對不上） */
    sealed interface RenameResult {
        data object Ok : RenameResult
        data class Conflict(val remoteTitle: String, val byName: String, val remoteVersion: Long) : RenameResult
        data class Failed(val message: String) : RenameResult
    }

    /**
     * 行程改名（對齊網頁 persistTripRename）：
     * - 純本地行程只改 Room。
     * - 雲端行程以交易寫 title／customTitle／titleVersion＋1／lastEditedBy*；
     *   共編時若雲端 titleVersion 與清單載入時不同，回 Conflict 讓使用者選（force=true 表示保留我的修改）。
     * - 共編改名成功後通知其他成員，nid＝['trip_renamed', docId, 'v'+版本]，與網頁相同，兩端不會重複發。
     */
    fun renameTrip(
        item: LocalItinerary,
        newName: String,
        fromName: String,
        force: Boolean = false,
        expectedVersion: Long? = null,
        onResult: (RenameResult) -> Unit
    ) {
        val name = newName.trim().take(40)
        val docId = item.firestoreDocId?.takeIf { it.isNotBlank() }
        viewModelScope.launch(Dispatchers.IO) {
            val result: RenameResult = try {
                if (docId == null) {
                    itineraryDao.updateTitle(item.id, name)
                    RenameResult.Ok
                } else {
                    val isCollab = docId in stateHolder.collabDocIds.value
                    val expected = expectedVersion ?: stateHolder.remoteTripMeta.value[docId]?.titleVersion ?: 0L
                    val ref = db.collection("micro_trips").document(docId)
                    var memberEmails = emptyList<String>()
                    val outcome = db.runTransaction { tx ->
                        val snap = tx.get(ref)
                        val remoteVersion = (snap.get("titleVersion") as? Number)?.toLong() ?: 0L
                        memberEmails = (snap.get("memberEmails") as? List<*>)?.filterIsInstance<String>().orEmpty()
                        if (isCollab && !force && remoteVersion != expected) {
                            RenameResult.Conflict(
                                remoteTitle = snap.getString("title").orEmpty().ifBlank { "未命名行程" },
                                byName = snap.getString("lastEditedByName")?.takeIf { it.isNotBlank() }
                                    ?: snap.getString("lastEditedBy")?.takeIf { it.isNotBlank() }
                                    ?: "其他成員",
                                remoteVersion = remoteVersion
                            ) to remoteVersion
                        } else {
                            val written = remoteVersion + 1
                            tx.set(ref, mapOf(
                                "title" to name,
                                "customTitle" to true,
                                "titleVersion" to written,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "lastEditedBy" to currentEmail,
                                "lastEditedByName" to fromName.ifBlank { currentEmail }
                            ), com.google.firebase.firestore.SetOptions.merge())
                            RenameResult.Ok to written
                        }
                    }.await()
                    val (res, version) = outcome
                    if (res is RenameResult.Ok) {
                        stateHolder.remoteTripMeta.value = stateHolder.remoteTripMeta.value.let { m ->
                            m + (docId to (m[docId] ?: RemoteTripMeta()).copy(titleVersion = version))
                        }
                        withContext(Dispatchers.Main) {
                            stateHolder.remoteHistory.value = stateHolder.remoteHistory.value.map {
                                if (it.firestoreDocId == docId) it.copy(title = name) else it
                            }
                        }
                        itineraryDao.getItineraryByFirestoreDocId(docId)?.let { itineraryDao.updateTitle(it.id, name) }
                        if (isCollab && memberEmails.isNotEmpty()) {
                            notificationRepo.pushToMany(
                                emails = memberEmails,
                                idFor = { notificationRepo.nid(listOf("trip_renamed", docId, "v$version")) },
                                type = "trip_renamed",
                                fromName = fromName,
                                tripId = docId,
                                tripTitle = name
                            )
                        }
                    }
                    res
                }
            } catch (e: Exception) {
                Log.e(tag, "❌ 行程改名失敗：${e.message}")
                RenameResult.Failed(e.message ?: "未知錯誤")
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun loadHistoryFromFirebase() {
        if (stateHolder.isLoadingRemoteHistory.value) return
        viewModelScope.launch(Dispatchers.IO) {
            stateHolder.isLoadingRemoteHistory.value = true
            try {
                // 分別查詢，各自 try-catch，避免其中一個被規則拒絕時整體失敗
                val docsByUid = try {
                    db.collection("micro_trips")
                        .whereEqualTo("ownerUid", currentUid)
                        .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(100).get().await().documents
                } catch (e: Exception) {
                    Log.w(tag, "⚠️ ownerUid 查詢失敗（規則不支援）：${e.message}")
                    emptyList()
                }
                val docsByEmail = if (currentEmail.isNotBlank()) {
                    try {
                        db.collection("micro_trips")
                            .whereEqualTo("userEmail", currentEmail)
                            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                            .limit(100).get().await().documents
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ userEmail 查詢失敗：${e.message}")
                        emptyList()
                    }
                } else emptyList()
                // 網頁端共編：自己是成員但非 owner 的行程記在 memberEmails 陣列
                // （不加 orderBy，避免 arrayContains + orderBy 需要複合索引；合併後統一排序）
                val docsByMemberEmail = if (currentEmail.isNotBlank()) {
                    try {
                        db.collection("micro_trips")
                            .whereArrayContains("memberEmails", currentEmail)
                            .limit(100).get().await().documents
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ memberEmails 查詢失敗：${e.message}")
                        emptyList()
                    }
                } else emptyList()
                // 合併去重（以 document ID 為準）
                val mergedDocs = (docsByUid + docsByEmail + docsByMemberEmail)
                    .distinctBy { it.id }
                    .sortedByDescending { parseCreatedAtMs(it.get("createdAt")) }
                val items = mergedDocs.mapNotNull { doc ->
                    if (doc.id in stateHolder.recentlyDeletedDocIds) return@mapNotNull null
                    try {
                        val stopsRaw = doc.get("stops") as? List<*> ?: return@mapNotNull null
                        val stopIds = com.example.travellink_ai.data.model.stableStopIds(stopsRaw)
                        val stops = stopsRaw.mapIndexedNotNull { i, stopMap ->
                            (stopMap as? Map<*, *>)?.let { s ->
                                Stop(
                                    name     = s["name"]  as? String ?: "",
                                    time     = s["time"]  as? String ?: "",
                                    desc     = s["desc"]  as? String ?: "",
                                    emoji    = s["emoji"] as? String ?: "📍",
                                    duration = (s["duration"] as? Number)?.toLong()
                                               ?: (s["stayMin"] as? Number)?.toLong() ?: 120L,
                                    order    = (s["order"]    as? Number)?.toLong() ?: 1L,
                                    businessHours         = s["businessHours"] as? String ?: "未提供",
                                    nearbyToiletLocations = emptyList(),
                                    placeId  = s["placeId"] as? String ?: "",
                                    stopId   = stopIds[i],
                                    isStation = parseIsStation(s),
                                    isLodging = s["isLodging"] as? Boolean ?: false,
                                    lat      = (s["lat"] as? Number)?.toDouble(),
                                    lng      = (s["lng"] as? Number)?.toDouble(),
                                    dayIndex = parseDayIndex(s),
                                    walkNext = parseWalkNext(s),
                                    manualStartMin = parseManualMin(s, "manualStartMin"),
                                    manualEndMin   = parseManualMin(s, "manualEndMin")
                                )
                            }
                        }
                        val createdAt = parseCreatedAtMs(doc.get("createdAt"))
                        if (stateHolder.recentlyDeletedCreatedAts.any { kotlin.math.abs(it - createdAt) < 10_000L })
                            return@mapNotNull null
                        LocalItinerary(
                            id             = -1L,
                            title          = doc.getString("title")   ?: "",
                            aiTitle        = doc.getString("aiTitle") ?: "",
                            aiReply        = doc.getString("aiReply") ?: "",
                            region         = doc.getString("region")  ?: "",
                            days           = resolveDaysField(doc),
                            people         = doc.getString("people")  ?: "",
                            stops          = stops,
                            isSynced       = true,
                            createdAt      = createdAt,
                            imageUrl       = doc.getString("imageUrl"),
                            firestoreDocId = doc.id
                        )
                    } catch (e: Exception) { null }
                }
                stateHolder.remoteHistory.value = items
                // 真正的共編＝Firestore 文件實際成員 > 1（members map 或 memberEmails）。
                // 個人行程只有自己一位成員，據此判斷「共編」標籤才準（同行人數/joinPin 不準）。
                stateHolder.collabDocIds.value = mergedDocs.mapNotNull { doc ->
                    val memberCount = (doc.get("members") as? Map<*, *>)?.size ?: 0
                    val emailCount  = (doc.get("memberEmails") as? List<*>)?.size ?: 0
                    if (memberCount > 1 || emailCount > 1) doc.id else null
                }.toSet()
                stateHolder.remoteTripMeta.value = mergedDocs.associate { doc -> doc.id to tripMetaOf(doc) }
                Log.d(tag, "✅ Firebase 歷史載入：${items.size} 筆（共編 ${stateHolder.collabDocIds.value.size} 筆）")
                cleanupAbandonedGroups(mergedDocs)
            } catch (e: Exception) {
                Log.e(tag, "❌ Firebase 歷史載入失敗：${e.message}")
            } finally {
                stateHolder.isLoadingRemoteHistory.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        itineraryListener?.remove()
        presenceListener?.remove(); stopMemberPrefsListener()
        presenceHeartbeatJob?.cancel()
        val docId = realCollabDocId ?: return
        val myUid = currentUid
        db.collection("micro_trips").document(docId)
            .collection("presence").document(myUid).delete()
        val myLocks = _editingLocks.value.filter { it.value.uid == myUid }
        if (myLocks.isNotEmpty()) {
            val updates: Map<String, Any> = myLocks.keys.associate {
                "editingLocks.$it" to FieldValue.delete()
            }
            db.collection("micro_trips").document(docId).update(updates)
        }
    }
}
