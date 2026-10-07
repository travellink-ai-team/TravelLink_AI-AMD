package com.example.travellink_ai.ui.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.agent.AgentRouting
import com.example.travellink_ai.data.ai.AiProvider
import com.example.travellink_ai.data.assistant.AssistantMessage
import com.example.travellink_ai.ui.agent.AgentRunView
import com.example.travellink_ai.ui.agent.AgentViewModel
import com.example.travellink_ai.ui.agent.DemoControls
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.example.travellink_ai.ui.theme.DesignTokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Accent     = DesignTokens.Accent
private val AccentDark = DesignTokens.AccentDark
private val Ink        = DesignTokens.Ink
private val Ink2       = DesignTokens.Ink2
private val Surface2   = DesignTokens.Surface2
private val Border     = DesignTokens.Border

/**
 * AI 隨行管家覆蓋層：可拖曳的圓形頭像（點開/點關聊天室）+ 聊天面板。
 * 放進任一畫面根 Box 的最後一個子元素即可。
 *
 * @param bottomReservedPx 畫面底部要讓出來的高度（px）。頭像只會停在這個高度以上，
 *   不會壓到底部按鈕列（預覽頁的「開始行程」）。只限制頭像，聊天面板不受影響。
 */
@Composable
fun AssistantOverlay(viewModel: ItineraryViewModel, bottomReservedPx: Int = 0) {
    val open by viewModel.assistantOpen.collectAsState()
    val busy by viewModel.assistantBusy.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWpx = with(density) { maxWidth.toPx() }
        val maxHpx = with(density) { maxHeight.toPx() }
        val bubblePx = with(density) { 60.dp.toPx() }
        val marginPx = with(density) { 16.dp.toPx() }

        // 從 DataStore 還原頭像位置（貼邊 + 垂直比例，跨畫面/重啟都記住）
        val savedPos = remember { viewModel.getAssistantBubblePos() }
        var posX by remember {
            mutableStateOf(if (savedPos.first == 0) marginPx else maxWpx - bubblePx - marginPx)
        }
        // 頭像最低能到哪：底部要讓出按鈕列時，比原本的下緣再往上
        val maxY = (maxHpx - bubblePx - marginPx - bottomReservedPx).coerceAtLeast(marginPx)
        var posY by remember {
            mutableStateOf((savedPos.second * (maxHpx - bubblePx)).coerceIn(marginPx, maxY))
        }
        // 底部欄高度是畫面排版後才量到的（或之後變高），量到時把已經在下方的頭像推上來。
        // 只調整目前位置、不覆寫記住的位置——回到沒有底部欄的畫面仍在原處。
        LaunchedEffect(maxY) { posY = posY.coerceIn(marginPx, maxY) }

        // ── 聊天面板 ──────────────────────────────────────────────
        if (open) {
            AssistantChatPanel(
                viewModel = viewModel,
                onClose = { viewModel.setAssistantOpen(false) },
                containerHeightPx = maxHpx,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // ── 可拖曳頭像：聊天室打開時隱藏（聊天室有自己的關閉鈕），關掉再出現 ──
        var dragAmount by remember { mutableStateOf(0f) }
        if (!open) Box(
            modifier = Modifier
                .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                .size(60.dp)
                // maxY 也要當 key：底部欄高度變動時，拖曳範圍要跟著換（否則還是舊的下限）
                .pointerInput(maxWpx, maxY) {
                    detectDragGestures(
                        onDragStart = { dragAmount = 0f },
                        onDrag = { change, delta ->
                            change.consume()
                            posX = (posX + delta.x).coerceIn(marginPx, maxWpx - bubblePx - marginPx)
                            posY = (posY + delta.y).coerceIn(marginPx, maxY)
                            dragAmount += kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
                        },
                        onDragEnd = {
                            // 吸到最近的左右邊，並存回 DataStore
                            val side = if (posX + bubblePx / 2 < maxWpx / 2) 0 else 1
                            posX = if (side == 0) marginPx else maxWpx - bubblePx - marginPx
                            viewModel.saveAssistantBubblePos(side, posY / (maxHpx - bubblePx))
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { viewModel.toggleAssistant() })
                }
                .clip(CircleShape)
                .background(if (open) AccentDark else Accent),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp
                )
            } else {
                Text(if (open) "✕" else "🤖", fontSize = if (open) 22.sp else 26.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun AssistantChatPanel(
    viewModel: ItineraryViewModel,
    onClose: () -> Unit,
    containerHeightPx: Float,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.assistantMessages.collectAsState()
    val recState by viewModel.assistantRecState.collectAsState()
    val busy by viewModel.assistantBusy.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // ── 旅程應變（AMD 組）：已整合進管家，同 AMD 版網站。Gemini 模式（AI 工具運用組）完全不出現 ──
    val agentVm: AgentViewModel = hiltViewModel()
    val aiKind by AiProvider.kind.collectAsState()
    val hostInfo by agentVm.hostInfo.collectAsState()
    val agentChat by agentVm.chat.collectAsState()
    val agentRun by agentVm.run.collectAsState()
    val agentOn = aiKind == AiProvider.Kind.AMD && hostInfo != null
    // 「幫我調整行程」：下一句一定交給旅程應變（不靠關鍵字）
    var adjustMode by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }

    // 管家的對話與 Agent 的執行依時間排在一起
    val items = remember(messages, agentChat, agentOn) {
        val agentItems = if (agentOn || agentChat.isNotEmpty()) agentChat.map { ChatItem.Agent(it) } else emptyList()
        (messages.map { ChatItem.Msg(it) } + agentItems).sortedBy { it.ts }
    }
    LaunchedEffect(items.size, agentRun?.events?.size, agentRun?.applied) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isBlank()) return
        if (agentOn) {
            when {
                // 修改卡在等「好／不用」：照舊交給管家（「不用，取消」不能被當成刪站）
                viewModel.isReplyToPendingProposal(msg) -> viewModel.sendAssistantMessage(msg)
                AgentRouting.isClearlyOffTopic(msg) -> viewModel.replyOffTopic(msg)
                adjustMode || AgentRouting.isItineraryChangeIntent(msg) -> agentVm.startUser(msg)
                else -> viewModel.sendAssistantMessage(msg)
            }
            adjustMode = false
        } else viewModel.sendAssistantMessage(msg)
    }

    // ── 面板高度：半高 ⇄ 全螢幕，拖頂端把手切換，半高再往下拉就關閉 ──
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val halfPx = containerHeightPx * 0.68f
    val fullPx = containerHeightPx
    val snapPx = with(density) { 48.dp.toPx() }
    val heightAnim = remember { Animatable(halfPx) }
    var dragStartPx by remember { mutableFloatStateOf(halfPx) }
    fun settle(targetPx: Float) = scope.launch { heightAnim.animateTo(targetPx, spring(stiffness = Spring.StiffnessMediumLow)) }
    val isFull = heightAnim.value >= fullPx - 1f
    val dragModifier = Modifier
        .pointerInput(containerHeightPx) {
            detectVerticalDragGestures(
                onDragStart = { dragStartPx = heightAnim.value },
                onVerticalDrag = { change, dy ->
                    change.consume()
                    scope.launch {
                        heightAnim.snapTo((heightAnim.value - dy).coerceIn(0f, fullPx))
                    }
                },
                onDragEnd = {
                    val moved = heightAnim.value - dragStartPx   // 正＝往上拉
                    val wasFull = dragStartPx >= fullPx - snapPx
                    when {
                        moved > snapPx -> settle(fullPx)
                        moved < -snapPx && wasFull -> settle(halfPx)
                        // 從半高往下拉超過一段就收起
                        moved < -snapPx && heightAnim.value < halfPx * 0.6f -> onClose()
                        else -> settle(if (wasFull) fullPx else halfPx)
                    }
                },
                onDragCancel = { settle(if (dragStartPx >= fullPx - snapPx) fullPx else halfPx) }
            )
        }
    // 全螢幕時面板頂到狀態列底下，標題往下讓出被蓋住的那段
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val headerInsetPx = (statusBarPx - (containerHeightPx - heightAnim.value)).coerceAtLeast(0f)
    val corner = if (isFull) 0.dp else 24.dp

    Surface(
        modifier = modifier.fillMaxWidth().height(with(density) { heightAnim.value.toDp() }),
        color = Color.White,
        shape = RoundedCornerShape(topStart = corner, topEnd = corner),
        shadowElevation = 16.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            // 把手＋標題列：往上拉＝全螢幕、往下拉＝回半高／關閉；點把手直接切換
            Column(dragModifier.fillMaxWidth()) {
                Spacer(Modifier.height(with(density) { headerInsetPx.toDp() }))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { settle(if (isFull) halfPx else fullPx) }
                        .padding(top = 10.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Border))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🤖 隨行管家", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                    if (aiKind == AiProvider.Kind.AMD) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AMD gpt-oss-120b", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmdRed,
                            modifier = Modifier.background(AmdRedBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "關閉", tint = Ink2) }
                }
            }
            HorizontalDivider(color = Border.copy(alpha = 0.5f))

            // 訊息列
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Msg -> {
                            val m = item.m
                            MessageBubble(
                                m,
                                recState = recState,
                                onAddStop = { viewModel.addRecommendedStop(it) },
                                onApply = { viewModel.applyAssistantProposal(m.id) },
                                onDismiss = { viewModel.dismissAssistantProposal(m.id) }
                            )
                        }
                        is ChatItem.Agent -> AgentBubble(item.e, agentRun, agentVm)
                    }
                }
                if (busy) {
                    item {
                        Text("隨行管家思考中…", fontSize = 13.sp, color = Ink2,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            // AMD 模式：旅程應變的入口（同網頁管家抽屜）
            if (agentOn) {
                HorizontalDivider(color = Border.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChipButton("🌦️ 檢查天氣並調整", on = false, enabled = agentRun?.running != true) { agentVm.startWeather() }
                    ChipButton("🛠 幫我調整行程", on = adjustMode) { adjustMode = !adjustMode }
                    if (BuildConfig.DEBUG) ChipButton("🎬 情境模擬", on = showDemo) { showDemo = !showDemo }
                }
                if (BuildConfig.DEBUG && showDemo) hostInfo?.let { info ->
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { DemoControls(agentVm, info) }
                }
            }

            // 輸入列
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp)
                    .navigationBarsPadding().imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                        .background(Surface2).padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) Text(
                        if (adjustMode) "說說要怎麼調整，例如：好累，想早點回飯店" else "問我景點、美食或改行程…",
                        fontSize = 14.sp, color = Ink2
                    )
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = Ink),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !busy
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .background(if (canSend) Accent else Border)
                        .clickable(enabled = canSend) {
                            send(input); input = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Send, "送出", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    m: AssistantMessage,
    recState: Map<String, ItineraryViewModel.RecAddState> = emptyMap(),
    onAddStop: (String) -> Unit,
    onApply: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val isUser = m.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Accent else Surface2)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, fontSize = 15.sp, color = if (isUser) Color.White else Ink, lineHeight = 21.sp)
        }
        // 推薦卡（有修改卡時併進修改卡，不分成兩塊）
        if (m.actions.isEmpty()) m.recommendations.forEach { rec ->
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White).padding(1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DesignTokens.AccentLight)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AccentDark)
                        if (rec.reason.isNotBlank())
                            Text(rec.reason, fontSize = 12.sp, color = Ink2)
                    }
                    AddButton(recState[rec.name.trim()], filled = true) { onAddStop(rec.name) }
                }
            }
        }
        // 修改卡：AI 提議的行程修改，使用者按「套用」才改（與網頁隨行管家同一套）
        if (m.actions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ActionCard(m, onApply, onDismiss, onAddStop, recState)
        }
    }
}

/**
 * 修改卡：AI 提議的修改（附上推薦理由），下方再列其餘推薦（可單獨加入）。
 * 推薦與修改說的常是同一批地點，分兩塊顯示會像講了兩次，所以合成一張。
 */
@Composable
private fun ActionCard(
    m: AssistantMessage, onApply: () -> Unit, onDismiss: () -> Unit, onAddStop: (String) -> Unit,
    recState: Map<String, ItineraryViewModel.RecAddState> = emptyMap()
) {
    fun reasonOf(name: String) = m.recommendations.firstOrNull { it.name.trim() == name.trim() }?.reason.orEmpty()
    val actionNames = m.actions.map { it.name.trim() }.toSet()
    val others = m.recommendations.filter { it.name.trim() !in actionNames }
    val state = m.actionState
    Column(
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, if (state == AssistantMessage.ActionState.PENDING) Accent else Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("📝 修改行程", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
        m.actions.forEach { a ->
            val line = when (a.type) {
                "add_stop" -> "＋ 加入「${a.name}」"
                "remove_stop" -> "－ 刪除「${a.target.ifBlank { a.name }}」"
                "replace_stop" -> "⇄ 把「${a.target}」換成「${a.name}」"
                else -> a.type
            }
            Text(line, fontSize = 14.sp, color = Ink)
            reasonOf(a.name).takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = Ink2, modifier = Modifier.padding(start = 18.dp))
            }
        }
        when (state) {
            AssistantMessage.ActionState.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Accent)
                        .clickable(onClick = onApply).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text("套用", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Surface2)
                        .clickable(onClick = onDismiss).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) { Text("不用", fontSize = 14.sp, color = Ink2, fontWeight = FontWeight.Bold) }
            }
            AssistantMessage.ActionState.APPLIED -> Text("✓ 已套用", fontSize = 13.sp, color = AccentDark, fontWeight = FontWeight.Bold)
            AssistantMessage.ActionState.DISMISSED -> Text("已取消，行程沒有更動", fontSize = 13.sp, color = Ink2)
            AssistantMessage.ActionState.NONE -> Unit
        }
        if (others.isNotEmpty()) {
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
            Text("其他推薦", fontSize = 12.sp, color = Ink2, fontWeight = FontWeight.SemiBold)
            others.forEach { rec ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AccentDark)
                        if (rec.reason.isNotBlank()) Text(rec.reason, fontSize = 12.sp, color = Ink2)
                    }
                    AddButton(recState[rec.name.trim()], filled = false) { onAddStop(rec.name) }
                }
            }
        }
    }
}

/** 推薦的「加入」按鈕：加入中、已加入（不能再按）、失敗可重試 */
@Composable
private fun AddButton(state: ItineraryViewModel.RecAddState?, filled: Boolean, onClick: () -> Unit) {
    val (label, enabled) = when (state) {
        ItineraryViewModel.RecAddState.ADDING -> "加入中…" to false
        ItineraryViewModel.RecAddState.ADDED -> "✓ 已加入" to false
        ItineraryViewModel.RecAddState.FAILED -> "重試" to true
        null -> "加入" to true
    }
    val done = state == ItineraryViewModel.RecAddState.ADDED
    val bg = when {
        done -> Surface2
        filled -> Accent
        else -> DesignTokens.AccentLight
    }
    val fg = when {
        done -> Ink2
        filled -> Color.White
        else -> AccentDark
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(label, fontSize = 13.sp, color = fg, fontWeight = FontWeight.Bold) }
}

private val AmdRed = Color(0xFFB0121B)
private val AmdRedBg = Color(0xFFFDECEA)

/** 聊天室的一列：管家的訊息，或一次旅程應變 */
private sealed class ChatItem(val ts: Long, val key: String) {
    class Msg(val m: AssistantMessage) : ChatItem(m.ts, "m${m.id}")
    class Agent(val e: AgentViewModel.ChatEntry) : ChatItem(e.ts, "a${e.ts}")
}

/**
 * 一次旅程應變：使用者那句話（或按的按鈕）在右邊，管家這邊是執行步驟與提案卡；
 * 被下一次取代或放棄後只留一句摘要。
 */
@Composable
private fun AgentBubble(e: AgentViewModel.ChatEntry, run: com.example.travellink_ai.ui.agent.AgentRun?, agentVm: AgentViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        e.userText?.let { MessageBubble(AssistantMessage("user", it), onAddStop = {}) }
        val live = e.summary == null && run != null && run.startedAtMs == e.runStartedAt
        when {
            live -> Box(Modifier.widthIn(max = 340.dp)) { AgentRunView(run!!, agentVm) }
            else -> MessageBubble(AssistantMessage("assistant", e.summary ?: "這次調整已結束。"), onAddStop = {})
        }
    }
}

@Composable
private fun ChipButton(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (on) Accent else DesignTokens.AccentLight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = when { on -> Color.White; enabled -> AccentDark; else -> Ink2 }
        )
    }
}
