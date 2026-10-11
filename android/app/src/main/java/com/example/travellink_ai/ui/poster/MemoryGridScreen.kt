package com.example.travellink_ai.ui.poster

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.example.travellink_ai.util.SocialShare
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.theme.DesignTokens

private val TEMPLATE_LABELS = listOf(
    "classic" to "原本",
    "grid6" to "整齊六宮",
    "collage5" to "錯落拼貼",
    "feature5" to "雜誌主打"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemoryGridScreen(
    item: LocalItinerary,
    onBack: () -> Unit,
    vm: MemoryGridViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(item.id) { vm.load(item) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val inPreview = ui.step == GridStep.PREVIEW
    // 系統返回：預覽→回編輯；編輯→回旅遊回憶
    BackHandler { if (inPreview) vm.backToEdit() else onBack() }

    Scaffold(
        containerColor = DesignTokens.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inPreview) "確認九張" else "製作 IG 九宮格",
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (inPreview) vm.backToEdit() else onBack() }) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Surface)
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (inPreview) PreviewContent(ui, vm) else EditContent(ui, vm)
        }
    }
}

// ── 編輯頁 ──────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditContent(ui: MemoryGridViewModel.UiState, vm: MemoryGridViewModel) {
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    var confirmTemplate by remember { mutableStateOf<String?>(null) }

    confirmTemplate?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmTemplate = null },
            title = { Text("換版面") },
            text = { Text("換版面會清掉你剛才調整的位置與大小，回到範本預設。要繼續嗎？") },
            confirmButton = {
                TextButton(onClick = { vm.selectTemplate(key); confirmTemplate = null }) { Text("換") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTemplate = null }) { Text("取消") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(3f / 4f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(12.dp))
                .background(DesignTokens.Surface2)
                .onSizeChanged { canvasPx = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = canvasPx.width.toFloat()
                        val h = canvasPx.height.toFloat()
                        // 按下即命中選中 —— 不必先點一下再拖曳
                        if (w > 0) vm.hitTest(down.position.x / w, down.position.y / h)
                        var moved = false
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (w > 0 && (pan != androidx.compose.ui.geometry.Offset.Zero || zoom != 1f)) {
                                if (pan != androidx.compose.ui.geometry.Offset.Zero)
                                    vm.moveSelected(pan.x / w, pan.y / h)
                                if (zoom != 1f) vm.scaleSelected(zoom)
                                moved = true
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (moved) vm.commitEdit()   // 停手補畫到最新（拖曳中已節流重繪，照片跟著動）
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val preview = ui.preview
            when {
                ui.loading -> CircularProgressIndicator()
                preview != null -> Image(
                    preview.asImageBitmap(), "九宮格預覽",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )
                else -> Text("選幾張照片開始", color = DesignTokens.Ink3, fontSize = 13.sp)
            }

            if (ui.showGrid && ui.preview != null) GridLinesOverlay()

            // 卡框：每張小卡虛線框（依照片實際長寬），選中的變橘色。標題選中才畫框。
            val layout = ui.layout
            if (layout != null && ui.preview != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    val idle = Color.White.copy(alpha = 0.55f)
                    val active = Color(0xFFE8733A)   // Accent2 橘

                    fun frame(cx: Float, cy: Float, w: Float, hRel: Float, color: Color, stroke: Float) {
                        val hw = w / 2 * size.width
                        val hh = hRel / 2 * size.height
                        drawRect(
                            color,
                            topLeft = Offset(cx * size.width - hw, cy * size.height - hh),
                            size = Size(hw * 2, hh * 2),
                            style = Stroke(width = stroke, pathEffect = dash)
                        )
                    }

                    layout.cards.forEachIndexed { i, c ->
                        val hRel = cardRelHeight(c, ui.photoRatios)
                        val on = ui.selection == GridSelection.Card(i)
                        frame(c.cx, c.cy, c.w, hRel, if (on) active else idle, if (on) 6f else 3f)
                    }
                    if (ui.selection == GridSelection.Title) {
                        layout.titleCard?.let { frame(it.cx, it.cy, it.w, it.h, active, 6f) }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        // 格線開關 + 重設版面
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Checkbox(checked = ui.showGrid, onCheckedChange = { vm.toggleGrid() })
            Text("顯示切線", fontSize = 12.sp, color = DesignTokens.Ink2)
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = { vm.resetLayout() }) {
                Text("↺ 重設版面", fontSize = 13.sp, color = DesignTokens.Accent)
            }
        }
        Text(
            when (ui.selection) {
                is GridSelection.Card -> "拖曳移動、雙指縮放這張照片"
                GridSelection.Title -> "拖曳移動標題、雙指縮放"
                GridSelection.Background -> "拖曳調整底圖露出的位置、雙指放大"
                null -> "點照片或標題選中；點空白處可調底圖；長按下方照片設為底圖"
            },
            fontSize = 11.sp, color = DesignTokens.Ink3,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(14.dp))
        Text("版面", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TEMPLATE_LABELS.forEach { (key, label) ->
                FilterChip(
                    selected = ui.templateKey == key,
                    onClick = {
                        if (ui.edited && key != ui.templateKey) confirmTemplate = key
                        else vm.selectTemplate(key)
                    },
                    label = { Text(label, fontSize = 13.sp) },
                    modifier = Modifier.padding(end = 8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DesignTokens.Accent, selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("這趟的照片", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
            Spacer(Modifier.width(8.dp))
            Text("已選 ${ui.selected.size} / ${ui.capacity}", fontSize = 12.sp, color = DesignTokens.Ink2)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(modifier = Modifier.weight(1f, fill = false)) {
            items(ui.photos, key = { it.photoId }) { ref ->
                val idx = ui.selected.indexOfFirst { it.photoId == ref.photoId }
                val on = idx >= 0
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp).size(88.dp)
                        .clip(RoundedCornerShape(10.dp)).background(DesignTokens.Surface2)
                        .then(if (on) Modifier.border(3.dp, DesignTokens.Accent, RoundedCornerShape(10.dp)) else Modifier)
                        .combinedClickable(
                            onClick = { vm.togglePhoto(ref) },
                            onLongClick = { vm.setAsBackground(ref) }
                        )
                ) {
                    AsyncImage(ref.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (on) {
                        val badge = if (idx == 0) "底" else "$idx"
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp)
                                .background(DesignTokens.Accent, CircleShape).size(22.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.goToPreview() },
            enabled = ui.selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
        ) { Text("下一步：預覽九張", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

/** 小卡在版面座標的相對高度（畫布 3:4 → ×0.75）。與 ViewModel 同公式。 */
private fun cardRelHeight(c: PhotoSlot, ratios: Map<String, Float>): Float {
    val ratio = c.aspect ?: c.photoRef?.url?.let { ratios[it] } ?: 1f
    return c.w * ratio * 0.75f
}

/** 編輯頁的九宮格切線疊層。 */
@Composable
private fun GridLinesOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.65f)
        val w = size.width; val h = size.height
        drawLine(c, Offset(w / 3, 0f), Offset(w / 3, h), 2f)
        drawLine(c, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), 2f)
        drawLine(c, Offset(0f, h / 3), Offset(w, h / 3), 2f)
        drawLine(c, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), 2f)
    }
}

// ── 預覽頁（對齊網頁「確認九張」）────────────────────────────────
@Composable
private fun PreviewContent(ui: MemoryGridViewModel.UiState, vm: MemoryGridViewModel) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("發布後你的個人檔案會長這樣", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        Text("同一張大圖切成九塊；格線是切的位置，數字是發布次序。",
            fontSize = 12.sp, color = DesignTokens.Ink2)
        Spacer(Modifier.height(10.dp))

        // 3×3 大圖 + 格線 + 發布次序
        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(3f / 4f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(8.dp)).background(DesignTokens.Surface2),
            contentAlignment = Alignment.Center
        ) {
            val large = ui.previewLarge
            if (large != null) {
                Image(large.asImageBitmap(), "九宮格", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                GridLinesOverlay()
                Column(Modifier.fillMaxSize()) {
                    for (row in 0..2) Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0..2) Box(Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                Modifier.align(Alignment.TopStart).padding(4.dp)
                                    .background(DesignTokens.Accent2, CircleShape).size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${9 - (row * 3 + col)}", color = Color.White,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else CircularProgressIndicator()
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(DesignTokens.Surface).border(1.dp, DesignTokens.Border, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column {
                Text("由右下角開始發布", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                Text("第 1 張在右下角，最後一張在左上角，主頁才會正確排列。",
                    fontSize = 12.sp, color = DesignTokens.Ink2, lineHeight = 17.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        SingleTilePreview(ui, vm)

        Spacer(Modifier.height(14.dp))
        if (ui.exportDone) {
            PublishOrderCard()
            Spacer(Modifier.height(10.dp))
        }
        val saveGate = com.example.travellink_ai.util.rememberGallerySaveGate()
        Button(
            onClick = { saveGate { vm.exportNine() } },
            enabled = !ui.exporting && ui.previewLarge != null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
        ) {
            Text(
                when {
                    ui.exporting -> "存檔中… ${ui.exportedCount} / 9"
                    ui.exportDone -> "已存到相簿（可再存一次）"
                    else -> "保存九張到相簿"
                },
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
        // 分享到 Instagram 及其他社群（存好九張後才出現）
        if (ui.exportDone && ui.exportedUris.isNotEmpty()) {
            val shareCtx = LocalContext.current
            OutlinedButton(
                onClick = { SocialShare.shareImages(shareCtx, ui.exportedUris) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Accent)
            ) {
                Text("分享到 Instagram 及其他社群", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "九宮格牆需依「發布順序」9→1 逐張發到 Instagram；分享後在 IG 依序貼即可。",
                fontSize = 11.sp, color = DesignTokens.Ink3,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

/** 單張檢視：切出目前 previewTileOrder 那一格放大看，左右切換。 */
@Composable
private fun SingleTilePreview(ui: MemoryGridViewModel.UiState, vm: MemoryGridViewModel) {
    val large = ui.previewLarge ?: return
    val order = ui.previewTileOrder
    val (row, col) = GridTiler.publishOrderToCell(order)

    val tile = remember(large, order) {
        val tw = large.width / 3; val th = large.height / 3
        runCatching { Bitmap.createBitmap(large, col * tw, row * th, tw, th) }.getOrNull()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("單張檢視", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        Spacer(Modifier.weight(1f))
        Text("第 $order 張 · ${positionLabel(row, col)}", fontSize = 12.sp, color = DesignTokens.Ink2)
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.setPreviewTile(if (order <= 1) 9 else order - 1) }) {
            Icon(Icons.Default.KeyboardArrowLeft, "上一張")
        }
        Box(
            Modifier.weight(1f).aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)).background(DesignTokens.Surface2),
            contentAlignment = Alignment.Center
        ) {
            if (tile != null) Image(tile.asImageBitmap(), "單張", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        IconButton(onClick = { vm.setPreviewTile(if (order >= 9) 1 else order + 1) }) {
            Icon(Icons.Default.KeyboardArrowRight, "下一張")
        }
    }
    Text(
        "每張單獨發到 IG 也要看得懂，別讓重要主體只剩一個角落。",
        fontSize = 11.sp, color = DesignTokens.Ink3
    )
}

private fun positionLabel(row: Int, col: Int): String {
    if (row == 1 && col == 1) return "正中"
    val v = listOf("上", "中", "下")[row]
    val h = listOf("左", "中", "右")[col]
    return "$v$h"
}

@Composable
private fun PublishOrderCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(DesignTokens.AccentLight).padding(14.dp)
    ) {
        Text("✓ 已存到相簿 · 發布順序", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.AccentDark)
        Spacer(Modifier.height(4.dp))
        Text("依這個順序發：第 1 張先發（排到主頁右下），最後發第 9 張（左上）。",
            fontSize = 12.sp, color = DesignTokens.AccentDark, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0..2) {
                    val order = 9 - (row * 3 + col)
                    val first = order == 1
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (first) DesignTokens.Accent else DesignTokens.Surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$order", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (first) Color.White else DesignTokens.Ink)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("九張發完前先別穿插其他貼文或 Reels；發滿後主頁就是完整大圖。",
            fontSize = 11.sp, color = DesignTokens.AccentDark, lineHeight = 16.sp)
    }
}
