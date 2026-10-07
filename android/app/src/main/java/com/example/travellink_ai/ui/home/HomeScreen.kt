package com.example.travellink_ai.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.text.font.FontWeight
import com.example.travellink_ai.ui.theme.DmSerif
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.*

// ── 設計語言色彩（與地圖頁一致）────────────────────────────
private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border

// ── Hero 背景：台東自然意象漸層（之後可換成實際風景照）───────
private val heroThemes = listOf(
    listOf(Color(0xFF0D3B2E), Color(0xFF1A6B55), Color(0xFF2E9E7D)),  // 太麻里日出
    listOf(Color(0xFF0A2744), Color(0xFF1A5276), Color(0xFF2E7D9E)),  // 成功漁港黃昏
    listOf(Color(0xFF2D1B00), Color(0xFF6B4226), Color(0xFF2A6B5E)),  // 鹿野高台熱氣球
    listOf(Color(0xFF1A2A1A), Color(0xFF2E5C2E), Color(0xFF4A9E6B)),  // 知本森林
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    historyList: List<LocalItinerary>,
    viewModel: ItineraryViewModel,
    pendingFeedbacks: List<LocalItinerary> = emptyList(),
    onStartPlanning: () -> Unit,
    onViewAllHistory: () -> Unit,
    onHistoryItemClick: (LocalItinerary) -> Unit,
    onFeedbackClick: (LocalItinerary) -> Unit = {},
    onMenuClick: () -> Unit = {},
    currentUserName: String = "旅人",
    currentUserEmoji: String = "🌟",
    onSearch: (String) -> Unit = {},
    openJoinDialog: Boolean = false,          // 側欄「輸入邀請碼加入」
    onJoinDialogOpened: () -> Unit = {},
    openModeDialog: Boolean = false,          // 探索「用這份開始規劃」→ 先選製作方式
    onModeDialogOpened: () -> Unit = {},
    onOpenExplore: () -> Unit = {}
) {
    // 啟動時隨機選一組 Hero 漸層
    val heroGradient = remember { heroThemes.random() }

    // 過濾：只顯示兩週內的行程
    val twoWeeksAgo = remember { System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000) }
    val recentList = remember(historyList) {
        historyList.filter { it.createdAt >= twoWeeksAgo }.take(5)
    }

    val collabVm: CollabViewModel = hiltViewModel()

    // 好友通知（與抽屜/通知頁共用同一 Activity 範圍的 ViewModel 實例）
    val friendVm: com.example.travellink_ai.ui.friends.FriendViewModel = hiltViewModel()
    val unreadFriendNotifs by friendVm.unreadCount.collectAsState()
    val feedbackVm: com.example.travellink_ai.ui.feedback.FeedbackViewModel = hiltViewModel()
    val unreadFeedback by feedbackVm.unreadPendingFeedbackCount.collectAsState()
    // 手機系統通知的紀錄（行程提醒、到站、回饋…），通知頁也會列出
    val sysContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.example.travellink_ai.util.SystemNotificationLog.load(sysContext) }
    val sysEntries by com.example.travellink_ai.util.SystemNotificationLog.entries.collectAsState()
    // 鈴鐺紅點 = 未讀好友通知 + 未讀待回饋 + 未讀系統通知紀錄
    // （回饋類系統通知不算：同一趟已經算在「未讀待回饋」裡）
    val notifBadgeCount = unreadFriendNotifs + unreadFeedback + sysEntries.count {
        !it.read && it.kind != com.example.travellink_ai.util.SystemNotificationLog.Kind.FEEDBACK
    }

    // ── 加入共編 Dialog 狀態 ─────────────────────────────────────
    var showJoinDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openJoinDialog) {
        if (openJoinDialog) { showJoinDialog = true; onJoinDialogOpened() }
    }
    val joinError by collabVm.joinError.collectAsState()

    // QR 掃描 Launcher（必須在 Composable 頂層無條件宣告）
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            // 解析 travellink://join/{docId}
            if (scanned.startsWith("travellink://join/")) {
                val docId = scanned.removePrefix("travellink://join/").trim()
                if (docId.isNotBlank()) collabVm.setPendingJoinDocId(docId)
            }
        }
    }

    // 加入共編 Dialog
    if (showJoinDialog) {
        JoinByCodeDialog(
            joinError = joinError,
            userName  = currentUserName,
            userEmoji = currentUserEmoji,
            onJoinByPin = { pin -> collabVm.joinItineraryByPin(pin, currentUserName, currentUserEmoji) },
            onScanQr = {
                showJoinDialog = false
                scanLauncher.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("掃描旅伴分享的 QR Code")
                    setCameraId(0)
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                })
            },
            onDismiss = { showJoinDialog = false; collabVm.clearJoinError() }
        )
    }

    // ── 規劃模式選擇 Dialog：點「開始規劃行程」先選 個人 / 建立共編 / 加入共編 ──
    var showModeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openModeDialog) {
        if (openModeDialog) { showModeDialog = true; onModeDialogOpened() }
    }
    if (showModeDialog) {
        PlanningModeDialog(
            // 沒選就關掉：範本預填一併作廢，免得下次開精靈莫名帶入
            onDismiss  = { showModeDialog = false; viewModel.pendingTemplateSeed = null },
            onPersonal = { showModeDialog = false; onStartPlanning() },
            onCreateCollab = {
                showModeDialog = false
                collabVm.createCollabGroup(currentUserName, currentUserEmoji) {
                    viewModel.currentScreen = "group_setup"
                }
            }
        )
    }

    // ── 官方精選範本（取代原本的測試用社群牆；與網頁首頁同一份）──────────────
    val templatesVm: com.example.travellink_ai.ui.explore.ExploreTemplatesViewModel = hiltViewModel()
    val templates by templatesVm.templates.collectAsState()
    var previewTemplate by remember { mutableStateOf<com.example.travellink_ai.data.model.ExploreTemplate?>(null) }

    // 點 logo 捲回頂端（對齊網頁 logo 回首頁的手勢）
    val homeListState = rememberLazyListState()
    val homeScope = rememberCoroutineScope()

    // 通知中心已改為獨立全螢幕頁（NotificationScreen），鈴鐺改導覽至 currentScreen = "notifications"

    Scaffold(
        containerColor = Surface2,   // 與內容底色一致，避免底部導覽上緣露出主題粉色帶
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "選單",
                            tint = Ink,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // C2：點 logo 回頂端（對齊網頁 logo 可點回首頁）
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { homeScope.launch { homeListState.animateScrollToItem(0) } }
                    ) {
                        // logo 對齊網頁 .topbar-logo：🗺 emoji + DM Serif 襯線字標（無框、無副標）
                        Text(
                            "🗺 TravelLinkAI",
                            fontFamily = DmSerif,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = (-0.5).sp,
                            color = Ink
                        )
                    }
                },
                actions = {
                    // 🔔 通知鈴鐺
                    Box(
                        modifier = Modifier.padding(end = 4.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        IconButton(onClick = { viewModel.currentScreen = "notifications" }) {
                            Icon(
                                imageVector = Icons.Default.NotificationsNone,
                                contentDescription = "通知",
                                tint = if (notifBadgeCount > 0) Accent else Ink2,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // 未讀紅點徽章
                        if (notifBadgeCount > 0) {
                            // 高度固定 14dp，一位數是正圓、「9+」撐成膠囊；
                            // 文字裁掉字型上下留白並以行高置中，數字才會落在圓點正中間
                            Box(
                                modifier = Modifier
                                    .padding(top = 9.dp, end = 9.dp)
                                    .height(14.dp)
                                    .widthIn(min = 14.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFFD32F2F))
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (notifBadgeCount > 9) "9+" else notifBadgeCount.toString(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontSize = 9.sp,
                                        lineHeight = 9.sp,
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                                        )
                                    )
                                )
                            }
                        }
                    }

                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                ),
                // 外層 Scaffold 的 innerPadding 已含狀態列高度，這裡歸零避免二次墊高
                // （原本上方會多出一整條狀態列高度的空白）
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Surface2),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ── 搜尋（對齊網頁頂欄「搜尋目的地、行程名稱…」；送出後到探索頁篩選）──
            item {
                HomeSearchBar(onSearch = onSearch)
            }

            // ── Hero 區塊 ──────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(276.dp)   // 配合加大的標題字級，避免 CTA 按鈕被 SpaceBetween 壓扁
                        .background(
                            Brush.linearGradient(heroGradient)
                        )
                ) {
                    // 底部柔和遮罩
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Surface2)
                                )
                            )
                    )
                    // Hero 文字 + 按鈕：用 fillMaxSize + SpaceBetween 確保按鈕永遠貼底
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 上半：標籤 + 標題 + 副標
                        Column {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color.White.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "🌿 探索台灣之美",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "今天想去\n哪裡探索？",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                lineHeight = 38.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "讓 AI 為你量身打造專屬行程",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        // 下半：CTA 按鈕（建立微旅行 + 加入共編 並排）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showModeDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                                elevation = ButtonDefaults.buttonElevation(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    // F4：統一為網頁用語「建立微旅行」
                                    "建立微旅行",
                                    color = Accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                            }
                            // 加入共編：從模式選擇移出來，首頁直接可入
                            OutlinedButton(
                                onClick = { showJoinDialog = true },
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
                            ) {
                                Text("🔑 輸入邀請碼", color = Color.White,
                                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // ── 最近行程區塊 ────────────────────────────────────────
            item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "最近行程",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Ink
                            )
                            Text(
                                "兩週內的行程紀錄",
                                fontSize = 14.sp,
                                color = Ink2
                            )
                        }
                        if (recentList.isNotEmpty()) {
                            TextButton(onClick = onViewAllHistory) {
                                Text(
                                    "查看全部",
                                    fontSize = 15.sp,
                                    color = Accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (recentList.isEmpty()) {
                        // 空狀態
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🗺️", fontSize = 38.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "還沒有行程記錄",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "點擊「建立微旅行」讓 AI 幫你安排",
                                    fontSize = 14.sp,
                                    color = Ink2
                                )
                            }
                        }
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(recentList) { item ->
                                RecentItineraryCard(
                                    item = item,
                                    onClick = { onHistoryItemClick(item) }
                                )
                            }
                        }
                    }
                }
            }

            // ── ✦ 官方精選範本（首頁放 3 份，其餘到探索頁）──────────────
            templates?.let { d -> item {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "✦ 官方精選範本",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Ink
                            )
                            Text(
                                "台東怎麼玩，挑一份範本就開始",
                                fontSize = 14.sp,
                                color = Ink2
                            )
                        }
                        Text("看看精選範本 ›", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent,
                            modifier = Modifier.clickable(onClick = onOpenExplore))
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(d.homeTemplates.take(3), key = { it.key }) { tpl ->
                            com.example.travellink_ai.ui.explore.TemplateCard(
                                tpl = tpl, gate = d.gate,
                                onPreview = { previewTemplate = tpl },
                                modifier = Modifier.width(280.dp),
                                compact = true
                            )
                        }
                    }
                }
            } }

            // 共編入口已移入「開始規劃行程」的模式選擇 Dialog（PlanningModeDialog）
        }
    }

    // ── 範本預覽 → 用這份開始規劃（先選製作方式，同網頁）──────────────
    previewTemplate?.let { tpl ->
        templates?.let { d ->
            com.example.travellink_ai.ui.explore.TemplatePreviewSheet(
                tpl = tpl, gate = d.gate,
                onUse = {
                    previewTemplate = null
                    viewModel.pendingTemplateSeed = tpl.toSeed()
                    showModeDialog = true
                },
                onDismiss = { previewTemplate = null }
            )
        }
    }
}

// ── 規劃模式選擇 Dialog ──────────────────────────────────────
@Composable
private fun PlanningModeDialog(
    onDismiss: () -> Unit,
    onPersonal: () -> Unit,
    onCreateCollab: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("建立微旅行", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text("選擇你的製作方式", fontSize = 14.sp, color = Ink2)
                Spacer(Modifier.height(16.dp))

                PlanningModeOption(
                    emoji = "🧍", title = "單人製作",
                    subtitle = "自己規劃，獨享專屬行程", onClick = onPersonal
                )
                Spacer(Modifier.height(10.dp))
                PlanningModeOption(
                    emoji = "👥", title = "共編行程",
                    subtitle = "邀請朋友一起規劃行程", onClick = onCreateCollab
                )
            }
        }
    }
}

@Composable
private fun PlanningModeOption(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(emoji, fontSize = 26.sp)
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(subtitle, fontSize = 12.sp, color = Ink2)
        }
    }
}

// ── 最近行程卡片 ────────────────────────────────────────────
@Composable
private fun RecentItineraryCard(item: LocalItinerary, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date(item.createdAt))

    Card(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        // 卡片頂部色條
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    Brush.horizontalGradient(listOf(Accent, Color(0xFF4CAF93)))
                )
        )
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentLight
                ) {
                    Text(
                        text = item.region.ifBlank { "台東" },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 14.sp,
                        color = AccentDark,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = dateStr,
                    fontSize = 14.sp,
                    color = Ink2
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                // title 含使用者自訂命名（優先顯示），aiTitle 是 AI 副標
                text = item.title.ifBlank { item.aiTitle },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip("📅 ${item.days.take(10)}")
                InfoChip("👥 ${item.people}")
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = Surface2) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            fontSize = 14.sp,
            color = Ink2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NotificationPanel(
    pendingFeedbacks: List<LocalItinerary>,
    onFeedbackClick: (LocalItinerary) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        // 標題列
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "通知",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink
            )
            if (pendingFeedbacks.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFFFFEBEE)
                ) {
                    Text(
                        "${pendingFeedbacks.size} 則待回饋",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 14.sp,
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HorizontalDivider(color = Border.copy(alpha = 0.5f))

        if (pendingFeedbacks.isEmpty()) {
            // 空狀態
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔔", fontSize = 42.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "目前沒有通知",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "行程結束後會提醒您填寫使用回饋",
                    fontSize = 14.sp,
                    color = Ink2
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pendingFeedbacks) { item ->
                    NotificationItem(
                        item = item,
                        dateFmt = dateFmt,
                        onFeedbackClick = { onFeedbackClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    item: LocalItinerary,
    dateFmt: SimpleDateFormat,
    onFeedbackClick: () -> Unit
) {
    val endTimeStr = remember(item.days) {
        try {
            val endPart = item.days.substringAfterLast(" - ").trim()
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            val endDate = sdf.parse(endPart)
            if (endDate != null) dateFmt.format(endDate) else ""
        } catch (e: Exception) { "" }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AccentLight),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 圖示
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RateReview,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "旅程回饋待填寫",
                    fontSize = 14.sp,
                    color = AccentDark,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.title.ifBlank { item.aiTitle },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (endTimeStr.isNotBlank()) {
                    Text(
                        "結束於 $endTimeStr",
                        fontSize = 14.sp,
                        color = Ink2
                    )
                }
            }

            // 按鈕
            Button(
                onClick = onFeedbackClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("填寫", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── 加入共編卡片 ─────────────────────────────────────────────
@Composable
private fun JoinCollabCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.Start) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GroupAdd,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("輸入邀請碼", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("加入朋友的共編行程，也可掃 QR", fontSize = 14.sp, color = Ink2)
        }
    }
}

/**
 * 邀請碼顯示層連字號：純英數狀態（如 UBSDQ9AM）在畫面上顯示為 UBSD-Q9AM。
 * 超過 4 碼才插入；6 位數舊 PIN 已停發（2026-07-14 起），顯示 1234-56 的邊角可忽略。
 */
private val InviteCodeDashTransformation = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val formatted = if (raw.length > 4) raw.substring(0, 4) + "-" + raw.substring(4) else raw
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(formatted),
        object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 4) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 4) offset - 1 else offset
        }
    )
}

// ── 加入共編 Dialog（輸入邀請碼 或 掃描 QR；舊行程 6 位 PIN 仍相容）────
@Composable
private fun JoinByCodeDialog(
    joinError: String?,
    userName: String,
    userEmoji: String,
    onJoinByPin: (pin: String) -> Unit,
    onScanQr: () -> Unit,
    onDismiss: () -> Unit
) {
    // 邀請碼（KYEX-AGDD 這種網頁格式；舊行程的 6 位數密碼直接輸入也通）。
    // 狀態只存英數（貼上含連字號/空白會自動剝掉），顯示時由 VisualTransformation
    // 自動補 XXXX-XXXX 連字號——修正「貼上 UBSD-Q9AM 變 UBSD-UBS-」的輸入互打 bug
    var inviteCode by remember { mutableStateOf("") }
    val codeValid = inviteCode.length == 8 ||
        (inviteCode.length == 6 && inviteCode.all { it.isDigit() })

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        title = {
            Text(
                "加入共編行程 🔑",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // ── 已登入身份顯示 ──────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE8F3F0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(userEmoji, fontSize = 26.sp)
                    Column {
                        Text("以此身份加入", fontSize = 11.sp, color = Color.Gray)
                        Text(userName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(
                    "請輸入朋友分享的 8 碼邀請碼",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink2
                )

                // ── 邀請碼輸入（狀態只存英數 8 碼，連字號由顯示層自動補）──
                androidx.compose.material3.OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { input ->
                        inviteCode = input.uppercase()
                            .filter { it.isLetterOrDigit() }
                            .take(8)
                    },
                    visualTransformation = InviteCodeDashTransformation,
                    placeholder = { Text("KYEX-AGDD", color = Color(0xFFB0B0B0)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = Color(0xFF6366F1),
                        textAlign = TextAlign.Center
                    )
                )

                // ── 錯誤訊息 ───────────────────────────────────
                if (joinError != null) {
                    Text(
                        joinError,
                        fontSize = 14.sp,
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // ── OR 分隔 ────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                    Text("  或  ", fontSize = 13.sp, color = Color(0xFF9E9E9E))
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                }

                // ── 掃描 QR 按鈕 ───────────────────────────────
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("掃描旅伴的 QR Code", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onJoinByPin(inviteCode) },
                enabled = codeValid,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("加入行程", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 快捷功能小方塊 ──────────────────────────────────────────
@Composable
private fun QuickActionCard(
    emoji: String,
    label: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    val alpha = if (enabled) 1f else 0.5f
    Card(
        modifier = modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color.White else Surface2
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(emoji, fontSize = 26.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.copy(alpha = alpha)
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Ink2.copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun HomeSearchBar(onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = { onSearch(text.trim()); text = "" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Surface2)
            .border(1.dp, Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = Ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("搜尋目的地、行程名稱…", fontSize = 14.sp, color = Ink2)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (text.isNotEmpty()) {
            Text("搜尋", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.clickable { submit() }.padding(start = 8.dp))
        }
    }
}
