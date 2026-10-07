package com.example.travellink_ai.ui.collab

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.ui.friends.FriendBrief
import com.example.travellink_ai.ui.friends.FriendViewModel
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.example.travellink_ai.ui.planning.PlanningBottomSheet

// ── 配色（與首頁一致的暖色調 + 網頁端的靛藍主色）────────────────
private val GsInk      = DesignTokens.Ink
private val GsInk2     = DesignTokens.Ink2   // 對齊網頁 --ink2 #5A5750
private val GsIndigo   = Color(0xFF6366F1)   // App 專有次要靛色（網頁無對應 token）
private val GsIndigoBg = Color(0xFFEEF0FE)   // App 專有靛色底
private val GsGreen    = DesignTokens.Accent
private val GsGreenBg  = DesignTokens.AccentLight
private val GsBg       = DesignTokens.Bg

// 與網頁端一致的選項
private val gsInterestOptions = listOf(
    "美食" to "🍜", "文化" to "🏛️", "自然" to "🌿",
    "打卡" to "📸", "運動" to "🏃", "放鬆" to "😌"
)
private val gsPaceOptions = listOf(
    "輕快" to "⚡ 緊湊充實", "平衡" to "🚶 標準步調", "悠閒" to "🌿 放鬆慢遊"
)
private val gsBudgetOptions = listOf(
    "節省（每人 $500 內）"        to ("節省" to "每人 $500 內"),
    "適中（每人 $500–$1,500）"    to ("適中" to "每人 $500–$1,500"),
    "舒適（每人 $1,500–$3,000）"  to ("舒適" to "每人 $1,500–$3,000"),
    "豪華（每人 $3,000 以上）"    to ("豪華" to "每人 $3,000 以上")
)

/**
 * 多人共作設定頁：建立群組後先分享邀請碼、各成員填偏好，
 * 擁有者再從這裡開始生成團體行程（對齊網頁端流程）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSetupScreen(
    viewModel: ItineraryViewModel,
    collabViewModel: CollabViewModel,
    onBack: () -> Unit
) {
    val inviteCode   by collabViewModel.joinPin.collectAsState()
    val members      by collabViewModel.collabMembers.collectAsState()
    val groupProfile by collabViewModel.groupProfile.collectAsState()
    val myRole       by collabViewModel.myRole.collectAsState()
    val userPrefs    by viewModel.userPrefs.collectAsState()
    val visualState  by viewModel.visualState.collectAsState()
    val itinerary    by viewModel.itinerary.collectAsState()
    val isOwner = myRole == "owner"
    val friendVm: FriendViewModel = hiltViewModel()
    val friends      by friendVm.acceptedFriends.collectAsState()
    val memberEmails by collabViewModel.memberEmails.collectAsState()
    val inviteResult by collabViewModel.inviteFriendResult.collectAsState()
    val toastCtx = LocalContext.current
    // 送出中的好友（email 小寫）：按下到有結果之前不能重按；成功時 memberEmails 已先帶上對方
    val inviting = remember { mutableStateListOf<String>() }
    LaunchedEffect(inviteResult) {
        val msg = inviteResult ?: return@LaunchedEffect
        inviting.clear()
        android.widget.Toast.makeText(toastCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
        collabViewModel.clearInviteFriendResult()
    }

    // 系統返回鍵走同一條 onBack：本頁沒有 BackHandler 時系統返回會直接結束 Activity，
    // 未生成的空群組就繞過了 abandonGroupSetupIfEmpty 的清理
    BackHandler { onBack() }

    // 生成完成偵測：先看到「群組尚無景點」、之後 stops 出現且不在生成中 → 跳轉行程預覽。
    // 擁有者生成完成會觸發；成員端等待時收到 Firestore 快照也會觸發（自動跟著進入行程）。
    var sawEmptyStops by remember { mutableStateOf(false) }
    var wasLoading    by remember { mutableStateOf(false) }
    var genFailed     by remember { mutableStateOf(false) }
    LaunchedEffect(itinerary?.stops?.size, visualState.isLoading) {
        val stopsEmpty = itinerary?.stops.isNullOrEmpty()
        if (stopsEmpty) sawEmptyStops = true
        if (visualState.isLoading) genFailed = false
        if (sawEmptyStops && !stopsEmpty && !visualState.isLoading) {
            viewModel.currentScreen = "preview"
        }
        // 生成結束但仍沒有景點 → 生成失敗，顯示提示
        if (wasLoading && !visualState.isLoading && stopsEmpty) genFailed = true
        wasLoading = visualState.isLoading
    }

    // 我的偏好表單（預設帶入本地偏好設定）
    val selectedInterests = remember(userPrefs) {
        mutableStateListOf<String>().also { list ->
            userPrefs.interests.filter { i -> gsInterestOptions.any { it.first == i } }
                .take(3).forEach { list.add(it) }
        }
    }
    var selectedPace   by remember(userPrefs) { mutableStateOf(userPrefs.pace.ifBlank { "平衡" }) }
    var selectedBudget by remember { mutableStateOf("") }
    var desiredSpots   by remember { mutableStateOf("") }
    var prefsSaved     by remember { mutableStateOf(false) }

    var showPlanningSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GsBg)
    ) {
        // ── 標題列（statusBarsPadding：避免頂進狀態列導致返回鈕點不到）──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = GsInk)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("👥 共編行程", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = GsInk)
                Text(
                    "分享邀請碼、填好各自偏好，擁有者隨時可生成團體行程",
                    fontSize = 12.sp, color = GsInk2
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GsTripInfoCard(memberCount = members.size)
            GsInviteCard(
                inviteCode = inviteCode,
                shareLink  = collabViewModel.getShareLink()
            )
            // 與行程頁的邀請對話框相同：直接把已成立的好友加進來（擁有者才能邀請）
            if (isOwner) GsFriendInviteCard(
                friends = friends,
                invitedEmails = memberEmails,
                sending = inviting,
                onInvite = { f -> inviting += f.email.lowercase(); collabViewModel.inviteFriendToCollab(f.email, f.name, itinerary?.title.orEmpty()) }
            )
            GsMembersCard(members = members, myUid = collabViewModel.currentUserUid)
            GsMyPrefsCard(
                selectedInterests = selectedInterests,
                selectedPace      = selectedPace,
                onPaceChange      = { selectedPace = it },
                selectedBudget    = selectedBudget,
                onBudgetChange    = { selectedBudget = it },
                desiredSpots      = desiredSpots,
                onSpotsChange     = { desiredSpots = it },
                prefsSaved        = prefsSaved,
                onSave = {
                    collabViewModel.saveMyPrefs(
                        interests    = selectedInterests.toList(),
                        pace         = selectedPace,
                        budget       = selectedBudget,
                        desiredSpots = desiredSpots.trim()
                    )
                    prefsSaved = true
                }
            )
            GsGroupSummaryCard(groupProfile = groupProfile)

            // ── 開始規劃（僅擁有者）──────────────────────────
            if (isOwner) {
                if (genFailed) {
                    Text(
                        "上次生成未完成，請檢查網路後再試一次",
                        fontSize = 13.sp, color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Button(
                    onClick = { showPlanningSheet = true },
                    enabled = !visualState.isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GsIndigo)
                ) {
                    Text("🚀 開始規劃行程內容", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    "等待擁有者開始規劃行程…",
                    fontSize = 13.sp, color = GsInk2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Text(
                "先邀請朋友加入、各自填好偏好，再開始規劃。",
                fontSize = 12.sp, color = GsInk2,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }

        // ── 生成中覆蓋層（此頁在全螢幕分支，看不到 MainActivity 的 loading）──
        if (visualState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = GsIndigo)
                        Text(visualState.loadingPhase, fontSize = 13.sp, color = GsInk)
                    }
                }
            }
        }
    }

    // 規劃 BottomSheet：帶入 collabViewModel 進入群組模式（偏好聯集＋多數決）
    if (showPlanningSheet) {
        PlanningBottomSheet(
            viewModel = viewModel,
            onDismiss = { showPlanningSheet = false },
            collabViewModel = collabViewModel
        )
    }
}

// ── 行程資訊卡 ──────────────────────────────────────────────
@Composable
private fun GsTripInfoCard(memberCount: Int) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("👥", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("未命名共編行程", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GsInk)
                Text("8小時 · $memberCount/10 人", fontSize = 12.sp, color = GsInk2)
            }
        }
    }
}

// ── 邀請卡（邀請碼 + 分享連結）──────────────────────────────
@Composable
private fun GsInviteCard(inviteCode: String, shareLink: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("邀請朋友（上限 10 人）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GsInk)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GsIndigoBg,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = inviteCode.ifBlank { "······" },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            color = GsIndigo,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (inviteCode.isNotBlank())
                            clipboard.setText(AnnotatedString(inviteCode))
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(15.dp), tint = GsIndigo)
                    Spacer(Modifier.width(4.dp))
                    Text("複製碼", fontSize = 13.sp, color = GsIndigo)
                }
            }
            // 分享連結（deep link，App 端點擊直接加入）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GsBg,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = shareLink.ifBlank { "連結生成中…" },
                        fontSize = 12.sp, color = GsInk2, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (shareLink.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareLink)
                            }
                            context.startActivity(Intent.createChooser(intent, "邀請旅伴"))
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(15.dp), tint = GsIndigo)
                    Spacer(Modifier.width(4.dp))
                    Text("分享連結", fontSize = 13.sp, color = GsIndigo)
                }
            }
            com.example.travellink_ai.ui.planning.LineShareButton(
                text = com.example.travellink_ai.ui.planning.buildLineInviteText("", inviteCode, shareLink),
                enabled = inviteCode.isNotBlank()
            )
        }
    }
}

// ── 邀請好友 ────────────────────────────────────────────────
@Composable
private fun GsFriendInviteCard(
    friends: List<FriendBrief>,
    invitedEmails: Set<String>,
    sending: List<String>,
    onInvite: (FriendBrief) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("從好友邀請", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GsInk)
            if (friends.isEmpty()) {
                Text("還沒有好友。到側欄「我的好友」加好友後，就能一鍵邀請。", fontSize = 12.sp, color = GsInk2)
                return@Column
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
            ) {
                friends.forEach { f ->
                    val key = f.email.lowercase()
                    val joined = key in invitedEmails
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(GsIndigoBg),
                            contentAlignment = Alignment.Center
                        ) { Text(f.emoji, fontSize = 18.sp) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GsInk)
                            Text(f.email, fontSize = 11.sp, color = GsInk2)
                        }
                        when {
                            joined -> Text("已加入", fontSize = 12.sp, color = GsIndigo, fontWeight = FontWeight.SemiBold)
                            key in sending -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = GsIndigo
                            )
                            else -> Button(
                                onClick = { onInvite(f) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GsIndigo)
                            ) { Text("邀請", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

// ── 成員卡 ──────────────────────────────────────────────────
@Composable
private fun GsMembersCard(
    members: List<com.example.travellink_ai.data.model.CollabMemberInfo>,
    myUid: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "成員（${members.size}）",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GsInk
            )
            if (members.isEmpty()) {
                Text("成員載入中…", fontSize = 13.sp, color = GsInk2)
            }
            members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (m.isOnline) Color(0xFF34C759) else Color(0xFFC7C7CC),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (m.uid == myUid) "${m.displayName}（你）" else m.displayName,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GsInk,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (m.role == "owner") GsIndigoBg else GsGreenBg
                    ) {
                        Text(
                            text = when (m.role) {
                                "owner"  -> "擁有者"
                                "editor" -> "可編輯"
                                else     -> "唯讀"
                            },
                            fontSize = 11.sp,
                            color = if (m.role == "owner") GsIndigo else GsGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── 我的偏好卡 ──────────────────────────────────────────────
@Composable
private fun GsMyPrefsCard(
    selectedInterests: MutableList<String>,
    selectedPace: String,
    onPaceChange: (String) -> Unit,
    selectedBudget: String,
    onBudgetChange: (String) -> Unit,
    desiredSpots: String,
    onSpotsChange: (String) -> Unit,
    prefsSaved: Boolean,
    onSave: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("我的偏好（會納入團體生成）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GsInk)

            Text("興趣（最多 3）", fontSize = 13.sp, color = GsInk2)
            GsChipFlow(
                options = gsInterestOptions.map { "${it.second} ${it.first}" },
                isSelected = { label -> gsInterestOptions.any { "${it.second} ${it.first}" == label && it.first in selectedInterests } },
                onToggle = { label ->
                    val key = gsInterestOptions.first { "${it.second} ${it.first}" == label }.first
                    if (key in selectedInterests) selectedInterests.remove(key)
                    else if (selectedInterests.size < 3) selectedInterests.add(key)
                }
            )

            Text("節奏", fontSize = 13.sp, color = GsInk2)
            // 三等分格，避免長文字 chip 在窄螢幕溢出
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gsPaceOptions.forEach { (value, label) ->
                    val selected = selectedPace == value
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) GsIndigoBg else GsBg,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPaceChange(value) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) GsIndigo else GsInk,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }

            Text("每人預算（當地餐飲與付費體驗）", fontSize = 13.sp, color = GsInk2)
            gsBudgetOptions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (value, display) ->
                        val (label, sub) = display
                        val selected = selectedBudget == value
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) GsIndigoBg else GsBg,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onBudgetChange(if (selected) "" else value) }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (selected) GsIndigo else GsInk)
                                Text(sub, fontSize = 11.sp,
                                    color = if (selected) GsIndigo else GsInk2)
                            }
                        }
                    }
                }
            }

            Text("想去的景點（選填）", fontSize = 13.sp, color = GsInk2)
            OutlinedTextField(
                value = desiredSpots,
                onValueChange = onSpotsChange,
                placeholder = { Text("例如 三仙台、伯朗大道", fontSize = 13.sp, color = GsInk2) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GsIndigo,
                    unfocusedBorderColor = Color(0xFFD0D0D0)
                )
            )

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (prefsSaved) GsGreen else GsIndigoBg,
                    contentColor   = if (prefsSaved) Color.White else GsIndigo
                )
            ) {
                Text(
                    if (prefsSaved) "✓ 已儲存我的偏好" else "儲存我的偏好",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── 團體綜合卡 ──────────────────────────────────────────────
@Composable
private fun GsGroupSummaryCard(groupProfile: CollabViewModel.GroupProfile) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("團體綜合（即時）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GsInk)
            Text("節奏：${groupProfile.pace}（多數決）", fontSize = 13.sp, color = GsInk2)
            Text(
                "興趣：${if (groupProfile.interests.isEmpty()) "—"
                        else groupProfile.interests.joinToString("、")}",
                fontSize = 13.sp, color = GsInk2
            )
            Text(
                "預算：${groupProfile.budget.ifBlank { "—" }}（多數決）",
                fontSize = 13.sp, color = GsInk2
            )
        }
    }
}

// ── 簡易 chip 列（兩行內的小集合，不需要 FlowRow 依賴）────────
@Composable
private fun GsChipFlow(
    options: List<String>,
    isSelected: (String) -> Boolean,
    onToggle: (String) -> Unit
) {
    // 一排 3 個，配合手機寬度（360dp 級距）
    options.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { label ->
                FilterChip(
                    selected = isSelected(label),
                    onClick = { onToggle(label) },
                    label = { Text(label, fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GsIndigoBg,
                        selectedLabelColor = GsIndigo
                    )
                )
            }
        }
    }
}
