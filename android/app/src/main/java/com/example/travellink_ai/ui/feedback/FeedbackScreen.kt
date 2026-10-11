package com.example.travellink_ai.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.ui.feedback.FeedbackViewModel
import com.example.travellink_ai.ui.feedback.StopFeedbackItem
import kotlinx.coroutines.delay

// ── 設計顏色 ─────────────────────────────────────────────────
private val Accent      = DesignTokens.Accent
private val AccentLight = DesignTokens.AccentLight
private val AccentDark  = DesignTokens.AccentDark
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border
private val Gold        = DesignTokens.Gold        // 對齊網頁 --gold #C9A227
private val DangerLight = DesignTokens.RedLight     // 對齊網頁 --red-light
private val DangerMain  = DesignTokens.Red          // 對齊網頁 --red #D94040

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    viewModel: FeedbackViewModel,
    onDone: () -> Unit
) {
    val itinerary      by viewModel.itinerary.collectAsState()
    val feedback       by viewModel.feedbackState.collectAsState()
    val isSubmitting   by viewModel.isFeedbackSubmitting.collectAsState()
    val submitSuccess  by viewModel.feedbackSubmitSuccess.collectAsState()

    val stops = (itinerary?.stops ?: emptyList()).filter { !it.isStation }
    val stopNames = stops.map { it.name }
    val title = itinerary?.title?.ifBlank { itinerary?.aiTitle } ?: "此行程"

    // 提交成功後自動跳回首頁
    LaunchedEffect(submitSuccess) {
        if (submitSuccess) {
            delay(2800)
            onDone()
        }
    }

    // 提交成功：顯示感謝頁
    if (submitSuccess) {
        ThankYouScreen(
            rating = feedback.overallRating,
            onDone = onDone
        )
        return
    }

    var showSkipDialog by remember { mutableStateOf(false) }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("略過回饋", fontWeight = FontWeight.Bold) },
            text  = { Text("確定要略過回饋嗎？您的意見能幫助 AI 給您更好的行程推薦。") },
            confirmButton = {
                TextButton(onClick = { showSkipDialog = false; onDone() }) {
                    Text("略過", color = Ink2)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text("繼續填寫", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("旅途回饋", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                        Text(title, fontSize = 13.sp, color = Ink2, maxLines = 1)
                    }
                },
                actions = {
                    TextButton(onClick = { showSkipDialog = true }) {
                        Text("略過", color = Ink2, fontSize = 15.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Surface2,
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
                Button(
                    onClick = { viewModel.submitFeedback() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    enabled = feedback.overallRating > 0 && !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("送出回饋", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── ① 整體評分 ────────────────────────────────────────
            item {
                FeedbackCard(title = "整體旅程評分") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when (feedback.overallRating) {
                                1 -> "很失望 😞"
                                2 -> "還需改善 😐"
                                3 -> "普通 🙂"
                                4 -> "不錯 😊"
                                5 -> "非常滿意 🤩"
                                else -> "點選星星給分"
                            },
                            fontSize = 15.sp,
                            color = if (feedback.overallRating > 0) Accent else Ink2,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..5).forEach { star ->
                                Icon(
                                    imageVector = if (star <= feedback.overallRating)
                                        Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "$star 星",
                                    tint = if (star <= feedback.overallRating) Gold else Border,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clickable { viewModel.updateFeedbackOverallRating(star) }
                                )
                            }
                        }
                    }
                }
            }

            // ── ② 景點體驗（逐站評分 + 下次還想再去）───────────────
            if (stops.isNotEmpty()) {
                item {
                    Text(
                        "每個景點的體驗",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // 逐站評分卡片
                items(stops) { stop ->
                    val sf = feedback.stopFeedbacks[stop.name] ?: StopFeedbackItem()
                    StopFeedbackCard(
                        emoji = stop.emoji,
                        name = stop.name,
                        time = stop.time,
                        feedback = sf,
                        onVisitedChange  = { viewModel.updateStopVisited(stop.name, it) },
                        onRatingChange   = { viewModel.updateStopRating(stop.name, it) },
                        onDurationChange = { viewModel.updateStopDuration(stop.name, it) }
                    )
                }

                // 下次還想再去的景點（下拉式多選）
                item {
                    WantToVisitAgainCard(
                        allStopNames = stopNames.filter { it !in feedback.dontWantToVisitAgainStops },
                        selected = feedback.wantToVisitAgainStops,
                        onChanged = { viewModel.updateWantToVisitAgainStops(it) }
                    )
                }

                // 不想再去的景點（下拉式多選）
                item {
                    DontWantToVisitAgainCard(
                        allStopNames = stopNames.filter { it !in feedback.wantToVisitAgainStops },
                        selected = feedback.dontWantToVisitAgainStops,
                        onChanged = { viewModel.updateDontWantToVisitAgainStops(it) }
                    )
                }
            }

            // ── ③ 旅程亮點（選填）────────────────────────────────
            item {
                FeedbackCard(title = "旅程亮點（選填）") {
                    OutlinedTextField(
                        value = feedback.highlight,
                        onValueChange = { viewModel.updateFeedbackHighlight(it) },
                        placeholder = { Text("旅程中印象最深的事…", color = Ink2, fontSize = 15.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border
                        )
                    )
                }
            }

            // ── ④ AI 品質評估 ─────────────────────────────────────
            item {
                FeedbackCard(title = "AI 行程品質") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AiQualityRow(
                            label = "⏱ 景點時間安排是否合理？",
                            value = feedback.aiScheduleAccurate,
                            onChanged = { viewModel.updateAiScheduleAccurate(it) }
                        )
                        AiQualityRow(
                            label = "🚗 交通時間估算準確嗎？",
                            value = feedback.aiTransitAccurate,
                            onChanged = { viewModel.updateAiTransitAccurate(it) }
                        )
                        AiQualityRow(
                            label = "🕐 營業時間資訊正確嗎？",
                            value = feedback.aiHoursAccurate,
                            onChanged = { viewModel.updateAiHoursAccurate(it) }
                        )
                    }
                }
            }

            // ── ⑤ 給 AI 的建議 ───────────────────────────────────
            item {
                FeedbackCard(title = "給 AI 的建議（選填）") {
                    OutlinedTextField(
                        value = feedback.suggestions,
                        onValueChange = { viewModel.updateFeedbackSuggestions(it) },
                        placeholder = {
                            Text(
                                "下次希望 AI 注意什麼？例如：想多安排自然步道、景點不要太密集…",
                                color = Ink2, fontSize = 15.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ── 下次還想再去的景點卡片（下拉多選）──────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WantToVisitAgainCard(
    allStopNames: List<String>,
    selected: List<String>,
    onChanged: (List<String>) -> Unit
) {
    // 可用選項 = 全部景點扣除已選的
    val available = allStopNames.filter { it !in selected }

    FeedbackCard(title = "下次還想再去的景點（選填）") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // 已選景點 — 顯示為可刪除的 Chip 列表
            if (selected.isNotEmpty()) {
                selected.forEach { stopName ->
                    SelectedStopChip(
                        name = stopName,
                        onRemove = { onChanged(selected - stopName) }
                    )
                }
            }

            // 下拉選單（只要還有未選景點就顯示）
            if (available.isNotEmpty()) {
                StopDropdown(
                    placeholder = if (selected.isEmpty()) "選擇景點…" else "繼續新增景點…",
                    options = available,
                    onSelect = { chosenName ->
                        onChanged(selected + chosenName)
                    }
                )
            } else if (selected.isNotEmpty()) {
                // 全部景點都已選
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "所有景點都已加入！",
                        fontSize = 14.sp,
                        color = AccentDark,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ── 不想再去的景點卡片（下拉多選）────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DontWantToVisitAgainCard(
    allStopNames: List<String>,
    selected: List<String>,
    onChanged: (List<String>) -> Unit
) {
    val available = allStopNames.filter { it !in selected }

    FeedbackCard(title = "不想再去的景點（選填）") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // 已選景點 — 顯示為可刪除的 Chip（紅色系）
            if (selected.isNotEmpty()) {
                selected.forEach { stopName ->
                    AvoidStopChip(
                        name = stopName,
                        onRemove = { onChanged(selected - stopName) }
                    )
                }
            }

            // 下拉選單
            if (available.isNotEmpty()) {
                StopDropdown(
                    placeholder = if (selected.isEmpty()) "選擇景點…" else "繼續新增景點…",
                    options = available,
                    onSelect = { chosenName -> onChanged(selected + chosenName) }
                )
            } else if (selected.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = DangerMain,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "所有景點都已加入！",
                        fontSize = 14.sp,
                        color = DangerMain,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (selected.isEmpty() && available.isEmpty()) {
                Text(
                    "目前沒有可選的景點",
                    fontSize = 14.sp,
                    color = Ink2
                )
            }
        }
    }
}

@Composable
private fun AvoidStopChip(name: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DangerLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.NotInterested,
                    contentDescription = null,
                    tint = DangerMain,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    name,
                    fontSize = 15.sp,
                    color = DangerMain,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除",
                    tint = Ink2,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectedStopChip(name: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AccentLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    name,
                    fontSize = 15.sp,
                    color = AccentDark,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除",
                    tint = Ink2,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopDropdown(
    placeholder: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            placeholder = {
                Text(placeholder, fontSize = 15.sp, color = Ink2)
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedTrailingIconColor = Accent,
                unfocusedTrailingIconColor = Ink2
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            options.forEach { stopName ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(stopName, fontSize = 16.sp, color = Ink)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(stopName)
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ── 共用元件 ─────────────────────────────────────────────────

@Composable
private fun FeedbackCard(
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

@Composable
private fun StopFeedbackCard(
    emoji: String,
    name: String,
    time: String,
    feedback: StopFeedbackItem,
    onVisitedChange: (Boolean) -> Unit,
    onRatingChange: (Int) -> Unit,
    onDurationChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (feedback.visited) Color.White else Surface2
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(emoji, fontSize = 22.sp)
                    Column {
                        Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        if (time.isNotBlank()) {
                            Text(time, fontSize = 13.sp, color = Ink2)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (feedback.visited) "有去" else "沒去",
                        fontSize = 13.sp,
                        color = if (feedback.visited) Accent else Ink2
                    )
                    Switch(
                        checked = feedback.visited,
                        onCheckedChange = onVisitedChange,
                        modifier = Modifier.height(24.dp),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Accent,
                            uncheckedTrackColor = Border
                        )
                    )
                }
            }

            if (feedback.visited) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Border.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))

                // 星評
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("值得嗎？", fontSize = 14.sp, color = Ink2)
                    Spacer(Modifier.weight(1f))
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = if (star <= feedback.rating)
                                Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = if (star <= feedback.rating) Gold else Border,
                            modifier = Modifier
                                .size(26.dp)
                                .clickable { onRatingChange(if (feedback.rating == star) 0 else star) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 停留時間
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("停留時間", fontSize = 14.sp, color = Ink2)
                    Spacer(Modifier.weight(1f))
                    listOf("太短", "剛好", "太長").forEach { opt ->
                        val selected = feedback.durationFeedback == opt
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDurationChange(if (selected) "" else opt) },
                            color = if (selected) AccentLight else Surface2,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                opt,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontSize = 14.sp,
                                color = if (selected) AccentDark else Ink2,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 感謝頁（提交成功後顯示）────────────────────────────────────

@Composable
private fun ThankYouScreen(
    rating: Int,
    onDone: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val iconScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "icon_scale"
    )

    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(Surface2),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = androidx.compose.ui.Modifier.padding(32.dp)
        ) {
            // 打勾圓圈
            Box(
                modifier = androidx.compose.ui.Modifier
                    .size(96.dp)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = androidx.compose.ui.Modifier.size(52.dp)
                )
            }

            Spacer(androidx.compose.ui.Modifier.height(4.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + scaleIn(initialScale = 0.85f)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "感謝您的回饋！",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink
                    )
                    Text(
                        "您的意見將幫助 AI 為您\n規劃更棒的台東之旅 🌿",
                        fontSize = 16.sp,
                        color = Ink2,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    // 顯示使用者給的評分
                    if (rating > 0) {
                        Spacer(androidx.compose.ui.Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            (1..5).forEach { star ->
                                Icon(
                                    imageVector = if (star <= rating) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = if (star <= rating) Gold else Border,
                                    modifier = androidx.compose.ui.Modifier.size(28.dp)
                                )
                            }
                        }
                        Text(
                            when (rating) {
                                1 -> "您給了 1 顆星"
                                2 -> "您給了 2 顆星"
                                3 -> "您給了 3 顆星"
                                4 -> "您給了 4 顆星"
                                5 -> "您給了滿分 5 顆星 🎉"
                                else -> ""
                            },
                            fontSize = 14.sp,
                            color = if (rating == 5) Accent else Ink2,
                            fontWeight = if (rating == 5) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(androidx.compose.ui.Modifier.height(16.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn()
            ) {
                OutlinedButton(
                    onClick = onDone,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Accent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                ) {
                    Text("返回首頁", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
            }

            Text(
                "3 秒後自動返回",
                fontSize = 13.sp,
                color = Ink2.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun AiQualityRow(
    label: String,
    value: Boolean?,
    onChanged: (Boolean?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(true to "✅ 準確", false to "❌ 不準").forEach { (v, text) ->
                val selected = value == v
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onChanged(if (selected) null else v) },
                    color = when {
                        selected && v  -> AccentLight
                        selected && !v -> DangerLight
                        else -> Surface2
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 13.sp,
                        color = when {
                            selected && v  -> AccentDark
                            selected && !v -> DangerMain
                            else -> Ink2
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
