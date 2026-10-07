package com.example.travellink_ai.ui.drawer


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.example.travellink_ai.ui.theme.DmSerif
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.auth.AuthState
import com.example.travellink_ai.ui.auth.AuthViewModel
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import java.text.SimpleDateFormat
import java.util.*

// ── 色彩 ─────────────────────────────────────────────────────
private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border
private val Gold        = DesignTokens.Gold        // 對齊網頁 --gold #C9A227

// ── 抽屜側欄內容 ─────────────────────────────────────────────
@Composable
fun DrawerContent(
    viewModel: ItineraryViewModel,
    authViewModel: AuthViewModel,
    onClose: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenMyTrips: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onCreateTrip: () -> Unit = {},
    onJoinByCode: () -> Unit = {}
) {
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showChangePwdDialog by remember { mutableStateOf(false) }
    // 只有 Email/密碼帳號才有「修改密碼」（與網頁、個人資料頁同規則）
    val isPasswordAccount = com.google.firebase.auth.FirebaseAuth.getInstance()
        .currentUser?.providerData?.any { it.providerId == "password" } == true
    val userPrefs by viewModel.userPrefs.collectAsState()
    val authState by authViewModel.authState.collectAsState()
    val profile   = (authState as? AuthState.Authenticated)?.profile

    // 好友待確認邀請數（與 MainActivity/HomeScreen 共用同一個 Activity 範圍的 ViewModel 實例）
    val friendVm: com.example.travellink_ai.ui.friends.FriendViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val incomingInvites by friendVm.incoming.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Color.White)
    ) {
        // ── Header ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Accent, Color(0xFF4CAF93)))
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .statusBarsPadding()
        ) {
            Column {
                // 用戶頭像 / App icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                        .then(if (profile != null) Modifier.clickable { onClose(); onOpenProfile() } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(profile?.emoji ?: "✈", fontSize = 30.sp)
                }
                Spacer(Modifier.height(12.dp))
                if (profile != null) {
                    Text(
                        profile.name.ifBlank { "旅人" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        profile.email,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        // 字標對齊網頁 .topbar-logo DM Serif
                        "TravelLinkAI",
                        fontFamily = DmSerif,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = (-0.5).sp,
                        color = Color.White
                    )
                    Text(
                        "AI 智慧行程規劃",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // ── 選單項目 ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // 順序對齊網頁使用者選單（renderUserMenu）：行程 → 好友 → 喜好／密碼 → 登出；
            // App 專有的回饋、通知、關於放在登出之前的「其他」區
            DrawerItem(
                icon = Icons.AutoMirrored.Filled.Assignment,
                label = "我的微旅行",
                subtitle = "查看與管理所有行程",
                onClick = { onClose(); onOpenMyTrips() }
            )
            DrawerItem(
                icon = Icons.Default.Add,
                label = "建立微旅行",
                subtitle = "讓 AI 幫你規劃新行程",
                onClick = { onClose(); onCreateTrip() }
            )
            DrawerItem(
                icon = Icons.Default.VpnKey,
                label = "輸入邀請碼加入",
                subtitle = "加入朋友的共編行程",
                onClick = { onClose(); onJoinByCode() }
            )
            if (profile != null) {
                DrawerItem(
                    icon = Icons.Default.People,
                    label = "我的好友",
                    subtitle = "管理好友與邀請",
                    onClick = { onClose(); onOpenFriends() },
                    badgeCount = incomingInvites.size
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Border.copy(alpha = 0.5f)
            )

            DrawerOfflineMapItem()
            DrawerItem(
                icon = Icons.Default.Tune,
                label = "修改個人喜好",
                subtitle = "興趣、節奏、飲食禁忌與預設值",
                onClick = { onClose(); viewModel.currentScreen = "preferences" }
            )
            if (profile != null && isPasswordAccount) {
                DrawerItem(
                    icon = Icons.Default.Lock,
                    label = "修改密碼",
                    subtitle = "更新登入密碼",
                    onClick = { showChangePwdDialog = true }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Border.copy(alpha = 0.5f)
            )

            DrawerSectionLabel("其他")

            // 🧪 只有開發版：文字 AI 引擎（AMD 組展示用；正式版恆為 Gemini）
            if (com.example.travellink_ai.BuildConfig.DEBUG) {
                DrawerAiProviderToggle()
                DrawerShowcaseToggle()
            }

            DrawerItem(
                icon = Icons.Default.RateReview,
                label = "我的回饋紀錄",
                subtitle = "查看已評分的旅程",
                onClick = { onClose(); viewModel.currentScreen = "feedback_history" }
            )
            // 通知設定（直接在抽屜內切換，不需要獨立頁面）
            DrawerNotificationToggle(
                title = "回饋通知",
                onText = "行程結束時發送提醒",
                enabled = userPrefs.feedbackNotificationEnabled,
                onToggle = { viewModel.toggleFeedbackNotification(it) }
            )
            DrawerNotificationToggle(
                title = "行程提醒",
                onText = "出發前一天與當天早上提醒",
                enabled = userPrefs.upcomingTripNotificationEnabled,
                onToggle = { viewModel.toggleUpcomingTripNotification(it) }
            )
            DrawerItem(
                icon = Icons.Default.Info,
                label = "關於 TravelLinkAI",
                subtitle = "版本資訊與使用說明",
                onClick = { onClose(); viewModel.currentScreen = "about" }
            )

            if (profile != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Border.copy(alpha = 0.5f)
                )
                // 👋 登出（網頁選單最後一項，紅字）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSignOutDialog = true }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, tint = DesignTokens.Red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("登出", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Red)
                }
            }
        }

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("登出") },
                text = { Text("確定要登出帳號嗎？") },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        onClose()
                        authViewModel.signOut()
                    }) { Text("登出", color = DesignTokens.Red) }
                },
                dismissButton = { TextButton(onClick = { showSignOutDialog = false }) { Text("取消") } }
            )
        }
        if (showChangePwdDialog) {
            com.example.travellink_ai.ui.auth.ChangePasswordDialog(
                onDismiss = { showChangePwdDialog = false },
                onSubmit = { current, new, cb -> authViewModel.changePassword(current, new, cb) }
            )
        }

        // ── 底部版本號 ────────────────────────────────────────
        Text(
            "v1.0.0",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            fontSize = 13.sp,
            color = Ink2.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Ink2.copy(alpha = 0.7f),
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(subtitle, fontSize = 13.sp, color = Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .heightIn(min = 20.dp)
                    .widthIn(min = 20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFD32F2F))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (badgeCount > 9) "9+" else badgeCount.toString(),
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Icon(Icons.Default.ChevronRight, null, tint = Border, modifier = Modifier.size(18.dp))
        }
    }
}

// 台東離線地圖（MapLibre + PMTiles）：下載／更新／刪除
@Composable
private fun DrawerOfflineMapItem() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by com.example.travellink_ai.util.OfflineBasemap.state.collectAsState()
    LaunchedEffect(Unit) { com.example.travellink_ai.util.OfflineBasemap.refresh(context) }
    var showManage by remember { mutableStateOf(false) }
    var showConfirmDownload by remember { mutableStateOf(false) }
    val size = com.example.travellink_ai.util.OfflineBasemap.APPROX_SIZE_MB

    val subtitle = when (val st = state) {
        is com.example.travellink_ai.util.OfflineBasemap.State.NotDownloaded -> "約 $size MB・沒網路時也能看地圖"
        is com.example.travellink_ai.util.OfflineBasemap.State.Downloading -> "下載中 ${(st.progress * 100).toInt()}%"
        is com.example.travellink_ai.util.OfflineBasemap.State.Ready ->
            if (st.outdated) "有新版圖資，點此更新" else "已下載・點此管理"
        is com.example.travellink_ai.util.OfflineBasemap.State.Failed -> "下載失敗，點此重試"
    }
    Column {
        DrawerItem(
            icon = Icons.Default.Map,
            label = "台東離線地圖",
            subtitle = subtitle,
            onClick = {
                when (val st = state) {
                    is com.example.travellink_ai.util.OfflineBasemap.State.Downloading -> Unit
                    is com.example.travellink_ai.util.OfflineBasemap.State.Ready ->
                        if (st.outdated) showConfirmDownload = true else showManage = true
                    // 先問再下載：19 MB 用行動網路的人可能不想現在下
                    else -> showConfirmDownload = true
                }
            }
        )
        (state as? com.example.travellink_ai.util.OfflineBasemap.State.Downloading)?.let { st ->
            LinearProgressIndicator(
                progress = { st.progress },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 20.dp, bottom = 6.dp),
                color = Accent,
                trackColor = Border.copy(alpha = 0.4f)
            )
        }
    }
    if (showConfirmDownload) {
        // 用行動網路（計量網路）時特別提醒流量
        val metered = remember {
            (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.isActiveNetworkMetered == true
        }
        val updating = (state as? com.example.travellink_ai.util.OfflineBasemap.State.Ready)?.outdated == true
        AlertDialog(
            onDismissRequest = { showConfirmDownload = false },
            title = { Text(if (updating) "更新台東離線地圖？" else "下載台東離線地圖？") },
            text = {
                Text(
                    "約 $size MB，含綠島、蘭嶼（OpenStreetMap 圖資）。下載後沒網路時也能看地圖、路線與目前位置。" +
                        if (metered) "\n\n你目前使用行動網路，下載會耗用流量，建議連上 Wi-Fi 再下載。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDownload = false
                    com.example.travellink_ai.util.OfflineBasemap.startDownload(context)
                }) { Text(if (updating) "更新" else "下載") }
            },
            dismissButton = { TextButton(onClick = { showConfirmDownload = false }) { Text("取消") } }
        )
    }
    if (showManage) {
        AlertDialog(
            onDismissRequest = { showManage = false },
            title = { Text("台東離線地圖") },
            text = { Text("已下載（圖資版本 ${com.example.travellink_ai.util.OfflineBasemap.VERSION}）。\n刪除後可釋放約 $size MB，沒網路時會改顯示簡易路線圖。") },
            confirmButton = { TextButton(onClick = { showManage = false }) { Text("完成") } },
            dismissButton = {
                TextButton(onClick = {
                    com.example.travellink_ai.util.OfflineBasemap.delete(context); showManage = false
                }) { Text("刪除", color = DesignTokens.Red) }
            }
        )
    }
}

/** 🧪 開發版限定：文字 AI 引擎 Gemini ↔ AMD（見 AiProvider） */
@Composable
private fun DrawerAiProviderToggle() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val kind by com.example.travellink_ai.data.ai.AiProvider.kind.collectAsState()
    val amd = kind == com.example.travellink_ai.data.ai.AiProvider.Kind.AMD
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (amd) Color(0xFFFDECEA) else AccentLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = if (amd) Color(0xFFB0121B) else Accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("🧪 AI 引擎", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                if (amd) "AMD gpt-oss-120b（文字）" else "Gemini（預設）",
                fontSize = 13.sp,
                color = if (amd) Color(0xFFB0121B) else Ink2
            )
        }
        Switch(
            checked = amd,
            onCheckedChange = { on ->
                com.example.travellink_ai.data.ai.AiProvider.set(
                    context,
                    if (on) com.example.travellink_ai.data.ai.AiProvider.Kind.AMD
                    else com.example.travellink_ai.data.ai.AiProvider.Kind.GEMINI
                )
            },
            modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFFB0121B),
                uncheckedTrackColor = Border
            )
        )
    }
}

/** 🎬 開發版限定：展示模式（旅程應變助理只留情境模擬，見 ShowcaseMode） */
@Composable
private fun DrawerShowcaseToggle() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by com.example.travellink_ai.data.ai.ShowcaseMode.on.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Slideshow, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("🎬 展示模式", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                if (on) "只留情境模擬，隱藏開發選項" else "關閉（顯示 mock 等開發選項）",
                fontSize = 13.sp, color = Ink2
            )
        }
        Switch(
            checked = on,
            onCheckedChange = { com.example.travellink_ai.data.ai.ShowcaseMode.set(context, it) },
            modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, uncheckedTrackColor = Border)
        )
    }
}

@Composable
private fun DrawerNotificationToggle(
    title: String,
    onText: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.NotificationsNone,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                if (enabled) onText else "已關閉通知",
                fontSize = 13.sp,
                color = if (enabled) Ink2 else Border
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                uncheckedTrackColor = Border
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ① 旅遊偏好設定頁
// ══════════════════════════════════════════════════════════════

private val stationOptions    = listOf("台東車站", "知本車站", "鹿野車站", "關山車站", "池上車站")
// 資料值與網頁端一致（輕快/平衡/悠閒），順序也對齊網頁精靈
private val paceOptions       = listOf(
    "輕快" to "🏃 緊湊充實 — 景點多、行程豐富",
    "平衡" to "🚶 標準步調 — 不趕不閒",
    "悠閒" to "🐢 放鬆慢遊 — 景點少、停留久"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(
    viewModel: ItineraryViewModel,
    onBack: () -> Unit,
    onboarding: Boolean = false   // 新帳號引導：完成／跳過都回首頁
) {
    val userPrefs by viewModel.userPrefs.collectAsState()

    // 本地可編輯狀態
    var station           by remember(userPrefs) { mutableStateOf(userPrefs.departureStation) }
    var pace              by remember(userPrefs) { mutableStateOf(userPrefs.pace) }
    // 舊資料（14 標籤含 emoji）在載入時正規化成網頁對齊後的 6 標籤
    val selectedInterests = remember(userPrefs) {
        mutableStateListOf<String>().also {
            it.addAll(com.example.travellink_ai.data.model.normalizeInterests(userPrefs.interests))
        }
    }
    // 飲食禁忌 / 想避免的事物（對齊網頁「設定個人喜好」，每趟行程自動套用）
    val selectedAvoidTags = remember(userPrefs) {
        mutableStateListOf<String>().also { it.addAll(userPrefs.avoidTags) }
    }
    var avoidNote by remember(userPrefs) { mutableStateOf(userPrefs.avoidNote) }

    var showSaved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定個人喜好 ✨", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink) },
                navigationIcon = {
                    if (!onboarding) IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Surface2,
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
              Column(Modifier.navigationBarsPadding()) {
                Button(
                    onClick = {
                        viewModel.saveUserPrefs(
                            ItineraryViewModel.UserPrefs(
                                departureStation    = station,
                                pace                = pace,
                                interests           = selectedInterests.toSet(),
                                avoidTags           = selectedAvoidTags.toSet(),
                                avoidNote           = avoidNote.trim(),
                                feedbackNotificationEnabled = userPrefs.feedbackNotificationEnabled
                            )
                        )
                        showSaved = true
                        if (onboarding) onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Icon(
                        if (showSaved) Icons.Default.Check else Icons.Default.Save,
                        null,
                        Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when { onboarding -> "完成設定"; showSaved -> "已儲存！"; else -> "儲存偏好" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
                if (onboarding) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) { Text("先跳過，下次再說", color = Ink2) }
                }
              }
            }
        }
    ) { padding ->
        LazyColumn(
            // 偏好含自由填寫欄位，edge-to-edge 下需 imePadding 避免被鍵盤遮住
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 提示
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentLight
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lightbulb, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Text(
                            // 說明文字對齊網頁「設定個人喜好」
                            "這些長期偏好會成為每趟行程的預設值與 AI 規劃的參考；飲食禁忌則會套用到所有行程。建立行程時仍可為單趟自由調整。",
                            fontSize = 14.sp,
                            color = AccentDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ① 旅遊偏好（對齊網頁順序：興趣 → 節奏 → 飲食）
            item {
                PrefCard(title = "平常偏好的旅遊興趣（可多選）") {
                    com.example.travellink_ai.data.model.ALL_TRAVEL_PREFERENCES.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { pref ->
                                val sel = selectedInterests.contains(pref)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) AccentLight else Surface2)
                                        .border(
                                            width = if (sel) 2.dp else 1.dp,
                                            color = if (sel) Accent else Border,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            if (sel) selectedInterests.remove(pref) else selectedInterests.add(pref)
                                            showSaved = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        com.example.travellink_ai.data.model.displayInterest(pref),
                                        fontSize = 14.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) AccentDark else Ink,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ② 行程節奏
            item {
                PrefCard(title = "平常偏好的行程節奏") {
                    paceOptions.forEach { (p, desc) ->
                        val sel = pace == p
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) AccentLight else Color.Transparent)
                                .clickable { pace = p; showSaved = false }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    p, fontSize = 16.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) AccentDark else Ink
                                )
                                Text(desc, fontSize = 13.sp, color = Ink2)
                            }
                            if (sel) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                        if (p != paceOptions.last().first) HorizontalDivider(color = Border.copy(alpha = 0.4f))
                    }
                }
            }

            // ②b 飲食禁忌 / 想避免的事物（對齊網頁「設定個人喜好」）
            item {
                PrefCard(title = "飲食禁忌 / 想避免的事物") {
                    Text(
                        "選填，每趟行程都會自動套用",
                        fontSize = 13.sp, color = Ink2,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    AvoidTagGroup(
                        groupLabel = "飲食",
                        tags = com.example.travellink_ai.data.model.AVOID_TAGS_DIET,
                        selected = selectedAvoidTags,
                        onToggle = { tag ->
                            if (selectedAvoidTags.contains(tag)) selectedAvoidTags.remove(tag)
                            else selectedAvoidTags.add(tag)
                            showSaved = false
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    AvoidTagGroup(
                        groupLabel = "行程",
                        tags = com.example.travellink_ai.data.model.AVOID_TAGS_TRIP,
                        selected = selectedAvoidTags,
                        onToggle = { tag ->
                            if (selectedAvoidTags.contains(tag)) selectedAvoidTags.remove(tag)
                            else selectedAvoidTags.add(tag)
                            showSaved = false
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = avoidNote,
                        onValueChange = { avoidNote = it; showSaved = false },
                        placeholder = {
                            Text("其他想避免的（自由填寫）…例：想避開夜市、不愛人擠人…",
                                fontSize = 13.sp, color = Border)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border
                        ),
                        minLines = 2, maxLines = 3
                    )
                }
            }

            // ③ 出發車站
            item {
                PrefCard(title = "預設出發車站") {
                    stationOptions.forEach { s ->
                        val sel = station == s
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) AccentLight else Color.Transparent)
                                .clickable { station = s; showSaved = false }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "🚉 $s", fontSize = 16.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) AccentDark else Ink
                            )
                            if (sel) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                        if (s != stationOptions.last()) HorizontalDivider(color = Border.copy(alpha = 0.4f))
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
fun AvoidTagGroup(
    groupLabel: String,
    tags: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    Column {
        Text(groupLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
        Spacer(Modifier.height(6.dp))
        tags.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tag ->
                    val sel = selected.contains(tag)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) AccentLight else Surface2)
                            .border(
                                width = if (sel) 2.dp else 1.dp,
                                color = if (sel) Accent else Border,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onToggle(tag) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            tag, fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) AccentDark else Ink,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PrefCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ③ 關於 TravelLinkAI
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val features = listOf(
        "🗺️ AI 智慧行程規劃" to "根據你的偏好自動生成完整一日遊行程",
        "📍 真實景點驗證" to "僅推薦 Google Maps 上實際存在的景點",
        "🚉 以車站為中心" to "所有景點均在指定車站車程範圍內",
        "⏰ 智慧時間排程" to "自動考量營業時間與交通時間",
        "🖼️ AI 插圖生成" to "為每趟行程生成獨特的風格插畫",
        "💬 旅途回饋學習" to "你的評分幫助 AI 推薦更好的景點"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("關於 TravelLinkAI", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Surface2
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App Header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // logo 對齊網頁：🗺 emoji（無框）
                        Text("🗺", fontSize = 56.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            // 字標對齊網頁 .topbar-logo DM Serif（關於頁）
                            "TravelLinkAI",
                            fontFamily = DmSerif,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = (-0.5).sp,
                            color = Ink
                        )
                        Text(
                            "AI 智慧行程規劃",
                            fontSize = 15.sp,
                            color = Ink2
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = AccentLight) {
                            Text(
                                "版本 v1.0.0",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                                fontSize = 14.sp,
                                color = AccentDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 功能介紹
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("主要功能", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(12.dp))
                        features.forEachIndexed { idx, (title, desc) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(title.take(2), fontSize = 22.sp)
                                Column {
                                    Text(
                                        title.drop(2).trim(),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink
                                    )
                                    Text(desc, fontSize = 13.sp, color = Ink2)
                                }
                            }
                            if (idx < features.lastIndex) HorizontalDivider(color = Border.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            // 使用說明
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("使用說明", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "1️⃣" to "點擊首頁的「建立微旅行」按鈕",
                            "2️⃣" to "填寫目的地、日期、人數與偏好",
                            "3️⃣" to "AI 自動生成行程並產生地圖路線",
                            "4️⃣" to "旅程結束後填寫回饋幫助 AI 進步"
                        ).forEach { (num, text) ->
                            Row(
                                modifier = Modifier.padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(num, fontSize = 18.sp)
                                Text(text, fontSize = 15.sp, color = Ink)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ④ 我的回饋紀錄
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackHistoryScreen(
    historyList: List<LocalItinerary>,
    onBack: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    val ratedItems = remember(historyList) {
        historyList
            .filter { it.feedbackSubmitted && it.overallRating > 0 }
            .sortedByDescending { it.createdAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的回饋紀錄", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                        Text("${ratedItems.size} 筆已評分旅程", fontSize = 13.sp, color = Ink2)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Surface2
    ) { padding ->
        if (ratedItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⭐", fontSize = 50.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("尚無回饋紀錄", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("完成旅程並填寫回饋後會顯示在這裡", fontSize = 15.sp, color = Ink2)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ratedItems) { item ->
                    FeedbackHistoryCard(item = item, dateFmt = dateFmt)
                }
            }
        }
    }
}

@Composable
private fun FeedbackHistoryCard(
    item: LocalItinerary,
    dateFmt: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左側色條
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(
                        when (item.overallRating) {
                            5    -> Accent
                            4    -> Color(0xFF4CAF93)
                            3    -> Gold
                            else -> Color(0xFFE57373)
                        }
                    )
            )
            Column(modifier = Modifier.padding(14.dp).weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // 地區標籤
                    Surface(shape = RoundedCornerShape(6.dp), color = AccentLight) {
                        Text(
                            item.region.ifBlank { "台東" },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 14.sp,
                            color = AccentDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // 日期
                    Text(
                        dateFmt.format(Date(item.createdAt)),
                        fontSize = 14.sp,
                        color = Ink2
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.title.ifBlank { item.aiTitle },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                // 星星評分
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = if (star <= item.overallRating)
                                Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (star <= item.overallRating) Gold else Border,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (item.overallRating) {
                            1 -> "很失望"
                            2 -> "還需改善"
                            3 -> "普通"
                            4 -> "不錯"
                            5 -> "非常滿意 🎉"
                            else -> ""
                        },
                        fontSize = 14.sp,
                        color = if (item.overallRating >= 4) Accent else Ink2,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // 景點摘要
                if (item.stops.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.stops.filter { !it.isStation }.take(3)
                            .joinToString(" → ") { it.emoji + it.name },
                        fontSize = 13.sp,
                        color = Ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
