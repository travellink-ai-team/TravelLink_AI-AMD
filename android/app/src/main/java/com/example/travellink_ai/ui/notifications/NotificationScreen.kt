package com.example.travellink_ai.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.FriendNotification
import com.example.travellink_ai.data.model.UserProfile
import com.example.travellink_ai.ui.friends.FriendViewModel
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.util.SystemNotificationLog
import com.example.travellink_ai.util.SystemNotificationLog.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知分類。站內通知（雲端，好友／共編）與手機系統通知的紀錄（本機，[SystemNotificationLog]）
 * 放在同一頁，依分類分區，每區可展開／收起。
 */
private enum class NotifCategory(val label: String, val emoji: String) {
    SOCIAL("好友與共編", "👥"),
    UPCOMING("行程提醒", "🧳"),     // 今天／明天出發
    ONGOING("行程進行中", "🚩"),    // 離開時間到了、到站、離開沒打卡
    FEEDBACK("行程回饋", "📝")
}

private fun Kind.category(): NotifCategory = when (this) {
    Kind.UPCOMING -> NotifCategory.UPCOMING
    Kind.LEAVE_TIME, Kind.NEARBY_STOP, Kind.LEFT_NO_CHECKIN, Kind.AGENT -> NotifCategory.ONGOING
    Kind.FEEDBACK -> NotifCategory.FEEDBACK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    profile: UserProfile,
    pendingFeedbacks: List<LocalItinerary>,
    onFeedbackClick: (LocalItinerary) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenTrip: (tripId: String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: FriendViewModel = hiltViewModel(),
    feedbackViewModel: com.example.travellink_ai.ui.feedback.FeedbackViewModel = hiltViewModel()
) {
    LaunchedEffect(profile.email) { viewModel.bind(profile) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { SystemNotificationLog.load(context) }

    val notifications by viewModel.notifications.collectAsState()
    val readFeedbackIds by feedbackViewModel.readFeedbackIds.collectAsState()
    val sysEntries by SystemNotificationLog.entries.collectAsState()

    // null＝全部
    var filter by rememberSaveable { mutableStateOf<NotifCategory?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    // 收起的分區（預設全部展開）
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }

    // 回饋類系統通知：同一趟還在「待填回饋」就不重複列（待填那筆可以直接填）
    fun isPending(e: SystemNotificationLog.Entry) = pendingFeedbacks.any {
        (e.localId >= 0 && it.id.toLong() == e.localId) ||
            (!e.docId.isNullOrBlank() && it.firestoreDocId == e.docId)
    }
    val sysVisible = sysEntries.filterNot { it.kind == Kind.FEEDBACK && isPending(it) }

    fun unreadIn(c: NotifCategory): Int = when (c) {
        NotifCategory.SOCIAL -> notifications.count { !it.read }
        NotifCategory.FEEDBACK -> pendingFeedbacks.count { it.id !in readFeedbackIds } +
            sysVisible.count { it.kind.category() == c && !it.read }
        else -> sysVisible.count { it.kind.category() == c && !it.read }
    }
    fun sizeOf(c: NotifCategory): Int = when (c) {
        NotifCategory.SOCIAL -> notifications.size
        NotifCategory.FEEDBACK -> pendingFeedbacks.size + sysVisible.count { it.kind.category() == c }
        else -> sysVisible.count { it.kind.category() == c }
    }

    val shown = NotifCategory.values().filter { (filter == null || it == filter) && sizeOf(it) > 0 }
    val allCollapsed = shown.isNotEmpty() && shown.all { it.name in collapsed }

    fun openSys(e: SystemNotificationLog.Entry) {
        SystemNotificationLog.markRead(context, e.id)
        when (e.kind) {
            Kind.FEEDBACK -> pendingFeedbacks.firstOrNull {
                it.id.toLong() == e.localId || (!e.docId.isNullOrBlank() && it.firestoreDocId == e.docId)
            }?.let(onFeedbackClick)
            else -> e.docId?.takeIf { it.isNotBlank() }?.let(onOpenTrip)
        }
    }

    Scaffold(
        containerColor = DesignTokens.Bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("通知", fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首頁", tint = DesignTokens.Ink)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DesignTokens.Surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 分類選單 + 全部展開／收起 + 全部已讀
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        onClick = { filterOpen = true },
                        shape = RoundedCornerShape(999.dp),
                        color = DesignTokens.Surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(filter?.label ?: "全部", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                            Icon(Icons.Default.ArrowDropDown, null, tint = DesignTokens.Ink2)
                        }
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        val total = NotifCategory.values().sumOf { unreadIn(it) }
                        DropdownMenuItem(
                            text = { MenuLabel("全部", total, filter == null) },
                            onClick = { filter = null; filterOpen = false }
                        )
                        NotifCategory.values().forEach { c ->
                            DropdownMenuItem(
                                text = { MenuLabel("${c.emoji} ${c.label}", unreadIn(c), c == filter) },
                                onClick = { filter = c; filterOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (shown.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            collapsed = if (allCollapsed) collapsed - shown.map { it.name }.toSet()
                                        else collapsed + shown.map { it.name }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(if (allCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess, null,
                            modifier = Modifier.size(18.dp), tint = DesignTokens.Ink2)
                        Spacer(Modifier.width(2.dp))
                        Text(if (allCollapsed) "全部展開" else "全部收起", color = DesignTokens.Ink2, fontSize = 13.sp)
                    }
                }
                TextButton(
                    onClick = {
                        viewModel.markAllRead()
                        feedbackViewModel.markAllFeedbackRead()
                        SystemNotificationLog.markAllRead(context)
                    },
                    enabled = NotifCategory.values().any { unreadIn(it) > 0 },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(18.dp), tint = DesignTokens.Accent)
                    Spacer(Modifier.width(2.dp))
                    Text("全部已讀", color = DesignTokens.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.5f))

            if (shown.isEmpty()) {
                EmptyNotifications(filter)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    shown.forEach { c ->
                        val open = c.name !in collapsed
                        item(key = "h_${c.name}") {
                            SectionHeader(c, sizeOf(c), unreadIn(c), open) {
                                collapsed = if (open) collapsed + c.name else collapsed - c.name
                            }
                        }
                        if (!open) return@forEach
                        when (c) {
                            NotifCategory.SOCIAL -> items(notifications, key = { "n_${it.id}" }) { n ->
                                FriendNotifRow(n) {
                                    viewModel.markRead(n.id)
                                    when {
                                        n.type == "friend_invite" -> onOpenFriends()
                                        n.type == "collab_invite" && n.tripId.isNotBlank() -> onOpenTrip(n.tripId)
                                    }
                                }
                            }
                            NotifCategory.FEEDBACK -> {
                                items(pendingFeedbacks, key = { "f_${it.createdAt}" }) { item ->
                                    FeedbackNotifRow(item, read = item.id in readFeedbackIds) { onFeedbackClick(item) }
                                }
                                items(sysVisible.filter { it.kind.category() == c }, key = { "s_${it.id}" }) { e ->
                                    SystemNotifRow(e) { openSys(e) }
                                }
                            }
                            else -> items(sysVisible.filter { it.kind.category() == c }, key = { "s_${it.id}" }) { e ->
                                SystemNotifRow(e) { openSys(e) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String, unread: Int, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (selected) DesignTokens.Accent else DesignTokens.Ink,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadPill(unread)
        }
    }
}

@Composable
private fun UnreadPill(n: Int) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFD32F2F))
            .padding(horizontal = 7.dp, vertical = 1.dp)
    ) { Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SectionHeader(c: NotifCategory, size: Int, unread: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${c.emoji} ${c.label}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
        Text("  $size", fontSize = 13.sp, color = DesignTokens.Ink3)
        if (unread > 0) { Spacer(Modifier.width(8.dp)); UnreadPill(unread) }
        Spacer(Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "收起" else "展開", tint = DesignTokens.Ink2)
    }
}

private fun notifDisplay(n: FriendNotification): Pair<String, String> {
    val who = n.fromName.ifBlank { n.fromEmail }
    // 尚未命名的行程 title 可能為空，避免顯示「行程「」」→ 補預設名
    val title = n.tripTitle.ifBlank { "未命名行程" }
    return when (n.type) {
        "friend_invite" -> "✉️" to "$who 邀請你成為好友"
        "friend_accept" -> "🎉" to "$who 接受了你的好友邀請"
        "friend_trip_completed" -> "🏁" to "$who 完成了行程「$title」"
        "trip_renamed" -> "✏️" to "$who 把共編行程改名為「$title」"
        "trip_regenerated" -> "🔄" to "$who 重新生成了行程「$title」"
        "collab_invite" -> "👥" to "$who 邀請你共編「${n.tripTitle.ifBlank { "未命名共編行程" }}」"
        else -> "🔔" to n.message.ifBlank { who }
    }
}

@Composable
private fun FriendNotifRow(n: FriendNotification, onClick: () -> Unit) {
    val (emoji, text) = notifDisplay(n)
    val timeFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val timeStr = n.createdAt?.toDate()?.let { timeFmt.format(it) } ?: ""
    NotifCard(emoji, text, null, timeStr, n.read, onClick)
}

@Composable
private fun SystemNotifRow(e: SystemNotificationLog.Entry, onClick: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    NotifCard(e.kind.emoji, e.title, e.body, timeFmt.format(Date(e.createdAt)), e.read, onClick)
}

@Composable
private fun NotifCard(emoji: String, title: String, body: String?, timeStr: String, read: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (read) DesignTokens.Surface else DesignTokens.AccentLight,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = DesignTokens.Ink, fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold)
                if (!body.isNullOrBlank()) Text(body, fontSize = 13.sp, color = DesignTokens.Ink2)
                if (timeStr.isNotBlank()) Text(timeStr, fontSize = 12.sp, color = DesignTokens.Ink3)
            }
            if (!read) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFD32F2F)))
            }
        }
    }
}

@Composable
private fun FeedbackNotifRow(item: LocalItinerary, read: Boolean, onFeedbackClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (read) DesignTokens.Surface else DesignTokens.AccentLight,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("📝", fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text("旅程回饋待填寫", fontSize = 13.sp, color = DesignTokens.AccentDark, fontWeight = FontWeight.Bold)
                Text(
                    item.title.ifBlank { item.aiTitle },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onFeedbackClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("填寫", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun EmptyNotifications(filter: NotifCategory?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(filter?.emoji ?: "🔔", fontSize = 42.sp)
        Spacer(Modifier.height(12.dp))
        Text(if (filter == null) "目前沒有通知" else "沒有「${filter.label}」的通知",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
        Spacer(Modifier.height(4.dp))
        Text("好友邀請、共編、出發提醒、行程中提醒與回饋都會出現在這裡", fontSize = 14.sp, color = DesignTokens.Ink2)
    }
}
