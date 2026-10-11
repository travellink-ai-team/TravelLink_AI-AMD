package com.example.travellink_ai.data.repository

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.data.model.ParkingLotInfo
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.VisualData
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 雲端行程在「我的微旅行」清單需要、但 [LocalItinerary] 沒有的欄位。
 * @param status       micro_trips.status（planning / ongoing / completed，兩端共用；缺值為空字串）
 * @param canEdit      自己是擁有者或可編輯成員（對齊網頁 canRenameCollabTrip）
 * @param titleVersion 改名樂觀鎖版本（對齊網頁 persistTripRename）
 */
data class RemoteTripMeta(
    val status: String = "",
    val canEdit: Boolean = true,
    val titleVersion: Long = 0L
)

/**
 * Activity 範圍內多個 ViewModel 共用的可變狀態。
 * 以 Hilt Singleton 注入，確保所有 VM 持有同一份實例。
 */
@Singleton
class ItineraryStateHolder @Inject constructor() {

    // ── 核心行程狀態 ──────────────────────────────────────────
    val itinerary          = MutableStateFlow<Itinerary?>(null)
    val currentFirestoreDocId = MutableStateFlow<String?>(null)
    var lastGeneratedLocalId: Long = -1L

    // ── 地圖 / 視覺狀態 ───────────────────────────────────────
    val stopLocations  = MutableStateFlow<Map<String, LatLng>>(emptyMap())
    val roadSegments   = MutableStateFlow<List<List<LatLng>>>(emptyList())
    val backgroundUrl  = MutableStateFlow<String?>(null)
    val visualState    = MutableStateFlow(VisualData())
    val isWorkflowRunning = AtomicBoolean(false)

    // ── 導覽狀態（Compose State，由 MainActivity 直接讀取）──────
    private val _currentScreen = mutableStateOf("home")
    /**
     * 返回目的地：進入 preview / memory_edit 時記下「來源畫面」，供該頁返回鍵使用，
     * 避免返回一律寫死回首頁（例如從歷史頁進來應返回歷史頁、行程進行中按相機進回憶應返回行程）。
     * preview 僅認可 home/history 為來源（其餘一律視為 home，防止 result↔preview 返回循環）；
     * 從行程頁自己的子頁回來則沿用原本的返回目標。
     */
    var previewReturnScreen = "home"
        private set
    var memoryReturnScreen = "home"
        private set
    var currentScreen: String
        get() = _currentScreen.value
        set(requested) {
            // 行程進行中已併進行程頁（同一頁切成打卡模式，對齊網頁），舊的進行中頁路由一律導到行程頁
            val value = if (requested == "trip_progress") "preview" else requested
            val from = _currentScreen.value
            when (value) {
                "preview"     -> previewReturnScreen = when (from) {
                    "home", "history" -> from
                    // 從行程頁點進去的子頁（景點資訊、進行中地圖、旅程回憶）回來，返回目標維持原樣
                    "preview", "stop_detail", "trip_map", "memory_edit" -> previewReturnScreen
                    else -> "home"
                }
                // 九宮格/短片返回旅遊回憶是「內部返回」，不能把返回目標記成它們，
                // 否則旅遊回憶再返回又跳回去，無限來回。
                "memory_edit" -> if (from != "memory_grid" && from != "memory_recap") memoryReturnScreen = from
            }
            _currentScreen.value = value
        }
    var resultInitialView by mutableStateOf("map")
    // 探索「✨ 用這份開始規劃」：下一次打開建立精靈時預填（取用即清）
    var pendingTemplateSeed by mutableStateOf<com.example.travellink_ai.data.model.TemplateSeed?>(null)

    // ── 雲端歷史（CollabVM 寫入，ItineraryVM 刪除時同步清除）──
    val remoteHistory           = MutableStateFlow<List<LocalItinerary>>(emptyList())
    val isLoadingRemoteHistory  = MutableStateFlow(false)
    // 真正的共編行程 docId 集合（Firestore 實際成員數 > 1 才算；供歷史頁「共編」標籤判斷，
    // 避免用同行人數/joinPin 誤判——兩者在個人行程上也會有值）
    val collabDocIds            = MutableStateFlow<Set<String>>(emptySet())
    // docId → 狀態／權限／改名版本（loadHistoryFromFirebase 一併填入）
    val remoteTripMeta          = MutableStateFlow<Map<String, RemoteTripMeta>>(emptyMap())

    // 最近刪除的 docId / createdAt，防止 Firebase 快取帶回已刪項目
    val recentlyDeletedDocIds      = mutableSetOf<String>()
    val recentlyDeletedCreatedAts  = mutableSetOf<Long>()

    // ── 交通模式 ──────────────────────────────────────────────
    /** "walking" | "taxi" | "driving"，生成前由 PlanningBottomSheet 設定 */
    val transportMode = MutableStateFlow("taxi")

    // ── 停車場（僅 driving 模式）────────────────────────────────
    /** stopName → 最近停車場資訊；非 driving 模式時為空 Map */
    val parkingLots = MutableStateFlow<Map<String, ParkingLotInfo>>(emptyMap())

    /**
     * 停車場→景點的步行段（開車模式下，每個有停車場的景點產生一條步行線）
     * Pair.first = 停車場座標，Pair.second = 景點座標
     */
    val walkingLegs = MutableStateFlow<List<Pair<LatLng, LatLng>>>(emptyList())

    // ── 跨 VM 訊號 ────────────────────────────────────────────
    /** CollabVM 通知 ItineraryVM 重新載入地圖資料 */
    val triggerMapReload = MutableSharedFlow<List<Stop>>(replay = 0, extraBufferCapacity = 1)

    /**
     * 共編省 API：擁有者/編輯者寫回 Firestore 的各段車程（分鐘）。
     * CollabVM 解析快照時驗證 sig（stopId 順序串接）相符才填入，
     * ItineraryVM 的 triggerMapReload 重算直接沿用，成員端不必再打 Directions。
     */
    var remoteTransitMins: List<Long>? = null

    /** ItineraryVM 生成行程後寫入 Firestore 的 PIN，供 CollabVM / UI 直接讀取 */
    val ownerJoinPin = MutableStateFlow("")
}
