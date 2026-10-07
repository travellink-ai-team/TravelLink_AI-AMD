package com.example.travellink_ai.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.util.isStartingSoon
import com.example.travellink_ai.util.startOfToday
import com.example.travellink_ai.util.tripStartMs
import com.example.travellink_ai.ui.collab.CollabViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border
private val Danger      = DesignTokens.Red         // 對齊網頁 --red #D94040
private val Gold        = DesignTokens.Gold         // 對齊網頁 --gold #C9A227

/** 清單的兩種列：分區標題與行程卡。攤平成單一 list 才能共用同一份卡片程式碼與穩定 key */
private sealed interface HistoryRow {
    /** soon＝區內有今天／明天出發的行程（標題加紅點，收起時也看得到） */
    data class Header(val title: String, val count: Int, val soon: Boolean = false) : HistoryRow
    data class Trip(val item: LocalItinerary) : HistoryRow
}

/** 排序方式（兩端同一組：旅遊日期為 App 預設，其餘對齊網頁 mtSort）。 */
private enum class SortMode(val label: String) {
    TRIP_DATE("旅遊日期"),          // 依旅遊日分「即將出發／已結束」兩區（預設）
    CREATED("建立時間"),            // 再點一次切換 新→舊／舊→新
    STATUS("狀態"),                 // 進行中 → 規劃中 → 已完成
    COLLAB("共編優先")
}

/**
 * 行程狀態徽章，對齊網頁 MT_STATUS_LABEL 與 .mt-status 配色。
 * 依據是兩端共用的 micro_trips.status（按「開始行程／結束行程」時寫入），不看旅遊日期——
 * 否則同一趟在網頁顯示「規劃中」、App 顯示「即將出發」，切換時會對不起來。
 */
internal enum class TripStatus(val label: String, val order: Int, val bg: Color, val fg: Color) {
    ONGOING("⚡ 進行中", 0, DesignTokens.StatusOngoingBg, DesignTokens.StatusOngoingText),
    PLANNING("✏️ 規劃中", 1, DesignTokens.AccentLight, DesignTokens.Accent),
    NOT_GENERATED("⏳ 尚未產生景點", 1, DesignTokens.AccentLight, DesignTokens.Accent),
    COMPLETED("🎉 已完成", 3, DesignTokens.StatusDoneBg, DesignTokens.StatusDoneText);

    /** 搜尋用純文字（去 emoji），讓「進行中」「已完成」也能當關鍵字 */
    val keyword: String get() = label.substringAfter(' ')
}

internal fun statusOf(raw: String, item: LocalItinerary, isCollab: Boolean): TripStatus = when (raw) {
    "ongoing"   -> TripStatus.ONGOING
    "completed" -> TripStatus.COMPLETED
    else -> if (!isCollab && item.stops.none { !it.isStation }) TripStatus.NOT_GENERATED else TripStatus.PLANNING
}

/** 本機進度快取（TripProgressViewModel 的 SharedPreferences）；雲端還沒載入或純本地行程時用 */
private fun localProgressStatus(prefs: android.content.SharedPreferences, item: LocalItinerary): String {
    val key = item.firestoreDocId?.takeIf(String::isNotBlank) ?: "local_${item.id}"
    return try {
        prefs.getString(key, null)?.let { org.json.JSONObject(it).optString("status") }.orEmpty()
    } catch (e: Exception) { "" }
}

/**
 * 搜尋：空白分隔的每個關鍵字都要命中（對齊網頁 filterMyTripsList，如「台東 6/10」）。
 * 日期同時放補零與不補零兩種寫法，「6/10」與「06/10」都搜得到。
 */
internal fun matchesQuery(item: LocalItinerary, status: TripStatus, isCollab: Boolean, tokens: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val dates = buildList {
        tripStartMs(item)?.let { add(it) }
        add(item.createdAt)
    }.flatMap { ms ->
        listOf("yyyy/MM/dd", "yyyy/M/d", "M/d").map { SimpleDateFormat(it, Locale.TAIWAN).format(Date(ms)) }
    }
    val hay = listOf(
        item.title, item.aiTitle, item.region, item.days, item.people, status.keyword,
        if (isCollab) "共編 多人" else ""
    ).plus(dates).joinToString(" ").lowercase()
    return tokens.all { hay.contains(it) }
}

/** 改名撞版本時暫存：要寫的行程、我打的名字、雲端目前狀態 */
private data class PendingRenameConflict(
    val item: LocalItinerary,
    val myName: String,
    val conflict: CollabViewModel.RenameResult.Conflict
)

/** 卡片與選取狀態的穩定識別：雲端項用 docId，純本地項用 id + createdAt */
private fun LocalItinerary.rowKey(): String =
    firestoreDocId?.takeIf(String::isNotBlank) ?: "local_${id}_$createdAt"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ItineraryHistoryScreen(
    viewModel: ItineraryViewModel,
    onBack: () -> Unit,
    currentUserName: String = "旅人",
    onMenuClick: (() -> Unit)? = null
) {
    val collabVm: CollabViewModel = hiltViewModel()
    val remoteMeta      by collabVm.remoteTripMeta.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val progressPrefs = remember { context.getSharedPreferences("trip_progress", android.content.Context.MODE_PRIVATE) }

    val localHistory    by viewModel.historyList.collectAsState()
    val remoteHistory   by collabVm.remoteHistory.collectAsState()
    val isLoadingRemote by collabVm.isLoadingRemoteHistory.collectAsState()
    val collabDocIds    by collabVm.collabDocIds.collectAsState()

    LaunchedEffect(Unit) {
        collabVm.loadHistoryFromFirebase()
    }

    val fullDateFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val tripDateFmt = remember { SimpleDateFormat("yyyy/MM/dd (E)", Locale.TAIWAN) }

    // 合併去重：本地優先，Firebase 補充兩週前的舊資料
    // 以 firestoreDocId 為主鍵去重——加入的共編行程，本地存「加入時間」而雲端存「建立時間」，
    // createdAt 常差超過 10 秒，舊的時間戳近似比對會漏判成重複兩張卡。雲端項一律帶 docId（doc.id）。
    val combinedHistory = remember(localHistory, remoteHistory) {
        val localDocIds = localHistory.mapNotNull { it.firestoreDocId?.takeIf(String::isNotBlank) }.toSet()
        val localTimestamps = localHistory.map { it.createdAt }
        val remoteOnly = remoteHistory.filter { remote ->
            val docId = remote.firestoreDocId
            if (!docId.isNullOrBlank()) {
                docId !in localDocIds
            } else {
                // 無 docId 才退回時間戳近似比對（理論上雲端項都有 docId，此為防禦）
                localTimestamps.none { localTs -> abs(localTs - remote.createdAt) < 10_000 }
            }
        }
        localHistory + remoteOnly
    }

    // ── 依旅遊日期分區（取代舊的「依建立時間降冪」單一排序）──────────────
    // 這是行程規劃 App，「什麼時候要去」比「什麼時候建的」更貼近使用者心裡的軸；
    // 但純降冪會把最遙遠的未來行程頂到最上面，所以拆成兩區各自排：
    //   即將出發：旅遊日 >= 今天，最近的在前（升冪）
    //   已結束　：旅遊日 < 今天，最近的在前（降冪）；解析不出旅遊日期的併入此區末尾
    val todayStartMs = remember { startOfToday() }
    val (upcoming, past) = remember(combinedHistory, todayStartMs) {
        val withDate = combinedHistory.mapNotNull { item -> tripStartMs(item)?.let { it to item } }
        val noDate   = combinedHistory.filter { tripStartMs(it) == null }
        val up  = withDate.filter { it.first >= todayStartMs }.sortedBy { it.first }.map { it.second }
        val pst = withDate.filter { it.first < todayStartMs }.sortedByDescending { it.first }.map { it.second } +
            noDate.sortedByDescending { it.createdAt }
        up to pst
    }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.TRIP_DATE) }
    var createdAsc by rememberSaveable { mutableStateOf(false) }   // 建立時間：false＝新→舊
    fun sortLabel(m: SortMode) =
        if (m == SortMode.CREATED) "建立時間（${if (createdAsc) "舊→新" else "新→舊"}）" else m.label
    // 旅遊日期排序下收起的分區（以標題辨識）
    var collapsedSections by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }

    fun isCollabItem(item: LocalItinerary) =
        !item.firestoreDocId.isNullOrBlank() && item.firestoreDocId in collabDocIds
    // 雲端 status 優先（別的裝置或網頁開始的行程也看得到），沒有才用本機進度快取
    val statusByKey = remember(combinedHistory, remoteMeta, collabDocIds) {
        combinedHistory.associate { item ->
            val raw = item.firestoreDocId?.let { remoteMeta[it]?.status }?.takeIf(String::isNotBlank)
                ?: localProgressStatus(progressPrefs, item)
            item.rowKey() to statusOf(raw, item, isCollabItem(item))
        }
    }
    fun statusFor(item: LocalItinerary) = statusByKey[item.rowKey()] ?: TripStatus.PLANNING

    val rows = remember(upcoming, past, combinedHistory, sortMode, createdAsc, collapsedSections,
                        query, statusByKey, collabDocIds) {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val keep: (LocalItinerary) -> Boolean = { matchesQuery(it, statusFor(it), isCollabItem(it), tokens) }
        // 旅遊日期排序時進行中置頂，讓人一眼回到正在跑的行程
        val ongoing = combinedHistory.filter { statusFor(it) == TripStatus.ONGOING && keep(it) }
            .sortedByDescending { it.createdAt }
        val ongoingKeys = ongoing.map { it.rowKey() }.toSet()
        fun rest(list: List<LocalItinerary>) = list.filter { it.rowKey() !in ongoingKeys && keep(it) }
        when (sortMode) {
            SortMode.TRIP_DATE -> buildList<HistoryRow> {
                // 收起的分區只留標題（含筆數），卡片不放進清單
                fun section(title: String, list: List<LocalItinerary>) {
                    if (list.isEmpty()) return
                    add(HistoryRow.Header(title, list.size,
                        soon = list.any { isStartingSoon(it, todayStartMs) }))
                    if (title !in collapsedSections) list.forEach { add(HistoryRow.Trip(it)) }
                }
                section("進行中", ongoing)
                section("即將出發", rest(upcoming))
                section("已結束", rest(past))
            }
            // 其餘排序完全照所選方式，進行中不另外置頂（使用者 2026-09-24 決定）
            SortMode.CREATED -> combinedHistory.filter(keep)
                .let { l -> if (createdAsc) l.sortedBy { it.createdAt } else l.sortedByDescending { it.createdAt } }
                .map { HistoryRow.Trip(it) }
            // sortedBy 是穩定排序：同狀態內維持建立時間新→舊
            SortMode.STATUS -> combinedHistory.filter(keep).sortedByDescending { it.createdAt }
                .sortedBy { statusFor(it).order }
                .map { HistoryRow.Trip(it) }
            SortMode.COLLAB -> combinedHistory.filter(keep).sortedByDescending { it.createdAt }
                .sortedBy { if (isCollabItem(it)) 0 else 1 }
                .map { HistoryRow.Trip(it) }
        }
    }

    // ── 改名（對齊網頁 renameMyTrip：40 字上限、共編撞版本時讓使用者選）──────
    var renameTarget by remember { mutableStateOf<LocalItinerary?>(null) }
    var renameConflict by remember { mutableStateOf<PendingRenameConflict?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameSaving by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 共編唯讀成員不能改名；雲端權限還沒載入時，非共編行程視為自己的
    fun canRename(item: LocalItinerary): Boolean {
        val docId = item.firestoreDocId?.takeIf(String::isNotBlank) ?: return true
        return remoteMeta[docId]?.canEdit ?: !isCollabItem(item)
    }

    fun submitRename(item: LocalItinerary, name: String, force: Boolean = false, expectedVersion: Long? = null) {
        renameSaving = true
        collabVm.renameTrip(item, name, currentUserName, force, expectedVersion) { result ->
            renameSaving = false
            when (result) {
                CollabViewModel.RenameResult.Ok -> {
                    renameTarget = null; renameConflict = null; renameError = null
                    scope.launch { snackbarHostState.showSnackbar("已更新行程名稱") }
                }
                is CollabViewModel.RenameResult.Conflict -> {
                    renameTarget = null; renameError = null
                    renameConflict = PendingRenameConflict(item, name, result)
                }
                is CollabViewModel.RenameResult.Failed -> {
                    renameConflict = null
                    renameError = "名稱同步失敗，請稍後重試"
                    if (renameTarget == null) scope.launch { snackbarHostState.showSnackbar("名稱同步失敗，請稍後重試") }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameTripDialog(
            initial = target.title.ifBlank { target.aiTitle },
            saving = renameSaving,
            error = renameError,
            onDismiss = { renameTarget = null; renameError = null },
            onSave = { submitRename(target, it) }
        )
    }
    renameConflict?.let { pending ->
        AlertDialog(
            onDismissRequest = { renameConflict = null },
            title = { Text("行程名稱已更新", fontWeight = FontWeight.Bold) },
            text = {
                Text("${pending.conflict.byName} 剛剛修改了名稱。\n\n目前版本：${pending.conflict.remoteTitle}\n你的名稱：${pending.myName}")
            },
            confirmButton = {
                TextButton(enabled = !renameSaving, onClick = {
                    submitRename(pending.item, pending.myName, force = true, expectedVersion = pending.conflict.remoteVersion)
                }) { Text("保留我的修改", color = Accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameConflict = null
                    collabVm.loadHistoryFromFirebase()   // 拉回最新名稱與版本
                }) { Text("使用最新版本") }
            }
        )
    }

    // ── 多選刪除狀態 ────────────────────────────────────────────
    var isSelectMode by remember { mutableStateOf(false) }
    // 用 createdAt 作為唯一鍵，因為 Firebase-only 項目的 id 都是 -1L
    var selectedKeys by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 進入選取模式
    fun enterSelectMode(item: LocalItinerary) {
        isSelectMode = true
        selectedKeys = setOf(item.createdAt)
    }

    // 離開選取模式並清除選取
    fun exitSelectMode() {
        isSelectMode = false
        selectedKeys = emptySet()
    }

    fun toggleSelection(item: LocalItinerary) {
        selectedKeys = if (item.createdAt in selectedKeys) {
            selectedKeys - item.createdAt
        } else {
            selectedKeys + item.createdAt
        }
    }

    val selectedItems = combinedHistory.filter { it.createdAt in selectedKeys }

    // ── 刪除確認 Dialog ────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("刪除行程", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (selectedItems.size == 1)
                        "確定要刪除「${selectedItems.first().title.ifBlank { selectedItems.first().aiTitle }}」嗎？\n\n此操作無法復原。"
                    else
                        "確定要刪除選取的 ${selectedItems.size} 筆行程嗎？\n\n此操作無法復原。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteItineraries(selectedItems)
                        exitSelectMode()
                    }
                ) {
                    Text("刪除", color = Danger, fontWeight = FontWeight.Bold)
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
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectMode) {
                        Text(
                            if (selectedKeys.isEmpty()) "請選取行程"
                            else "已選取 ${selectedKeys.size} 筆",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Ink
                        )
                    } else {
                        Column {
                            Text(
                                "我的微旅行",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Ink
                            )
                            Text(
                                "${combinedHistory.size} 筆紀錄",
                                fontSize = 14.sp,
                                color = Ink2
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (isSelectMode) {
                        IconButton(onClick = { exitSelectMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消選取", tint = Ink)
                        }
                    } else if (onMenuClick != null) {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Default.Menu, contentDescription = "選單", tint = Ink)
                        }
                    }
                },
                actions = {
                    if (isSelectMode) {
                        // 全選／取消全選
                        // 只選畫面上看得到的（搜尋篩掉的不選），避免誤刪看不到的行程
                        val visibleKeys = rows.filterIsInstance<HistoryRow.Trip>().map { it.item.createdAt }.toSet()
                        val allVisibleSelected = visibleKeys.isNotEmpty() && selectedKeys.containsAll(visibleKeys)
                        TextButton(
                            onClick = {
                                selectedKeys = if (allVisibleSelected) emptySet() else visibleKeys
                            }
                        ) {
                            Text(
                                if (allVisibleSelected) "取消全選" else "全選",
                                color = Accent,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                        // 刪除按鈕（有選取才啟用）
                        IconButton(
                            onClick = { if (selectedKeys.isNotEmpty()) showDeleteDialog = true },
                            enabled = selectedKeys.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "刪除選取",
                                tint = if (selectedKeys.isNotEmpty()) Danger else Ink2
                            )
                        }
                    } else {
                        // 選取模式切換按鈕
                        TextButton(onClick = { isSelectMode = true }) {
                            Text(
                                "選取",
                                color = Accent,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                        // 手動重新整理按鈕
                        IconButton(
                            onClick = { collabVm.loadHistoryFromFirebase() },
                            enabled = !isLoadingRemote
                        ) {
                            if (isLoadingRemote) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Accent
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "重新載入",
                                    tint = Accent
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Surface2
    ) { padding ->
        if (combinedHistory.isEmpty() && !isLoadingRemote) {
            // 空狀態
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📋", fontSize = 50.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("尚無歷史紀錄", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("完成第一趟行程後會顯示在這裡", fontSize = 15.sp, color = Ink2)
                }
            }
        } else {
          Column(Modifier.fillMaxSize().padding(padding)) {
            if (!isSelectMode) {
                TripSearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 載入中提示列
                if (isLoadingRemote) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentLight)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Accent
                            )
                            Text(
                                "正在從雲端載入歷史行程…",
                                fontSize = 14.sp,
                                color = AccentDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // key 必須穩定：雲端資料是非同步補進來的，清單會在畫面已經畫出來之後重排。
                // 沒有 key 時 LazyColumn 只能用索引辨識項目，重排的瞬間同一個位置會靜默換成
                // 排序下拉（選取模式時隱藏，避免與多選操作混淆）
                if (!isSelectMode) {
                    item(key = "sort_bar") {
                        var expanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("排序", fontSize = 13.sp, color = Ink2)
                            Spacer(Modifier.width(6.dp))
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .clickable { expanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(sortLabel(sortMode), fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold, color = Accent)
                                    Icon(Icons.Default.ArrowDropDown, null, tint = Accent)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    SortMode.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(sortLabel(m), fontWeight = if (m == sortMode) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = {
                                                // 已是建立時間再點一次＝反向；從別的排序切過來一律從新→舊開始
                                                if (m == SortMode.CREATED) createdAsc = sortMode == SortMode.CREATED && !createdAsc
                                                sortMode = m; expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 另一筆行程，使用者就會點到不是自己按的那一筆。
                items(
                    rows,
                    key = { row ->
                        when (row) {
                            is HistoryRow.Header -> "header_${row.title}"
                            is HistoryRow.Trip   -> row.item.rowKey()
                        }
                    }
                ) { row ->
                    if (row is HistoryRow.Header) {
                        val collapsed = row.title in collapsedSections
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    collapsedSections = if (collapsed) collapsedSections - row.title
                                                        else collapsedSections + row.title
                                }
                                .padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${row.title}　${row.count}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink2
                            )
                            if (row.soon) SoonDot(Modifier.padding(start = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = if (collapsed) "展開" else "收起",
                                tint = Ink2,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        return@items
                    }
                    val item = (row as HistoryRow.Trip).item
                    val isCloudOnly = item.id == -1L
                    val isSelected = item.createdAt in selectedKeys
                    // 共編＝Firestore 實際成員 > 1（collabDocIds），非同行人數/joinPin
                    val isCollab = !item.firestoreDocId.isNullOrBlank() &&
                        item.firestoreDocId in collabDocIds
                    // 排序依旅遊日期，顯示就必須跟著是旅遊日期，否則看起來像排序壞掉。
                    // 解析不出旅遊日期的才退回建立時間，並標註以免誤讀。
                    val tripMs = tripStartMs(item)
                    HistoryCard(
                        item = item,
                        dateStr = if (tripMs != null) tripDateFmt.format(Date(tripMs))
                                  else "建立於 ${fullDateFmt.format(Date(item.createdAt))}",
                        isCloudOnly = isCloudOnly,
                        isCollab = isCollab,
                        isSelectMode = isSelectMode,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelectMode) {
                                toggleSelection(item)
                            } else {
                                val docId = item.firestoreDocId
                                if (!docId.isNullOrBlank()) {
                                    // 共同編輯行程：透過 openCollabTrip 建立 Firestore 即時監聽
                                    collabVm.openCollabTrip(docId)
                                } else {
                                    viewModel.loadFromHistory(item)
                                }
                            }
                        },
                        onLongClick = {
                            if (!isSelectMode) enterSelectMode(item)
                        },
                        onOpenMemory = { viewModel.openMemory(item) },
                        status = statusFor(item),
                        isSoon = isStartingSoon(item, todayStartMs),
                        onRename = if (canRename(item)) ({ renameError = null; renameTarget = item }) else null
                    )
                }

                if (combinedHistory.isNotEmpty() && rows.none { it is HistoryRow.Trip }) {
                    item(key = "empty_search") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🔍", fontSize = 40.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("找不到符合的行程，換個關鍵字試試", fontSize = 15.sp, color = Ink2)
                        }
                    }
                }
            }
          }
        }
    }
}

/** 搜尋框（對齊網頁 .mt-search：圓角 12、1.5dp 邊框、聚焦變 accent） */
@Composable
private fun TripSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("搜尋關鍵字（地區、日期、名稱…可空格組合，如「台東 6/10」）", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = DesignTokens.Ink3) },
        trailingIcon = if (value.isNotEmpty()) {
            { IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Close, contentDescription = "清除搜尋", tint = Ink2) } }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Border,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
        )
    )
}

/** 改名對話框（對齊網頁「修改行程名稱」：40 字、字數計數、儲存會同步給共編成員） */
@Composable
private fun RenameTripDialog(
    initial: String,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial.take(40)) }
    val trimmed = text.trim()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("修改行程名稱", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("名稱最多 40 個字，儲存後會同步給共編成員。", fontSize = 14.sp, color = Ink2)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Border)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("請使用容易辨識的名稱", fontSize = 13.sp, color = DesignTokens.Ink3)
                    Text("${text.length} / 40", fontSize = 13.sp, color = DesignTokens.Ink3)
                }
                if (error != null) Text(error, fontSize = 13.sp, color = Danger)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && trimmed.isNotEmpty() && trimmed != initial.trim(),
                onClick = { onSave(trimmed) }
            ) { Text(if (saving) "儲存中…" else "儲存", color = Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    item: LocalItinerary,
    dateStr: String,
    isCloudOnly: Boolean,
    isCollab: Boolean = false,
    isSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenMemory: () -> Unit = {},
    status: TripStatus = TripStatus.PLANNING,
    isSoon: Boolean = false,
    onRename: (() -> Unit)? = null
) {
    val borderColor = if (isSelected) Accent else Color.Transparent
    val cardElevation = if (isSelected) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AccentLight else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
      Column {
        // 卡片主內容：只有這個 Row 有 combinedClickable（點擊＝開行程、長按＝多選）。
        // 「旅遊回憶」按鈕刻意放在此 Row 之外，否則巢狀在 combinedClickable 下的
        // TextButton 點擊會被卡片手勢吃掉（W4 實測：點按鈕會誤開行程預覽）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            // 左側色條
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(
                        Brush.verticalGradient(listOf(Accent, Color(0xFF4CAF93)))
                    )
            )
            Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 地區標籤
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else AccentLight
                        ) {
                            Text(
                                text = item.region.ifBlank { "台東" },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 14.sp,
                                color = AccentDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // ☁️ 雲端標記
                        if (isCloudOnly) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFE3F2FD)
                            ) {
                                Text(
                                    text = "☁️ 雲端",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 14.sp,
                                    color = Color(0xFF1565C0),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // 👥 共編標記：由 isCollab（Firestore 實際成員數 > 1）決定。
                        // 舊邏輯用同行人數(people>1) 會把「個人規劃但 2 人同行」誤標；joinPin 也不準
                        // （個人行程在結果頁也會被設 PIN）。只有真的有其他成員才算共編。
                        if (isCollab) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFEDE7F6)
                            ) {
                                Text(
                                    text = "👥 共編",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 14.sp,
                                    color = Color(0xFF512DA8),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 今天／明天出發：日期前加紅點
                        if (isSoon) SoonDot()
                        Text(
                            text = dateStr,
                            fontSize = 14.sp,
                            color = Ink2
                        )
                        // 選取模式下顯示勾選圖示
                        if (isSelectMode) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "已選取",
                                    tint = Accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(1.5.dp, Border, CircleShape)
                                        .background(Color.White, CircleShape)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 狀態徽章（對齊網頁 .mt-status：999 圓角膠囊）
                Text(
                    text = status.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(DesignTokens.Radius.Pill))
                        .background(status.bg)
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = status.fg
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // title 含使用者自訂命名（優先顯示），aiTitle 是 AI 副標
                    text = item.title.ifBlank { item.aiTitle },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryChip("📅 ${item.days.take(20)}")
                    HistoryChip("👥 ${item.people}")
                }
                if (item.stops.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Border.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.stops.take(3).joinToString(" → ") { it.emoji + it.name },
                        fontSize = 14.sp,
                        color = Ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 已填寫回饋：顯示整體評分星星
                if (item.feedbackSubmitted && item.overallRating > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        (1..5).forEach { star ->
                            Icon(
                                imageVector = if (star <= item.overallRating)
                                    Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (star <= item.overallRating) Gold else Border,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "已評分",
                            fontSize = 14.sp,
                            color = Ink2
                        )
                    }
                }

            }
        }
        // 📸 旅遊回憶入口（W4 C6）：放在卡片點擊區（上方 Row）之外，
        // 自身的 TextButton clickable 才能正常攔截點擊。非選取模式才顯示。
        if (!isSelectMode) {
            Row(
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextButton(
                    onClick = onOpenMemory,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text("📸 旅程回憶", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (onRename != null) {
                    TextButton(
                        onClick = onRename,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("📝 改名", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun HistoryChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Surface2
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 14.sp,
            color = Ink2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 「即將開始」紅點（今天／明天出發） */
@Composable
private fun SoonDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(Color(0xFFD32F2F)))
}
