package com.example.travellink_ai.ui.planning

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.example.travellink_ai.data.model.IllustrationStopBoxes
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.StopBox
import com.example.travellink_ai.data.model.boxFor
import com.example.travellink_ai.data.model.sharingBox
import com.example.travellink_ai.data.model.hitTest
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.ui.trip.IllustrationPosition
import com.example.travellink_ai.ui.trip.fmtClock
import kotlinx.coroutines.delay

private const val MAX_ZOOM = 4f

/**
 * 互動插圖（教授建議 A）：可以雙指縮放、點圖上的站看景點卡；行程進行中標出「我」在哪。
 *
 * 站在圖上的位置來自 [boxes]（後端看圖找的框，0～1 比例）；沒有框時就是一般插圖，照樣能縮放。
 * 插圖用 Fit 完整顯示（不裁邊，邊緣的站才點得到），框與小點畫在同一個 graphicsLayer 裡跟著縮放。
 *
 * [compact]＝行程頁上的插圖卡：寬度撐滿、上下裁切，進行中把「我」所在的位置捲到卡片中間；
 * 不接手勢（整張卡由外層處理點擊，打開全螢幕）。
 */
@Composable
internal fun InteractiveIllustration(
    model: Any,
    stops: List<Stop>,
    boxes: IllustrationStopBoxes?,
    detecting: Boolean,
    position: IllustrationPosition?,
    doneStopIds: Set<String>,
    onStopClick: (Stop) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Unspecified) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val boxList = boxes?.boxes.orEmpty()
    // 卡片模式要捲到的位置（插圖高度的比例）：在站上看那一站、移動中看小點；沒有就從最上面（標題）開始
    val focusY: Float? = position?.let { p ->
        fun anchorY(stop: Stop?) = stop?.let { boxList.boxFor(it) }?.anchorY
        anchorY(p.atStop) ?: run {
            val a = anchorY(p.fromStop); val b = anchorY(p.toStop)
            if (a != null && b != null) a + (b - a) * p.ratio else b
        }
    }
    // 插圖在畫面上（未縮放時）實際佔的範圍：全螢幕 Fit 置中；卡片寬度撐滿、依 focusY 上下捲
    val targetFit: Rect? = remember(layoutSize, imageSize, compact, focusY) {
        if (layoutSize == IntSize.Zero || !imageSize.isSpecified || imageSize.width <= 0f) null
        else if (compact) {
            val w = layoutSize.width.toFloat(); val h = w * imageSize.height / imageSize.width
            val top = if (h <= layoutSize.height) (layoutSize.height - h) / 2f
                else (layoutSize.height / 2f - (focusY ?: 0f) * h).coerceIn(layoutSize.height - h, 0f)
            Rect(Offset(0f, top), Size(w, h))
        } else {
            val s = minOf(layoutSize.width / imageSize.width, layoutSize.height / imageSize.height)
            val w = imageSize.width * s; val h = imageSize.height * s
            Rect(Offset((layoutSize.width - w) / 2f, (layoutSize.height - h) / 2f), Size(w, h))
        }
    }
    // 卡片跟著小點往下捲時慢慢滑過去，不要一格一格跳
    val cardTop by animateFloatAsState(targetFit?.top ?: 0f, tween(900), label = "cardScroll")
    val fit: Rect? = targetFit?.let { if (compact) Rect(Offset(it.left, cardTop), it.size) else it }
    val stopById = remember(stops) { stops.associateBy { it.stopId } }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = layoutSize.width * (s - 1f) / 2f
        val maxY = layoutSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    /** 畫面上的點 → 未縮放的版面座標（graphicsLayer 以中心為原點縮放再平移） */
    fun toContent(p: Offset): Offset {
        val c = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
        return c + (p - c - offset) / scale
    }
    fun zoomAround(focus: Offset, newScale: Float) {
        val c = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
        val s = newScale.coerceIn(1f, MAX_ZOOM)
        offset = clampOffset(focus - c - (focus - c - offset) * (s / scale), s)
        scale = s
    }

    // ── 移動中的小點：只前進不後退、換一段路才重來；數值變動用動畫慢慢滑過去 ──
    val segKey = position?.let { "${it.fromStop?.stopId}>${it.toStop?.stopId}" }
    val dot = remember(segKey) { Animatable(position?.ratio ?: 0f) }
    LaunchedEffect(segKey, position?.ratio) {
        val target = position?.ratio ?: return@LaunchedEffect
        if (target > dot.value) dot.animateTo(target, tween(1500))
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "pulse"
    )

    val gestures = if (compact) Modifier else Modifier
            .pointerInput(boxList, fit) {
                detectTapGestures(
                    onDoubleTap = { p -> zoomAround(p, if (scale > 1.5f) 1f else 2.5f) },
                    onTap = { p ->
                        val r = fit ?: return@detectTapGestures
                        val q = toContent(p)
                        val nx = (q.x - r.left) / r.width
                        val ny = (q.y - r.top) / r.height
                        boxList.hitTest(nx, ny)?.let { b ->
                            pickSharedStop(boxList.sharingBox(b).mapNotNull { stopById[it.stopId] }, position, doneStopIds)
                                ?.let(onStopClick)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomAround(centroid, scale * zoom)
                    offset = clampOffset(offset + pan, scale)
                }
            }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { layoutSize = it }
            .then(gestures)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        ) {
            // 知道圖的尺寸之前先照一般方式顯示；之後放進算好的範圍，框和小點才對得準
            // 放在算好的範圍、維持原比例。卡片模式插圖比卡片高，不能用 size()（會被卡片高度壓扁，
            // 疊上去的小點和虛線就對不到圖上的路線），要照實際大小量、超出的部分由外層裁掉
            val imageModifier = if (fit == null) Modifier.fillMaxSize() else Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints.fixed(fit.width.roundToInt().coerceAtLeast(1), fit.height.roundToInt().coerceAtLeast(1)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(fit.left.roundToInt(), fit.top.roundToInt())
                }
            }
            AsyncImage(
                model = model,
                contentDescription = "AI 旅遊插圖",
                modifier = imageModifier,
                contentScale = if (fit != null) ContentScale.FillBounds else if (compact) ContentScale.Crop else ContentScale.Fit,
                onState = { st ->
                    if (st is AsyncImagePainter.State.Success) imageSize = st.painter.intrinsicSize
                }
            )
            if (fit != null && boxList.isNotEmpty() && position != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = fit
                    fun rectOf(b: StopBox) = Rect(
                        r.left + b.left * r.width, r.top + b.top * r.height,
                        r.left + b.right * r.width, r.top + b.bottom * r.height
                    )
                    fun centerOf(b: StopBox) = Offset(r.left + b.anchorX * r.width, r.top + b.anchorY * r.height)
                    val px = 1f / scale   // 線寬換算回螢幕上固定的粗細

                    // 已走完的站：淡淡蓋一層，看得出哪些去過了
                    boxList.filter { it.stopId in doneStopIds && it.stopId != position.atStop?.stopId }.forEach { b ->
                        val rr = rectOf(b)
                        drawRoundRect(Color.White.copy(alpha = 0.28f), rr.topLeft, rr.size, CornerRadius(12.dp.toPx() * px))
                    }

                    position.atStop?.let { here ->
                        boxList.boxFor(here)?.let { b ->
                            highlightBox(rectOf(b), pulse, px)
                        }
                    }

                    val from = position.fromStop?.let { boxList.boxFor(it) }
                    val to = position.toStop?.let { boxList.boxFor(it) }
                    if (to != null && (position.arrived || from == null)) highlightBox(rectOf(to), pulse, px)
                    if (from != null && to != null) {
                        val a = centerOf(from); val b = centerOf(to)
                        drawLine(
                            Color.White, a, b, strokeWidth = 6.dp.toPx() * px, cap = StrokeCap.Round
                        )
                        drawLine(
                            DesignTokens.Accent2, a, b, strokeWidth = 3.dp.toPx() * px, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx() * px, 8.dp.toPx() * px))
                        )
                        meDot(lerp(a, b, dot.value), pulse, px)
                    }
                }
            }
        }

        // ── 上方狀態列：進行中標「目前在／前往」，平常提示可以點 ──
        Column(
            Modifier
                .align(Alignment.TopStart)
                .then(if (compact) Modifier.padding(10.dp) else Modifier.statusBarsPadding().padding(start = 64.dp, top = 12.dp, end = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            position?.let { p -> StatusChip(p, onClick = { (p.atStop ?: p.toStop)?.let(onStopClick) }) }
            if (compact) return@Column
            var showHint by remember(boxes?.imageUrl) { mutableStateOf(true) }
            LaunchedEffect(boxes?.imageUrl) { delay(5_000); showHint = false }
            AnimatedVisibility(visible = detecting || (boxList.isNotEmpty() && showHint), enter = fadeIn(), exit = fadeOut()) {
                Surface(shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.55f)) {
                    Text(
                        if (detecting) "正在標出景點位置…" else "👆 點圖上的景點看詳細・雙指放大",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 13.sp, color = Color.White
                    )
                }
            }
        }
    }
}

private fun DrawScope.highlightBox(r: Rect, pulse: Float, px: Float) {
    val grow = 6.dp.toPx() * px * pulse
    drawRoundRect(
        DesignTokens.Accent2.copy(alpha = 0.35f * (1f - pulse)),
        Offset(r.left - grow, r.top - grow), Size(r.width + grow * 2, r.height + grow * 2),
        CornerRadius(14.dp.toPx() * px), style = Stroke(6.dp.toPx() * px)
    )
    drawRoundRect(
        DesignTokens.Accent2, r.topLeft, r.size, CornerRadius(12.dp.toPx() * px),
        style = Stroke(3.dp.toPx() * px)
    )
}

private fun DrawScope.meDot(c: Offset, pulse: Float, px: Float) {
    drawCircle(DesignTokens.Accent2.copy(alpha = 0.35f * (1f - pulse)), radius = (10.dp.toPx() + 14.dp.toPx() * pulse) * px, center = c)
    drawCircle(Color.White, radius = 10.dp.toPx() * px, center = c)
    drawCircle(DesignTokens.Accent2, radius = 7.dp.toPx() * px, center = c)
}

@Composable
private fun StatusChip(p: IllustrationPosition, onClick: () -> Unit) {
    val text = when {
        p.atStop != null -> "📍 目前在：${p.atStop.name}"
        p.toStop != null && p.arrived -> "📍 快到了：${p.toStop.name}，記得打卡"
        p.toStop != null -> "🚗 前往：${p.toStop.name}" + (p.etaMin?.let { " · 約 ${fmtClock(it)} 到" } ?: "")
        else -> return
    }
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Accent2Dark,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** 點插圖上的站彈出的景點卡：時間、停留、當天營業時間、導航、在地圖上看 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IllustrationStopSheet(
    stop: Stop,
    hours: String,
    status: String?,
    onNavigate: () -> Unit,
    onShowOnMap: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DesignTokens.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stop.emoji, fontSize = 28.sp)
                Text(stop.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink,
                    modifier = Modifier.weight(1f))
            }
            status?.let {
                Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.StatusOngoingText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stop.time.isNotBlank()) InfoPill("⏰ ${stop.time.substringAfterLast(" ")}")
                if (!stop.isStation && stop.duration > 0) InfoPill("停留 ${stop.duration} 分")
            }
            if (hours.isNotBlank() && hours != "未提供") {
                Text("🕐 $hours", fontSize = 14.sp, color = DesignTokens.Ink2)
            }
            if (stop.desc.isNotBlank()) {
                Text(stop.desc, fontSize = 15.sp, lineHeight = 21.sp, color = DesignTokens.Ink2)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onNavigate,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                ) {
                    Icon(Icons.Default.Navigation, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("導航", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onShowOnMap,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Map, null, Modifier.size(16.dp), tint = DesignTokens.Accent)
                    Spacer(Modifier.width(6.dp))
                    Text("在地圖上看", fontWeight = FontWeight.Bold, color = DesignTokens.Accent)
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(shape = RoundedCornerShape(999.dp), color = DesignTokens.AccentLight) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.AccentDark)
    }
}

/**
 * 同一個框對到好幾站（起點＝終點只畫一個「1／11」標記）時，點下去開哪一站：
 * 行程進行中優先開「目前在」或「要去」的那站，其次是還沒走過的；平常開第一站。
 */
internal fun pickSharedStop(candidates: List<Stop>, position: IllustrationPosition?, doneStopIds: Set<String>): Stop? {
    if (candidates.size <= 1 || position == null) return candidates.firstOrNull()
    val focusIds = listOfNotNull(position.atStop?.stopId, position.toStop?.stopId)
    return candidates.firstOrNull { it.stopId in focusIds }
        ?: candidates.firstOrNull { it.stopId !in doneStopIds }
        ?: candidates.first()
}
