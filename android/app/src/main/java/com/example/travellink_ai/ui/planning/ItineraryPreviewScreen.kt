package com.example.travellink_ai.ui.planning

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.LatLng
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.weather.CwaWeatherService
import com.example.travellink_ai.data.weather.WeatherInfo
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.trip.ongoingTripItems
import kotlin.math.roundToInt

// ── 設計語言（與全 App 一致）─────────────────────────────────
private val PAccent      = DesignTokens.Accent
private val PAccentDark  = DesignTokens.AccentDark
private val PAccentLight = DesignTokens.AccentLight
private val PIink        = DesignTokens.Ink
private val PIink2       = DesignTokens.Ink2
private val PIink3       = DesignTokens.Ink3        // 修正：原 #9A9590 為 Ink3 舊錯值
private val PSurface2    = DesignTokens.Surface2
private val PBorder      = DesignTokens.Border
private val PDanger      = DesignTokens.Red        // 對齊網頁 --red #D94040

// ── 時間格式化 ────────────────────────────────────────────────
private fun fmtDuration(min: Long): String = when {
    min <= 0  -> "–"
    min < 60  -> "${min} 分鐘"
    min % 60 == 0L -> "${min / 60} 小時"
    else -> "${min / 60} 小時 ${min % 60} 分"
}

// 時間軸交通 chip 專用：欄寬窄，要單行放得下
private fun fmtTransitCompact(min: Long): String = when {
    min < 60       -> "${min}分"
    min % 60 == 0L -> "${min / 60}小時"
    else           -> "${min / 60}小時${min % 60}分"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryPreviewScreen(
    viewModel: ItineraryViewModel,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    /** ⋯ 更多 →「匯出行程圖」：跳到插圖頁（沒插圖就開生成對話框） */
    onExportImage: () -> Unit = {},
    /** 底部按鈕欄（含導覽列間距）的實際高度 px。讓浮在畫面上的隨行管家頭像避開它 */
    onBottomBarHeightChange: (Int) -> Unit = {}
) {
    val collabVm: CollabViewModel = hiltViewModel()
    val tripVm: com.example.travellink_ai.ui.trip.TripProgressViewModel = hiltViewModel()
    val friendVm: com.example.travellink_ai.ui.friends.FriendViewModel = hiltViewModel()
    val agentVm: com.example.travellink_ai.ui.agent.AgentViewModel = hiltViewModel()
    val aiKind by com.example.travellink_ai.data.ai.AiProvider.kind.collectAsState()

    val itinerary              by viewModel.itinerary.collectAsState()
    val tripProgress           by tripVm.progress.collectAsState()
    val aiSuggestions          by viewModel.aiSuggestions.collectAsState()
    val isLoadingSug           by viewModel.isLoadingSuggestions.collectAsState()
    val suggestionErr          by viewModel.suggestionError.collectAsState()
    val transitTimes           by viewModel.transitTimes.collectAsState()
    val businessHourConflicts  by viewModel.businessHourConflicts.collectAsState()
    val editingLocks           by collabVm.editingLocks.collectAsState()
    val myUserId               = collabVm.currentUserUid.ifBlank { collabVm.collabIdentity.userId }
    val myRole                 by collabVm.myRole.collectAsState()
    val collabMembers          by collabVm.collabMembers.collectAsState()
    val isOwner                = myRole == "owner"
    val isViewer               = myRole == "viewer"
    val transportMode          by viewModel.transportMode.collectAsState()
    val segmentModes           by viewModel.segmentModes.collectAsState()
    val stopLocations          by viewModel.stopLocations.collectAsState()
    // 本地成本參考庫（含真實票價／餐費、營業時間）：費用卡、超支替換、附近替代景點共用。
    // 背景載入避免阻塞 UI；載入前費用卡先以參考表估算，就緒後自動重算。
    val costAppContext = LocalContext.current.applicationContext
    val costRef by produceState<CostReference?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CostReference.get(costAppContext) }
    }

    var editingStopId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stops = itinerary?.stops ?: emptyList()

    var showDeleteDialog      by remember { mutableStateOf(false) }
    var showShareSheet        by remember { mutableStateOf(false) }
    var showMapShare          by remember { mutableStateOf(false) }
    var showOnlineMembers     by remember { mutableStateOf(false) }
    var showAddSpecificPoi    by remember { mutableStateOf(false) }
    val addPoiLoading         by viewModel.addPoiLoading.collectAsState()
    val addPoiError           by viewModel.addPoiError.collectAsState()
    val addPoiSuccess         by viewModel.addPoiSuccess.collectAsState()
    // ── 新增景點（對齊網頁端）：搜尋新增 / 探索附近 ──
    var showAddSearch         by remember { mutableStateOf(false) }
    var showExplore           by remember { mutableStateOf(false) }
    var exploreSpotName       by remember { mutableStateOf<String?>(null) }
    // 從編輯 sheet 觸發「探索這附近」時，先關編輯 sheet，待退場後再開探索 sheet
    // （兩個 ModalBottomSheet 同時切換會互相吃掉，需延遲）。此 scope 綁畫面存活期，
    // 不受編輯 sheet 移除影響，避免延遲任務被取消。
    val screenScope = rememberCoroutineScope()
    val addSearchResults      by viewModel.addSearchResults.collectAsState()
    val addSearchLoading      by viewModel.addSearchLoading.collectAsState()
    val exploreResults        by viewModel.nearbyResults.collectAsState()
    val exploreLoading        by viewModel.nearbyLoading.collectAsState()
    val addResultMsg          by viewModel.addResultMsg.collectAsState()
    val joinPin               by collabVm.joinPin.collectAsState()
    val onlineMembers         by collabVm.onlineMembers.collectAsState()
    val returnTrains          by viewModel.returnTrains.collectAsState()
    // A5：離島行程改顯示船班卡。船班表是 assets 靜態檔，讀一次即可
    val previewCtx = LocalContext.current
    val ferrySchedules = remember { com.example.travellink_ai.data.island.FerrySchedules.get(previewCtx.applicationContext) }
    val islandOfTrip = remember(itinerary?.region) {
        com.example.travellink_ai.data.island.IslandRegistry.byDestination(itinerary?.region)
    }
    // 每日可用到幾點：與生成時同一組日窗（離島末日扣回程航程與登船緩衝）
    val previewDayEndMins = remember(itinerary?.days, itinerary?.stops, islandOfTrip) {
        val d = itinerary?.days ?: ""
        if (!d.contains(" - ")) emptyMap() else {
            val ws = DayPlanner.effectiveWindows(d, itinerary?.stops ?: emptyList())
            val isl = islandOfTrip
            (if (isl == null) ws else ws.mapIndexed { i, w ->
                if (i == ws.lastIndex)
                    w.copy(endMins = w.endMins - isl.sailingMins -
                        com.example.travellink_ai.data.island.IslandRegistry.BOARDING_BUFFER_MINS)
                else w
            }).associate { it.dayIndex to it.endMins }
        }
    }

    // ── 超過精靈設定的結束時間（規劃中）：行程尾端顯示警告，加站後也跳提示 ──
    val planOverrun = remember(itinerary?.days, itinerary?.stops, itinerary?.region) {
        itinerary?.let { PlanOverrun.of(it.days, it.stops, it.region) }
    }

    // ── 回程台鐵班次（A7 ②）：回程站或抵達時間變動時重查 ──
    // 行程進行中改由 rememberOngoingTrip 依即時推估的抵站時間查，這裡不搶著覆蓋
    val returnStopKey = stops.lastOrNull()?.takeIf { it.isStation }?.let { "${it.name}@${it.time}" }
    LaunchedEffect(returnStopKey, tripProgress.isOngoing) {
        if (returnStopKey != null && !tripProgress.isOngoing) viewModel.loadReturnTrains()
    }

    // ── 輕量 PIN 取得（不設 Listener，不觸發地圖更新 / 營業時間重算）
    // 使用 fetchPinForPreview 而非 loadItinerary，避免 triggerMapReload 導致
    // recalculateStopTimes 重跑進而重複產生營業時間衝突通知
    val currentDocId = viewModel.currentFirestoreDocIdPublic
    LaunchedEffect(currentDocId) {
        if (!currentDocId.isNullOrBlank() && currentDocId != "current") {
            collabVm.fetchPinForPreview(currentDocId)
        }
    }

    // 共編行程在預覽頁也啟動 presence（幾人在線）；離開時收掉。
    // 以「members map 非空」判定共編（含只有 owner 的行程），避免單人行程無謂心跳。
    val isCollabTrip = collabMembers.isNotEmpty()
    DisposableEffect(currentDocId, isCollabTrip) {
        if (isCollabTrip && !currentDocId.isNullOrBlank() && currentDocId != "current") {
            collabVm.startPreviewPresence(currentDocId, collabVm.collabIdentity.displayName, "🌟")
        }
        onDispose { collabVm.stopPreviewPresence() }
    }

    // 行程進行狀態（W3）：開始行程後同一頁切成打卡模式（對齊網頁），
    // 掛即時監聽讓旅伴（含網頁端）開始／打卡／結束時這頁跟著切換
    LaunchedEffect(currentDocId) {
        tripVm.refreshStatus()
        tripVm.attach()
    }

    // 加入者端：進入行程預覽時若路線未計算（transitTimes 為空），主動補算
    LaunchedEffect(stops.size) {
        if (transitTimes.isEmpty() && stops.size >= 2) {
            viewModel.fetchDirections(stops)
        }
    }

    // ── 在線成員通知（Snackbar）──────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 行程進行中：GPS、打卡、預計離開與完成對話框（只在進行中才掛上）──
    val ongoing = if (tripProgress.isOngoing)
        com.example.travellink_ai.ui.trip.rememberOngoingTrip(viewModel, tripVm)
    else null

    // ── 🤖 AMD 組：行程進行中的延誤資訊（T4）與主動通知 ──
    // 放在頁面層級而不是清單的某一列：使用者捲到下面時那一列會被回收，通知就發不出來
    val agentDelay = if (aiKind == com.example.travellink_ai.data.ai.AiProvider.Kind.AMD && ongoing != null)
        ongoing.here?.let { agentDelayInput(ongoing, it) } else null
    val agentEnabled = aiKind == com.example.travellink_ai.data.ai.AiProvider.Kind.AMD && !isViewer
    val agentRun by agentVm.run.collectAsState()
    val agentSimDelay by agentVm.simDelay.collectAsState()
    val agentDoneIds = remember(stops, tripProgress) {
        stops.filter { tripProgress.isDone(it.stopId) }.map { it.stopId }.toSet()
    }
    // 旅程應變助理已整合進隨行管家：這頁只負責把目前的行程交給 Agent
    SideEffect {
        agentVm.setHost(if (!agentEnabled) null else itinerary?.let { itin ->
            com.example.travellink_ai.ui.agent.AgentViewModel.AgentHost(
                itinerary = itin,
                doneIds = agentDoneIds,
                // T4：行程進行中、已在某一站時，帶上延誤資訊（目前站、預計離開、衝突、回程期限）
                delay = agentDelay,
                prepare = { viewModel.prepareAgentRequest() },
                returnTrain = { date ->
                    viewModel.returnTrainDeadline(date)?.let { (hhmm, station) ->
                        DayPlanner.parseHhMm(hhmm)?.let {
                            com.example.travellink_ai.data.agent.Deadline(
                                com.example.travellink_ai.data.agent.AgentTripMapper.isoAt(date, it), station)
                        }
                    }
                },
                apply = { ids, draft, updatedAt, lock -> viewModel.applyAgentProposal(ids, draft, updatedAt, lock) }
            )
        })
    }
    DisposableEffect(Unit) { onDispose { agentVm.setHost(null) } }
    // 通知上按了「讓 AI 處理」：等延誤資訊就緒就打開管家並送出（使用者已經按下，不是自動呼叫）
    val agentAutoDelay by agentVm.autoDelay.collectAsState()
    LaunchedEffect(agentAutoDelay, agentDelay != null) {
        if (agentAutoDelay && agentDelay != null) {
            agentVm.consumeAutoDelay()
            viewModel.setAssistantOpen(true)
            agentVm.startDelay("⏱️ 讓 AI 處理延誤")
        }
    }
    val agentConflictKeys = agentDelay?.conflicts?.map { "${it.kind}_${it.stopId.orEmpty()}" }?.toSet().orEmpty()
    val alertCtx = LocalContext.current.applicationContext
    LaunchedEffect(agentConflictKeys) {
        // 只在手機上偵測、跳通知，不呼叫後端；同一組衝突只通知一次（T4 規格 §1.3）
        if (agentConflictKeys.isNotEmpty() && !isViewer && agentVm.shouldNotify(agentConflictKeys)) {
            com.example.travellink_ai.ui.agent.AgentAlert.notify(
                alertCtx, viewModel.currentFirestoreDocIdPublic, agentDelay?.conflicts.orEmpty().map { it.message })
        }
    }
    var showResetDialog by remember { mutableStateOf(false) }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重設進度？", fontWeight = FontWeight.Bold) },
            text = { Text("行程會退回「規劃中」，清除所有打卡、跳過與預計離開時間。") },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; tripVm.resetProgress() }) {
                    Text("重設", color = PDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("取消") } }
        )
    }
    // 記錄上一次的成員 UID 集合，用來偵測新加入成員
    var prevMemberUids by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(onlineMembers) {
        val currentUids = onlineMembers.map { it.uid }.toSet()
        if (prevMemberUids.isNotEmpty()) {
            val newComers = onlineMembers.filter {
                it.uid !in prevMemberUids && it.uid != myUserId
            }
            if (newComers.isNotEmpty()) {
                val names = newComers.joinToString("、") { it.displayName }
                snackbarHostState.showSnackbar(
                    message  = "👥 $names 加入了共同編輯",
                    duration = SnackbarDuration.Short
                )
            }
        }
        prevMemberUids = currentUids
    }

    // ── 指定景點加入成功 → 關閉 Dialog + Snackbar ──────────────────
    LaunchedEffect(addPoiSuccess) {
        val added = addPoiSuccess
        if (added != null) {
            showAddSpecificPoi = false
            viewModel.clearAddPoiStatus()
            snackbarHostState.showSnackbar(
                message  = "✅ 已將「$added」加入最順路的位置，時間已重新排定",
                duration = SnackbarDuration.Short
            )
        }
    }

    // ── 卡片加入景點結果 → Snackbar ──────────────────────────────
    val planNotice by viewModel.planNotice.collectAsState()
    LaunchedEffect(planNotice) {
        val msg = planNotice ?: return@LaunchedEffect
        viewModel.consumePlanNotice()
        snackbarHostState.showSnackbar(message = "⚠️ $msg", duration = SnackbarDuration.Long)
    }
    LaunchedEffect(addResultMsg) {
        val msg = addResultMsg
        if (msg != null) {
            snackbarHostState.showSnackbar(message = "✅ $msg", duration = SnackbarDuration.Short)
            viewModel.clearAddResultMsg()
        }
    }

    // ── 搜尋新增景點 Sheet ───────────────────────────────────────
    if (showAddSearch) {
        AddPlaceSearchSheet(
            results   = addSearchResults,
            isLoading = addSearchLoading,
            onSearch  = { viewModel.searchPlacesToAdd(it) },
            onAdd     = { viewModel.addPlaceCandidate(it) },
            onDismiss = { showAddSearch = false; viewModel.clearAddSearchResults() }
        )
    }

    // ── 探索附近景點 Sheet ───────────────────────────────────────
    if (showExplore) {
        ExploreNearbySheet(
            spotName  = exploreSpotName,
            results   = exploreResults,
            isLoading = exploreLoading,
            onAdd     = { viewModel.addPlaceCandidate(it) },
            onDismiss = { showExplore = false; viewModel.clearExploreResults() }
        )
    }

    // ── 歷史行程資料新鮮度提醒（超過半年才出現一次）─────────────────
    val staleDataNotice by viewModel.staleDataNotice.collectAsState()
    LaunchedEffect(staleDataNotice) {
        val notice = staleDataNotice
        if (notice != null) {
            snackbarHostState.showSnackbar(
                message  = "⏳ $notice",
                duration = SnackbarDuration.Long
            )
            viewModel.dismissStaleDataNotice()
        }
    }

    // ── 邀請共編 Dialog（共用元件）──────────────────────────────
    // 邀請好友結果 → snackbar
    val inviteFriendResult by collabVm.inviteFriendResult.collectAsState()
    LaunchedEffect(inviteFriendResult) {
        val msg = inviteFriendResult
        if (msg != null) {
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            collabVm.clearInviteFriendResult()
        }
    }
    if (showMapShare) {
        buildFullRouteUrl(stops, stopLocations, transportMode)?.let { route ->
            MapShareDialog(route = route, onDismiss = { showMapShare = false })
        } ?: run { showMapShare = false }
    }

    if (showShareSheet) {
        val acceptedFriends by friendVm.acceptedFriends.collectAsState()
        val memberEmails by collabVm.memberEmails.collectAsState()
        CollabInviteDialog(
            shareLink     = collabVm.getShareLink(),
            joinPin       = joinPin,
            onlineMembers = onlineMembers,
            friends       = acceptedFriends,
            invitedEmails = memberEmails,
            onInviteFriend = { email, name ->
                val title = itinerary?.title?.ifBlank { itinerary?.aiTitle } ?: ""
                collabVm.inviteFriendToCollab(email, name, title)
            },
            tripTitle     = itinerary?.title?.ifBlank { itinerary?.aiTitle } ?: "",
            onDismiss     = { showShareSheet = false }
        )
    }

    // ── 在線成員清單 Dialog ─────────────────────────────────────
    if (showOnlineMembers) {
        OnlineMembersDialog(
            members       = collabMembers,
            myUserId      = myUserId,
            isOwner       = isOwner,
            onRoleChange  = { uid, role -> collabVm.updateMemberRole(uid, role) },
            onKick        = { uid -> collabVm.removeMember(uid) },
            onLeave       = {
                showOnlineMembers = false
                collabVm.leaveCollab { onCancel() }
            },
            onDismiss     = { showOnlineMembers = false },
            legacyMembers = onlineMembers
        )
    }
    // ── 指定景點 Dialog（輸入名稱 → 自動查詢並插入最順路位置）─────────
    if (showAddSpecificPoi) {
        AddSpecificPoiDialog(
            isLoading = addPoiLoading,
            error     = addPoiError,
            onSubmit  = { viewModel.addSpecificStop(it) },
            onDismiss = {
                if (!addPoiLoading) {
                    showAddSpecificPoi = false
                    viewModel.clearAddPoiStatus()
                }
            }
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("刪除行程", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "確定要刪除「${itinerary?.title?.ifBlank { itinerary?.aiTitle } ?: "此行程"}」嗎？\n\n此操作無法復原。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteCurrentItinerary()
                    }
                ) {
                    Text("刪除", color = PDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 行程已開始就不是「預覽」了：拿掉這幾個字，只留進行中徽章
                            // （原本兩者並排會把徽章擠到換行）
                            if (!tripProgress.isOngoing) Text(
                                "行程預覽",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PIink,
                                maxLines = 1
                            )
                            // 狀態徽章（對齊網頁 hero-status-badge）
                            when {
                                tripProgress.isOngoing -> TripStatusBadge(
                                    "⚡ 進行中", DesignTokens.StatusOngoingBg, DesignTokens.StatusOngoingText,
                                    startPadding = 0.dp)
                                tripProgress.isCompleted -> TripStatusBadge(
                                    "🎉 已完成", DesignTokens.Surface2, PIink2)
                            }
                        }
                        itinerary?.let {
                            Text(
                                it.title.ifBlank { it.aiTitle },
                                fontSize = 13.sp, color = PIink2, maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = PIink)
                    }
                },
                actions = {
                    // ── 成員徽章：共編行程常駐顯示「旅伴」（同網頁；網頁不回報在線狀態，不顯示幾人在線）；
                    //    點擊開成員面板，owner 可在面板調整「可編輯／唯讀」編輯權限 ──
                    if (collabMembers.isNotEmpty() || onlineMembers.size > 1) {
                        Surface(
                            onClick  = { showOnlineMembers = true },
                            shape    = RoundedCornerShape(999.dp),
                            color    = Color(0xFF10B981).copy(alpha = 0.12f),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    "👥 旅伴",
                                    fontSize = 13.sp,
                                    color      = Color(0xFF059669),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (tripProgress.isOngoing) {
                        // 進行中的地圖（停車點、廁所、你在這裡）
                        IconButton(onClick = { tripVm.mapMode = "plain"; viewModel.currentScreen = "trip_map" }) {
                            Icon(Icons.Default.Map, contentDescription = "查看地圖", tint = PIink2)
                        }
                        // 🧪 Debug-only：Demo 模式開關（假 GPS＋demo 時鐘＋demo 工具列），release build 不會出現
                        if (com.example.travellink_ai.BuildConfig.DEBUG) {
                            IconButton(onClick = { tripVm.demoModeOn = !tripVm.demoModeOn }) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = if (tripVm.demoModeOn) "關閉 Demo 模式" else "開啟 Demo 模式（假 GPS、時間快轉、立即發通知）",
                                    tint = if (tripVm.demoModeOn) Color(0xFFE53935) else PIink2
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = "邀請共編",
                            tint = Color(0xFF6366F1)
                        )
                    }
                    // 只有 owner 才能刪除（非 owner 含 viewer/editor 都看不到刪除按鈕）；
                    // 進行中不給刪（頂欄也放不下），要刪先結束或重設
                    if (isOwner && !tripProgress.isOngoing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "刪除行程",
                                tint = PDanger
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = PSurface2,
        bottomBar = {
            Surface(
                color = Color.White, shadowElevation = 8.dp,
                // 量的是整個底部欄（含導覽列）；隨行管家頭像的活動範圍據此停在它上方
                modifier = Modifier.onGloballyPositioned { onBottomBarHeightChange(it.size.height) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (ongoing != null) {
                        // ── 進行中：結束行程＋重設進度（對齊網頁 hero；唯讀成員不顯示）──
                        if (!isViewer) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { showResetDialog = true },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PIink2),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PBorder)
                            ) { Text("↩ 重設進度", fontWeight = FontWeight.SemiBold) }
                            Button(
                                onClick = ongoing.requestFinish,
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PAccent)
                            ) { Text("🏁 結束行程", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        }
                    } else {
                        // ── 開始行程（W3）：同一頁切成進行中模式，不換頁 ──────────
                        if (stops.isNotEmpty()) {
                            Button(
                                onClick = { tripVm.startTrip() },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    // F5：主要動作用綠 accent（網頁語意），不用橘 accent2
                                    containerColor = PAccent
                                )
                            ) {
                                Text("▶ 開始行程", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PAccent),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PAccent)
                            ) { Text("重新規劃", fontWeight = FontWeight.SemiBold) }

                            Button(
                                onClick = onConfirm,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                                enabled = stops.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Place, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("查看路線", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    // 🗺 Google Maps 路線＋⋯ 更多（對齊網頁 hero：分享地圖、匯出行程圖收進「更多」）
                    val routeReady = stops.count { it.lat != null || stopLocations[it.name] != null } >= 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { launchFullRoute(previewCtx, stops, stopLocations, transportMode) },
                            modifier = Modifier.weight(1f),
                            enabled = routeReady
                        ) {
                            Text("🗺 Google Maps 路線",
                                color = PAccent, fontWeight = FontWeight.SemiBold)
                        }
                        Box {
                            var moreOpen by remember { mutableStateOf(false) }
                            TextButton(onClick = { moreOpen = true }) {
                                Text("⋯ 更多", color = PAccent, fontWeight = FontWeight.SemiBold)
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("📤 分享地圖") },
                                    enabled = routeReady,
                                    onClick = { moreOpen = false; showMapShare = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("🖼 匯出行程圖") },
                                    enabled = stops.isNotEmpty(),
                                    onClick = { moreOpen = false; onExportImage() }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // 拖曳景點到畫面邊緣時自動捲動要用：清單狀態＋可視範圍（root 座標）
        val mainListState = rememberLazyListState()
        val mainListRef = remember { LayoutRef() }
        LazyColumn(
            state = mainListState,
            modifier = Modifier.fillMaxSize().padding(padding)
                .onGloballyPositioned { mainListRef.coords = it },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── Viewer 唯讀提示橫幅 ──────────────────────────────
            if (isViewer) item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(Color(0xFFFFF8E1), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("👁️", fontSize = 16.sp)
                    Text(
                        "你目前是唯讀成員，無法編輯景點。請聯絡行程擁有者取得編輯權限。",
                        fontSize = 13.sp,
                        color = Color(0xFF795548)
                    )
                }
            }

            // ── 行程摘要卡（進行中改由日程主卡顯示進度與今日天氣）────────
            if (ongoing == null) item {
                itinerary?.let { itin ->
                    // ── 天氣預報（C5）：每天以當天第一個有座標的景點對應 CWA 鄉鎮預報 ──
                    // 多日行程逐日一列（對齊網頁「行程天氣」每天一格）；單日行程維持一列
                    val tripDateStr = itin.days.substringBefore(" ").take(10)
                    val dayTargets = remember(stops, stopLocations, tripDateStr) {
                        weatherTargetsByDay(stops, stopLocations, tripDateStr)
                    }
                    val dailyWeather by produceState<List<Pair<Int, WeatherInfo>>>(
                        initialValue = emptyList(), dayTargets
                    ) {
                        if (dayTargets.isEmpty()) {
                            android.util.Log.d("TravelLink_Debug",
                                "🌦 天氣未查詢：date=「$tripDateStr」無可用座標或日期")
                        }
                        value = withContext(Dispatchers.IO) {
                            dayTargets.mapNotNull { t ->
                                CwaWeatherService.fetchForecast(t.lat, t.lng, t.date)?.let { t.day to it }
                            }
                        }
                    }
                    val isMultiDay = dayTargets.size > 1

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                // days 兩種格式：「yyyy/MM/dd HH:mm - yyyy/MM/dd HH:mm」（App）
                                // 或「N小時」（網頁）。有時間資訊時多顯示一顆 🕒 chip，
                                // 讓精靈選的出發時間/遊玩時數在摘要卡看得到
                                val startPart  = itin.days.substringBefore(" - ")
                                val endPart    = itin.days.substringAfter(" - ", "")
                                val startClock = startPart.substringAfter(" ", "").take(5)
                                val endClock   = endPart.substringAfter(" ", "").take(5)
                                SummaryChip("📅", summaryDateText(startPart.take(10), stops))
                                // 時間以實際排出來的行程為準（精靈設的是可用時段，實際常提早收工）；
                                // 站點還沒有時間時才退回精靈設定
                                val actualRange = actualTimeRange(stops)
                                if (actualRange != null) {
                                    SummaryChip("🕒", actualRange)
                                } else if (startClock.contains(":") && endClock.contains(":")) {
                                    SummaryChip("🕒", "$startClock–$endClock")
                                }
                                SummaryChip("👥", itin.people)
                                // 景點數不含出發/回程車站
                                SummaryChip("📍", "${stops.count { !it.isStation }} 個景點")
                            }
                            if (dailyWeather.isNotEmpty()) {
                                HorizontalDivider(color = PBorder.copy(alpha = 0.5f))
                            }
                            dailyWeather.forEach { (day, w) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = if (isMultiDay) 6.dp else 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isMultiDay) {
                                        Text(
                                            "第 $day 天 ${w.date.drop(5)}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PIink2,
                                            modifier = Modifier.width(78.dp)
                                        )
                                    }
                                    Text(w.emoji, fontSize = 17.sp)
                                    Spacer(Modifier.width(8.dp))
                                    // 鄉鎮放第二行：同一行時天氣描述一長（如「短暫陣雨或雷雨」）就擠到換行跑版
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = buildString {
                                                if (w.description.isNotBlank()) append("${w.description}・")
                                                append("${w.minTemp}–${w.maxTemp}°C")
                                                if (w.rainProbability >= 0) append("・降雨 ${w.rainProbability}%")
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = PIink
                                        )
                                        if (w.townshipName.isNotBlank()) {
                                            Text(w.townshipName, fontSize = 12.sp, color = PIink2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── ⏱️ 延誤提醒（AMD 組）：旅程應變已整合進隨行管家，這裡只在排不下時提醒，按下打開管家處理 ──
            if (agentEnabled) agentDelay?.takeIf { it.conflicts.isNotEmpty() || agentSimDelay }?.let { d ->
                item {
                    com.example.travellink_ai.ui.agent.DelayEntry(d, enabled = agentRun?.running != true) {
                        viewModel.setAssistantOpen(true)
                        agentVm.startDelay()
                    }
                }
            }

            // ── 🚦 主要交通工具（對齊網頁 #tripPrimaryVehicleWrap：行程進行中與唯讀成員不顯示）──
            if (stops.isNotEmpty() && !isViewer && !tripProgress.isOngoing) item {
                val isIsland = com.example.travellink_ai.data.island.IslandRegistry
                    .byDestination(itinerary?.region ?: "") != null
                PrimaryVehicleRow(
                    mode = transportMode,
                    // 離島不能開車上島（與精靈同規則）
                    options = PRIMARY_VEHICLE_OPTIONS.filter { !(isIsland && it.first == "car") },
                    onChange = { viewModel.setTripTransportMode(it) }
                )
            }

            // ── 行程進行中：同一頁切成打卡時間軸（編輯鎖住，對齊網頁）────
            if (ongoing != null) ongoingTripItems(ongoing)

            // ── 可拖曳排序的景點列表 ────────────────────────────
            if (ongoing == null) item {
                DraggableStopsList(
                    stops                 = stops,
                    transitTimes          = transitTimes,
                    businessHourConflicts = businessHourConflicts,
                    editingLocks          = editingLocks,
                    myUserId              = myUserId,
                    isViewer              = isViewer,
                    transportMode         = transportMode,
                    segmentModes          = segmentModes,
                    onMoveStop            = { from, to, day -> viewModel.moveStop(from, to, day) },
                    listState             = mainListState,
                    listRef               = mainListRef,
                    onSegmentWalkChange   = { id, walk -> viewModel.setSegmentWalk(id, walk) },
                    onEdit                = { stopId ->
                        val tappedStop = stops.firstOrNull { it.stopId == stopId }
                        if (tappedStop != null && !tappedStop.isStation) {
                            viewModel.clearSuggestions()
                            editingStopId = stopId
                        }
                    }
                    ,dayEndMins = previewDayEndMins
                    ,overrun = planOverrun
                    // AMD 模式：超時時可以直接請 AI 調整（在隨行管家裡跑，提案一樣要使用者按套用）
                    ,onAskAiForOverrun = if (!agentEnabled) null else fun() {
                        val o = planOverrun ?: return
                        viewModel.setAssistantOpen(true)
                        agentVm.startUser(
                            "行程預計 ${DayPlanner.hhmmOf(o.plannedEnd)} 結束，比我設定的 ${DayPlanner.hhmmOf(o.userEnd)} " +
                                "晚 ${o.overMin} 分鐘。請刪掉或縮短停留，讓行程在 ${DayPlanner.hhmmOf(o.limit)} 前結束。",
                            userText = "⏰ 行程超過我設定的 ${DayPlanner.hhmmOf(o.userEnd)}，請幫我調整",
                            // 結束時間送使用者設定的那個（預設會送行程排定的結束，那樣後端看不出超時）
                            endTime = DayPlanner.hhmmOf(o.userEnd)
                        )
                    }
                )
            }

            // ── 新增景點 / 指定景點按鈕（viewer、進行中隱藏）───────────────
            if (!isViewer && ongoing == null) item {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 搜尋新增景點：搜尋 Google Maps → 卡片清單 → 逐張加入（對齊網頁端）
                    OutlinedButton(
                        onClick = { viewModel.clearAddSearchResults(); showAddSearch = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PAccent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PBorder)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜尋新增景點", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // 探索附近景點：依行程中心推薦附近真實景點（距離／營業時間／評分／AI推薦）
                    OutlinedButton(
                        onClick = {
                            exploreSpotName = null
                            showExplore = true
                            viewModel.exploreNearby(null)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PAccent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PBorder)
                    ) {
                        Text("🧭", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("探索附近景點", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── 費用估算卡（A2）─────────────────────────────────────
            if (stops.isNotEmpty()) item {
                Spacer(Modifier.height(12.dp))
                // 本地成本參考庫（含真實票價／餐費），背景載入避免阻塞 UI；
                // 載入前先以參考表估算，就緒後自動重算為實際費用。
                val costBreakdown = remember(stops, transitTimes, segmentModes, costRef) {
                    estimateTripCost(
                        stops        = stops,
                        transitTimes = transitTimes,
                        segmentModes = segmentModes,
                        budget       = itinerary?.budget ?: "",
                        people       = itinerary?.people ?: "2人",
                        reference    = costRef
                    )
                }
                // 超出預算時的替換建議（候選要檢查它那一天有沒有開）
                val budgetSwap = remember(stops, costRef, itinerary?.days) {
                    costRef?.let { ref ->
                        BudgetSwap.propose(
                            stops = stops,
                            places = ref.places,
                            lookupFee = { name, lat, lng -> ref.lookup(name, lat, lng)?.fee },
                            dateOfDay = { day -> LocalAlternatives.dateOfDay(itinerary?.days, day) }
                        )
                    }
                }
                val swapTargetLocked = budgetSwap?.target?.stopId
                    ?.let { editingLocks[it] }
                    ?.let { it.uid != myUserId && !it.isExpired } == true
                CostEstimateCard(
                    costBreakdown,
                    budget = itinerary?.budget ?: "",
                    swap = budgetSwap,
                    swapLockedByOther = swapTargetLocked,
                    // 檢視者不能改；進行中的行程不做預算替換（時間與打卡都已在跑）
                    onSwap = if (isViewer || ongoing != null) null else { proposal, option ->
                        viewModel.updateStop(
                            proposal.target.stopId,
                            BudgetSwap.applyTo(
                                proposal.target, option,
                                LocalAlternatives.dateOfDay(itinerary?.days, proposal.target.dayIndex)
                            )
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── 回程船班卡（A5）：離島行程才有；本島走下面的台鐵卡 ──────
            // 進行中改看 ongoingTripItems 裡隨進度浮動的版本
            if (ongoing == null) islandOfTrip?.let { isl ->
                item {
                    Spacer(Modifier.height(4.dp))
                    ReturnFerryCard(
                        island = isl,
                        schedules = ferrySchedules,
                        // 行程最後一站（回程港口）的時間就是預計抵港時間
                        arriveAtPortHhmm = stops.lastOrNull { it.isFerry }?.time
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── 回程台鐵班次卡（A7 ②）：查不到就整張不顯示 ──────────
            if (ongoing == null) returnTrains?.let { plan ->
                item {
                    Spacer(Modifier.height(4.dp))
                    ReturnTrainCard(plan)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ── 編輯 / 新增 ModalBottomSheet ────────────────────────────
    editingStopId?.let { stopId ->
        val isNew = stopId.isEmpty()
        val existing = if (!isNew) stops.firstOrNull { it.stopId == stopId } else null

        // 若意外選到車站（isStation=true），直接關閉不開啟 Sheet
        if (!isNew && existing?.isStation == true) {
            editingStopId = null
            return@let
        }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        // 取得鎖（非新增景點才需要），取不到則關閉 sheet
        var lockAcquired by remember(stopId) { mutableStateOf(isNew) }
        LaunchedEffect(stopId) {
            if (!isNew) {
                lockAcquired = collabVm.acquireEditingLock(stopId)
                if (!lockAcquired) editingStopId = null  // 被鎖，取消開啟
            }
        }

        if (lockAcquired) {
            ModalBottomSheet(
                onDismissRequest = {
                    if (!isNew) collabVm.releaseEditingLock(stopId)
                    editingStopId = null; viewModel.clearSuggestions()
                },
                sheetState = sheetState,
                containerColor = Color.White,
                contentWindowInsets = { WindowInsets(0) }
            ) {
                // 附近替代景點：本地資料即時算，不必等 AI（新增景點時沒有對象，不顯示）
                val alternatives = remember(existing, stops, costRef) {
                    val ref = costRef
                    if (existing == null || ref == null) emptyList()
                    else LocalAlternatives.nearby(
                        existing, stops, ref.places,
                        LocalAlternatives.dateOfDay(itinerary?.days, existing.dayIndex)
                    ).take(LocalAlternatives.MAX_OPTIONS)
                }
                EditStopSheet(
                    existingStop         = existing,
                    alternatives         = alternatives,
                    onReplaceWith        = { place ->
                        existing?.let { target ->
                            collabVm.releaseEditingLock(stopId)
                            viewModel.updateStop(
                                stopId,
                                LocalAlternatives.applyTo(
                                    target, place,
                                    LocalAlternatives.dateOfDay(itinerary?.days, target.dayIndex),
                                    "替換原本的「${target.name}」"
                                )
                            )
                            editingStopId = null; viewModel.clearSuggestions()
                        }
                    },
                    suggestions          = aiSuggestions,
                    isLoadingSuggestions = isLoadingSug,
                    suggestionError      = suggestionErr,
                    onFetchSuggestions   = {
                        viewModel.fetchStopSuggestions(stops.indexOfFirst { it.stopId == stopId })
                    },
                    onExploreNearby = {
                        // 探索這附近：先關編輯 sheet，延遲讓其退場後再開探索 sheet
                        existing?.name?.let { spotName ->
                            collabVm.releaseEditingLock(stopId)
                            editingStopId = null; viewModel.clearSuggestions()
                            screenScope.launch {
                                kotlinx.coroutines.delay(320)
                                exploreSpotName = spotName
                                viewModel.exploreNearby(spotName)
                                showExplore = true
                            }
                        }
                    },
                    onSave = { updated ->
                        collabVm.releaseEditingLock(stopId)
                        viewModel.updateStop(stopId, updated)
                        editingStopId = null; viewModel.clearSuggestions()
                    },
                    onDelete = {
                        collabVm.releaseEditingLock(stopId)
                        viewModel.deleteStop(stopId)
                        editingStopId = null; viewModel.clearSuggestions()
                    },
                    onDismiss = {
                        collabVm.releaseEditingLock(stopId)
                        editingStopId = null; viewModel.clearSuggestions()
                    }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 可拖曳排序的景點列（Column-based，支援長按拖曳 + 平滑位移動畫）
// ════════════════════════════════════════════════════════════════
@Composable
private fun DraggableStopsList(
    stops: List<Stop>,
    transitTimes: List<Long>,
    businessHourConflicts: List<ItineraryViewModel.BusinessHourConflict>,
    editingLocks: Map<String, com.example.travellink_ai.data.model.EditingLock>,
    myUserId: String,
    isViewer: Boolean = false,
    transportMode: String = "taxi",
    segmentModes: List<String> = emptyList(),
    onSegmentWalkChange: (stopId: String, walk: Boolean) -> Unit = { _, _ -> },
    /** (from, to, 放開時所在的天；單日行程為 null) */
    onMoveStop: (Int, Int, Int?) -> Unit,
    onEdit: (String) -> Unit,
    /** 每日可用到幾點（分鐘），空 map 表示不顯示自由時間 */
    dayEndMins: Map<Int, Int> = emptyMap(),
    /** 超過使用者設定的結束時間：尾端改顯示警告（取代自由時間） */
    overrun: Overrun? = null,
    /** 超時警告上的「讓 AI 調整」（AMD 模式才有） */
    onAskAiForOverrun: (() -> Unit)? = null,
    /** 外層清單：拖到可視範圍上下緣時自動捲動 */
    listState: LazyListState? = null,
    listRef: LayoutRef? = null
) {
    val density = LocalDensity.current
    // 量不到實際高度時的備援（卡片 ~110dp）
    val estimatedItemHeightPx = with(density) { 110.dp.toPx() }

    var dragFromIndex by remember { mutableIntStateOf(-1) }
    // 卡片位移＝手指移動量＋拖曳中自動捲動的量（清單往上捲，卡片要往下補回才會留在手指下）
    var dragOffsetY   by remember { mutableFloatStateOf(0f) }

    // 每張卡／每條 Day 分隔在這個 Column 裡的實際位置。過去用固定 110dp 換算，
    // 但中間夾著空檔列、分隔線，清單越長誤差越大，多日行程常放不到想要的位置
    val itemBounds = remember { HashMap<Int, ClosedFloatingPointRange<Float>>() }
    val dividerTops = remember { HashMap<Int, Float>() }
    val columnRef = remember { LayoutRef() }

    fun boundsOf(i: Int): ClosedFloatingPointRange<Float> =
        itemBounds[i] ?: (i * estimatedItemHeightPx).let { it..(it + estimatedItemHeightPx) }
    fun draggedCenter(): Float {
        val b = boundsOf(dragFromIndex)
        return (b.start + b.endInclusive) / 2f + dragOffsetY
    }

    // 根據卡片目前位置即時計算「放下後」的目標 index（驅動其他卡片位移動畫）：
    // 卡片中心越過哪張卡的中心，就排到那張的位置
    val minIdx = if (stops.firstOrNull()?.isStation == true) 1 else 0
    val maxIdx = if (stops.lastOrNull()?.isStation == true) stops.size - 2 else stops.size - 1
    val dragTargetIndex = if (dragFromIndex == -1) -1 else {
        val c = draggedCenter()
        fun mid(i: Int) = boundsOf(i).let { (it.start + it.endInclusive) / 2f }
        val down = ((dragFromIndex + 1) until stops.size).lastOrNull { mid(it) < c }
        val up = (0 until dragFromIndex).firstOrNull { mid(it) > c }
        (down ?: up ?: dragFromIndex).coerceIn(minIdx, maxOf(minIdx, maxIdx))
    }
    val draggedHeight = if (dragFromIndex == -1) estimatedItemHeightPx
        else boundsOf(dragFromIndex).let { it.endInclusive - it.start }

    // 有任何景點被他人鎖定中 → 禁止拖曳（避免排序與編輯衝突）
    val anyLockByOthers = editingLocks.any { (_, lock) ->
        lock.uid != myUserId && !lock.isExpired
    }

    // A5 多日：畫面上只有 HH:mm，兩天一夜的清單裡兩個 09:00 長得一樣，
    // 所以每一天的第一站前面插一條分隔。單日行程不會出現。
    val dayCount = stops.maxOfOrNull { it.dayIndex }?.coerceAtLeast(1) ?: 1
    var lastDayDrawn = 0

    // 放開時落在哪一天：卡片中心在哪條 Day 分隔之下（在第一條之上就算第 1 天）
    fun dropDay(): Int? {
        if (dayCount <= 1) return null
        val c = draggedCenter()
        return dividerTops.filter { it.value <= c }.maxByOrNull { it.value }?.key ?: 1
    }

    val onDropState = rememberUpdatedState {
        val from = dragFromIndex
        if (from != -1) {
            val to = dragTargetIndex
            val day = dropDay()
            if (to != from || (day != null && day != stops.getOrNull(from)?.dayIndex)) onMoveStop(from, to, day)
        }
        dragFromIndex = -1; dragOffsetY = 0f
    }

    // 拖曳中：卡片靠近清單上下緣就自動捲動，越靠邊越快
    if (listState != null && listRef != null) {
        val dragging = dragFromIndex != -1
        LaunchedEffect(dragging) {
            if (!dragging) return@LaunchedEffect
            val edge = with(density) { 96.dp.toPx() }
            val maxStep = with(density) { 16.dp.toPx() }
            while (dragFromIndex != -1) {
                withFrameNanos { }
                val col = columnRef.coords?.takeIf { it.isAttached } ?: continue
                val vp = listRef.coords?.takeIf { it.isAttached }?.boundsInRoot() ?: continue
                val y = col.localToRoot(androidx.compose.ui.geometry.Offset(0f, draggedCenter())).y
                val step = when {
                    y < vp.top + edge -> -maxStep * ((vp.top + edge - y) / edge).coerceIn(0.2f, 1f)
                    y > vp.bottom - edge -> maxStep * ((y - (vp.bottom - edge)) / edge).coerceIn(0.2f, 1f)
                    else -> 0f
                }
                if (step != 0f) dragOffsetY += listState.scrollBy(step)
            }
        }
    }

    Column(Modifier.onGloballyPositioned { columnRef.coords = it }) {
        stops.forEachIndexed { index, stop ->
            if (dayCount > 1 && stop.dayIndex != lastDayDrawn) {
                // 換日前先把前一天沒排滿的時間攤開來講
                if (lastDayDrawn > 0) stops.getOrNull(index - 1)
                    ?.let { FreeTimeRow(it, dayEndMins[it.dayIndex]) }
                lastDayDrawn = stop.dayIndex
                val day = stop.dayIndex
                Box(Modifier.onGloballyPositioned { dividerTops[day] = it.positionInParent().y }) {
                    PreviewDayDivider(day, stops.count { it.dayIndex == day && !it.isStation })
                }
            } else if (index > 0) {
                // 中場空檔：兩站之間扣掉車程後還空著的時間。不畫出來的話畫面上
                // 只看得到「柚子湖 13:51」接著「朝日溫泉 16:00」，中間一片空白
                // 沒有任何說明（實測 my_1786540468987 第 1 天等溫泉開門 61 分鐘）
                MidDayGapRow(
                    prev = stops[index - 1], next = stop,
                    // transitTimes[i]＝第 i 站 → 第 i+1 站；這裡要的是前一站到這一站那段。
                    // 過去取 [index]（這一站到下一站），換站後實測多算出「空檔 31 分」
                    transitMin = transitTimes.getOrNull(index - 1),
                    waitsForOpening = businessHourConflicts.any {
                        it.stopName == stop.name && it.conflictType == ItineraryViewModel.ConflictType.BEFORE_OPENING
                    }
                )
            }
            // key 確保 Compose 在重排後正確追蹤動畫狀態
            key(stop.order) {
                val isDragging = dragFromIndex == index
                val transitMin = transitTimes.getOrNull(index)

                // 計算非拖曳項目應向上/下位移多少（讓出空位）
                val targetShift = when {
                    dragFromIndex == -1 || isDragging -> 0f
                    // 向下拖：from+1 ~ target 的項目向上移
                    dragFromIndex < dragTargetIndex &&
                            index in (dragFromIndex + 1)..dragTargetIndex -> -draggedHeight
                    // 向上拖：target ~ from-1 的項目向下移
                    dragFromIndex > dragTargetIndex &&
                            index in dragTargetIndex until dragFromIndex -> draggedHeight
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(
                    targetValue = targetShift,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioLowBouncy
                    ),
                    label = "shift_$index"
                )

                // 車站站點或 viewer 不可拖曳；一般景點才掛 pointerInput
                val dragHandleMod = if (stop.isStation || isViewer) Modifier else Modifier.pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragFromIndex = index; dragOffsetY = 0f },
                        onDrag      = { _, delta -> dragOffsetY += delta.y },
                        onDragEnd   = { onDropState.value() },
                        onDragCancel = { dragFromIndex = -1; dragOffsetY = 0f }
                    )
                }

                Box(
                    modifier = Modifier
                        // 量位置要在 graphicsLayer 之前：量到的是版面位置，不含拖曳位移
                        .onGloballyPositioned {
                            val top = it.positionInParent().y
                            itemBounds[index] = top..(top + it.size.height)
                        }
                        .zIndex(if (isDragging) 2f else 0f)
                        .graphicsLayer {
                            translationY    = if (isDragging) dragOffsetY else animatedShift
                            shadowElevation = if (isDragging) 20f else 0f
                            scaleX = if (isDragging) 1.028f else 1f
                            scaleY = if (isDragging) 1.028f else 1f
                        }
                ) {
                    val lockByOther = editingLocks[stop.stopId]
                        ?.takeIf { it.uid != myUserId && !it.isExpired }
                    StopTimelineRow(
                        stop          = stop,
                        index         = index,
                        totalStops    = stops.size,
                        transitMin    = transitMin,
                        isDragging    = isDragging,
                        isViewer      = isViewer,
                        transportMode = segmentModes.getOrElse(index) { transportMode },
                        tripMode      = transportMode,
                        onSegmentWalkChange = if (isViewer) null else { walk -> onSegmentWalkChange(stop.stopId, walk) },
                        // 有他人持鎖、任意他人鎖或 viewer → 停用 drag handle
                        dragHandleMod = if (anyLockByOthers || isViewer) Modifier else dragHandleMod,
                        conflict      = businessHourConflicts.firstOrNull { it.stopIndex == index },
                        lockByOther   = lockByOther,
                        onEdit        = { if (!isViewer && !stop.isStation && lockByOther == null) onEdit(stop.stopId) }
                    )
                }
            }
        }
        if (overrun != null) OverrunRow(overrun, onAskAiForOverrun)
        else stops.lastOrNull()?.let { FreeTimeRow(it, dayEndMins[it.dayIndex]) }
    }
}

/** 行程超過精靈設定的結束時間（過去超時時這裡什麼都不顯示，自由時間列只在有空檔時出現） */
@Composable
private fun OverrunRow(o: Overrun, onAskAi: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)
            .background(Color(0xFFFFF4E5), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "⚠️ 預計 ${DayPlanner.hhmmOf(o.plannedEnd)} 結束，比你設定的 ${DayPlanner.hhmmOf(o.userEnd)} 晚 ${o.overMin} 分鐘",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A4B00)
        )
        Text(
            (if (o.limit != o.userEnd) "離島要預留回程航程，" else "") + "可以刪掉一站或縮短停留時間。",
            fontSize = 12.sp, color = Color(0xFF8A4B00)
        )
        if (onAskAi != null) OutlinedButton(
            onClick = onAskAi,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            border = BorderStroke(1.dp, Color(0xFFB0121B).copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0121B))
        ) { Text("🤖 讓 AI 調整", fontSize = 13.sp, maxLines = 1) }
    }
}

/**
 * 兩站之間的空檔（扣掉車程），≥ 30 分才顯示。
 *
 * 空檔本身未必是錯的——等朝日溫泉 16:00 開門去泡是合理的安排——錯的是
 * 讓使用者對著時間軸上一段沒有解釋的空白，猜自己是不是看錯了。
 */
@Composable
private fun MidDayGapRow(prev: Stop, next: Stop, transitMin: Long?, waitsForOpening: Boolean) {
    if (prev.isStation || next.isStation) return
    val prevEnd = (parsePreviewClock(prev.time) ?: return) + prev.duration.toInt()
    val nextStart = parsePreviewClock(next.time) ?: return
    val idle = nextStart - prevEnd - (transitMin?.toInt() ?: 0)
    if (idle < 30) return
    val label = if (idle >= 60) "${idle / 60} 小時 ${idle % 60} 分" else "$idle 分"
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(if (waitsForOpening) "⏳" else "🕐", fontSize = 13.sp)
        Text(
            if (waitsForOpening) "等 ${next.name} 開門 $label" else "空檔 $label",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PIink2
        )
        Text(
            if (waitsForOpening) "可先去附近走走" else "可自行安排或加站",
            fontSize = 12.sp, color = PIink2
        )
    }
}

/**
 * 自由時間列：當日剩餘時間 ≥ 30 分才顯示。
 *
 * 行程有空檔本身不是問題——把空檔藏進某個景點的停留時間才是。使用者看到
 * 「哈巴狗岩 停留 45 分」會真的站在那裡發呆，看到「自由時間」則知道能自己安排。
 */
@Composable
private fun FreeTimeRow(lastStop: Stop, dayEnd: Int?) {
    if (dayEnd == null || lastStop.isStation) return
    val end = (parsePreviewClock(lastStop.time) ?: return) + lastStop.duration.toInt()
    val free = dayEnd - end
    if (free < 30) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("🕐", fontSize = 13.sp)
        Text("自由時間 " + if (free >= 60) "${free / 60} 小時 ${free % 60} 分" else "$free 分",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PIink2)
        Text("可自行安排或加站", fontSize = 12.sp, color = PIink2)
    }
}

/** "HH:mm" → 分鐘；解析不出回 null */
private fun parsePreviewClock(v: String): Int? {
    val p = v.split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
    return h * 60 + m
}

// ════════════════════════════════════════════════════════════════
// 單一景點列（dot + 連接線 + 卡片）
// ════════════════════════════════════════════════════════════════
@Composable
private fun StopTimelineRow(
    stop: Stop,
    index: Int,
    totalStops: Int,
    transitMin: Long?,
    isDragging: Boolean,
    dragHandleMod: Modifier,
    conflict: ItineraryViewModel.BusinessHourConflict?,
    lockByOther: com.example.travellink_ai.data.model.EditingLock? = null,
    isViewer: Boolean = false,
    transportMode: String = "taxi",
    tripMode: String = transportMode,
    onSegmentWalkChange: ((Boolean) -> Unit)? = null,
    onEdit: () -> Unit
) {
    val isLast = index == totalStops - 1
    var segmentMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // ── 左側時間欄（只顯示 HH:mm，移除可能的日期前綴）──────
        val displayTime = stop.time.trim().let { t ->
            if (t.contains(" ")) t.substringAfterLast(" ") else t
        }
        Text(
            text = displayTime,
            modifier = Modifier.width(54.dp).padding(top = 6.dp),
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            color = PIink2,
            lineHeight = 16.sp
        )
        Spacer(Modifier.width(8.dp))

        // ── 中間：dot + 連接線（固定 68 dp 寬（放得下橫式交通 chip），確保每列卡片起始位置一致）──
        Column(
            modifier = Modifier.width(68.dp),   // ← 固定寬度，解決最後一張卡片偏移問題
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isDragging) PAccentDark else PAccent)
            )
            // 連接線（含交通時間）；最後一個景點保留同等寬度但不繪製任何東西
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 垂直線
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(
                                Brush.verticalGradient(
                                    listOf(PAccent.copy(0.4f), PAccent.copy(0.1f))
                                )
                            )
                    )
                    // 交通時間 chip（有資料才顯示）
                    if (transitMin != null && transitMin > 0) {
                      Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PSurface2,
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp, PBorder.copy(alpha = 0.6f)
                            ),
                            // 橫式單行：原本 56dp 欄寬會把「16 分鐘」折成直排。
                            // unbounded 讓「1小時1分」這種長的可略超出欄寬（兩側各有 8/10dp 間距可吃），文字改用精簡格式
                            modifier = Modifier
                                .wrapContentWidth(unbounded = true)
                                .then(if (onSegmentWalkChange != null)
                                    Modifier.clickable { segmentMenuOpen = true } else Modifier)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(modeEmoji(transportMode), fontSize = 13.sp, maxLines = 1)
                                Text(
                                    fmtTransitCompact(transitMin),
                                    fontSize = 14.sp,
                                    color = PIink2,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        // 逐段交通（對齊網頁路段下拉）：只給「主要交通工具」與「走路」
                        DropdownMenu(expanded = segmentMenuOpen, onDismissRequest = { segmentMenuOpen = false }) {
                            if (tripMode != "walking") {
                                DropdownMenuItem(
                                    text = { Text("${modeEmoji(tripMode)} ${modeLabel(tripMode)}",
                                        fontWeight = if (!stop.walkNext) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { segmentMenuOpen = false; onSegmentWalkChange?.invoke(false) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("🚶 走路", fontWeight = if (stop.walkNext) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { segmentMenuOpen = false; onSegmentWalkChange?.invoke(true) }
                            )
                        }
                      }
                    }
                }
            } else {
                // 最後一個景點：保持相同高度的空白，讓卡片對齊
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.width(10.dp))

        // ── 景點卡片 ────────────────────────────────────────
        if (stop.isStation) {
            // ── 車站卡片（出發/回程，固定不可編輯）──────────────
            Card(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = if (isLast) 0.dp else 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F3F0)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stop.emoji, fontSize = 24.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stop.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color(0xFF1F5448)
                        )
                        Text(
                            stop.desc,   // "出發車站" 或 "回程車站"
                            fontSize = 14.sp,
                            color = Color(0xFF2A6B5E)
                        )
                    }
                    // 🔒 固定車站標籤
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF2A6B5E).copy(alpha = 0.12f)
                    ) {
                        Text(
                            "固定",
                            fontSize = 13.sp,
                            color = Color(0xFF2A6B5E),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        } else {
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 4.dp)
                .then(if (!isViewer) Modifier.clickable(onClick = onEdit) else Modifier),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragging) PAccentLight else Color.White
            ),
            elevation = CardDefaults.cardElevation(if (isDragging) 6.dp else 0.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // 主資訊行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stop.emoji, fontSize = 24.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stop.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = PIink
                        )
                        if (stop.desc.isNotBlank()) {
                            Text(stop.desc, fontSize = 14.sp, color = PIink2, maxLines = 1)
                        }
                    }
                    // ≡ 拖曳 handle（viewer 或車站隱藏）
                    if (!isViewer && !stop.isStation) Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "長按拖曳排序",
                        tint = if (isDragging) PAccent else PBorder,
                        modifier = dragHandleMod
                            .size(22.dp)
                            .padding(start = 4.dp)
                    )
                }

                // 停留時間 + 編輯提示
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = PBorder.copy(alpha = 0.35f))
                Spacer(Modifier.height(5.dp))
                // 停留時間
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = PIink2,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        "停留 ${fmtDuration(stop.duration)}",
                        fontSize = 14.sp,
                        color = PIink2,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 點擊編輯提示（viewer 隱藏）：放停留時間右下另起一行，
                // 與停留時間同行時，時間一長就會把兩者擠到換行跑版
                if (!isViewer) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = PAccent.copy(alpha = 0.6f),
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            "點擊編輯",
                            fontSize = 13.sp,
                            color = PAccent.copy(alpha = 0.6f)
                        )
                    }
                }

                // 🔒 共編鎖定提示（他人正在編輯）
                if (lockByOther != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEDE7F6), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "鎖定中",
                            tint = Color(0xFF7B1FA2),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            "「${lockByOther.displayName}」正在編輯",
                            fontSize = 12.sp,
                            color = Color(0xFF7B1FA2),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // ⚠️ 營業時間衝突警告 / ℹ️ 開門前自動延後通知
                if (conflict != null) {
                    Spacer(Modifier.height(5.dp))
                    val conflictStyle = when (conflict.conflictType) {
                        ItineraryViewModel.ConflictType.BEFORE_OPENING ->
                            Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), Icons.Default.Schedule)
                        ItineraryViewModel.ConflictType.LONG_DETOUR ->
                            Triple(Color(0xFFFCE4EC), Color(0xFFC62828), Icons.Default.Warning)
                        else ->
                            Triple(Color(0xFFFFF3E0), Color(0xFFE65100), Icons.Default.Warning)
                    }
                    val (bgColor, fgColor, icon) = conflictStyle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = fgColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            when (conflict.conflictType) {
                                ItineraryViewModel.ConflictType.OUTSIDE_HOURS ->
                                    "排定 ${conflict.scheduledTime}，超出營業時間（${conflict.businessHours}）"
                                ItineraryViewModel.ConflictType.CLOSES_DURING_VISIT ->
                                    "停留至 ${conflict.endTime}，但營業時間（${conflict.businessHours}）已結束"
                                ItineraryViewModel.ConflictType.BEFORE_OPENING ->
                                    "原定 ${conflict.scheduledTime} 尚未開門，已自動調整至 ${conflict.openingTime}（${conflict.businessHours}）"
                                ItineraryViewModel.ConflictType.LONG_DETOUR ->
                                    "距前一站 ${conflict.drivingMins} 分鐘車程（超過 60 分），路途較遠，建議評估是否移除"
                            },
                            fontSize = 12.sp,
                            color = fgColor,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
        } // end else (non-station card)
    }
}

// ════════════════════════════════════════════════════════════════
// 費用估算卡（A2）
// ════════════════════════════════════════════════════════════════
// 🚆 回程台鐵班次卡（A7 ②）
// ════════════════════════════════════════════════════════════════
/**
 * 顯示「行程結束後接得上哪班火車」。
 * 有指定最終目的地時列精準班次與抵達時間；未指定時南下／北上分組各列幾班。
 */
// internal 而非 private：同模組的 androidTest 需要直接渲染這張卡來驗版面
// （繞過登入牆與 AI 生成，詳見 ReturnTrainCardTest）

/** 多日行程的日分隔（A5 Stage 5）。單日不會出現。 */
/** 存 LayoutCoordinates 給拖曳／捲動計算用；不放進 State，免得每次重新排版都觸發重組 */
internal class LayoutRef { var coords: androidx.compose.ui.layout.LayoutCoordinates? = null }

@Composable
private fun PreviewDayDivider(dayIndex: Int, stopCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Day $dayIndex", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = PIink)
        Text("$stopCount 個景點", fontSize = 12.sp, color = PIink2)
        HorizontalDivider(Modifier.weight(1f), color = PBorder)
    }
}

/**
 * 回程船班卡（A5 Stage 5）。版型沿用 A7 的回程台鐵卡，但資料性質不同：
 * 台鐵是即時 API，船班是人工抄的靜態表，所以這裡一定要標示來源與免責，
 * 資料過期時只留電話、不顯示時刻。
 */
@Composable
internal fun ReturnFerryCard(
    island: com.example.travellink_ai.data.island.IslandProfile,
    schedules: com.example.travellink_ai.data.island.FerrySchedules,
    arriveAtPortHhmm: String?
) {
    val route = schedules.routeForIsland(island.code) ?: return
    val season = route.activeSeason
    val stale = schedules.isStale()
    val boardBy = arriveAtPortHhmm
    // 抵港時間之後最早的一班；抓不到就不顯示班次（與 A7「對不上就不顯示」同原則）
    val next = if (stale || season == null || boardBy == null) null
        else season.nextDeparture("island", boardBy)
    val last = if (stale) null else season?.lastDeparture("island")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7F5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⛴️", fontSize = 18.sp)
                Text("回程船班", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PIink)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${route.islandPort} → ${route.mainlandPort}，航程約 ${route.sailingMins} 分鐘",
                fontSize = 12.sp, color = PIink2
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = PBorder.copy(alpha = 0.5f))
            Spacer(Modifier.height(8.dp))

            when {
                next != null -> {
                    Text("建議搭 $next 這班", fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold, color = PIink)
                    Text(
                        "行程預計 $boardBy 回到${route.islandPort}" +
                            (last?.let { "；當日末班 $it" } ?: ""),
                        fontSize = 12.sp, color = PIink2
                    )
                }
                last != null -> {
                    Text("當日末班 $last", fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold, color = PIink)
                    Text("行程結束時間晚於末班船，請調整行程或改訂隔日船班",
                        fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
                }
                else -> Text("班次資料需向船公司確認", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = PIink)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    if (stale) append("班表資料已逾 90 天未更新，僅顯示聯絡方式。")
                    else if (season?.isOfficial == false) append("時刻為代訂平台彙整、非船公司官方公告。")
                    append("實際以船公司公佈與候船室現場為準，天候不佳可能停航。")
                },
                fontSize = 11.sp, color = PIink2
            )
            route.contactable.firstOrNull()?.let { op ->
                Spacer(Modifier.height(4.dp))
                Text("☎ ${op.name} ${op.phone}", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = PIink)
            }
        }
    }
}

@Composable
internal fun ReturnTrainCard(
    plan: com.example.travellink_ai.data.transit.ReturnTrainPlan
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFF0F7F5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("🚆", fontSize = 18.sp)
                Text("回程班次", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PIink)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("預計 ${plan.arriveTime} 抵達${plan.stationName}")
                    if (plan.finalDestination.isNotBlank()) append("，往${plan.finalDestination}")
                },
                fontSize = 12.sp,
                color = PIink2
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = PBorder.copy(alpha = 0.5f))
            Spacer(Modifier.height(6.dp))

            if (plan.finalDestination.isNotBlank()) {
                // OD 模式：班次已經是往目的地的，直接列
                plan.departures.forEach { TrainRow(it, showDirection = false) }
            } else {
                // 車站模式：南下北上分組
                plan.departures.groupBy { it.directionLabel }.forEach { (label, list) ->
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PIink3,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                    list.forEach { TrainRow(it, showDirection = false) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "班次資料來源：交通部 TDX，已預留 10 分鐘進站時間",
                fontSize = 10.sp,
                color = PIink3
            )
        }
    }
}

@Composable
private fun TrainRow(
    train: com.example.travellink_ai.data.transit.TraDeparture,
    showDirection: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                train.departureTime,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = PAccent
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "${train.trainType} ${train.trainNo}",
                    fontSize = 13.sp,
                    color = PIink
                )
                Text(
                    buildString {
                        if (showDirection) append("${train.directionLabel} ")
                        append("往${train.destinationName}")
                        train.arrivalTime?.let { append(" · ${it} 抵達") }
                        train.durationMins?.let { append("（${it} 分）") }
                    },
                    fontSize = 11.sp,
                    color = PIink2
                )
            }
        }
        // 誤點只在行程進行中才有值（預覽頁通常為 null）
        train.delayMins?.let { d ->
            if (d > 0) {
                Text("誤點 $d 分", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PDanger)
            } else {
                Text("準點", fontSize = 12.sp, color = PIink3)
            }
        }
    }
}

@Composable
private fun CostEstimateCard(
    breakdown: CostBreakdown,
    budget: String = "",
    swap: BudgetSwap.Proposal? = null,
    swapLockedByOther: Boolean = false,
    /** null＝不能替換（檢視者／進行中），只顯示超支提示 */
    onSwap: ((BudgetSwap.Proposal, BudgetSwap.Option) -> Unit)? = null
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFFF0F7F5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("💰", fontSize = 18.sp)
                    Text(
                        "費用估算",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PIink
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "總計約 ${"%,d".format(breakdown.grandTotal)} 元",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PAccent
                    )
                    Text(
                        "${breakdown.peopleCount} 人，每人約 ${"%,d".format(breakdown.totalPerPerson)} 元",
                        fontSize = 12.sp,
                        color = PIink2
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = PBorder.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))

            listOf(
                Triple("🚕", "交通費", breakdown.transportPerPerson),
                Triple("🎫", "入場費", breakdown.entrancePerPerson),
                Triple("🍽️", "餐費",   breakdown.mealPerPerson)
            ).forEach { (icon, label, amount) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$icon $label", fontSize = 13.sp, color = PIink2)
                    Text(
                        "${"%,d".format(amount)} 元/人",
                        fontSize = 13.sp,
                        color = PIink,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ── 預算追蹤（對齊網頁 renderBudgetTracker）──────────────
            // 依預算等級算「可動用餘額」＝人均級距 −（交通＋門票＋餐費），
            // 進度條顯示目前花費占人均預算上限的比例，超支轉紅提醒。
            CostConfig.budgetTier(budget)?.let { tier ->
                val spent = breakdown.totalPerPerson
                val denom = tier.perMax ?: tier.perMin
                val pct = if (denom > 0) (spent.toFloat() / denom).coerceIn(0f, 1f) else 0f
                val overBudget = tier.perMax != null && spent > tier.perMax

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = PBorder.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))

                val tierRangeText = when {
                    tier.perMax == null -> "每人 \$${"%,d".format(tier.perMin)} 以上"
                    tier.perMin > 0     -> "每人 \$${"%,d".format(tier.perMin)}–\$${"%,d".format(tier.perMax)}"
                    else                -> "每人 \$${"%,d".format(tier.perMax)} 內"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🎯 預算等級（${tier.key}）", fontSize = 13.sp, color = PIink2)
                    Text(tierRangeText, fontSize = 13.sp, color = PIink, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(PBorder.copy(alpha = 0.4f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (overBudget) Color(0xFFC0392B) else PAccent)
                    )
                }
                Spacer(Modifier.height(6.dp))

                if (overBudget) {
                    Text(
                        "⚠️ 已超出預算上限，每人超支約 \$${"%,d".format(spent - tier.perMax!!)}",
                        fontSize = 12.sp,
                        color = Color(0xFFC0392B),
                        fontWeight = FontWeight.Medium
                    )
                    if (onSwap != null) {
                        BudgetSwapSection(
                            swap = swap,
                            overBy = spent - tier.perMax,
                            lockedByOther = swapLockedByOther,
                            onSwap = onSwap
                        )
                    }
                } else {
                    val remainText = if (tier.perMax == null) {
                        "\$${"%,d".format((tier.perMin - spent).coerceAtLeast(0))}+"
                    } else {
                        "\$${"%,d".format((tier.perMin - spent).coerceAtLeast(0))}–\$${"%,d".format(tier.perMax - spent)}"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "花費占人均預算 ${(pct * 100).toInt()}%",
                            fontSize = 12.sp, color = PIink2
                        )
                        Text(
                            "可動用餘額 每人 $remainText",
                            fontSize = 12.sp, color = PAccent, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val matchedCount = breakdown.entranceMatched + breakdown.mealMatched
            if (matchedCount > 0) {
                Text(
                    "✓ 其中 $matchedCount 項採實際收費資料，估算更準確",
                    fontSize = 11.sp,
                    color = PAccent
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                "* 費用為估算值，實際以現場收費為準",
                fontSize = 11.sp,
                color = PIink2.copy(alpha = 0.7f)
            )
        }
    }
}

// ── 超出預算時的替換建議（對齊網頁「最貴景點換成附近省錢的」）──────
// 只建議、按下後再確認才換；換完走 updateStop，路線與時間照一般編輯重算。
@Composable
private fun BudgetSwapSection(
    swap: BudgetSwap.Proposal?,
    overBy: Int,
    lockedByOther: Boolean,
    onSwap: (BudgetSwap.Proposal, BudgetSwap.Option) -> Unit
) {
    Spacer(Modifier.height(8.dp))
    if (swap == null) {
        Text("💡 行程中沒有已知票價的收費景點可替換，超支多半來自交通或餐費",
            fontSize = 12.sp, color = PIink2)
        return
    }
    val targetName = swap.target.name
    if (swap.options.isEmpty()) {
        Text("💡 最貴的「$targetName」（門票 \$${swap.targetFee}/人）附近 " +
            "${BudgetSwap.MAX_KM.toInt()} 公里內沒有更省錢的景點",
            fontSize = 12.sp, color = PIink2)
        return
    }

    var pending by remember(swap) { mutableStateOf<BudgetSwap.Option?>(null) }

    Text("💡 可把最貴的「$targetName」（門票 \$${swap.targetFee}/人）換成附近：",
        fontSize = 12.sp, color = PIink, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    swap.options.forEach { opt ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(opt.place.name, fontSize = 13.sp, color = PIink, fontWeight = FontWeight.Medium)
                val feeText = when {
                    opt.feeKnown && opt.fee == 0 -> "免費"
                    opt.feeKnown -> "門票 \$${opt.fee}"
                    else -> "無門票資料，估 \$${opt.fee}"
                }
                Text("距離 ${"%.1f".format(opt.distanceKm)} 公里 · $feeText · 每人省約 \$${opt.saving}",
                    fontSize = 11.sp, color = PIink2)
            }
            TextButton(onClick = { pending = opt }, enabled = !lockedByOther) {
                Text("替換", fontSize = 13.sp)
            }
        }
    }
    if (lockedByOther) {
        Text("「$targetName」正在被其他成員編輯，稍後再替換", fontSize = 11.sp, color = PIink2)
    }
    val best = swap.options.maxOf { it.saving }
    if (best < overBy) {
        Text("換了仍會超支約 \$${"%,d".format(overBy - best)}（其餘來自交通或餐費）",
            fontSize = 11.sp, color = PIink2)
    }

    pending?.let { opt ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("替換景點") },
            text = {
                Text("把「$targetName」換成「${opt.place.name}」？\n" +
                    "每人門票約省 \$${opt.saving}，換完會重新計算路線與時間。")
            },
            confirmButton = {
                TextButton(onClick = { pending = null; onSwap(swap, opt) }) { Text("替換") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            }
        )
    }
}

// ════════════════════════════════════════════════════════════════
// 編輯 / 新增景點 Sheet
// ════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStopSheet(
    existingStop: Stop?,
    alternatives: List<LocalAlternatives.Candidate>,
    onReplaceWith: (PlaceCost) -> Unit,
    suggestions: List<ItineraryViewModel.StopSuggestion>,
    isLoadingSuggestions: Boolean,
    suggestionError: String?,
    onFetchSuggestions: (timeHint: String) -> Unit,
    onExploreNearby: () -> Unit,
    onSave: (Stop) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var emoji         by remember { mutableStateOf(existingStop?.emoji        ?: "📍") }
    var name          by remember { mutableStateOf(existingStop?.name         ?: "") }
    var time          by remember { mutableStateOf(existingStop?.time         ?: "") }
    var desc          by remember { mutableStateOf(existingStop?.desc         ?: "") }
    var duration      by remember { mutableLongStateOf(existingStop?.duration ?: 120L) }
    var businessHours by remember { mutableStateOf(existingStop?.businessHours ?: "未提供") }
    var sugVisible    by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 刪除前確認（對齊網頁 #deletePlaceOverlay 文案）
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Text("🗑️", fontSize = 28.sp) },
            title = { Text("刪除這個景點？", fontWeight = FontWeight.Bold) },
            text = { Text("刪除後會重新計算後續行程時間。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("確認刪除", color = PDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("保留景點") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ── 標題 ──────────────────────────────────────────
        Text(
            text = "編輯景點",
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            color = PIink
        )
        Spacer(Modifier.height(16.dp))

        // ── Emoji + 景點名稱 ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = emoji,
                onValueChange = { if (it.length <= 4) emoji = it },
                modifier = Modifier.width(68.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
                ),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 24.sp)
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                label = { Text("景點名稱") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
                )
            )
        }
        Spacer(Modifier.height(10.dp))

        // ── 時間 ──────────────────────────────────────────
        OutlinedTextField(
            value = time,
            onValueChange = { time = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("時間（例：09:00）") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Schedule, null, tint = PIink2) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
            ),
            supportingText = if (time.isBlank()) {
                { Text("留空將依行程自動排定", fontSize = 12.sp, color = PIink2) }
            } else null
        )
        Spacer(Modifier.height(10.dp))

        // ── 簡介 ──────────────────────────────────────────
        OutlinedTextField(
            value = desc,
            // 網頁端同步下來的簡介可能超過 40 字：只擋「變長」，變短一律放行，
            // 否則超長簡介會連刪字都被擋住（68 > 40 → 拒絕更新）
            onValueChange = { if (it.length <= 40 || it.length < desc.length) desc = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("簡介（25 字內）") },
            maxLines = 2,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
            ),
            trailingIcon = {
                Text("${desc.length}/40", fontSize = 12.sp,
                    color = if (desc.length > 40) PDanger else PIink2,
                    modifier = Modifier.padding(end = 8.dp))
            }
        )
        Spacer(Modifier.height(10.dp))

        // ── 停留時間（Slider 10–180 分鐘，步進 10 分）────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = PSurface2
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Schedule, null, tint = PAccent, modifier = Modifier.size(16.dp))
                        Text("停留時間", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PIink)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = Color.White) {
                        Text(
                            fmtDuration(duration),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = PAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Slider(
                    value = duration.toFloat(),
                    onValueChange = { duration = (it / 10).toLong() * 10 },
                    valueRange = 10f..180f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = PAccent,
                        activeTrackColor = PAccent,
                        inactiveTrackColor = PBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("10 分", fontSize = 11.sp, color = PIink2)
                    Text("3 小時", fontSize = 11.sp, color = PIink2)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 附近替代景點（本地資料，即時；對齊網頁「每站備好附近替代景點」）──
        if (existingStop != null && alternatives.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = PSurface2
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("🔁 附近替代景點", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PIink)
                    Text("${LocalAlternatives.MAX_KM.toInt()} 公里內、當天有營業，直接換掉此景點",
                        fontSize = 12.sp, color = PIink2)
                    Spacer(Modifier.height(6.dp))
                    NearbyAlternativesList(
                        targetName = existingStop.name,
                        candidates = alternatives,
                        onReplace = onReplaceWith
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── AI 建議區塊（新增 / 編輯都可用）─────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = PAccentLight
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // 標題列
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        // 佔掉按鈕以外的寬度，說明文字才不會把按鈕擠到變形
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✨", fontSize = 20.sp)
                        Column {
                            Text(
                                "換一個景點",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = PAccentDark
                            )
                            // 固定拆成兩行（窄螢幕自動換行會把詞拆開）
                            Text("讓 AI 推薦替代選擇", fontSize = 12.sp, color = PIink2, maxLines = 1)
                            Text("套用後取代此景點", fontSize = 12.sp, color = PIink2, maxLines = 1)
                        }
                    }
                    Button(
                        onClick = { sugVisible = true; onFetchSuggestions(time) },
                        colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        enabled = !isLoadingSuggestions
                    ) {
                        if (isLoadingSuggestions) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("詢問中…", fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("取得建議", fontSize = 14.sp)
                        }
                    }
                }

                // 建議卡片（動畫展開）
                AnimatedVisibility(
                    visible = sugVisible && suggestions.isNotEmpty(),
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        HorizontalDivider(color = PBorder.copy(alpha = 0.4f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "點擊景點套用至欄位",
                            fontSize = 13.sp,
                            color = PAccentDark,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        suggestions.forEach { s ->
                            SuggestionCard(s) {
                                emoji         = s.emoji
                                name          = s.name
                                time          = s.time
                                desc          = s.desc
                                duration      = s.duration
                                businessHours = s.businessHours
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                // 無建議提示
                AnimatedVisibility(
                    visible = sugVisible && suggestions.isEmpty() && !isLoadingSuggestions
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            suggestionError ?: "暫時無法取得建議，請稍後再試",
                            fontSize = 14.sp, color = PIink2, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── 探索這附近（以此景點為起點找附近景點「加入」行程，不取代）──────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = PAccentLight
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🧭", fontSize = 20.sp)
                    Column {
                        Text(
                            "探索這附近",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = PAccentDark
                        )
                        Text(
                            "找附近景點加入行程，不取代此景點",
                            fontSize = 12.sp,
                            color = PIink2
                        )
                    }
                }
                Button(
                    onClick = onExploreNearby,
                    colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Explore, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("探索", fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── 操作按鈕 ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (onDelete != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PDanger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PDanger)
                ) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刪除", fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = {
                    onSave(
                        Stop(
                            name          = name.trim(),
                            // 網頁格式的共編景點沒有 time，留空時沿用原值，由時間重算補齊
                            time          = time.trim().ifBlank { existingStop?.time ?: "" },
                            desc          = desc.trim(),
                            emoji         = emoji.ifBlank { "📍" },
                            duration      = duration,
                            order         = existingStop?.order ?: 99L,
                            businessHours = businessHours,
                            nearbyToiletLocations = existingStop?.nearbyToiletLocations ?: emptyList()
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                enabled = name.isNotBlank()
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("儲存", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── AI 建議卡片 ─────────────────────────────────────────────
@Composable
private fun SuggestionCard(
    s: ItineraryViewModel.StopSuggestion,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(s.emoji, fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PIink)
                Text(s.desc, fontSize = 13.sp, color = PIink2, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(6.dp), color = PAccentLight) {
                    Text(s.time, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 11.sp, color = PAccentDark, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "停留 ${fmtDuration(s.duration)}",
                    fontSize = 11.sp,
                    color = PIink2
                )
            }
            Icon(Icons.Default.ArrowForward, null, tint = PAccent, modifier = Modifier.size(14.dp))
        }
    }
}

// ── 指定景點 Dialog（自 ItineraryPreviewScreen 抽出，維持穩定的小組合範圍）──
@Composable
private fun AddSpecificPoiDialog(
    isLoading: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var poiName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有特別想去的景點？", fontWeight = FontWeight.Bold, color = PIink) },
        text = {
            Column {
                Text(
                    "輸入景點名稱，會自動查詢營業時間與位置，插入行程中最順路的地方並重排時間。",
                    fontSize = 13.sp, color = PIink2
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = poiName,
                    onValueChange = { poiName = it },
                    label = { Text("景點名稱") },
                    placeholder = { Text("例：三仙台、鹿野高台") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
                    )
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 13.sp, color = PDanger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(poiName) },
                enabled = poiName.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("查詢中…")
                } else Text("加入行程", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("取消", color = PIink2) }
        }
    )
}

// ══════════════════════════════════════════════════════════════════
// 新增景點（對齊網頁端）：搜尋新增景點 Sheet / 探索附近景點 Sheet
// ══════════════════════════════════════════════════════════════════

// ── 「安排在哪一天」列（App 為單日行程，固定第 1 天；保留與網頁端一致的版面）──
@Composable
private fun DayPickerRow() {
    Column {
        Text("安排在哪一天", fontSize = 13.sp, color = PIink2, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PSurface2)
                .border(1.dp, PBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("第 1 天", fontSize = 15.sp, color = PIink, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlaceSearchSheet(
    results: List<ItineraryViewModel.PlaceCandidate>,
    isLoading: Boolean,
    onSearch: (String) -> Unit,
    onAdd: (ItineraryViewModel.PlaceCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("新增景點", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PIink)
            Spacer(Modifier.height(4.dp))
            Text("搜尋 Google Maps 上的景點，再加入目前行程。", fontSize = 13.sp, color = PIink2)
            Spacer(Modifier.height(16.dp))
            DayPickerRow()
            Spacer(Modifier.height(14.dp))
            Text("景點名稱或類型", fontSize = 13.sp, color = PIink2, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("例如：台東美術館、咖啡廳", color = PIink2) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
                    )
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { if (query.isNotBlank()) { searched = true; onSearch(query) } },
                    enabled = query.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = PAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("搜尋", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(14.dp))
            when {
                isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PAccent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                results.isEmpty() -> Text(
                    if (searched) "找不到符合的景點，換個名稱或類型試試。" else "輸入景點名稱，搜尋後再選擇要加入的地點。",
                    fontSize = 13.sp, color = PIink2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = TextAlign.Center
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results, key = { it.name }) { c -> PlaceCandidateCard(c, onAdd = { onAdd(c) }) }
                }
            }
        }
    }
}

/**
 * 在 Google Maps 開啟整趟行程的完整路線（所有站點依序當 waypoints），對齊網頁「🗺 Google Maps 路線」。
 * 座標優先用 stop.lat/lng，缺則退回 stopLocations 快取。未安裝 Google Maps App 時退回瀏覽器。
 * 註：Maps URL API 的 waypoints 上限 9 個中途點；超長行程會取前後與前 9 個中途點。
 */
/** 整趟 Google Maps 路線連結；omittedStops＝超過中途點上限被省略的站數 */
private data class FullRoute(val url: String, val omittedStops: Int)

private fun buildFullRouteUrl(
    stops: List<com.example.travellink_ai.data.model.Stop>,
    coords: Map<String, LatLng>,
    transportMode: String
): FullRoute? {
    val pts = stops.mapNotNull { s ->
        val lat = s.lat; val lng = s.lng
        val ll = if (lat != null && lng != null) LatLng(lat, lng) else coords[s.name]
        ll?.let { "${it.latitude},${it.longitude}" }
    }
    if (pts.size < 2) return null
    val mode = if (transportMode == "walking") "walking" else "driving"
    val middle = pts.drop(1).dropLast(1)
    val waypoints = middle.take(9)   // Maps URL API 中途點上限 9
    val url = buildString {
        append("https://www.google.com/maps/dir/?api=1")
        append("&origin=").append(pts.first())
        append("&destination=").append(pts.last())
        if (waypoints.isNotEmpty())
            append("&waypoints=").append(android.net.Uri.encode(waypoints.joinToString("|")))
        append("&travelmode=").append(mode)
    }
    return FullRoute(url, middle.size - waypoints.size)
}

/** 分享地圖（對齊網頁「🗺 分享 Google Maps 路線」：連結＋QR＋分享／複製／開啟） */
@Composable
private fun MapShareDialog(route: FullRoute, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val qr = remember(route.url) { generateQrBitmap(route.url) }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = { Text("🗺 分享 Google Maps 路線", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("把整趟路線交給旅伴或司機；掃描 QR Code 可直接在 Google Maps 開啟。",
                    fontSize = 14.sp, color = PIink2)
                qr?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Google Maps 路線 QR Code",
                        modifier = Modifier.size(160.dp)
                    )
                }
                if (route.omittedStops > 0) {
                    Text("Google Maps 最多支援 9 個中停點，目前省略 ${route.omittedStops} 站。",
                        fontSize = 12.sp, color = PIink2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(route.url))
                        copied = true
                    }, shape = RoundedCornerShape(12.dp)) {
                        Text(if (copied) "已複製" else "📋 複製連結", color = PAccent)
                    }
                    OutlinedButton(onClick = {
                        openRouteUrl(context, route.url)
                    }, shape = RoundedCornerShape(12.dp)) {
                        Text("開啟地圖", color = PAccent)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, route.url)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享地圖"))
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PAccent)
            ) { Text("📤 分享連結", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } }
    )
}

private fun launchFullRoute(
    context: android.content.Context,
    stops: List<com.example.travellink_ai.data.model.Stop>,
    coords: Map<String, LatLng>,
    transportMode: String
) {
    val route = buildFullRouteUrl(stops, coords, transportMode) ?: return
    openRouteUrl(context, route.url)
}

private fun openRouteUrl(context: android.content.Context, url: String) {
    val uri = android.net.Uri.parse(url)
    val mapsIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(mapsIntent)
    } catch (e: android.content.ActivityNotFoundException) {
        // 沒裝 Google Maps → 交給瀏覽器或其他可處理的 App
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        } catch (e2: Exception) { /* 無任何可處理的 App，靜默 */ }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreNearbySheet(
    spotName: String?,
    results: List<ItineraryViewModel.PlaceCandidate>,
    isLoading: Boolean,
    onAdd: (ItineraryViewModel.PlaceCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("") }
    val shown = remember(results, filter) {
        val f = filter.trim()
        if (f.isBlank()) results
        else results.filter { it.name.contains(f, true) || it.typeName.contains(f, true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                if (spotName != null) "探索「$spotName」附近" else "附近推薦",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PIink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (spotName != null) "依此景點的位置搜尋，距離、營業時間與 AI 推薦會顯示在結果中。"
                else "未指定景點，改以整趟行程的中心位置推薦附近景點。",
                fontSize = 13.sp, color = PIink2
            )
            Spacer(Modifier.height(16.dp))
            DayPickerRow()
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("輸入名稱或類型可再篩選", color = PIink2) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PAccent, unfocusedBorderColor = PBorder
                )
            )
            Spacer(Modifier.height(14.dp))
            when {
                isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PAccent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                shown.isEmpty() -> Text(
                    "附近暫時找不到可推薦的景點。",
                    fontSize = 13.sp, color = PIink2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = TextAlign.Center
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.name }) { c -> PlaceCandidateCard(c, onAdd = { onAdd(c) }) }
                }
            }
        }
    }
}

// ── 候選景點卡片（搜尋新增／探索附近共用）──
@Composable
private fun PlaceCandidateCard(
    c: ItineraryViewModel.PlaceCandidate,
    onAdd: () -> Unit
) {
    val meta = remember(c) {
        buildList {
            if (c.distanceMeters > 0) add(
                if (c.distanceMeters < 1000) "約 ${c.distanceMeters} 公尺"
                else "約 %.1f 公里".format(c.distanceMeters / 1000.0)
            )
            val hours = c.businessHours.takeIf { it.isNotBlank() && it != "未提供" }
            when {
                hours != null       -> add("營業 ${hours.take(18)}")
                c.openNow == true    -> add("營業中")
                c.openNow == false   -> add("休息中")
            }
            when {
                c.rating >= 0        -> add("⭐ %.1f".format(c.rating))
                c.appRatingCount > 0 -> add("⭐ %.1f".format(c.appRatingAvg))
            }
        }.joinToString("　·　")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(c.emoji, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PIink,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false)
                )
                if (c.aiRecommended) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PAccentLight)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("✨ AI推薦", fontSize = 11.sp, color = PAccent, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (c.address.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(c.address, fontSize = 12.sp, color = PIink2, maxLines = 1)
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(meta, fontSize = 12.sp, color = PIink2)
            }
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = PAccent),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("加入", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

// ── 標題旁的行程狀態徽章（⚡ 進行中／🎉 已完成）─────────────────
@Composable
private fun TripStatusBadge(text: String, bg: Color, fg: Color, startPadding: Dp = 8.dp) {
    Surface(shape = RoundedCornerShape(999.dp), color = bg, modifier = Modifier.padding(start = startPadding)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, softWrap = false)
    }
}

// ── 摘要 chip ──────────────────────────────────────────────

/**
 * 日期 chip：多日行程顯示「MM/dd–MM/dd」（結束日＝出發日＋天數−1），單日維持原字串。
 * 解析不到日期（網頁舊格式「N小時」）就原樣顯示。
 */
private fun summaryDateText(startDate: String, stops: List<Stop>): String {
    val days = stops.maxOfOrNull { it.dayIndex }?.coerceAtLeast(1) ?: 1
    if (days <= 1) return startDate
    val start = runCatching {
        java.time.LocalDate.parse(startDate.replace('-', '/'),
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
    }.getOrNull() ?: return startDate
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd")
    return "${start.format(fmt)}–${start.plusDays((days - 1).toLong()).format(fmt)}"
}

/**
 * 實際行程時段：第一站的時間 → 最後一站結束（回程車站看抵達時間，一般景點加上停留）。
 * 多日行程是第 1 天出發到最後一天結束。站點時間不齊時回 null。
 */
private fun actualTimeRange(stops: List<Stop>): String? {
    val first = stops.firstOrNull() ?: return null
    val last = stops.last()
    val startMins = DayPlanner.parseHhMm(first.time) ?: return null
    val lastMins = DayPlanner.parseHhMm(last.time) ?: return null
    val endMins = if (last.isStation) lastMins else lastMins + last.duration.toInt()
    return "${DayPlanner.hhmmOf(startMins)}–${DayPlanner.hhmmOf(endMins)}"
}

@Composable
private fun SummaryChip(emoji: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(text, fontSize = 14.sp, color = PIink2, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 某一天要查天氣的日期與座標 */
private data class WeatherTarget(val day: Int, val date: String, val lat: Double, val lng: Double)

/**
 * 逐日取天氣查詢目標：第 d 天日期＝出發日＋(d−1)，座標取當天第一個有座標的站。
 * 出發日解析不到、或某天沒有任何座標時略過那一天。
 */
private fun weatherTargetsByDay(
    stops: List<Stop>,
    stopLocations: Map<String, com.google.android.gms.maps.model.LatLng>,
    tripDateStr: String
): List<WeatherTarget> {
    if (tripDateStr.length != 10) return emptyList()
    val fmt = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).apply { isLenient = false }
    val start = try { fmt.parse(tripDateStr) } catch (e: Exception) { null } ?: return emptyList()
    val dayCount = stops.maxOfOrNull { it.dayIndex }?.coerceAtLeast(1) ?: 1
    return (1..dayCount).mapNotNull { day ->
        val coord = stops.filter { it.dayIndex == day || (dayCount == 1) }.firstNotNullOfOrNull { st ->
            if (st.lat != null && st.lng != null) st.lat to st.lng
            else stopLocations[st.name]?.let { it.latitude to it.longitude }
        } ?: return@mapNotNull null
        val cal = java.util.Calendar.getInstance().apply { time = start; add(java.util.Calendar.DAY_OF_MONTH, day - 1) }
        WeatherTarget(day, fmt.format(cal.time), coord.first, coord.second)
    }
}

/** 主要交通工具選項：值與精靈／網頁相同（taxi/scooter/car），步行為 App 保留項（D7） */
private val PRIMARY_VEHICLE_OPTIONS = listOf(
    "taxi" to "🚕 計程車",
    "scooter" to "🛵 機車",
    "car" to "🚗 汽車",
    "walking" to "🚶 步行"
)

@Composable
private fun PrimaryVehicleRow(
    mode: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val label = PRIMARY_VEHICLE_OPTIONS.firstOrNull { it.first == mode }?.second ?: "🚗 汽車"
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🚦 主要交通工具", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PIink2)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.dp, PBorder, RoundedCornerShape(10.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PAccent)
                Icon(Icons.Default.ArrowDropDown, null, tint = PAccent)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, fontWeight = if (value == mode) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onChange(value) }
                    )
                }
            }
        }
    }
}

private fun modeEmoji(mode: String) = when (mode) {
    "walking" -> "🚶"
    "car"     -> "🚗"
    "scooter" -> "🛵"
    else      -> "🚕"
}

private fun modeLabel(mode: String) = when (mode) {
    "walking" -> "步行"
    "car"     -> "汽車"
    "scooter" -> "機車"
    else      -> "計程車"
}

/**
 * 旅程應變 Agent 的 T4 延誤資訊（AMD 組）。衝突沿用行程進行中的判斷（不含「提早到要等開門」）；
 * 末班船取當季從島上出發的末班（規格 2.1），只在回程那天送。回程火車送出前才查（見 returnTrainDeadline）。
 */
private fun agentDelayInput(
    o: com.example.travellink_ai.ui.trip.OngoingTripUi,
    h: com.example.travellink_ai.ui.trip.HereStop
): com.example.travellink_ai.ui.agent.DelayInput {
    val day = o.derived.currentDayIndex
    val isLastDay = day >= o.derived.dayCount
    val startIso = o.tripStartDate.replace('/', '-')
    val date = com.example.travellink_ai.data.agent.AgentTripMapper.dateOfDay(startIso, day)
    fun iso(hhmm: String?) = hhmm?.let { DayPlanner.parseHhMm(it) }
        ?.let { com.example.travellink_ai.data.agent.AgentTripMapper.isoAt(date, it) }?.takeIf { it.isNotEmpty() }
    val ferry = if (!isLastDay) null else o.islandOfTrip?.let { isl ->
        o.ferrySchedules.routeForIsland(isl.code)?.let { route ->
            iso(route.activeSeason?.lastDeparture("island"))?.let { com.example.travellink_ai.data.agent.Deadline(it, route.islandPort) }
        }
    }
    return com.example.travellink_ai.ui.agent.DelayInput(
        here = h.stop,
        leaveMin = h.leaveMin,
        day = day,
        conflicts = o.conflicts.filter { !it.isInfo }.map {
            com.example.travellink_ai.data.agent.AppConflict(it.kind.name.lowercase(), it.stopId, it.message)
        },
        isLastDay = isLastDay,
        lastFerry = ferry,
        nowMs = com.example.travellink_ai.debug.DemoClock.now()
    )
}
