package com.example.travellink_ai.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.data.agent.AgentChange
import com.example.travellink_ai.data.agent.AgentEvent
import com.example.travellink_ai.data.agent.AgentMock
import com.example.travellink_ai.data.agent.AgentRouting
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.AppConflict
import com.example.travellink_ai.data.agent.Deadline
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.theme.DesignTokens
import kotlinx.coroutines.delay

private val AmdRed = Color(0xFFB0121B)
private val AmdRedBg = Color(0xFFFDECEA)
private val OkGreen = Color(0xFF2E7D32)
private val WarnBg = Color(0xFFFFF4E5)
private val WarnFg = Color(0xFF8A4B00)

/**
 * 行程進行中的延誤資訊（T4）：目前所在、尚未離開的站與它的預計離開時間。
 *
 * @param leaveMin 預計離開（當日分鐘數，含使用者調過的時間）
 * @param conflicts App 自己算出的衝突（不含「提早到要等開門」這類提醒）；有衝突才主動顯示入口
 * @param isLastDay 回程那天才送回程火車期限（送出前才查，見 returnTrain）
 * @param lastFerry 當天從島上出發的末班船（規格 2.1）
 * @param nowMs 「現在」（demo 時是假時鐘），送給後端，後端不用伺服器時間
 */
data class DelayInput(
    val here: Stop,
    val leaveMin: Int,
    val day: Int,
    val conflicts: List<AppConflict>,
    val isLastDay: Boolean,
    val lastFerry: Deadline?,
    val nowMs: Long
)

/**
 * 隨行管家聊天室裡的一次旅程應變（AMD 組）：執行步驟＋結尾卡片（提案／提問／不用改／錯誤）。
 * 旅程應變助理已整合進隨行管家（同 AMD 版網站），行程頁只留延誤提醒與超時警告兩個入口。
 */
@Composable
fun AgentRunView(r: AgentRun, agentVm: AgentViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RunPanel(r, onStop = agentVm::stop, onClose = agentVm::closeInChat)
        when (val t = r.terminal) {
            is AgentEvent.Proposal -> {
                val blocked = if (!AgentTripMapper.canApply(r.sentIds, t.draftStops))
                    "這是錄製的示範結果，和目前的行程對不上，只能預覽。" else null
                ProposalCard(
                    p = t, run = r, blockedReason = blocked,
                    onApply = agentVm::applyCurrent,
                    onDiscard = agentVm::closeInChat
                )
            }
            // 延誤請求後端不會回 question（延誤模式不給 ask_user，排不下就規則刪站）。
            // 防護：萬一收到也不當文字需求重送——那會丟掉延誤資訊（目前站、預計離開），改跑一般流程
            is AgentEvent.Question -> QuestionCard(
                t,
                onAnswer = if (r.request?.trigger is AgentTrigger.Delay) null else agentVm::answerInChat
            )
            is AgentEvent.NoChange -> EndNote(
                "✅ ${AgentRouting.displayText(t.reason)}" + if (t.llmSkipped) "\n程式檢查沒發現問題，沒有呼叫 AI。" else "",
                Color(0xFFEFF7EF), OkGreen
            )
            is AgentEvent.Error -> EndNote("⚠️ ${AgentRouting.displayText(t.message)}", AmdRedBg, AmdRed)
            else -> Unit
        }
    }
}

// ── 行程頁的延誤提醒（按下會打開隨行管家並開始處理）───────────────────

@Composable
fun DelayEntry(d: DelayInput, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).background(WarnBg, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("⏱️ 後面的行程可能排不下", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WarnFg)
        d.conflicts.take(3).forEach { Text("・${it.message}", fontSize = 12.sp, color = WarnFg) }
        Button(
            onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AmdRed)
        ) { Text("🤖 請 AI 處理延誤", maxLines = 1) }
    }
}

/** 開發版限定：demo 情境注入與 mock 來源（放在隨行管家聊天室上方） */
@Composable
fun DemoControls(agentVm: AgentViewModel, info: AgentViewModel.HostInfo) {
    val simRain by agentVm.simRain.collectAsState()
    val simSea by agentVm.simSea.collectAsState()
    val simDelay by agentVm.simDelay.collectAsState()
    val mock by agentVm.mockChoice.collectAsState()
    val showcase by com.example.travellink_ai.data.ai.ShowcaseMode.on.collectAsState()
    Column(
        Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 展示模式：只留情境模擬，並說清楚只有觸發資料是模擬的
        Text(
            if (showcase) "🎬 情境模擬（只模擬觸發事件，AI 照常真實執行）" else "🧪 Demo（開發版）",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WarnFg
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(simRain, { agentVm.setSimRain(!simRain) }, label = { Text("模擬：下午大雨", fontSize = 12.sp) })
            if (info.isIsland) FilterChip(simSea, { agentVm.setSimSea(!simSea) }, label = { Text("模擬：回程停航", fontSize = 12.sp) })
            // 延誤只在行程進行中、已在某一站時有意義
            if (info.hasDelay) FilterChip(simDelay, { agentVm.setSimDelay(!simDelay) },
                label = { Text("模擬：延誤 ${AgentViewModel.SIM_DELAY_MIN} 分", fontSize = 12.sp) })
        }
        if (!showcase) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("來源", fontSize = 12.sp, color = WarnFg)
            FilterChip(mock == null, { agentVm.setMockChoice(null) }, label = { Text("真後端", fontSize = 12.sp) })
            FilterChip(mock == "auto", { agentVm.setMockChoice("auto") }, label = { Text("mock 自動", fontSize = 12.sp) })
            AgentMock.NAMES.forEach { n ->
                FilterChip(mock == n, { agentVm.setMockChoice(n) }, label = { Text(n, fontSize = 12.sp) })
            }
        }
    }
}

// ── 動態面板 ──────────────────────────────────────────────────────────

@Composable
private fun RunPanel(r: AgentRun, onStop: () -> Unit, onClose: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.running) { while (r.running) { nowMs = System.currentTimeMillis(); delay(200) } }
    val elapsed = if (r.running) nowMs - r.startedAtMs else (r.events.lastOrNull()?.ms ?: 0)
    Column(
        Modifier.fillMaxWidth().background(DesignTokens.Surface2, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AmdRed)
            Text(
                when {
                    r.running -> "AI 隨行管家處理中"
                    r.stoppedByUser -> "已停止"
                    else -> "完成"
                } + " · ${"%.1f".format(elapsed / 1000.0)} 秒",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink
            )
            Spacer(Modifier.weight(1f))
            if (r.mock != null) Badge(if (r.mockRecorded) "錄製重播" else "合成事件", WarnBg, WarnFg)
            if (r.request?.simulated == true) Badge("模擬情境", WarnBg, WarnFg)
            TextButton(onClick = if (r.running) onStop else onClose, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (r.running) "停止" else "關閉", fontSize = 13.sp, color = DesignTokens.Ink2)
            }
        }
        r.events.filter { !it.isTerminal }.forEach { EventRow(it) }
    }
}

@Composable
private fun EventRow(e: AgentEvent) {
    val (icon, text, color) = when (e) {
        is AgentEvent.Start -> Triple("▶️", "開始" + if (e.simulated) "（模擬情境）" else "", DesignTokens.Ink2)
        is AgentEvent.Check -> Triple("🔎", e.label, DesignTokens.Ink2)
        is AgentEvent.CheckResult -> Triple("⚠️", e.label, WarnFg)
        is AgentEvent.ToolCall -> Triple(toolIcon(e.tool), e.label, DesignTokens.Ink)
        is AgentEvent.ToolResult -> Triple(if (e.ok) "↳" else "✗", e.detail.ifBlank { "完成" }, if (e.ok) DesignTokens.Ink2 else AmdRed)
        is AgentEvent.Fallback -> Triple("🛟", e.label, WarnFg)
        else -> return
    }
    val shown = AgentRouting.displayText(text)
    val indent = if (e is AgentEvent.ToolResult) 22.dp else 0.dp
    Row(Modifier.padding(start = indent), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 13.sp, modifier = Modifier.width(22.dp))
        Text(shown, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
        Text("+${"%.1f".format(e.ms / 1000.0)}s", fontSize = 11.sp, color = DesignTokens.Ink2)
    }
    if (e is AgentEvent.CheckResult) e.issues.forEach {
        Text("・$it", fontSize = 12.sp, color = WarnFg, modifier = Modifier.padding(start = 22.dp))
    }
}

private fun toolIcon(tool: String) = when (tool) {
    "get_trip_state" -> "📋"
    "get_weather_forecast" -> "🌦️"
    "get_sea_conditions" -> "🌊"
    "get_ferry_status" -> "⛴️"
    "check_business_hours" -> "🕒"
    "search_local_poi" -> "🔍"
    "search_restaurants" -> "🍽️"
    "estimate_travel" -> "🚗"
    "propose_patch" -> "🧩"
    "ask_user" -> "❓"
    "present_proposal" -> "📝"
    else -> "🔧"
}

// ── 結尾卡片 ──────────────────────────────────────────────────────────

@Composable
private fun ProposalCard(
    p: AgentEvent.Proposal,
    run: AgentRun,
    blockedReason: String?,
    onApply: () -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📝 修改提案", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
            Spacer(Modifier.weight(1f))
            if (p.simulated) Badge("模擬情境", WarnBg, WarnFg)
            if (p.fallback) Badge("快速替代方案（未經 AI 推理）", WarnBg, WarnFg)
        }
        if (p.retimeOnly) Text(
            "⏱️ 只要順延就排得下" + if (p.llmSkipped) "（程式檢查，沒有呼叫 AI）" else "",
            fontSize = 12.sp, color = OkGreen, fontWeight = FontWeight.Bold
        )
        Text(AgentRouting.displayText(p.summary), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
        p.reasons.forEach { Text("・${AgentRouting.displayText(it)}", fontSize = 13.sp, color = DesignTokens.Ink2) }

        if (p.changes.isNotEmpty()) {
            HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.5f))
            p.changes.forEach { ChangeRow(it) }
        }
        p.ferry?.let { f ->
            val changed = f.after.filter { a -> f.before.none { it.direction == a.direction && it.day == a.day && it.depart == a.depart } }
            changed.forEach { a ->
                val b = f.before.firstOrNull { it.direction == a.direction }
                Text(
                    "⛴️ ${a.direction}" + (b?.let { " 第 ${it.day} 天 ${it.depart} →" } ?: "") + " 第 ${a.day} 天 ${a.depart}",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AmdRed
                )
            }
            if (f.note.isNotBlank()) Text(f.note, fontSize = 12.sp, color = DesignTokens.Ink2)
        }
        if (p.extraNights > 0) Text("🏨 多住 ${p.extraNights} 晚（住宿費另計）", fontSize = 13.sp, color = WarnFg)
        p.costDelta?.let { c ->
            val sign = if (c.diff > 0) "+" else ""
            Text("💰 每人花費 \$${c.perPersonBefore} → \$${c.perPersonAfter}（$sign${c.diff}）", fontSize = 13.sp, color = DesignTokens.Ink)
            if (c.note.isNotBlank()) Text(c.note, fontSize = 11.sp, color = DesignTokens.Ink2)
        }
        p.warnings.forEach {
            Text("⚠️ ${AgentRouting.displayText(it)}", fontSize = 12.sp, color = WarnFg,
                modifier = Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(8.dp)).padding(6.dp))
        }
        p.meta?.let { m ->
            val model = m.upstreamModels.firstOrNull() ?: m.model
            Text(
                "$model · AI 呼叫 ${m.llmCalls} 次 · ${"%.1f".format(p.ms / 1000.0)} 秒",
                fontSize = 11.sp, color = DesignTokens.Ink2
            )
        }
        when {
            run.applied -> Text("✓ 已套用，路線與時間重新計算中", fontSize = 13.sp, color = OkGreen, fontWeight = FontWeight.Bold)
            else -> {
                if (blockedReason != null) Text(blockedReason, fontSize = 12.sp, color = DesignTokens.Ink2)
                run.applyError?.let { Text("⚠️ $it", fontSize = 12.sp, color = AmdRed) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApply, enabled = blockedReason == null && !run.running && !run.applying,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AmdRed)
                    ) {
                        if (run.applying) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("套用", maxLines = 1)
                    }
                    OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("放棄", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(c: AgentChange) {
    // move 的天數與時間寫在後面，前面不再重複
    val where = if (c.type == "move") "" else listOfNotNull(c.day?.let { "第 $it 天" }, c.time).joinToString(" ")
    val text = buildAnnotatedString {
        if (where.isNotBlank()) withStyle(SpanStyle(color = DesignTokens.Ink2)) { append("$where  ") }
        when (c.type) {
            "replace" -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = DesignTokens.Ink2)) { append(c.from ?: "") }
                append(" → ")
                withStyle(SpanStyle(color = OkGreen, fontWeight = FontWeight.Bold)) { append(c.to ?: "") }
            }
            "remove" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = AmdRed)) { append(c.from ?: "") }
            "insert" -> withStyle(SpanStyle(color = OkGreen, fontWeight = FontWeight.Bold)) { append("＋ ${c.to ?: ""}") }
            "retime" -> {
                append(c.name ?: "")
                if (c.from != c.to) append("  ${c.from} → ${c.to}")
                if (c.stayFrom != null && c.stayTo != null && c.stayFrom != c.stayTo)
                    append("  停留 ${c.stayFrom} → ${c.stayTo} 分")
            }
            "move" -> {
                append(c.name ?: "")
                withStyle(SpanStyle(color = AmdRed, fontWeight = FontWeight.Bold)) {
                    append("  第 ${c.fromDay} 天 ${c.from} → 第 ${c.day} 天 ${c.to}")
                }
            }
            else -> append("${c.type} ${c.name ?: c.to ?: c.from ?: ""}")
        }
    }
    Text(text, fontSize = 13.sp, color = DesignTokens.Ink)
}

/** @param onAnswer null＝這種提問還不能回覆（延誤），選項只顯示不能按 */
@Composable
private fun QuestionCard(q: AgentEvent.Question, onAnswer: ((String) -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("❓ ${AgentRouting.displayText(q.question)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
        q.options.forEach { o ->
            OutlinedButton(
                onClick = { onAnswer?.invoke(o) }, enabled = onAnswer != null,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            ) { Text(o, fontSize = 13.sp) }
        }
        if (onAnswer == null) Text(
            "延誤處理還不能直接回覆選項。可以先調整目前這站的預計離開時間或跳過一站，再按一次「請 AI 處理延誤」。",
            fontSize = 12.sp, color = DesignTokens.Ink2
        )
    }
}

@Composable
private fun EndNote(text: String, bg: Color, fg: Color) {
    Text(text, fontSize = 13.sp, color = fg, modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp))
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
