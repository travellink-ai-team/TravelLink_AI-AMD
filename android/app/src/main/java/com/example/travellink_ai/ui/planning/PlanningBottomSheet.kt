package com.example.travellink_ai.ui.planning

import androidx.compose.animation.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.window.DialogWindowProvider
import com.example.travellink_ai.data.island.IslandProfile
import com.example.travellink_ai.data.island.IslandRegistry
import com.example.travellink_ai.ui.collab.CollabViewModel
import java.text.SimpleDateFormat
import java.util.*

// ── 色彩 ──────────────────────────────────────────────────
private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border

// ── 資料 ──────────────────────────────────────────────────
private val popularDestinations = listOf(
    "🏝️" to "台東",
    "🐠" to "綠島",
    "🛶" to "蘭嶼",
    "🌊" to "花蓮",
    "🏙️" to "台北",
    "🌿" to "南投",
    "☀️" to "墾丁",
    "🌆" to "台中",
    "🌃" to "高雄",
    "🌾" to "宜蘭",
)


private val travelStyleOptions = com.example.travellink_ai.data.model.ALL_TRAVEL_PREFERENCES

// 與網頁 BUDGET_TIERS 對齊（人均/日四階）；生成時組成網頁 token 格式
// 「適中（每人 $500–$1,500）」，讓共編預算平均與成本估算兩端都認得同一種值
private val budgetOptions = listOf(
    "🪙 節省" to "每人 \$500 內",
    "💳 適中" to "每人 \$500–\$1,500",
    "💎 舒適" to "每人 \$1,500–\$3,000",
    "👑 豪華" to "每人 \$3,000 以上"
)

/** "HH:mm" + N 小時（上限 23:59，單日行程用） */
private fun clockPlusHours(start: String, hours: Int): String {
    val p = start.split(":")
    val mins = ((p.getOrNull(0)?.toIntOrNull() ?: 9) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) + hours * 60)
        .coerceAtMost(23 * 60 + 59)
    return "%02d:%02d".format(mins / 60, mins % 60)
}

@Composable
private fun Modifier.showKeyboardOnFocus(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    return this.onFocusChanged { focusState ->
        if (focusState.isFocused) {
            keyboardController?.show()
        }
    }
}

// ── iPhone 風格滾輪選擇器 ─────────────────────────────────────
@Composable
private fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeight: androidx.compose.ui.unit.Dp = 40.dp
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val halfVisible = visibleCount / 2
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // 初次顯示時捲到目前選中的項目
    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
    }

    // 項目數量變動（例如切換月份天數變少）時，確保選中項目仍在範圍內並重新對齊
    LaunchedEffect(items.size) {
        if (!listState.isScrollInProgress) {
            listState.scrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
        }
    }

    // 使用者停止捲動後，依置中項目回報選擇結果
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val centerIndex = (
                        listState.firstVisibleItemIndex +
                            if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
                        ).coerceIn(0, items.lastIndex)
                    if (centerIndex != selectedIndex) onSelectedIndexChange(centerIndex)
                }
            }
    }

    Box(
        modifier = modifier.height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * halfVisible),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items) { idx, label ->
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = if (selected) 19.sp else 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Ink else Ink2.copy(alpha = 0.4f)
                    )
                }
            }
        }
        // 中央選取區指示線（上下兩條分隔線）
        HorizontalDivider(
            color = Border,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -(itemHeight / 2))
        )
        HorizontalDivider(
            color = Border,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (itemHeight / 2))
        )
    }
}

// ── iPhone 風格日期滾輪 Dialog（年份：當下年份 ±2 年）──────────────
@Composable
private fun WheelDatePickerDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember { (currentYear - 2..currentYear + 2).toList() }

    var selectedYear by remember { mutableStateOf(initialYear.coerceIn(years.first(), years.last())) }
    var selectedMonth by remember { mutableStateOf(initialMonth) }   // 1..12
    var selectedDay by remember { mutableStateOf(initialDay) }       // 1..daysInMonth

    val daysInMonth = remember(selectedYear, selectedMonth) {
        Calendar.getInstance().apply {
            set(selectedYear, selectedMonth - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    if (selectedDay > daysInMonth) selectedDay = daysInMonth

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedYear, selectedMonth, selectedDay) }) {
                Text("確定", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink2) }
        },
        title = { Text("選擇日期", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Row(modifier = Modifier.fillMaxWidth()) {
                WheelPicker(
                    items = years.map { "${it}年" },
                    selectedIndex = years.indexOf(selectedYear),
                    onSelectedIndexChange = { selectedYear = years[it] },
                    modifier = Modifier.weight(1.2f)
                )
                WheelPicker(
                    items = (1..12).map { "${it}月" },
                    selectedIndex = selectedMonth - 1,
                    onSelectedIndexChange = { selectedMonth = it + 1 },
                    modifier = Modifier.weight(1f)
                )
                WheelPicker(
                    items = (1..daysInMonth).map { "${it}日" },
                    selectedIndex = selectedDay - 1,
                    onSelectedIndexChange = { selectedDay = it + 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    )
}

// ── iPhone 風格時間滾輪 Dialog ───────────────────────────────────
@Composable
private fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHour by remember { mutableStateOf(initialHour) }
    var selectedMinute by remember { mutableStateOf(initialMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedHour, selectedMinute) }) {
                Text("確定", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink2) }
        },
        title = { Text("選擇時間", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Row(modifier = Modifier.fillMaxWidth()) {
                WheelPicker(
                    items = (0..23).map { String.format("%02d", it) },
                    selectedIndex = selectedHour,
                    onSelectedIndexChange = { selectedHour = it },
                    modifier = Modifier.weight(1f)
                )
                WheelPicker(
                    items = (0..59).map { String.format("%02d", it) },
                    selectedIndex = selectedMinute,
                    onSelectedIndexChange = { selectedMinute = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningBottomSheet(
    viewModel: ItineraryViewModel,
    onDismiss: () -> Unit,
    collabViewModel: CollabViewModel? = null
) {
    // ── 讀取使用者偏好設定（作為各步驟的預設值）──────────────
    val userPrefs by viewModel.userPrefs.collectAsState()
    // 只有 collabViewModel 不為 null 且目前有活躍共編行程才套用成員偏好（多數決節奏＋興趣聯集）
    val hasActiveGroup by (collabViewModel?.hasActiveGroup ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val isGroupMode = collabViewModel != null && hasActiveGroup
    val isCollabMode by (collabViewModel?.isCollabMode ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val groupProfile by (collabViewModel?.groupProfile ?: kotlinx.coroutines.flow.MutableStateFlow(CollabViewModel.GroupProfile())).collectAsState()
    val hasGroupMembers = isGroupMode && groupProfile.memberCount > 0

    // ── 步驟狀態 ──────────────────────────────────────────
    // stepSequence 是「步驟內容 id」序列：共編有成員時跳過風格步驟（id=3），
    // 旅遊風格與預算直接採用成員綜合資訊（興趣聯集＋預算多數決）
    var currentStepIndex by remember { mutableStateOf(0) }

    // 從探索範本進來：取用一次就清掉（對齊網頁 pendingTemplateSeed）
    val seed = remember { viewModel.pendingTemplateSeed }
    LaunchedEffect(Unit) {
        viewModel.pendingTemplateSeed = null
        // 趁使用者填精靈叫醒 AI 後端，按下生成時不用再等冷啟動（約 14 秒）
        viewModel.warmUpAi()
    }

    // Step 1
    var destination by remember { mutableStateOf(seed?.destination ?: "台東") }
    var customDestInput by remember { mutableStateOf(seed?.destination ?: "") }

    // Step 2 — 日期預設今天，當天來回
    val todayStr = remember {
        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(java.util.Date())
    }
    var startDate by remember { mutableStateOf(todayStr) }
    var startTime by remember { mutableStateOf("09:00") }
    // 對齊網頁時間模型：出發時間 + 遊玩時數（1–12h，預設 8），回程時間由此推導（單日行程）
    var tripHours by remember { mutableStateOf(8) }
    val derivedEndTime = remember(startTime, tripHours) { clockPlusHours(startTime, tripHours) }
    // A5 多日：天數與「最後一天結束時間」。1 天時完全走原本的單日邏輯。
    var tripDays by remember { mutableStateOf(if (seed?.twoDays == true) 2 else 1) }
    var lastDayEndTime by remember { mutableStateOf("15:00") }
    // 目的地是離島時，起訖錨點由 IslandProfile 決定，車站選擇沒有意義
    val island = remember(destination, customDestInput) {
        IslandRegistry.byDestination(customDestInput.ifBlank { destination })
    }
    val endDateTime = remember(startDate, derivedEndTime, tripDays, lastDayEndTime) {
        if (tripDays <= 1) "$startDate $derivedEndTime"
        else "${DayPlanner.addDays(startDate, tripDays - 1)} $lastDayEndTime"
    }
    // 對齊網頁：行程名稱（選填，40 字內）／回程地點（選填，留空＝同出發車站）
    var tripName by remember { mutableStateOf(seed?.tripName?.take(40) ?: "") }
    var returnStation by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Step 3（出發車站）— 預填使用者偏好
    var departureStation    by remember { mutableStateOf(userPrefs.departureStation) }

    // Step 4 — 預填使用者偏好
    var people by remember { mutableStateOf(2) }

    // Step 5 — 預填使用者偏好
    val selectedStyles = remember {
        mutableStateListOf<String>().also {
            // 精靈單趟行程興趣上限 3（個人喜好可多選，帶入時裁切）
            it.addAll(
                com.example.travellink_ai.data.model.normalizeInterests(userPrefs.interests)
                    .take(com.example.travellink_ai.data.model.MAX_TRAVEL_PREFERENCES)
            )
        }
    }

    // 本趟節奏／旅程風格（對齊網頁 wizData.pace / wizData.theme）：節奏預設沿用個人喜好
    var tripPace by remember { mutableStateOf(userPrefs.pace.ifBlank { "平衡" }) }
    var travelTheme by remember { mutableStateOf("") }
    // 住宿類型（對齊網頁 飯店／民宿／背包客棧／露營／自備住宿；空＝未選）
    var lodgingType by remember { mutableStateOf("") }

    // Step 6（交通方式）
    var selectedTransportMode by remember { mutableStateOf("car") }  // 預設值與網頁端一致
    // 離島預設機車（島上主要交通），且不提供自行開車
    LaunchedEffect(island) {
        if (island != null && selectedTransportMode == "car") selectedTransportMode = "scooter"
    }

    // Step 7
    var budget by remember { mutableStateOf("💳 適中") }
    var noteAccommodation by remember { mutableStateOf("") }
    var noteRestaurant by remember { mutableStateOf("") }
    var noteWishlist by remember { mutableStateOf(seed?.desiredSpots ?: "") }
    // 特別需求快速標籤（id 見 SpecialRequests.QUICK_TAGS）
    val selectedQuickTags = remember { mutableStateListOf<String>() }
    // 住宿含早餐（兩天一夜才有意義）：含的話看日出後回飯店吃早餐並退房
    val lodgingBreakfast = remember { mutableStateOf(false) }
    // 住宿/餐廳預訂日期時間
    var accommodationDate by remember { mutableStateOf("") }
    var accommodationTime by remember { mutableStateOf("") }
    var restaurantDate by remember { mutableStateOf("") }
    var restaurantTime by remember { mutableStateOf("") }
    var showBookingDatePicker by remember { mutableStateOf(false) }
    var showBookingTimePicker by remember { mutableStateOf(false) }
    // "accommodation" 或 "restaurant"，用來識別目前選的是哪個欄位
    var bookingPickerTarget by remember { mutableStateOf("accommodation") }
    // 可預訂日期範圍＝整個行程區間（出發日起，共 tripDays 天）。
    //
    // 不能用 key(startDate, tripDays) { rememberDatePickerState(...) } 讓 State 隨之重建：
    // 實測（螢幕錄影逐格）只要 key 一變，整個精靈面板就會消失再從底部滑入——改行程天數、
    // 確認新的出發日期都會「重新彈出」。改用 rememberUpdatedState 讓 isSelectableDate
    // 每次呼叫時讀最新值，State 本身不重建。
    val bookingStartDate by rememberUpdatedState(startDate)
    val bookingTripDays by rememberUpdatedState(tripDays)
    val bookingDatePickerState = rememberDatePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val startMs = if (bookingStartDate.isNotBlank()) sdf.parse(bookingStartDate)?.time else null
                if (startMs != null && utcTimeMillis < startMs) return false
                // 行程共 tripDays 天，最後一天結束為 startMs + tripDays*一天 - 1ms
                val endMs = startMs?.let { it + bookingTripDays.toLong() * 86_400_000L - 1L }
                if (endMs != null && utcTimeMillis > endMs) return false
                return true
            }
        }
    )
    // 出發日或天數變了，舊的選取可能落在新範圍外（原本靠 key 重建 State 來清掉）
    LaunchedEffect(startDate, tripDays) { bookingDatePickerState.selectedDateMillis = null }
    val bookingTimePickerState = rememberTimePickerState(initialHour = 12, initialMinute = 0)

    // 4 步（與網頁同順序）：①目的地＋人數 ②日期時間 ③交通與偏好 ④預算與其他。
    // 共編有成員時第 ③ 步的偏重／節奏改顯示「團體綜合」，不再整步跳過
    val stepSequence = listOf(0, 1, 2, 3)
    val totalSteps = stepSequence.size
    // 成員數變動（有人中途加入/退出）時序列長度可能改變，索引保持在合法範圍
    val safeStepIndex = currentStepIndex.coerceIn(0, stepSequence.lastIndex)
    val currentStepId = stepSequence[safeStepIndex]
    val canNext = when (currentStepId) {
        0 -> destination.isNotBlank()
        1 -> startDate.isNotBlank()
        2, 3 -> true
        else -> false
    }

    // ── 是否已有使用者輸入（離開前確認用）─────────────────────
    val hasUnsavedChanges =
        destination != "台東" ||
            customDestInput.isNotBlank() ||
            startDate != todayStr ||
            startTime != "09:00" ||
            tripHours != 8 ||
            tripDays != 1 ||
            tripName.isNotBlank() ||
            returnStation.isNotBlank() ||
            departureStation != userPrefs.departureStation ||
            people != 2 ||
            tripPace != userPrefs.pace.ifBlank { "平衡" } ||
            travelTheme.isNotBlank() ||
            lodgingType.isNotBlank() ||
            selectedStyles.toSet() != userPrefs.interests.toSet() ||
            selectedTransportMode != "car" ||
            budget != "💳 適中" ||
            noteAccommodation.isNotBlank() ||
            noteRestaurant.isNotBlank() ||
            noteWishlist.isNotBlank() ||
            selectedQuickTags.isNotEmpty() ||
            lodgingBreakfast.value ||
            accommodationDate.isNotBlank() ||
            accommodationTime.isNotBlank() ||
            restaurantDate.isNotBlank() ||
            restaurantTime.isNotBlank()

    var showDiscardConfirm by remember { mutableStateOf(false) }
    // 統一的離開入口：點 ✕、點空白處、按返回鍵都會經過這裡
    val attemptDismiss: () -> Unit = {
        if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = attemptDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                // 固定不可拖曳：任何往下拖到 Hidden 的手勢都擋回（彈回原位），
                // 只能用右上角 X 關閉（X → attemptDismiss，不經過 sheetState，仍可正常關閉並跳離開確認）。
                target != SheetValue.Hidden
            }
        ),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color.White,
        dragHandle = null
    ) {
        val view = LocalView.current
        // 只在視窗還不是 adjustResize 時設定一次。原本用 SideEffect：每次重組（點出發時間、
        // 點行程天數…任何狀態變動）都會呼叫 setSoftInputMode，即使值沒變，系統仍會把它當成
        // 「視窗屬性改變」而重排整個視窗，是精靈上點按鈕會「重新彈一下」的可疑來源。
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val adjust = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (window != null &&
                (window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != adjust
            ) {
                window.setSoftInputMode(adjust)
            }
            onDispose { }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            // ── 頂部：標題列 + 關閉按鈕 ──────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        // F4：統一為網頁用語「建立微旅行」
                        text = "建立微旅行",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink
                    )
                    Text(
                        text = "步驟 ${safeStepIndex + 1} / $totalSteps",
                        fontSize = 14.sp,
                        color = Ink2
                    )
                }
                IconButton(
                    onClick = attemptDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Surface2)
                ) {
                    Icon(Icons.Default.Close, "關閉", tint = Ink2, modifier = Modifier.size(18.dp))
                }
            }

            // ── 進度條 ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(totalSteps) { idx ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (idx <= safeStepIndex) Accent else Border)
                    )
                }
            }

            HorizontalDivider(color = Border.copy(alpha = 0.5f))

            // ── 步驟內容（帶滑入動畫）─────────────────────────────
            AnimatedContent(
                targetState = safeStepIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()) togetherWith
                                (slideOutHorizontally { it } + fadeOut())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 320.dp, max = 480.dp),
                label = "step_anim"
            ) { stepIdx ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    when (stepSequence.getOrElse(stepIdx) { 3 }) {
                        0 -> Step1DestinationAndPeople(
                            tripName = tripName,
                            onTripNameChange = { tripName = it.take(40) },
                            destination = destination,
                            customInput = customDestInput,
                            onSelect = { destination = it; customDestInput = it },
                            onCustomInput = { customDestInput = it; if (it.isNotBlank()) destination = it },
                            people = people,
                            onPeopleChange = { people = it.coerceIn(1, 20) }
                        )
                        1 -> Step2DateTime(
                            startDate = startDate, startTime = startTime,
                            tripHours = tripHours,
                            onHoursChange = { tripHours = it.coerceIn(1, 12) },
                            derivedEndTime = derivedEndTime,
                            tripDays = tripDays,
                            onDaysChange = { tripDays = it.coerceIn(1, 2) },
                            lastDayEndTime = lastDayEndTime,
                            onLastDayEndTimeChange = { lastDayEndTime = it },
                            onPickStart = { showDatePicker = true },
                            onPickStartTime = { showTimePicker = true }
                        )
                        2 -> Step3TransportAndPrefs(
                            island = island,
                            departureStation = departureStation,
                            onStationChange = { departureStation = it },
                            returnStation = returnStation,
                            onReturnStationChange = { returnStation = it },
                            selectedMode = selectedTransportMode,
                            onSelectMode = { selectedTransportMode = it },
                            selectedStyles = selectedStyles,
                            pace = tripPace,
                            onPaceChange = { tripPace = it },
                            theme = travelTheme,
                            onThemeChange = { travelTheme = it.take(200) },
                            groupProfile = if (hasGroupMembers) groupProfile else null
                        )
                        3 -> Step6BudgetAndNotes(
                            budget = budget,
                            onBudgetChange = { budget = it },
                            // 共編有成員：預算不再自選，直接顯示成員多數決結果
                            groupBudget = if (hasGroupMembers)
                                groupProfile.budget.ifBlank { "適中（每人 \$500–\$1,500）" } else null,
                            noteAccommodation = noteAccommodation,
                            onAccommodationChange = { noteAccommodation = it },
                            accommodationDate = accommodationDate,
                            accommodationTime = accommodationTime,
                            onPickAccommodationDate = {
                                bookingPickerTarget = "accommodation"
                                showBookingDatePicker = true
                            },
                            onPickAccommodationTime = {
                                bookingPickerTarget = "accommodation"
                                showBookingTimePicker = true
                            },
                            noteRestaurant = noteRestaurant,
                            onRestaurantChange = { noteRestaurant = it },
                            restaurantDate = restaurantDate,
                            restaurantTime = restaurantTime,
                            onPickRestaurantDate = {
                                bookingPickerTarget = "restaurant"
                                showBookingDatePicker = true
                            },
                            onPickRestaurantTime = {
                                bookingPickerTarget = "restaurant"
                                showBookingTimePicker = true
                            },
                            noteWishlist = noteWishlist,
                            onWishlistChange = { noteWishlist = it },
                            hasTripDateRange = startDate.isNotBlank(),
                            selectedTags = selectedQuickTags,
                            tripStartDate = startDate,
                            tripStartTime = startTime,
                            tripEndDateTime = endDateTime,
                            isIsland = island != null,
                            breakfastIncluded = lodgingBreakfast,
                            tripDays = tripDays,
                            lodgingVm = viewModel,
                            destinationName = customDestInput.ifBlank { destination },
                            people = people,
                            lodgingType = lodgingType,
                            onLodgingTypeChange = { lodgingType = if (lodgingType == it) "" else it }
                        )
                    }
                }
            }

            // ── 共編群組偏好摘要橫幅 ─────────────────────────────
            if (hasGroupMembers) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .background(Color(0xFFE8F3F0), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("👥", fontSize = 16.sp)
                    Column {
                        Text(
                            "多人共編模式（${groupProfile.memberCount} 位旅伴）",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Accent
                        )
                        if (groupProfile.interests.isNotEmpty()) {
                            Text(
                                "興趣聯集：${groupProfile.interests.take(5).joinToString("、")}${if (groupProfile.interests.size > 5) "…" else ""}",
                                fontSize = 11.sp,
                                color = Ink2
                            )
                        }
                        Text(
                            // 顯示文案對齊網頁 PACE_LABELS（內部值仍為 輕快/平衡/悠閒）
                            "節奏多數決：" + when (groupProfile.pace) {
                                "輕快" -> "緊湊充實"
                                "悠閒" -> "放鬆慢遊"
                                "平衡" -> "標準步調"
                                else   -> groupProfile.pace
                            },
                            fontSize = 11.sp,
                            color = Ink2
                        )
                        if (groupProfile.budget.isNotBlank()) {
                            Text(
                                "預算多數決：${groupProfile.budget}",
                                fontSize = 11.sp,
                                color = Ink2
                            )
                        }
                        if (groupProfile.desiredSpots.isNotEmpty()) {
                            Text(
                                "旅伴想去（會排入）：${groupProfile.desiredSpots.joinToString("、")}",
                                fontSize = 11.sp,
                                color = Ink2
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Border.copy(alpha = 0.5f))

            // ── 底部導航按鈕 ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (safeStepIndex > 0) {
                    OutlinedButton(
                        onClick = { currentStepIndex = safeStepIndex - 1 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink2)
                    ) {
                        Text("上一步")
                    }
                }
                Button(
                    onClick = {
                        if (safeStepIndex < stepSequence.lastIndex) {
                            currentStepIndex = safeStepIndex + 1
                        } else {
                            // 最後一步：開始生成
                            val finalDestination = customDestInput.ifBlank { destination }
                            // 希望包含＝自己填的＋共編成員「想去的景點」（重複的由 WishedSpots.parse 去掉）
                            val wishlistAll = (listOf(noteWishlist) +
                                if (hasGroupMembers) groupProfile.desiredSpots else emptyList())
                                .filter { it.isNotBlank() }.joinToString("、")
                            // 共編有成員：旅遊風格直接用成員興趣聯集（不再自選）
                            val styleList = if (hasGroupMembers && groupProfile.interests.isNotEmpty())
                                groupProfile.interests
                            else selectedStyles.map { it.substringAfter(" ") }
                            // 與網頁 BUDGET_TIERS token 格式一致：「適中（每人 $500–$1,500）」；
                            // 共編有成員：改用成員預算多數決（GroupSetupScreen 存的就是同一種 token）
                            val budgetDesc = budgetOptions.firstOrNull { (label, _) -> label == budget }?.second ?: ""
                            val budgetLabel = if (hasGroupMembers && groupProfile.budget.isNotBlank())
                                groupProfile.budget
                            else "${budget.substringAfter(" ")}（$budgetDesc）"
                            val notes = buildList {
                                // 一日遊不顯示住宿欄位，先前在兩天一夜填過的也不送
                                if (tripDays > 1 && noteAccommodation.isNotBlank()) {
                                    val dt = buildString {
                                        if (accommodationDate.isNotBlank()) append(" ${accommodationDate.takeLast(5)}")
                                        if (accommodationTime.isNotBlank()) append(" $accommodationTime")
                                    }
                                    add("住宿：$noteAccommodation$dt")
                                }
                                if (noteRestaurant.isNotBlank()) {
                                    val dt = buildString {
                                        if (restaurantDate.isNotBlank()) append(" ${restaurantDate.takeLast(5)}")
                                        if (restaurantTime.isNotBlank()) append(" $restaurantTime")
                                    }
                                    add("餐廳：$noteRestaurant$dt")
                                }
                                if (selectedQuickTags.isNotEmpty())
                                    add("特別需求：" + SpecialRequests.QUICK_TAGS
                                        .filter { it.id in selectedQuickTags }.joinToString("、") { it.phrase })
                                if (wishlistAll.isNotBlank()) add("希望包含：$wishlistAll")
                                if (tripDays > 1 && lodgingType.isNotBlank()) add("住宿類型：$lodgingType")
                                if (travelTheme.isNotBlank()) add("旅程風格：${travelTheme.trim()}")
                                // 個人喜好的飲食禁忌/避免事物：每趟行程自動套用（hashtag 格式與網頁一致）
                                if (userPrefs.avoidTags.isNotEmpty())
                                    add("飲食禁忌與避免事項（硬性）：${userPrefs.avoidTags.joinToString(" ") { "#$it" }}")
                                if (userPrefs.avoidNote.isNotBlank())
                                    add("其他想避免：${userPrefs.avoidNote}")
                            }
                            val effectivePace = if (hasGroupMembers) groupProfile.pace else tripPace
                            viewModel.generateItineraryWithAI(
                                destination = finalDestination,
                                startDateTime = "$startDate $startTime",
                                endDateTime = endDateTime,
                                people = "${people}人",
                                travelStyle = styleList,
                                budget = budgetLabel,
                                specialNotes = notes.joinToString("；"),
                                pace = effectivePace,
                                departureStation = departureStation,
                                groupInterests = if (hasGroupMembers && groupProfile.interests.isNotEmpty())
                                    groupProfile.interests else null,
                                isGroupGeneration = isGroupMode,
                                transportMode = selectedTransportMode,
                                customTitle = tripName.trim(),
                                returnStation = returnStation.trim(),
                                // 只解析標籤與「希望包含」：住宿／餐廳欄位常有店名（日出咖啡、日昇飯店）
                                timeRequests = SpecialRequests.resolve(selectedQuickTags, wishlistAll),
                                // 希望包含的地點列為必排；範本帶來的站有座標與營業時間，不必再查
                                wishlist = wishlistAll,
                                wishTemplateStops = seed?.stops.orEmpty(),
                                // 住宿只在兩天一夜（本島）才有意義；名稱空白＝沒指定。
                                // 入住時間用住宿欄位下方選填的時間（沒填就用實際抵達時間）
                                lodgingName = if (tripDays > 1 && island == null) noteAccommodation.trim() else "",
                                lodgingBreakfast = lodgingBreakfast.value && tripDays > 1,
                                lodgingCheckIn = if (tripDays > 1) accommodationTime else ""
                            )
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(if (safeStepIndex == 0) 2f else 1f),
                    enabled = canNext,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(
                        text = if (safeStepIndex < stepSequence.lastIndex) "下一步 →" else "✨ AI 生成行程",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // ── 日期滾輪 Dialog（iPhone 風格，年份：當下 ±2 年）───────────
    if (showDatePicker) {
        val cal = Calendar.getInstance()
        if (startDate.isNotBlank()) {
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).parse(startDate)?.let { cal.time = it }
        }
        WheelDatePickerDialog(
            initialYear = cal.get(Calendar.YEAR),
            initialMonth = cal.get(Calendar.MONTH) + 1,
            initialDay = cal.get(Calendar.DAY_OF_MONTH),
            onConfirm = { year, month, day ->
                startDate = String.format("%04d/%02d/%02d", year, month, day)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }

    // ── 時間滾輪 Dialog（iPhone 風格，出發時間）────────────────
    if (showTimePicker) {
        val parts = startTime.split(":")
        WheelTimePickerDialog(
            initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            onConfirm = { hour, minute ->
                startTime = String.format("%02d:%02d", hour, minute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }

    // ── 預訂 DatePicker 對話框（範圍限制在行程區間）─────────────
    // 已抽成獨立 @Composable（見 BookingDatePickerDialog），降低主函式 lambda 數。
    if (showBookingDatePicker) {
        BookingDatePickerDialog(
            state = bookingDatePickerState,
            onConfirm = { formatted ->
                if (bookingPickerTarget == "accommodation") accommodationDate = formatted
                else restaurantDate = formatted
                showBookingDatePicker = false
            },
            onDismiss = { showBookingDatePicker = false }
        )
    }

    // ── 預訂 TimePicker 對話框 ───────────────────────────────
    if (showBookingTimePicker) {
        BookingTimePickerDialog(
            state = bookingTimePickerState,
            onConfirm = { t ->
                if (bookingPickerTarget == "accommodation") accommodationTime = t
                else restaurantTime = t
                showBookingTimePicker = false
            },
            onDismiss = { showBookingTimePicker = false }
        )
    }

    // ── 離開確認 Dialog（點 ✕ / 點空白處 / 下拉滑動關閉都會觸發）─────
    // 已抽成獨立 @Composable（見 DiscardConfirmDialog），降低此超大函式的 lambda 數，
    // 避免 Compose codegen slot 錯位造成的 rememberComposableLambda ClassCastException。
    if (showDiscardConfirm) {
        DiscardConfirmDialog(
            onConfirm     = { showDiscardConfirm = false; onDismiss() },
            onKeepEditing = { showDiscardConfirm = false }
        )
    }
}

// ── 離開確認對話框（自 PlanningBottomSheet 抽出，維持穩定的小組合範圍）──────
@Composable
private fun DiscardConfirmDialog(
    onConfirm: () -> Unit,
    onKeepEditing: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放棄已輸入的內容？", fontWeight = FontWeight.Bold, color = Ink) },
        text = { Text("離開後，目前填寫的行程資訊將不會保留。", color = Ink2) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("離開", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text("繼續編輯", color = Accent) }
        }
    )
}

// ── 預訂日期對話框（自 PlanningBottomSheet 抽出）──────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingDatePickerDialog(
    state: DatePickerState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val formatted = state.selectedDateMillis?.let {
                    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
                } ?: ""
                onConfirm(formatted)
            }) { Text("確定", color = Accent) }
        }
    ) { DatePicker(state = state) }
}

// ── 預訂時間對話框（自 PlanningBottomSheet 抽出）──────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingTimePickerDialog(
    state: TimePickerState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(String.format("%02d:%02d", state.hour, state.minute))
            }) { Text("確定", color = Accent) }
        },
        text = {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = state)
            }
        }
    )
}

// ── Step 1：行程名稱 + 目的地 ─────────────────────────────────
@Composable
private fun Step1Destination(
    tripName: String,
    onTripNameChange: (String) -> Unit,
    destination: String,
    customInput: String,
    onSelect: (String) -> Unit,
    onCustomInput: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    // 停用自動聚焦以避免軟鍵盤彈起造成視窗調整與 Sheet 滑動動畫
    // LaunchedEffect(Unit) {
    //     kotlinx.coroutines.delay(150)
    //     focusRequester.requestFocus()
    // }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 行程名稱（選填，對齊網頁 tripName，40 字內）— 放在整個精靈的第一個輸入
        Text("為旅程取個名字", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        OutlinedTextField(
            value = tripName,
            onValueChange = onTripNameChange,
            label = { Text("行程名稱（選填）", color = Ink2) },
            placeholder = { Text("留空將自動命名", color = Border) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Border
            ),
            singleLine = true,
            trailingIcon = {
                Text("${tripName.length}/40", fontSize = 12.sp, color = Ink2,
                    modifier = Modifier.padding(end = 8.dp))
            }
        )

        HorizontalDivider(color = Border.copy(alpha = 0.4f))

        Text("想去哪裡？", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)

        OutlinedTextField(
            value = customInput,
            onValueChange = onCustomInput,
            label = { Text("自由輸入目的地", color = Ink2) },
            placeholder = { Text("例：台東、京都…", color = Border) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .showKeyboardOnFocus(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border
            ),
            singleLine = true
        )
//三仙台
        Text("熱門目的地", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(180.dp)
        ) {
            items(popularDestinations) { (emoji, name) ->
                val selected = destination == name && customInput.isBlank()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) AccentLight else Surface2)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Accent else Border,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(name) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emoji, fontSize = 24.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) AccentDark else Ink
                        )
                    }
                }
            }
        }
    }
}

// ── Step 2：出發日期 & 時間（單日／兩天一夜與遊玩時數）─────────────
@Composable
private fun Step2DateTime(
    startDate: String, startTime: String,
    tripHours: Int,
    onHoursChange: (Int) -> Unit,
    derivedEndTime: String,
    tripDays: Int,
    onDaysChange: (Int) -> Unit,
    lastDayEndTime: String,
    onLastDayEndTimeChange: (String) -> Unit,
    onPickStart: () -> Unit,
    onPickStartTime: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // 日期與出發時間
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("📅 出發日期 & 時間", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            DateSection(
                label = "出發",
                date = startDate, time = startTime,
                onDateClick = onPickStart, onTimeClick = onPickStartTime
            )
        }

        // 行程天數（A5）：1 天＝原本的單日邏輯；多日由分日器逐日排程
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("行程天數", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "當日來回", 2 to "兩天一夜").forEach { (d, label) ->
                    val selected = tripDays == d
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Accent else Surface2)
                            .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(14.dp))
                            .clickable { onDaysChange(d) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Ink)
                    }
                }
            }
            if (tripDays > 1) {
                Text("最後一天玩到幾點", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("12:00", "14:00", "15:00", "17:00").forEach { t ->
                        val selected = lastDayEndTime == t
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) AccentLight else Surface2)
                                .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(12.dp))
                                .clickable { onLastDayEndTimeChange(t) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) AccentDark else Ink)
                        }
                    }
                }
                Text(
                    "第 1 天 $startTime 起、其餘每天 09:00–18:00，最後一天到 $lastDayEndTime 結束。" +
                        "每天各自排程，不會把隔天的景點排進今天晚上。",
                    fontSize = 13.sp, color = Ink2
                )
            }
        }

        // 遊玩時數（對齊網頁：1–12 小時 stepper + 快捷選項）
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (tripDays > 1) "第 1 天遊玩時數" else "遊玩時數",
                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onHoursChange(tripHours - 1) },
                    enabled = tripHours > 1,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp)
                ) { Text("−", fontSize = 22.sp) }
                Text(
                    "$tripHours 小時",
                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Ink,
                    modifier = Modifier.widthIn(min = 110.dp),
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = { onHoursChange(tripHours + 1) },
                    enabled = tripHours < 12,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(44.dp)
                ) { Text("＋", fontSize = 22.sp) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                listOf(2, 4, 6, 8, 10).forEach { h ->
                    val sel = tripHours == h
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (sel) Accent else Surface2)
                            .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(16.dp))
                            .clickable { onHoursChange(h) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("${h}h", fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color.White else Ink)
                    }
                }
            }
            Text(
                "🕒 預計 $startTime → $derivedEndTime・約 $tripHours 小時",
                fontSize = 14.sp, color = Ink2,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── 出發 / 回程地點（Step 3 上半）─────────────────────────────
@Composable
private fun StationSection(
    island: IslandProfile?,
    departureStation: String,
    onStationChange: (String) -> Unit,
    returnStation: String,
    onReturnStationChange: (String) -> Unit
) {
    val stationOptions = listOf("台東車站", "知本車站", "鹿野車站", "關山車站", "池上車站")
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // 車站區塊。離島行程的起訖固定是島上港口，選台鐵站沒有意義
        if (island != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("出發 / 回程地點", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentLight)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("⛴️ ${island.port.name}（${island.name}）",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentDark)
                    Text(
                        "本島請至富岡漁港搭船，航程約 ${island.sailingMins} 分鐘。" +
                            "行程會以抵港時間起算，並在末班船前預留 ${IslandRegistry.BOARDING_BUFFER_MINS} 分鐘回到港口。",
                        fontSize = 13.sp, color = Ink2
                    )
                }
            }
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("出發 / 回程地點", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text("行程將以此地點為起終點規劃", fontSize = 14.sp, color = Ink2)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stationOptions.forEach { station ->
                    val selected = departureStation == station
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Accent else Surface2)
                            .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(20.dp))
                            .clickable { onStationChange(station) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "🚉 $station",
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Ink
                        )
                    }
                }
            }
            val focusRequester = remember { FocusRequester() }
            // 停用自動聚焦以避免軟鍵盤彈起造成視窗調整與 Sheet 滑動動畫
            // LaunchedEffect(Unit) {
            //     kotlinx.coroutines.delay(150)
            //     focusRequester.requestFocus()
            // }
            OutlinedTextField(
                value = departureStation,
                onValueChange = onStationChange,
                label = { Text("或輸入其他地點", color = Ink2) },
                placeholder = { Text("例：花蓮車站、台東大學、鐵花村", color = Border) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .showKeyboardOnFocus(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border
                ),
                singleLine = true
            )
            // 回程地點（選填；對齊網頁 startLocation/endLocation 可不同的行為）
            OutlinedTextField(
                value = returnStation,
                onValueChange = onReturnStationChange,
                label = { Text("回程地點（選填）", color = Ink2) },
                placeholder = { Text("留空＝同出發車站", color = Border) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border
                ),
                singleLine = true
            )

            // 最終目的地（A7 ②）：行程結束後要搭火車回哪裡。
            // 填了就用 OD 給精準班次與抵達時間，留空則列該站南下／北上的下幾班。
            // 這是使用者的長期習慣，直接存 SharedPreferences，不進生成參數也不綁單一行程。
            val prefsCtx = androidx.compose.ui.platform.LocalContext.current
            val prefsMgr = remember {
                com.example.travellink_ai.data.local.UserPreferencesManager(prefsCtx)
            }
            var finalDest by remember { mutableStateOf(prefsMgr.finalDestinationStation) }
            OutlinedTextField(
                value = finalDest,
                onValueChange = {
                    finalDest = it
                    prefsMgr.finalDestinationStation = it.trim()
                },
                label = { Text("最終目的地車站（選填）", color = Ink2) },
                placeholder = { Text("例：臺北、花蓮、高雄", color = Border) },
                supportingText = {
                    Text("填了就會在行程末尾推薦接得上的火車班次", fontSize = 12.sp, color = Ink2)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border
                ),
                singleLine = true
            )
        }
    }
}

// ── Step 1：行程名稱＋目的地＋人數（網頁把人數放在第 1 步）────────────
// 同行者類型已移除（2026-09-28 使用者決定）：只剩人數，AI prompt 也不再帶同行類型
@Composable
private fun Step1DestinationAndPeople(
    tripName: String,
    onTripNameChange: (String) -> Unit,
    destination: String,
    customInput: String,
    onSelect: (String) -> Unit,
    onCustomInput: (String) -> Unit,
    people: Int,
    onPeopleChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Step1Destination(
            tripName = tripName, onTripNameChange = onTripNameChange,
            destination = destination, customInput = customInput,
            onSelect = onSelect, onCustomInput = onCustomInput
        )
        HorizontalDivider(color = Border.copy(alpha = 0.4f))
        PeopleSection(people, onPeopleChange)
    }
}

@Composable
private fun PeopleSection(
    people: Int,
    onPeopleChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("旅行人數", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(
                onClick = { if (people > 1) onPeopleChange(people - 1) },
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Surface2)
            ) { Text("−", fontSize = 22.sp, color = Ink, fontWeight = FontWeight.Bold) }
            Text(
                "👥 ${people} 人", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink,
                modifier = Modifier.widthIn(min = 84.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { if (people < 20) onPeopleChange(people + 1) },
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AccentLight)
            ) { Text("+", fontSize = 22.sp, color = Accent, fontWeight = FontWeight.Bold) }
        }
        // 快選（對齊網頁 1/2/4/6 人）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4, 6).forEach { n ->
                val sel = people == n
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (sel) Accent else Surface2)
                        .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(16.dp))
                        .clickable { onPeopleChange(n) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("${n}人", fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Color.White else Ink)
                }
            }
        }
    }
}

// ── Step 3：交通 & 旅遊偏好（對齊網頁「🚆 交通 & 旅遊偏好」）──────────────
@Composable
private fun Step3TransportAndPrefs(
    island: IslandProfile?,
    departureStation: String,
    onStationChange: (String) -> Unit,
    returnStation: String,
    onReturnStationChange: (String) -> Unit,
    selectedMode: String,
    onSelectMode: (String) -> Unit,
    selectedStyles: MutableList<String>,
    pace: String,
    onPaceChange: (String) -> Unit,
    theme: String,
    onThemeChange: (String) -> Unit,
    groupProfile: CollabViewModel.GroupProfile?
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StationSection(island, departureStation, onStationChange, returnStation, onReturnStationChange)
        HorizontalDivider(color = Border.copy(alpha = 0.4f))
        TransportSection(selectedMode, onSelectMode, isIsland = island != null)
        HorizontalDivider(color = Border.copy(alpha = 0.4f))
        TripPreferenceSection(selectedStyles, pace, onPaceChange, groupProfile)
        TravelThemeSection(theme, onThemeChange)
    }
}

// ── 主要交通工具 ─────────────────────────────────────────────
@Composable
private fun TransportSection(
    selectedMode: String,
    onSelectMode: (String) -> Unit,
    isIsland: Boolean = false
) {
    // 選項與網頁端對齊（汽車為預設故置頂），步行為 App 額外支援。
    // 離島不提供自行開車：車無法隨船過去，島上就是租機車。
    val transportOptions = buildList {
        if (isIsland) {
            add(Triple("scooter", "🛵 機車", "島上主要交通，環島公路一圈即可繞完"))
            add(Triple("taxi",    "🚕 計程車", "島上有少量計程車，費用較高"))
            add(Triple("walking", "🚶 步行",   "僅適合港口周邊，環島步行不可行"))
        } else {
            add(Triple("car",     "🚗 汽車", "自己開車，規劃有停車場的景點"))
            add(Triple("taxi",    "🚕 計程車", "彈性靈活，不限景點距離"))
            add(Triple("scooter", "🛵 機車",   "行動彈性，跨區景點皆可安排"))
            add(Triple("walking", "🚶 步行",   "悠閒漫步，景點集中於同區域"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // 交通方式
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("主要交通工具", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text("生成行程時各段移動會以此工具為主，短程則步行", fontSize = 14.sp, color = Ink2)
            }
            transportOptions.forEach { (mode, label, desc) ->
                val selected = selectedMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) AccentLight else Surface2)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Accent else Border,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(label.substringBefore(" "), fontSize = 24.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label.substringAfter(" "),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) AccentDark else Ink
                        )
                        Text(desc, fontSize = 12.sp, color = Ink2)
                    }
                    if (selected) Text("✓", fontSize = 16.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
            }
        }

        HorizontalDivider(color = Border.copy(alpha = 0.4f))
    }
}

// ── 本趟偏重＋本趟節奏（對齊網頁：預設沿用個人喜好，「本趟調整 ▾」才展開）──────
private val PACE_OPTIONS = listOf("輕快" to "🏃", "平衡" to "🚶", "悠閒" to "🐢")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripPreferenceSection(
    selectedStyles: MutableList<String>,
    pace: String,
    onPaceChange: (String) -> Unit,
    groupProfile: CollabViewModel.GroupProfile?
) {
    // 共編有成員：興趣與節奏已在成員偏好設定（網頁同一句說明）
    if (groupProfile != null) {
        Text(
            "🧑‍🤝‍🧑 興趣與節奏已在「成員偏好」設定，生成時會綜合所有成員，這裡不再重複填寫。",
            fontSize = 14.sp, color = Ink2,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AccentLight)
                .padding(12.dp)
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本趟想偏重", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text("決定 AI 選擇的景點類型比重與行程節奏", fontSize = 13.sp, color = Ink2)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收合 ▴" else "本趟調整 ▾", color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!expanded) {
            val interests = selectedStyles.joinToString("、").ifBlank { "多元體驗" }
            Text("✨ 沿用你的個人喜好：$interests・${pace}節奏", fontSize = 14.sp, color = Ink2)
            return@Column
        }
        Text("興趣（最多 ${com.example.travellink_ai.data.model.MAX_TRAVEL_PREFERENCES} 個）",
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            travelStyleOptions.forEach { style ->
                val selected = selectedStyles.contains(style)
                val atLimit = !selected &&
                    selectedStyles.size >= com.example.travellink_ai.data.model.MAX_TRAVEL_PREFERENCES
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) AccentLight else Surface2)
                        .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(20.dp))
                        .clickable(enabled = !atLimit) {
                            if (selected) selectedStyles.remove(style) else selectedStyles.add(style)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        com.example.travellink_ai.data.model.displayInterest(style),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = when { selected -> AccentDark; atLimit -> Border; else -> Ink }
                    )
                }
            }
        }
        Text("本趟節奏", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PACE_OPTIONS.forEach { (value, emoji) ->
                val sel = pace == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Accent else Surface2)
                        .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(12.dp))
                        .clickable { onPaceChange(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$emoji $value", fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Color.White else Ink)
                }
            }
        }
    }
}

// ── 旅程風格：自由文字＋5 種角色（對齊網頁 wizData.theme）──────────────
private val THEME_PRESETS = listOf("冒險獵人", "療癒漫步者", "美食尋寶家", "在地故事家", "拍照家")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TravelThemeSection(theme: String, onThemeChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text("旅程風格", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("決定整體旅行氛圍與 AI 敘述感，與本趟想偏重互補，各有作用", fontSize = 13.sp, color = Ink2)
        }
        OutlinedTextField(
            value = theme,
            onValueChange = onThemeChange,
            placeholder = { Text("例：想要輕鬆散步、品嚐在地美食、發現隱藏景點…", color = Border) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Border),
            minLines = 2
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            THEME_PRESETS.forEach { t ->
                val sel = theme == t
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) AccentLight else Surface2)
                        .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(20.dp))
                        .clickable { onThemeChange(if (sel) "" else t) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (sel) AccentDark else Ink)
                }
            }
        }
    }
}

// ── Step 2：日期時間 ────────────────────────────────────────
@Composable
private fun Step2Dates(
    startDate: String, startTime: String,
    endDate: String, endTime: String,
    onPickStart: () -> Unit, onPickEnd: () -> Unit,
    onPickStartTime: () -> Unit, onPickEndTime: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("出發與回程日期", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)

        DateSection(
            label = "出發日期",
            date = startDate, time = startTime,
            onDateClick = onPickStart, onTimeClick = onPickStartTime
        )
        DateSection(
            label = "回程日期",
            date = endDate, time = endTime,
            onDateClick = onPickEnd, onTimeClick = onPickEndTime
        )
    }
}

@Composable
private fun DateSection(
    label: String, date: String, time: String,
    onDateClick: () -> Unit, onTimeClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 日期字串（yyyy/MM/dd）較長，給多一點寬度並強制單行，避免擠到第二排
            OutlinedButton(
                onClick = onDateClick,
                modifier = Modifier.weight(1.5f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (date.isEmpty()) Ink2 else Ink),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, if (date.isEmpty()) Border else Accent
                )
            ) {
                Icon(Icons.Default.DateRange, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (date.isEmpty()) "選擇日期" else date,
                    fontSize = 15.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            OutlinedButton(
                onClick = onTimeClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(time, fontSize = 15.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ── Step 3：出發 / 回程車站 ──────────────────────────────────
@Composable
private fun Step3Station(
    departureStation: String,
    onStationChange: (String) -> Unit
) {
    val context = LocalContext.current
    val stationOptions = listOf("台東車站", "知本車站", "鹿野車站", "關山車站", "池上車站")
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("出發 / 回程車站", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("行程將以此車站為起終點規劃", fontSize = 15.sp, color = Ink2)
        }

        // 常用車站 Chips
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常用車站", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                stationOptions.forEach { station ->
                    val selected = departureStation == station
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Accent else Surface2)
                            .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(20.dp))
                            .clickable { onStationChange(station) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "🚉 $station",
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Ink
                        )
                    }
                }
            }
        }

        val focusRequester = remember { FocusRequester() }
        // 停用自動聚焦以避免軟鍵盤彈起造成視窗調整與 Sheet 滑動動畫
        // LaunchedEffect(Unit) {
        //     kotlinx.coroutines.delay(150)
        //     focusRequester.requestFocus()
        // }
        // 自訂輸入
        OutlinedTextField(
            value = departureStation,
            onValueChange = onStationChange,
            label = { Text("或輸入其他車站", color = Ink2) },
            placeholder = { Text("例：花蓮車站、高雄車站", color = Border) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .showKeyboardOnFocus(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border
            ),
            singleLine = true
        )
    }
}

// ── Step 6：預算與特別需求 ───────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step6BudgetAndNotes(
    budget: String,
    onBudgetChange: (String) -> Unit,
    groupBudget: String? = null,   // 非 null＝共編模式：預算採成員多數決，不再自選
    noteAccommodation: String,
    onAccommodationChange: (String) -> Unit,
    accommodationDate: String,
    accommodationTime: String,
    onPickAccommodationDate: () -> Unit,
    onPickAccommodationTime: () -> Unit,
    noteRestaurant: String,
    onRestaurantChange: (String) -> Unit,
    restaurantDate: String,
    restaurantTime: String,
    onPickRestaurantDate: () -> Unit,
    onPickRestaurantTime: () -> Unit,
    noteWishlist: String,
    onWishlistChange: (String) -> Unit,
    hasTripDateRange: Boolean,
    selectedTags: MutableList<String>,
    tripStartDate: String,
    tripStartTime: String,
    tripEndDateTime: String,
    isIsland: Boolean,
    breakfastIncluded: MutableState<Boolean>,
    tripDays: Int,
    lodgingVm: ItineraryViewModel,
    destinationName: String,
    people: Int = 1,
    lodgingType: String = "",
    onLodgingTypeChange: (String) -> Unit = {}
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // 停用自動聚焦以避免軟鍵盤彈起造成視窗調整與 Sheet 滑動動畫
    // LaunchedEffect(Unit) {
    //     kotlinx.coroutines.delay(150)
    //     focusRequester.requestFocus()
    // }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("💰 預算 & 其他", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("設定旅行預算，並可選填住宿與希望拜訪的景點", fontSize = 15.sp, color = Ink2)
        }

        // ── 預算：共編模式顯示成員多數決（唯讀）；個人模式下拉自選 ──────
        if (groupBudget != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("預算等級（每人每日）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentLight, RoundedCornerShape(14.dp))
                        .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("👥", fontSize = 20.sp)
                    Column {
                        Text(groupBudget, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentDark)
                        Text("依成員偏好多數決自動採用，不需再選擇", fontSize = 12.sp, color = Ink2)
                    }
                }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("預算等級（每人每日）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = budget,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    containerColor = Color.White
                ) {
                    budgetOptions.forEach { (label, desc) ->
                        val isSelected = budget == label
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Accent else Ink
                                    )
                                    Text(desc, fontSize = 14.sp, color = Ink2)
                                }
                            },
                            onClick = { onBudgetChange(label); dropdownExpanded = false },
                            leadingIcon = if (isSelected) ({
                                Text("✓", color = Accent, fontWeight = FontWeight.Bold)
                            }) else null
                        )
                    }
                }
            }
            BudgetTotalNote(budget = budget, people = people)
        }

        HorizontalDivider(color = Border.copy(alpha = 0.5f))

        // ── 住宿類型（對齊網頁「住宿安排」）：一日遊不過夜，兩天一夜才顯示 ──
        if (tripDays > 1) {
            LodgingTypeChips(selected = lodgingType, onSelect = onLodgingTypeChange)
        }

        // ── 選填輸入框 ────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "特別需求（選填）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink2
            )
            QuickTagSection(
                selectedTags = selectedTags,
                wishlist = noteWishlist,
                tripStartDate = tripStartDate,
                tripStartTime = tripStartTime,
                tripEndDateTime = tripEndDateTime,
                isIsland = isIsland
            )
            // 已訂住宿只在兩天一夜顯示（對齊網頁）；切回一日遊時保留已填內容，但生成時不送出
            if (tripDays > 1) {
                BookingField(
                    value = noteAccommodation,
                    onValueChange = onAccommodationChange,
                    label = "已訂住宿",
                    placeholder = "例：台東知本老爺酒店",
                    emoji = "🏨",
                    selectedDate = accommodationDate,
                    selectedTime = accommodationTime,
                    onPickDate = onPickAccommodationDate,
                    onPickTime = onPickAccommodationTime,
                    hasTripDateRange = hasTripDateRange,
                    focusRequester = focusRequester
                )
            }
            // 兩天一夜才需要：含早餐時，看日出後回飯店吃早餐並退房；不含就另外找早餐店
            if (tripDays > 1 && !isIsland) {
                // 填完名稱即時告訴使用者找到了沒（過去要按下「AI 生成行程」之後才會查）
                LodgingStatusLine(viewModel = lodgingVm, name = noteAccommodation, destination = destinationName)
                LodgingBreakfastToggle(
                    checked = breakfastIncluded,
                    enabled = noteAccommodation.isNotBlank()
                )
            }
            BookingField(
                value = noteRestaurant,
                onValueChange = onRestaurantChange,
                label = "已訂餐廳",
                placeholder = "例：成功鎮某海鮮餐廳",
                emoji = "🍽️",
                selectedDate = restaurantDate,
                selectedTime = restaurantTime,
                onPickDate = onPickRestaurantDate,
                onPickTime = onPickRestaurantTime,
                hasTripDateRange = hasTripDateRange
            )
            NoteTextField(
                value = noteWishlist,
                onValueChange = onWishlistChange,
                label = "希望包含的景點或活動",
                placeholder = "例：想去衝浪、一定要看日出",
                emoji = "✨"
            )
        }
    }
}

// ── 特別需求快速標籤 ─────────────────────────────────────────
// 把最常見的需求變成一鍵選取：比自由文字可靠（不必猜使用者怎麼寫），
// 也讓「看日出」這類會改動時間窗的需求有明確的入口，選了就即時說明會怎麼排。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTagSection(
    selectedTags: MutableList<String>,
    wishlist: String,
    tripStartDate: String,
    tripStartTime: String,
    tripEndDateTime: String,
    isIsland: Boolean
) {
    // 提示涵蓋標籤與「希望包含」自由文字（與送出時同一套解析），所見即所得
    val requests = SpecialRequests.resolve(selectedTags, wishlist)
    val hints = if (isIsland || requests.isEmpty()) emptyList() else {
        val windows = DayPlanner.applyTimeRequests(
            DayPlanner.buildDayWindows("$tripStartDate $tripStartTime", tripEndDateTime), requests
        )
        SpecialRequests.describe(windows, requests)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("快速選擇", fontSize = 13.sp, color = Ink2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpecialRequests.QUICK_TAGS.forEach { tag ->
                val selected = tag.id in selectedTags
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) AccentLight else Surface2)
                        .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(20.dp))
                        .clickable {
                            if (selected) selectedTags.remove(tag.id) else selectedTags.add(tag.id)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${tag.emoji} ${tag.label}",
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) AccentDark else Ink
                    )
                }
            }
        }
        hints.forEach { Text(it, fontSize = 12.sp, color = Accent) }
        if (isIsland && requests.isNotEmpty()) {
            Text(
                "離島行程的出發與收工時間由船班決定，日出／夜間需求只會作為 AI 選點的參考，不會調整時間",
                fontSize = 12.sp, color = Ink2
            )
        }
    }
}

// ── 住宿名稱查詢結果（欄位下方一行）───────────────────────────────
// 停頓約 1.2 秒才查（不是每打一個字就查，避免打 Places 的費用與雜訊）；名稱或目的地一變，
// 舊的查詢會被取消。查到的結果由 ViewModel 快取，按「生成」時直接用，不會為同一個名稱查兩次。
@Composable
private fun LodgingStatusLine(viewModel: ItineraryViewModel, name: String, destination: String) {
    val typed = name.trim()
    var state by remember(typed, destination) { mutableStateOf<LodgingLookup?>(null) }
    LaunchedEffect(typed, destination) {
        state = null
        if (typed.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        state = LodgingLookup.Searching
        state = viewModel.lookupLodgingForUi(typed, destination)
    }
    val current = state ?: return
    Text(
        Lodging.statusText(current, typed),
        fontSize = 12.sp,
        color = when (current) {
            is LodgingLookup.Found -> Accent
            LodgingLookup.Searching -> Ink2
            else -> Color(0xFFE65100)      // 找不到／查詢失敗：橘色警示
        },
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ── 住宿含早餐開關 ───────────────────────────────────────────
// 決定早出發（看日出）那天日出後要不要回飯店：含早餐 → 回飯店吃並退房；
// 不含 → 就近另外找早餐店，不回飯店。飯店位置由上方填的住宿名稱查出。
@Composable
private fun LodgingBreakfastToggle(checked: MutableState<Boolean>, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "🍳 住宿含早餐", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) Ink else Ink2
            )
            Text(
                if (enabled) "看日出時，日出後回飯店休息到 ${DayPlanner.hhmmOf(Lodging.LEAVE_HOTEL_MINS)} 左右再出發；" +
                    "含早餐就在飯店吃，不含就先在附近吃再回去"
                else "先填上面的住宿名稱",
                fontSize = 12.sp, color = Ink2
            )
        }
        Switch(
            checked = checked.value && enabled,
            onCheckedChange = { checked.value = it },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
    }
}

// ── 含日期時間選擇的預訂欄位（住宿/餐廳專用）────────────────────
@Composable
private fun BookingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    emoji: String,
    selectedDate: String,
    selectedTime: String,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    hasTripDateRange: Boolean,
    focusRequester: FocusRequester? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("$emoji $label", fontSize = 15.sp) },
            placeholder = { Text(placeholder, fontSize = 14.sp, color = Border) },
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .showKeyboardOnFocus(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedLabelColor = Accent
            ),
            maxLines = 2
        )
        // 日期時間選擇列（只有當行程日期已填入才啟用）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dateActive = hasTripDateRange
            val hasDate = selectedDate.isNotBlank()
            val hasTime = selectedTime.isNotBlank()

            // 日期按鈕
            OutlinedButton(
                onClick = onPickDate,
                enabled = dateActive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasDate) Accent else Border
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (hasDate) Accent else Ink2,
                    disabledContentColor = Border
                )
            ) {
                Text(
                    text = if (hasDate) "📅 ${selectedDate.takeLast(5)}" else "📅 選擇日期",
                    fontSize = 14.sp,
                    fontWeight = if (hasDate) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            // 時間按鈕（需先選日期才啟用）
            OutlinedButton(
                onClick = onPickTime,
                enabled = hasDate,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasTime) Accent else Border
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (hasTime) Accent else Ink2,
                    disabledContentColor = Border
                )
            ) {
                Text(
                    text = if (hasTime) "🕐 $selectedTime" else "🕐 選擇時間",
                    fontSize = 14.sp,
                    fontWeight = if (hasTime) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
        // 提示文字
        if (!hasTripDateRange) {
            Text(
                "請先在步驟 2 填入行程日期",
                fontSize = 12.sp,
                color = Ink2.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    emoji: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text("$emoji $label", fontSize = 15.sp)
        },
        placeholder = {
            Text(placeholder, fontSize = 14.sp, color = Border)
        },
        modifier = Modifier.fillMaxWidth().showKeyboardOnFocus(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Border,
            focusedLabelColor = Accent
        ),
        maxLines = 2
    )
}

// ── Step 6：交通方式 ────────────────────────────────────────
@Composable
private fun Step6Transport(
    selectedMode: String,
    onSelect: (String) -> Unit
) {
    // 選項與網頁端對齊（計程車/機車/汽車），步行為 App 額外支援
    val options = listOf(
        Triple("taxi",    "🚕 計程車", "彈性靈活，不限景點距離"),
        Triple("scooter", "🛵 機車",   "行動彈性，跨區景點皆可安排"),
        Triple("car", "🚗 汽車", "自己開車，規劃有停車場的景點"),
        Triple("walking", "🚶 步行",   "悠閒漫步，景點集中於同區域")
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("交通方式", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("AI 會根據交通方式調整景點距離與安排", fontSize = 15.sp, color = Ink2)
        }
        options.forEach { (mode, label, desc) ->
            val selected = selectedMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) AccentLight else Surface2)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Accent else Border,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(label.substringBefore(" "), fontSize = 28.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label.substringAfter(" "),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) AccentDark else Ink
                    )
                    Text(desc, fontSize = 13.sp, color = Ink2)
                }
                if (selected) {
                    Text("✓", fontSize = 18.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 預算依人數換算總額＋不含往返交通說明（對齊網頁預算欄下方的換算與 ℹ️ 說明） */
@Composable
private fun BudgetTotalNote(budget: String, people: Int) {
    val range = when (budget.substringAfter(" ")) {
        "節省" -> 0 to 500
        "適中" -> 500 to 1500
        "舒適" -> 1500 to 3000
        "豪華" -> 3000 to null
        else   -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (range != null && people > 1) {
            val (lo, hi) = range
            val fmt = { v: Int -> "\$" + "%,d".format(v * people) }
            val total = when {
                hi == null -> "${fmt(lo)} 以上"
                lo == 0    -> "${fmt(hi)} 內"
                else       -> "${fmt(lo)}–${fmt(hi)}"
            }
            Text("👥 $people 人合計約 $total", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AccentDark)
        }
        Text(
            "ℹ️ 此預算為當地餐飲與付費體驗的花費，不含往返目的地的車票／機票等交通旅費（站間移動與離島船票會另行估算）。",
            fontSize = 12.sp, color = Ink2
        )
    }
}

private val LODGING_TYPES = listOf("飯店", "民宿", "背包客棧", "露營", "自備住宿")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LodgingTypeChips(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("住宿安排（選填）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LODGING_TYPES.forEach { t ->
                val sel = selected == t
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) AccentLight else Surface2)
                        .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(20.dp))
                        .clickable { onSelect(t) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(t, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (sel) AccentDark else Ink)
                }
            }
        }
    }
}
