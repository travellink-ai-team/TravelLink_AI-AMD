package com.example.travellink_ai.ui.friends

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.model.FriendNotification
import com.example.travellink_ai.data.model.Friendship
import com.example.travellink_ai.data.model.UserProfile
import com.example.travellink_ai.ui.theme.DesignTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendListScreen(
    profile: UserProfile,
    onBack: (() -> Unit)?,   // null＝在底部導覽的好友分頁，不顯示返回
    onMenuClick: (() -> Unit)? = null,   // 底部導覽分頁：左上角改開側欄
    viewModel: FriendViewModel = hiltViewModel()
) {
    LaunchedEffect(profile.email) { viewModel.bind(profile) }

    val accepted by viewModel.accepted.collectAsState()
    val incoming by viewModel.incoming.collectAsState()
    val outgoing by viewModel.outgoing.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val toast by viewModel.toast.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var feedCollapsed by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    Scaffold(
        containerColor = DesignTokens.Bg,
        topBar = {
            TopAppBar(
                title = { Text("我的好友", fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = DesignTokens.Ink)
                    } else if (onMenuClick != null) IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "選單", tint = DesignTokens.Ink)
                    }
                },
                actions = {
                    TextButton(onClick = { showAddDialog = true }) {
                        Text("➕ 加好友", color = DesignTokens.Accent, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ⏳ 待確認邀請（放最上面：需要動作的優先看到；收到在前、送出在後）
            val pendingCount = incoming.size + outgoing.size
            if (pendingCount > 0) {
                item { SectionTitle("⏳ 待確認邀請（$pendingCount）") }
                items(incoming, key = { "in_${it.id}" }) { f ->
                    FriendRow(
                        name = viewModel.otherPartyName(f),
                        sub = "${viewModel.otherPartyEmail(f)} · 邀請你成為好友",
                        actions = {
                            MiniButton("✓ 接受", primary = true) { viewModel.acceptInvite(f) }
                            MiniButton("✕ 拒絕", primary = false) { viewModel.removeFriendship(f, "reject") }
                        }
                    )
                }
                items(outgoing, key = { "out_${it.id}" }) { f ->
                    FriendRow(
                        name = viewModel.otherPartyName(f),
                        sub = "${viewModel.otherPartyEmail(f)} · 等待對方確認",
                        actions = { MiniButton("取消邀請", primary = false) { viewModel.removeFriendship(f, "cancel") } }
                    )
                }
            }

            // 📣 好友動態（通知流：好友接受、好友完成行程…）
            val feed = notifications
                .filter { it.type == "friend_accept" || it.type == "friend_trip_completed" }
                .take(10)
            if (feed.isNotEmpty()) {
                item(key = "feed_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { feedCollapsed = !feedCollapsed },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionTitle("📣 好友動態（${feed.size}）")
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (feedCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (feedCollapsed) "展開" else "收起",
                            tint = DesignTokens.Ink2,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (!feedCollapsed) items(feed, key = { "nf_${it.id}" }) { n -> FeedRow(n) }
            }

            // 💛 我的好友
            item { SectionTitle("💛 我的好友（${accepted.size}）") }
            if (accepted.isEmpty()) {
                item { EmptyFriendsHero(onAddFriend = { showAddDialog = true }) }
            } else {
                items(accepted, key = { "fr_${it.id}" }) { f ->
                    FriendRow(
                        name = viewModel.otherPartyName(f),
                        sub = viewModel.otherPartyEmail(f),
                        actions = { MiniButton("解除好友", primary = false, danger = true) { viewModel.removeFriendship(f, "unfriend") } }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddFriendDialog(
            viewModel = viewModel,
            myEmail = profile.email,
            onDismiss = { showAddDialog = false; viewModel.clearSearch() }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = DesignTokens.Ink2,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun FeedRow(n: FriendNotification) {
    val who = n.fromName.ifBlank { n.fromEmail }
    val (emoji, text) = when (n.type) {
        "friend_accept" -> "🎉" to "$who 接受了你的好友邀請"
        "friend_trip_completed" -> "🏁" to "$who 完成了行程「${n.tripTitle}」"
        else -> "🔔" to (n.message.ifBlank { who })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(text, fontSize = 14.sp, color = DesignTokens.Ink2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FriendRow(
    name: String,
    sub: String,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DesignTokens.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                Text(sub, fontSize = 13.sp, color = DesignTokens.Ink3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
private fun MiniButton(
    label: String,
    primary: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (primary) DesignTokens.Accent else DesignTokens.Surface2
    val fg = when {
        primary -> Color.White
        danger -> DesignTokens.Red
        else -> DesignTokens.Ink2
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
        }
    }
}

@Composable
private fun EmptyFriendsHero(onAddFriend: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DesignTokens.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("🧑‍🤝‍🧑", fontSize = 40.sp)
            Text("邀請旅伴，一起規劃共編行程", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("1. 輸入旅伴的 email 送出邀請", fontSize = 13.sp, color = DesignTokens.Ink2)
                Text("2. 對方登入 App 按「接受」", fontSize = 13.sp, color = DesignTokens.Ink2)
                Text("3. 建立共編行程，用邀請碼把好友拉進來", fontSize = 13.sp, color = DesignTokens.Ink2)
            }
            Button(
                onClick = onAddFriend,
                colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
            ) { Text("➕ 加好友", fontWeight = FontWeight.Bold) }
        }
    }
}
