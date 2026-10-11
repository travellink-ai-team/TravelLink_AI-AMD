package com.example.travellink_ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.ui.auth.AuthState
import com.example.travellink_ai.ui.auth.AuthViewModel
import com.example.travellink_ai.ui.auth.LoginScreen
import com.example.travellink_ai.ui.auth.ProfileScreen
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.feedback.FeedbackViewModel
import com.example.travellink_ai.ui.drawer.AboutScreen
import com.example.travellink_ai.ui.drawer.DrawerContent
import com.example.travellink_ai.ui.drawer.FeedbackHistoryScreen
import com.example.travellink_ai.ui.drawer.PreferencesScreen
import com.example.travellink_ai.ui.feedback.FeedbackScreen
import com.example.travellink_ai.ui.explore.ExploreScreen
import com.example.travellink_ai.ui.home.HomeScreen
import com.example.travellink_ai.ui.planning.*
import com.example.travellink_ai.util.soonKey
import kotlinx.coroutines.launch

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // 用 Compose State 包裝 Intent，使 onNewIntent 能觸發 recompose
    private val intentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        intentState.value = intent
        createNotificationChannel()
        UpcomingTripReceiver.schedule(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: ItineraryViewModel = hiltViewModel()
                    val collabVm: CollabViewModel = hiltViewModel()
                    val feedbackVm: FeedbackViewModel = hiltViewModel()
                    val authVm: AuthViewModel = hiltViewModel()
                    val tripVm: com.example.travellink_ai.ui.trip.TripProgressViewModel = hiltViewModel()

                    // 行程生成失敗提示（網路異常會特別說明）
                    val generationError by vm.generationError.collectAsState()
                    LaunchedEffect(generationError) {
                        generationError?.let {
                            android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show()
                            vm.consumeGenerationError()
                        }
                    }

                    // ── Auth Gate ─────────────────────────────────────────
                    val authState by authVm.authState.collectAsState()

                    when (authState) {
                        is AuthState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(color = Color(0xFF2A6B5E)) }
                            return@Surface
                        }
                        is AuthState.Unauthenticated -> {
                            LoginScreen(viewModel = authVm)
                            return@Surface
                        }
                        else -> { /* Authenticated — continue */ }
                    }

                    // 啟動時清理兩週前的舊行程，並載入使用者偏好設定
                    // authState 作為 key，帳號切換後重新查詢歷史（避免顯示其他帳號的行程）
                    LaunchedEffect(authState) {
                        vm.deleteOldItineraries()
                        vm.loadUserPrefs()
                        vm.refreshHistoryForCurrentUser()
                    }

                    // ── 處理共編邀請 deep link ─────────────────────────
                    val currentIntent by intentState
                    LaunchedEffect(currentIntent?.data) {
                        handleJoinIntent(currentIntent, collabVm)
                    }

                    // ── 加入共編 Dialog（deep link 觸發）─────────────────
                    val pendingJoinDocId by collabVm.pendingJoinDocId.collectAsState()
                    val authProfile = (authState as? AuthState.Authenticated)?.profile

                    // 綁定共享的好友 ViewModel（供抽屜 badge、首頁鈴鐺、通知頁、好友頁共用同一實例）
                    val friendVm: com.example.travellink_ai.ui.friends.FriendViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    LaunchedEffect(authProfile?.email) {
                        authProfile?.let { friendVm.bind(it) }
                    }
                    pendingJoinDocId?.let { joinDocId ->
                        JoinCollabDialog(
                            userName  = authProfile?.name  ?: "旅人",
                            userEmoji = authProfile?.emoji ?: "🌟",
                            onConfirm = {
                                collabVm.joinItinerary(
                                    joinDocId,
                                    authProfile?.name  ?: "旅人",
                                    authProfile?.emoji ?: "🌟"
                                )
                            },
                            onDismiss = { collabVm.clearPendingJoinDocId() }
                        )
                    }

                    // ── 請求通知權限（Android 13+）──────────────────────
                    val notifPermLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* 使用者決定即可 */ }
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // ── 處理通知點擊帶入的 Intent ─────────────────────────
                    LaunchedEffect(intent?.action) {
                        handleFeedbackIntent(intent, vm)
                    }
                    // 行程即將開始：登入後立刻檢查一次（08:00 之後才建立的明天行程，當天的每日鬧鐘已錯過；
                    // 同一趟同一天只發一次，由 receiver 去重）
                    LaunchedEffect(authProfile?.uid) {
                        if (authProfile != null) sendBroadcast(
                            Intent(this@MainActivity, UpcomingTripReceiver::class.java)
                                .setAction(UpcomingTripReceiver.ACTION_CHECK)
                        )
                    }
                    // 點「行程即將開始」通知：雲端行程直接打開，純本地行程帶到我的微旅行（卡片有紅點）
                    LaunchedEffect(currentIntent) {
                        val i = currentIntent
                        if (i?.action == UpcomingTripReceiver.ACTION_OPEN_TRIP) {
                            val docId = i.getStringExtra(UpcomingTripReceiver.EXTRA_DOC_ID)
                            if (!docId.isNullOrBlank()) collabVm.openCollabTrip(docId)
                            else vm.currentScreen = "history"
                            i.action = null   // 避免重組或轉向時重複導頁
                        }
                    }
                    // 點「行程可能排不下」通知（AMD 組）：回到行程頁；按「讓 AI 處理」就順便送出延誤請求。
                    // 行程頁的 AgentViewModel 與這裡同一個（Activity 範圍），助理看到旗標就送出
                    val agentVm: com.example.travellink_ai.ui.agent.AgentViewModel = hiltViewModel()
                    LaunchedEffect(currentIntent) {
                        val i = currentIntent
                        val a = i?.action
                        if (a == com.example.travellink_ai.ui.agent.AgentAlert.ACTION_OPEN ||
                            a == com.example.travellink_ai.ui.agent.AgentAlert.ACTION_RUN) {
                            val docId = i.getStringExtra(com.example.travellink_ai.ui.agent.AgentAlert.EXTRA_DOC_ID)
                            // 只回到目前開著的那趟行程；不是同一趟就不動，避免送錯行程
                            if (docId == null || docId == vm.currentFirestoreDocIdPublic) {
                                vm.currentScreen = "preview"
                                if (a == com.example.travellink_ai.ui.agent.AgentAlert.ACTION_RUN) agentVm.requestAutoDelay()
                            }
                            i.action = null
                        }
                    }
                    // 點「預計離開時間到了」通知：跳到那一站的景點資訊頁（行程要是目前開著的那趟）
                    LaunchedEffect(currentIntent) {
                        val i = currentIntent
                        if (i?.action == com.example.travellink_ai.ui.trip.LeaveReminder.ACTION_OPEN_STOP) {
                            val stopId = i.getStringExtra(com.example.travellink_ai.ui.trip.LeaveReminder.EXTRA_STOP_ID)
                            if (stopId != null && vm.itinerary.value?.stops?.any { it.stopId == stopId } == true) {
                                tripVm.selectedStopId = stopId
                                vm.currentScreen = "stop_detail"
                            }
                        }
                    }

                    // 新帳號第一次進來：先引導設定個人喜好（可跳過）
                    val isNewUser by authVm.isNewUser.collectAsState()
                    LaunchedEffect(isNewUser) {
                        if (isNewUser) {
                            vm.currentScreen = "pref_onboarding"
                            authVm.consumeNewUser()
                        }
                    }

                    val historyList       by vm.historyList.collectAsState()
                    val pendingFeedbacks by feedbackVm.pendingFeedbackItems.collectAsState()

                    // ── Drawer 狀態 ───────────────────────────────────────
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    // 全螢幕覆蓋頁（沒有 Bottom Nav 也沒有 Drawer）
                    val fullScreenScreens = setOf("preview", "result", "feedback", "preferences", "pref_onboarding", "about", "feedback_history", "profile", "group_setup", "trip_map", "stop_detail", "memory_edit", "memory_grid", "memory_recap", "notifications")
                    val isFullScreen = vm.currentScreen in fullScreenScreens

                    if (isFullScreen) {
                        when (vm.currentScreen) {
                            "preview" -> Box(Modifier.fillMaxSize()) {
                                // 預覽頁底部按鈕欄（開始行程等）的高度，機器人頭像要停在它上方
                                var previewBottomBarPx by remember { mutableIntStateOf(0) }
                                ItineraryPreviewScreen(
                                    viewModel = vm,
                                    onConfirm = { vm.confirmAndGenerateVisuals(feedbackVm) },
                                    onCancel  = { vm.currentScreen = vm.previewReturnScreen },
                                    onExportImage = { vm.confirmAndGenerateVisuals(feedbackVm, openExport = true) },
                                    onBottomBarHeightChange = { previewBottomBarPx = it }
                                )
                                com.example.travellink_ai.ui.assistant.AssistantOverlay(
                                    vm, bottomReservedPx = previewBottomBarPx
                                )
                            }
                            "result" -> ItineraryResultScreen(
                                viewModel = vm,
                                docId = "current",
                                initialShowImage = vm.resultInitialView == "image",
                                openExport = vm.resultInitialView == "export"
                            )
                            "feedback" -> FeedbackScreen(
                                viewModel = feedbackVm,
                                onDone = {
                                    feedbackVm.resetFeedback()
                                    vm.currentScreen = "home"
                                }
                            )
                            "preferences" -> PreferencesScreen(
                                viewModel = vm,
                                onBack = { vm.currentScreen = "home" }
                            )
                            "pref_onboarding" -> PreferencesScreen(
                                viewModel = vm,
                                onBack = { vm.currentScreen = "home" },
                                onboarding = true
                            )
                            "about" -> AboutScreen(
                                onBack = { vm.currentScreen = "home" }
                            )
                            "feedback_history" -> FeedbackHistoryScreen(
                                historyList = historyList,
                                onBack = { vm.currentScreen = "home" }
                            )
                            "profile" -> ProfileScreen(
                                authViewModel = authVm,
                                onBack = { vm.currentScreen = "home" }
                            )
                            // ── 通知中心 ──────────────────────────────────
                            "notifications" -> {
                                val p = authProfile
                                if (p != null) {
                                    com.example.travellink_ai.ui.notifications.NotificationScreen(
                                        profile = p,
                                        pendingFeedbacks = pendingFeedbacks,
                                        onFeedbackClick = { item -> vm.loadForFeedback(item) },
                                        onOpenFriends = { vm.currentScreen = "friends" },
                                        onOpenTrip = { tripId -> collabVm.openCollabTrip(tripId) },
                                        onBack = { vm.currentScreen = "home" }
                                    )
                                } else {
                                    LaunchedEffect(Unit) { vm.currentScreen = "home" }
                                }
                            }
                            "group_setup" -> com.example.travellink_ai.ui.collab.GroupSetupScreen(
                                viewModel = vm,
                                collabViewModel = collabVm,
                                onBack = {
                                    // 沒生成行程就離開 → 這一團作廢，不留空的「未命名共編行程」
                                    collabVm.abandonGroupSetupIfEmpty()
                                    vm.currentScreen = "home"
                                }
                            )
                            // 行程進行中已併進行程頁（preview 同頁切換），"trip_progress" 由
                            // ItineraryStateHolder 導到 "preview"，這裡不會再出現
                            // ── 行程進行中的獨立地圖頁（停車 / 廁所 / 一般檢視）──
                            "trip_map" -> {
                                val tripItin by vm.itinerary.collectAsState()
                                val tripProg by tripVm.progress.collectAsState()
                                val activePk = tripProg.activeParking?.second
                                com.example.travellink_ai.ui.map.ItineraryMapScreen(
                                    viewModel = vm,
                                    stops = tripItin?.stops ?: emptyList(),
                                    initialShowToilets = tripVm.mapMode == "toilets",
                                    parkingMarker = activePk?.latLng,
                                    parkingNote = activePk?.note ?: "",
                                    focusParking = tripVm.mapMode == "parking",
                                    onConfirmParking = if (tripVm.mapMode == "parking") {
                                        { pos, note ->
                                            tripProg.activeParking?.first?.let { sid ->
                                                tripVm.updateParking(sid, pos.latitude, pos.longitude, note)
                                            }
                                            vm.currentScreen = "preview"
                                        }
                                    } else null,
                                    onBack = { vm.currentScreen = "preview" },
                                    progress = tripProg,
                                    demoModeOn = tripVm.demoModeOn,
                                    simulatedLocation = tripVm.locationSimulator.location.collectAsState().value
                                )
                            }
                            "stop_detail" -> com.example.travellink_ai.ui.trip.StopDetailScreen(
                                viewModel = vm,
                                tripVm = tripVm,
                                onBack = { vm.currentScreen = "preview" }
                            )
                            // ── W4 C6 旅遊回憶（照片＋文字紀錄）────────────
                            "memory_edit" -> {
                                val target = vm.memoryTargetTrip
                                if (target != null) {
                                    com.example.travellink_ai.ui.memory.MemoryEditScreen(
                                        item = target,
                                        onBack = { vm.currentScreen = vm.memoryReturnScreen },
                                        onMakeGrid = { vm.currentScreen = "memory_grid" },
                                        onMakeRecap = { vm.currentScreen = "memory_recap" },
                                        onRate = { vm.loadForFeedback(target) }
                                    )
                                } else {
                                    // 無目標（不該發生）→ 回首頁
                                    LaunchedEffect(Unit) { vm.currentScreen = "home" }
                                }
                            }
                            // 製作 IG 九宮格（照片排版）
                            "memory_grid" -> {
                                val target = vm.memoryTargetTrip
                                if (target != null) {
                                    com.example.travellink_ai.ui.poster.MemoryGridScreen(
                                        item = target,
                                        onBack = { vm.currentScreen = "memory_edit" }
                                    )
                                } else {
                                    LaunchedEffect(Unit) { vm.currentScreen = "home" }
                                }
                            }
                            // 製作回顧短片（後端生成）
                            "memory_recap" -> {
                                val target = vm.memoryTargetTrip
                                if (target != null) {
                                    com.example.travellink_ai.ui.recap.RecapScreen(
                                        item = target,
                                        onBack = { vm.currentScreen = "memory_edit" }
                                    )
                                } else {
                                    LaunchedEffect(Unit) { vm.currentScreen = "home" }
                                }
                            }
                        }
                    } else {
                        // ── Drawer + Bottom Navigation 主框架 ────────────
                        // 初始 tab 直接依 currentScreen 推導：從全螢幕頁（如 preview）返回時，
                        // 本框架會重新組合，若寫死 0 會先閃一格首頁再被修正成歷史頁。
                        var selectedTab by rememberSaveable {
                            mutableIntStateOf(when (vm.currentScreen) { "history" -> 2; "friends" -> 3; else -> 0 })
                        }
                        var showPlanningSheet by remember { mutableStateOf(false) }
                        // 側欄「輸入邀請碼加入」→ 首頁的加入對話框
                        var requestJoinDialog by remember { mutableStateOf(false) }
                        // 首頁搜尋框 → 切到探索並帶入關鍵字（網頁頂欄搜尋就是篩探索頁）
                        var exploreQuery by remember { mutableStateOf<String?>(null) }
                        // 探索「用這份開始規劃」→ 回首頁開製作方式選擇
                        var requestModeDialog by remember { mutableStateOf(false) }
                        val friendVmMain: com.example.travellink_ai.ui.friends.FriendViewModel = hiltViewModel()
                        val incomingFriendInvites by friendVmMain.incoming.collectAsState()

                        // 「我的微旅行」分頁紅點：有今天／明天出發、且今天還沒點進分頁看過的行程
                        val soonPrefs = remember { getSharedPreferences("upcoming_trip_seen", MODE_PRIVATE) }
                        val todayStart = remember { com.example.travellink_ai.util.startOfToday() }
                        var seenSoonKeys by remember {
                            mutableStateOf(soonPrefs.getStringSet("seen_$todayStart", emptySet())!!.toSet())
                        }
                        val soonKeys = remember(historyList, todayStart) {
                            historyList.filter { com.example.travellink_ai.util.isStartingSoon(it, todayStart) }
                                .map { it.soonKey() }.toSet()
                        }
                        val hasUnseenSoonTrip = (soonKeys - seenSoonKeys).isNotEmpty()
                        LaunchedEffect(selectedTab, soonKeys) {
                            if (selectedTab == 2 && (soonKeys - seenSoonKeys).isNotEmpty()) {
                                seenSoonKeys = seenSoonKeys + soonKeys
                                // 只留今天的記錄；隔天同一趟（變成「今天出發」）會再亮一次
                                soonPrefs.edit().clear().putStringSet("seen_$todayStart", seenSoonKeys).apply()
                            }
                        }

                        // currentScreen 變動時於「本次組合內」同步 tab（取代 LaunchedEffect，
                        // 避免延遲一幀造成返回歷史頁時閃現首頁）
                        var lastSyncedScreen by remember { mutableStateOf(vm.currentScreen) }
                        if (vm.currentScreen != lastSyncedScreen) {
                            lastSyncedScreen = vm.currentScreen
                            when (vm.currentScreen) {
                                "history" -> selectedTab = 2
                                "friends" -> selectedTab = 3
                                "home"    -> selectedTab = 0
                            }
                        }

                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                DrawerContent(
                                    viewModel     = vm,
                                    authViewModel = authVm,
                                    onClose       = { scope.launch { drawerState.close() } },
                                    onOpenProfile = { vm.currentScreen = "profile" },
                                    onOpenMyTrips = { selectedTab = 2; vm.currentScreen = "history" },
                                    onOpenFriends = { selectedTab = 3; vm.currentScreen = "friends" },
                                    // 與首頁按鈕相同：先選單人製作或共編行程，不直接開單人精靈
                                    onCreateTrip  = { selectedTab = 0; vm.currentScreen = "home"; requestModeDialog = true },
                                    onJoinByCode  = { selectedTab = 0; vm.currentScreen = "home"; requestJoinDialog = true }
                                )
                            }
                        ) {
                        Scaffold(
                            // 系統列區域（狀態列/導覽列）底色改白，與頂欄/底部導覽融為一體，
                            // 避免畫面上下露出主題預設的粉色帶
                            containerColor = Color.White,
                            bottomBar = {
                                NavigationBar(
                                    containerColor = Color.White,
                                    tonalElevation = 0.dp
                                ) {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = { selectedTab = 0; vm.currentScreen = "home" },
                                        icon = { Icon(Icons.Default.Home, null) },
                                        label = { Text("首頁", fontSize = 12.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color(0xFF2A6B5E),
                                            selectedTextColor = Color(0xFF2A6B5E),
                                            indicatorColor = Color(0xFFE8F3F0)
                                        )
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = { selectedTab = 1 },
                                        icon = { Icon(Icons.Default.Explore, null) },   // B2：羅盤（探索語意），取代放大鏡
                                        label = { Text("探索", fontSize = 12.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color(0xFF2A6B5E),
                                            selectedTextColor = Color(0xFF2A6B5E),
                                            indicatorColor = Color(0xFFE8F3F0)
                                        )
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = { selectedTab = 2; vm.currentScreen = "history" },
                                        icon = {
                                            BadgedBox(badge = { if (hasUnseenSoonTrip) Badge() }) {
                                                Icon(Icons.AutoMirrored.Filled.Assignment, null)
                                            }
                                        },
                                        label = { Text("我的微旅行", fontSize = 12.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color(0xFF2A6B5E),
                                            selectedTextColor = Color(0xFF2A6B5E),
                                            indicatorColor = Color(0xFFE8F3F0)
                                        )
                                    )
                                    // 好友（對齊網頁底部導覽第三格；待確認邀請顯示紅點數字）
                                    NavigationBarItem(
                                        selected = selectedTab == 3,
                                        onClick = { selectedTab = 3; vm.currentScreen = "friends" },
                                        icon = {
                                            BadgedBox(badge = {
                                                if (incomingFriendInvites.isNotEmpty()) Badge {
                                                    Text(if (incomingFriendInvites.size > 9) "9+" else "${incomingFriendInvites.size}")
                                                }
                                            }) { Icon(Icons.Default.People, null) }
                                        },
                                        label = { Text("好友", fontSize = 12.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color(0xFF2A6B5E),
                                            selectedTextColor = Color(0xFF2A6B5E),
                                            indicatorColor = Color(0xFFE8F3F0)
                                        )
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                AnimatedContent(
                                    targetState = selectedTab,
                                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                                    label = "tab_anim"
                                ) { tab ->
                                    when (tab) {
                                        0 -> HomeScreen(
                                            historyList = historyList,
                                            viewModel = vm,
                                            pendingFeedbacks = pendingFeedbacks,
                                            onStartPlanning = { showPlanningSheet = true },
                                            onViewAllHistory = { selectedTab = 2 },
                                            onHistoryItemClick = { vm.loadFromHistory(it) },
                                            onFeedbackClick = { item -> vm.loadForFeedback(item) },
                                            onMenuClick = { scope.launch { drawerState.open() } },
                                            currentUserName  = authProfile?.name  ?: "旅人",
                                            currentUserEmoji = authProfile?.emoji ?: "🌟",
                                            onSearch = { q -> exploreQuery = q; selectedTab = 1 },
                                            openJoinDialog = requestJoinDialog,
                                            onJoinDialogOpened = { requestJoinDialog = false },
                                            openModeDialog = requestModeDialog,
                                            onModeDialogOpened = { requestModeDialog = false },
                                            onOpenExplore = { selectedTab = 1 }
                                        )
                                        1 -> ExploreScreen(
                                            viewModel = vm,
                                            onMenuClick = { scope.launch { drawerState.open() } },
                                            initialQuery = exploreQuery,
                                            onInitialQueryConsumed = { exploreQuery = null },
                                            onUseTemplate = { seed ->
                                                vm.pendingTemplateSeed = seed
                                                selectedTab = 0; vm.currentScreen = "home"
                                                requestModeDialog = true
                                                android.widget.Toast.makeText(this@MainActivity,
                                                    "已選擇「${seed.tripName}」，選好製作方式就會帶入",
                                                    android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        2 -> ItineraryHistoryScreen(
                                            viewModel = vm,
                                            onBack = { selectedTab = 0 },
                                            currentUserName = authProfile?.name ?: "旅人",
                                            onMenuClick = { scope.launch { drawerState.open() } }
                                        )
                                        3 -> {
                                            val p = authProfile
                                            if (p != null) {
                                                com.example.travellink_ai.ui.friends.FriendListScreen(
                                                    profile = p, onBack = null,
                                                    onMenuClick = { scope.launch { drawerState.open() } }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Loading 指示器（AI 生成中）：即時顯示各階段「逐步成形」
                                val visualState by vm.visualState.collectAsState()
                                // 對齊網頁 #genMiniBar：生成中可縮小成底部小條，先去逛別的分頁
                                var genMinimized by remember { mutableStateOf(false) }
                                LaunchedEffect(visualState.isLoading) { if (!visualState.isLoading) genMinimized = false }
                                if (visualState.isLoading && !genMinimized) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0x66000000))
                                            // 展開時擋住底下的點擊
                                            .clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null
                                            ) {},
                                        contentAlignment = androidx.compose.ui.Alignment.Center
                                    ) {
                                        GeneratingItineraryCard(
                                            phase = visualState.loadingPhase,
                                            onMinimize = { genMinimized = true }
                                        )
                                    }
                                } else if (visualState.isLoading) {
                                    GeneratingMiniBar(
                                        phase = visualState.loadingPhase,
                                        onExpand = { genMinimized = false },
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                    )
                                }
                            }
                        }

                        // 規劃 BottomSheet（疊加在 Scaffold 之上）
                        if (showPlanningSheet) {
                            PlanningBottomSheet(
                                viewModel = vm,
                                onDismiss = { showPlanningSheet = false },
                                collabViewModel = null   // 首頁規劃固定單人模式
                            )
                        }
                        } // end ModalNavigationDrawer
                    }
                }
            }
        }
    }

    /** Activity 以 singleTop 模式重用時，由此接收新 Intent（通知點擊 / deep link） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent  // 觸發 Compose recompose，使 LaunchedEffect 重新執行
    }

    private fun handleFeedbackIntent(intent: Intent?, vm: ItineraryViewModel) {
        if (intent?.action == FeedbackNotificationReceiver.ACTION_OPEN_FEEDBACK) {
            vm.currentScreen = "feedback"
        }
    }

    private fun handleJoinIntent(intent: Intent?, collabVm: CollabViewModel) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "travellink" || uri.host != "join") return
        val docId = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return
        collabVm.setPendingJoinDocId(docId)
    }

    /** 建立行程回饋通知頻道（Android 8.0+ 必須先建立 Channel） */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    FeedbackNotificationReceiver.CHANNEL_ID,
                    "行程回饋通知",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "行程結束後提醒您填寫使用回饋" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    com.example.travellink_ai.util.NEARBY_STOP_CHANNEL_ID,
                    "行程中景點提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "行程進行中，靠近景點時提醒打卡" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    UpcomingTripReceiver.CHANNEL_ID,
                    "行程即將開始提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "出發前一天與當天早上提醒您的行程" }
            )
        }
    }
}

// ── 加入共編行程 Dialog ──────────────────────────────────────
@Composable
private fun JoinCollabDialog(
    userName: String,
    userEmoji: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "你收到了行程邀請 🗺️",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("將以以下身份加入共同編輯：", fontSize = 13.sp, color = Color.Gray)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE8F3F0), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(userEmoji, fontSize = 28.sp)
                    Text(
                        userName,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A6B5E))
            ) {
                Text("加入行程")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 占位頁面（探索功能預留）────────────────────────────────
@Composable
private fun ComingSoonScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("🚀", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text("即將推出，敬請期待！", fontSize = 13.sp, color = Color(0xFF5A5750))
        }
    }
}

// AI 生成即時預覽：階段名稱對齊網頁 #wizGenProgress（查景點 → AI 規劃 → 對應地圖 → 完成），
// ViewModel 的 5 個內部階段併入前 3 格。
@Composable
private fun GeneratingItineraryCard(phase: String, onMinimize: () -> Unit = {}) {
    val steps = listOf("查景點", "AI 規劃", "對應地圖", "完成")
    val activeIdx = when {
        phase.contains("完成") -> 3
        phase.contains("路線") || phase.contains("計算") -> 2
        phase.contains("分析") -> 1
        else -> 0   // 定位、搜尋
    }
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(16.dp),
        modifier = Modifier.padding(32.dp).width(300.dp)
    ) {
        Column(Modifier.padding(24.dp)) {
            Text("✨ 正在為你生成行程…",
                fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color(0xFF1A1814))
            Spacer(Modifier.height(4.dp))
            Text("行程正在一步步成形…", fontSize = 13.sp, color = Color(0xFF5A5750))
            Spacer(Modifier.height(18.dp))
            steps.forEachIndexed { i, label ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier.size(22.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        when {
                            i < activeIdx -> Text("✓", color = Color(0xFF2A6B5E),
                                fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            i == activeIdx -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), color = Color(0xFF2A6B5E), strokeWidth = 2.dp)
                            else -> Box(
                                Modifier.size(8.dp).background(Color(0xFFD8D4CC),
                                    androidx.compose.foundation.shape.CircleShape))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label, fontSize = 14.sp,
                        color = if (i <= activeIdx) Color(0xFF1A1814) else Color(0xFFB0ABA2),
                        fontWeight = if (i == activeIdx) androidx.compose.ui.text.font.FontWeight.Bold
                                     else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onMinimize, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                Text("縮小，先逛逛", color = Color(0xFF2A6B5E), fontSize = 14.sp)
            }
        }
    }
}

/** 生成縮小後的底部小條（對齊網頁「生成中… 點此返回進度視窗」） */
@Composable
private fun GeneratingMiniBar(phase: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color(0xFF1A1814),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("生成中…", color = Color.White, fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("點此返回進度視窗", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            Text(phase.take(16), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
        }
    }
}
