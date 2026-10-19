package com.example.travellink_ai.ui.onboarding

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 各頁首次提示（coach mark）：畫面變暗、用聚光燈框出 [coachTarget] 標記的元件，旁邊說明它的用途。
 *
 * 用法：
 * 1. Activity 根部包一層 [CoachMarkHost]（提示畫在所有畫面之上）
 * 2. 要指的元件加 `Modifier.coachTarget(Tours.T_xxx)`
 * 3. 該頁放 `CoachTour(Tours.XXX, enabled = …)`：條件成立、沒看過就依序顯示，看完或跳過記為已看
 */
class CoachMarkState internal constructor() {
    /** 各目標在視窗中的位置（px） */
    internal val targets = mutableStateMapOf<String, Rect>()
    internal var active by mutableStateOf<ActiveTour?>(null)
}

internal class ActiveTour(val id: String, val steps: List<CoachStep>) {
    var index by mutableIntStateOf(0)
}

private const val TAG = "CoachTour"

val LocalCoachMarks = staticCompositionLocalOf<CoachMarkState?> { null }

@Composable
fun CoachMarkHost(content: @Composable () -> Unit) {
    val state = remember { CoachMarkState() }
    CompositionLocalProvider(LocalCoachMarks provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            state.active?.let { CoachOverlay(state, it) }
        }
    }
}

/** 標記提示要指的元件；離開畫面時自動移除 */
fun Modifier.coachTarget(id: String): Modifier = composed {
    val state = LocalCoachMarks.current ?: return@composed Modifier
    DisposableEffect(id) { onDispose { state.targets.remove(id) } }
    Modifier.onGloballyPositioned { state.targets[id] = it.boundsInWindow() }
}

/**
 * 一組提示。[enabled] 要在「畫面穩定、使用者可以操作」時才為 true（例如生成中、側欄滑動中都不要）。
 * 指向的元件不在畫面上的步驟會略過；一個都找不到就先不顯示，下次條件成立再試。
 */
@Composable
fun CoachTour(spec: TourSpec, enabled: Boolean) {
    val state = LocalCoachMarks.current ?: return
    val seen by Onboarding.seenTours.collectAsState()
    val welcome by Onboarding.showWelcome.collectAsState()
    val ready = enabled && !welcome && spec.id !in seen

    LaunchedEffect(ready) {
        if (!ready) {
            // 條件不成立了（換頁、開始生成…）：收起但不算看過，下次再出現
            if (state.active?.id == spec.id) {
                Log.d(TAG, "收起「${spec.id}」：條件不成立（enabled=$enabled）")
                state.active = null
            }
            return@LaunchedEffect
        }
        // 別組提示正在顯示就等它結束
        snapshotFlow { state.active == null }.first { it }
        // 等畫面排版穩定、使用者看清楚這頁再跳
        delay(700)
        // 剛啟動時畫面可能還在載入、元件還沒排版：等到至少一個目標出現（最多 5 秒），
        // 再多等一下讓其他目標也排好
        fun available() = spec.steps.filter { it.targetId == null || state.targets[it.targetId]?.isEmpty == false }
        val found = withTimeoutOrNull(5_000) {
            snapshotFlow { available().any { it.targetId != null } }.first { it }
        }
        if (found == null) {
            Log.d(TAG, "「${spec.id}」找不到任何目標，先不顯示（已排版：${state.targets.keys}）")
            return@LaunchedEffect
        }
        delay(300)
        if (state.active != null) return@LaunchedEffect
        val steps = available()
        Log.d(TAG, "顯示「${spec.id}」${steps.size}/${spec.steps.size} 步")
        state.active = ActiveTour(spec.id, steps)
    }
    DisposableEffect(spec.id) {
        onDispose { if (state.active?.id == spec.id) state.active = null }
    }
}

@Composable
private fun CoachOverlay(state: CoachMarkState, tour: ActiveTour) {
    val step = tour.steps[tour.index]
    val isLast = tour.index == tour.steps.lastIndex
    var origin by remember { mutableStateOf(Offset.Zero) }
    val pad = with(LocalDensity.current) { 6.dp.toPx() }
    val finish = {
        Log.d(TAG, "「${tour.id}」看完或跳過（第 ${tour.index + 1} 步）")
        Onboarding.markTourSeen(tour.id)
        state.active = null
    }
    BackHandler(onBack = finish)

    // 目標在這個覆蓋層裡的位置（四周留一點空隙）
    val hole = step.targetId?.let { state.targets[it] }
        ?.translate(-origin)
        ?.let { r -> Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            // 擋住底下的點擊：提示期間只能按「下一步／跳過」
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            hole?.let {
                val radius = CornerRadius(12.dp.toPx())
                drawRoundRect(Color.Transparent, it.topLeft, it.size, radius, blendMode = BlendMode.Clear)
                drawRoundRect(Color.White, it.topLeft, it.size, radius, style = Stroke(2.dp.toPx()))
            }
        }

        // 說明卡：目標下方放得下就放下方，否則放上方；沒有目標就置中
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                CoachBubble(
                    step = step,
                    position = "${tour.index + 1} / ${tour.steps.size}",
                    isLast = isLast,
                    onSkip = finish,
                    onNext = { if (isLast) finish() else tour.index++ }
                )
            }
        ) { measurables, constraints ->
            val margin = 16.dp.roundToPx()
            val gap = 12.dp.roundToPx()
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val bubbleMax = minOf(w - margin * 2, 340.dp.roundToPx()).coerceAtLeast(0)
            val p = measurables.first().measure(Constraints(maxWidth = bubbleMax))
            layout(w, h) {
                if (hole == null) {
                    p.place((w - p.width) / 2, (h - p.height) / 2)
                } else {
                    val x = (hole.center.x - p.width / 2f).roundToInt()
                        .coerceIn(margin, (w - p.width - margin).coerceAtLeast(margin))
                    val below = hole.bottom.roundToInt() + gap
                    val y = if (below + p.height <= h - margin) below
                    else (hole.top.roundToInt() - gap - p.height).coerceAtLeast(margin)
                    p.place(x, y)
                }
            }
        }
    }
}

@Composable
private fun CoachBubble(
    step: CoachStep,
    position: String,
    isLast: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(DesignTokens.Radius.Card),
        color = DesignTokens.Surface,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Text(step.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink,
                modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(6.dp))
            Text(step.text, fontSize = 15.sp, lineHeight = 22.sp, color = DesignTokens.Ink2,
                modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(position, fontSize = 13.sp, color = DesignTokens.Ink3)
                Spacer(Modifier.weight(1f))
                if (!isLast) TextButton(onClick = onSkip) {
                    Text("跳過", color = DesignTokens.Ink2, fontSize = 15.sp)
                }
                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(DesignTokens.Radius.Button),
                    colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                ) { Text(if (isLast) "知道了" else "下一步", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
