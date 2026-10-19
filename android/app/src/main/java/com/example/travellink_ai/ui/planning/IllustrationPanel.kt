package com.example.travellink_ai.ui.planning

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.travellink_ai.data.model.IllustrationStopBoxes
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.map.filterBusinessHoursForDate
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.ui.trip.IllustrationPosition
import com.example.travellink_ai.ui.trip.TripProgress
import com.example.travellink_ai.ui.trip.TripProgressViewModel
import com.example.travellink_ai.ui.trip.launchStopNavigation
import com.example.travellink_ai.ui.trip.rememberIllustrationPosition
import com.google.android.gms.maps.model.LatLng
import androidx.core.content.FileProvider
import com.example.travellink_ai.util.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 互動插圖畫面共用的資料：顯示中的那張插圖與它的站框、行程進行中的位置。
 * 多日行程有每日插圖時（[days] 非空）一天一張，[shownDay] 是目前顯示的那天。
 */
internal class IllustrationData(
    val model: Any?,
    val stops: List<Stop>,
    val boxes: IllustrationStopBoxes?,
    val detecting: Boolean,
    val generating: Boolean,
    val failed: Boolean,
    /** 多日插圖的進度（「已完成 1／3 天」） */
    val progressText: String,
    /** 多日行程的每一天（空＝單張插圖） */
    val days: List<Int>,
    val shownDay: Int?,
    /** 有圖的天 */
    val daysWithImage: Set<Int>,
    /** 這次沒畫成的天 */
    val failedDays: Set<Int>,
    val position: IllustrationPosition?,
    val progress: TripProgress
) {
    val hasImage: Boolean get() = model != null || daysWithImage.isNotEmpty()
    val doneStopIds: Set<String> get() = progress.checkIns.keys + progress.skips
}

/**
 * @param selectedDay 使用者選的那天；null＝自動（行程進行中看當天，否則第 1 天）
 */
@Composable
internal fun rememberIllustrationData(
    viewModel: ItineraryViewModel,
    tripVm: TripProgressViewModel,
    selectedDay: Int? = null
): IllustrationData {
    val backgroundUrl by viewModel.backgroundUrl.collectAsState()
    val visualState by viewModel.visualState.collectAsState()
    val generating by viewModel.imageGenerating.collectAsState()
    val itinerary by viewModel.itinerary.collectAsState()
    val progress by tripVm.progress.collectAsState()
    val remoteUrl by viewModel.remoteImageUrl.collectAsState()
    val allBoxes by viewModel.imageStopBoxes.collectAsState()
    val detecting by viewModel.stopBoxesDetecting.collectAsState()
    val imageDays by viewModel.imageDays.collectAsState()
    val dayBitmaps by viewModel.dayBitmaps.collectAsState()
    val failedDays by viewModel.failedDays.collectAsState()
    val progressText by viewModel.imageProgress.collectAsState()
    val singleModel: Any? = visualState.bitmap ?: backgroundUrl?.takeIf { it.isNotEmpty() }
    // 雲端插圖還沒有站框（舊行程、網頁畫的圖）就請後端找；同一張圖後端會直接回快取
    LaunchedEffect(remoteUrl, singleModel != null) { if (singleModel != null) viewModel.ensureStopBoxes() }

    val stops = itinerary?.stops.orEmpty()
    val tripDays = remember(stops) { stops.map { it.dayIndex }.distinct().sorted() }
    // 多日行程：有每日插圖（或這次剛畫好的）才一天一張；只有一張整趟的舊圖時照舊顯示那張
    val multi = tripDays.size > 1 && (imageDays.isNotEmpty() || dayBitmaps.isNotEmpty())
    val position = rememberIllustrationPosition(viewModel, tripVm)
    val positionDay = position?.let { (it.atStop ?: it.toStop)?.dayIndex }
    val shownDay = if (!multi) null else selectedDay?.takeIf { it in tripDays } ?: positionDay ?: tripDays.first()
    val entry = imageDays.firstOrNull { it.day == shownDay }

    return IllustrationData(
        model = if (multi) dayBitmaps[shownDay] ?: entry?.imageUrl else singleModel,
        stops = stops,
        boxes = if (multi) {
            // 第 1 天的站框也可能在 imageStopBoxes（編輯者畫的圖由後端找框，只寫那裡）
            entry?.boxes ?: allBoxes?.takeIf { entry != null && it.imageUrl == entry.imageUrl }
        } else {
            // 只用「這張插圖」的框：剛畫好還沒上傳時沒有雲端網址，舊框不能套上去
            allBoxes?.takeIf { remoteUrl != null && it.imageUrl == remoteUrl }
        },
        detecting = detecting,
        generating = generating,
        failed = visualState.isFailed,
        progressText = progressText,
        days = if (multi) tripDays else emptyList(),
        shownDay = shownDay,
        daysWithImage = if (multi) (imageDays.map { it.day } + dayBitmaps.keys).toSet() else emptySet(),
        failedDays = failedDays,
        // 小點只畫在「今天」那張：別天的圖上沒有這幾站（住宿同名時還會對錯天）
        position = position?.takeIf { !multi || positionDay == shownDay },
        progress = progress
    )
}

/** 點插圖上的站彈出的景點卡（含當天營業時間、行程進行中的狀態） */
@Composable
internal fun IllustrationStopSheetHost(
    stop: Stop?,
    data: IllustrationData,
    viewModel: ItineraryViewModel,
    onShowOnMap: (Stop) -> Unit,
    onDismiss: () -> Unit
) {
    stop ?: return
    val itinerary by viewModel.itinerary.collectAsState()
    val stopLocations by viewModel.stopLocations.collectAsState()
    val context = LocalContext.current
    val tripStart = itinerary?.days?.substringBefore(" ")?.takeIf { it.length == 10 } ?: ""
    val coord = stop.lat?.let { la -> stop.lng?.let { lo -> LatLng(la, lo) } } ?: stopLocations[stop.name]
    val day = if (tripStart.isEmpty()) "" else DayPlanner.addDays(tripStart, stop.dayIndex - 1)
    val progress = data.progress
    IllustrationStopSheet(
        stop = stop,
        hours = if (day.isEmpty()) stop.businessHours else filterBusinessHoursForDate(stop.businessHours, day),
        status = when {
            !progress.isOngoing -> null
            stop.stopId in progress.checkIns -> "✅ 已打卡"
            stop.stopId in progress.skips -> "⏭ 已跳過"
            stop.stopId == data.position?.toStop?.stopId -> "🚗 下一站"
            else -> null
        },
        onNavigate = { launchStopNavigation(context, stop.name, coord) },
        onShowOnMap = { onDismiss(); onShowOnMap(stop) },
        onDismiss = onDismiss
    )
}

/** 多日插圖的「第 1 天／第 2 天…」切換；沒有圖的天標灰 */
@Composable
private fun DayChips(data: IllustrationData, onSelectDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        data.days.forEach { day ->
            val selected = day == data.shownDay
            val has = day in data.daysWithImage
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onSelectDay(day) },
                shape = RoundedCornerShape(999.dp),
                color = if (selected) Color.White else Color.Black.copy(alpha = 0.55f)
            ) {
                Text(
                    "第 $day 天",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        selected -> DesignTokens.Ink
                        has -> Color.White
                        else -> Color.White.copy(alpha = 0.5f)
                    }
                )
            }
        }
    }
}

/** 這一天沒有插圖時的提示（多日行程某天沒畫成、或還在畫） */
@Composable
private fun MissingDay(data: IllustrationData, compact: Boolean, onDrawDay: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val day = data.shownDay
        Text(
            when {
                data.generating -> "🎨 第 $day 天繪製中…"
                day in data.failedDays -> "第 $day 天沒畫成"
                else -> "第 $day 天還沒有插圖"
            },
            fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold, color = Color.White
        )
        if (data.generating && data.progressText.isNotBlank())
            Text(data.progressText, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        if (!data.generating && onDrawDay != null) TextButton(onClick = onDrawDay) {
            Text("🎨 畫這一天", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 行程頁上的插圖卡：有圖時是一小塊可捲動到「我在哪」的預覽，點了開全螢幕；
 * 畫圖中顯示進度；還沒有圖時（規劃中、可編輯）是一張「畫成插圖」的邀請卡。
 * 都不符合（例如唯讀成員、進行中沒有圖）就不顯示。多日行程卡片下方可以切換天。
 */
@Composable
internal fun IllustrationCard(
    data: IllustrationData,
    canGenerate: Boolean,
    onOpen: () -> Unit,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    when {
        data.hasImage -> Box(
            modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(shape)
                .background(Color.Black)
                .clickable(onClick = onOpen)
        ) {
            if (data.model != null) InteractiveIllustration(
                model = data.model,
                stops = data.stops,
                boxes = data.boxes,
                detecting = false,
                position = data.position,
                doneStopIds = data.doneStopIds,
                onStopClick = { onOpen() },
                compact = true
            ) else MissingDay(data, compact = true, onDrawDay = null, modifier = Modifier.align(Alignment.Center))
            if (data.days.isNotEmpty()) DayChips(data, onSelectDay, Modifier.align(Alignment.BottomStart).padding(10.dp))
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                Text(
                    when {
                        data.generating && data.progressText.isNotBlank() -> "🎨 ${data.progressText}"
                        data.generating -> "🎨 重新繪製中…"
                        data.days.isNotEmpty() -> "⤢ 看大圖"
                        else -> "⤢ 看大圖・點景點"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = Color.White
                )
            }
        }
        data.generating -> Surface(modifier.fillMaxWidth(), shape = shape, color = Color.White) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🎨 正在把行程畫成插圖…", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                Text(data.progressText.ifBlank { "大約 30～60 秒，可以先做別的，畫好會出現在這裡。" },
                    fontSize = 13.sp, color = DesignTokens.Ink2)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = DesignTokens.Accent)
            }
        }
        canGenerate -> Surface(
            modifier.fillMaxWidth().clickable(onClick = if (data.failed) onRetry else onGenerate),
            shape = shape, color = Color.White
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎨", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (data.failed) "插圖生成失敗" else "把這趟畫成一張插圖",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                    Text(if (data.failed) "模型暫時無法使用，點這裡再試一次" else "AI 手繪旅遊地圖，可以點圖上的景點、出發後看自己走到哪",
                        fontSize = 13.sp, color = DesignTokens.Ink2)
                }
                Text(if (data.failed) "重試" else "生成", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Accent)
            }
        }
    }
}

/** 全螢幕互動插圖：按返回或左上角 ✕ 回行程頁；多日行程下方切換天 */
@Composable
internal fun IllustrationFullscreen(
    data: IllustrationData,
    viewModel: ItineraryViewModel,
    canGenerate: Boolean,
    onRegenerate: () -> Unit,
    onRegenerateDay: (Int) -> Unit,
    onSelectDay: (Int) -> Unit,
    onShowOnMap: (Stop) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<Stop?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Android 9 以下存相簿要先拿儲存權限
    val saveGate = com.example.travellink_ai.util.rememberGallerySaveGate()
    var saving by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val model = data.model
            // 換天時整個重建：縮放位置、小點動畫不要沿用上一天的
            if (model != null) key(data.shownDay) {
                InteractiveIllustration(
                    model = model,
                    stops = data.stops,
                    boxes = data.boxes,
                    detecting = data.detecting,
                    position = data.position,
                    doneStopIds = data.doneStopIds,
                    onStopClick = { selected = it }
                )
            } else MissingDay(
                data, compact = false,
                onDrawDay = data.shownDay?.takeIf { canGenerate }?.let { d -> { onRegenerateDay(d) } },
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
            ) { Icon(Icons.Default.Close, "關閉", tint = Color.White) }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                var menu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                ) { Icon(Icons.Default.MoreVert, "更多", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // 下載、分享（原本在地圖頁的插圖畫面，2026-10-19 搬來；唯讀成員也能用）。多日行程是目前這一天那張
                    DropdownMenuItem(
                        text = { Text(if (saving) "↓ 儲存中…" else "↓ 存到相簿") },
                        enabled = !saving && model != null,
                        onClick = {
                            menu = false
                            saveGate {
                                scope.launch {
                                    saving = true
                                    val ok = downloadItineraryImage(context, model as? Bitmap, model as? String)
                                    saving = false
                                    Toast.makeText(context, if (ok) "✅ 已儲存至相簿" else "❌ 儲存失敗，請重試", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("📤 分享") },
                        enabled = model != null,
                        onClick = {
                            menu = false
                            scope.launch { shareItineraryImage(context, model as? Bitmap, model as? String) }
                        }
                    )
                    if (canGenerate) {
                        HorizontalDivider()
                        val day = data.shownDay
                        if (day != null) DropdownMenuItem(
                            text = { Text("🎨 重畫第 $day 天") },
                            enabled = !data.generating,
                            onClick = { menu = false; onRegenerateDay(day) }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(when {
                                    data.generating -> "🎨 繪製中…"
                                    data.days.isNotEmpty() -> "🎨 全部重新繪製"
                                    else -> "🎨 重新繪製"
                                })
                            },
                            enabled = !data.generating,
                            onClick = { menu = false; onRegenerate() }
                        )
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (data.generating) Surface(shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.6f)) {
                    Text(
                        "🎨 " + data.progressText.ifBlank { "重新繪製中…畫好會自動換上" },
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, color = Color.White
                    )
                }
                if (data.days.isNotEmpty()) DayChips(data, onSelectDay)
            }
        }
        IllustrationStopSheetHost(selected, data, viewModel, onShowOnMap = onShowOnMap, onDismiss = { selected = null })
    }
}

/**
 * 生成／重新繪製插圖：卡通人物可以不填（預設「可愛的小旅人」）。
 * @param dayCount 多日行程的天數（每天一張）；[onlyDay] 只重畫那一天
 */
@Composable
internal fun GenerateIllustrationDialog(
    regenerate: Boolean,
    dayCount: Int,
    onlyDay: Int?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var character by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when {
                onlyDay != null -> "重畫第 $onlyDay 天"
                regenerate -> "重新繪製插圖"
                else -> "生成行程插圖"
            }, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        onlyDay != null -> "只重畫第 $onlyDay 天這一張，其他天保留。約 30～60 秒。"
                        dayCount > 1 -> "這是 $dayCount 天的行程，會每天畫一張（共 $dayCount 張，一次畫兩張，約 1～2 分鐘）" +
                            (if (regenerate) "，取代現在的插圖。" else "。")
                        regenerate -> "會依目前的行程重畫一張，取代現在這張。"
                        else -> "AI 會把這趟畫成一張手繪旅遊地圖，約 30～60 秒。"
                    },
                    fontSize = 14.sp, color = DesignTokens.Ink2
                )
                OutlinedTextField(
                    value = character,
                    onValueChange = { if (it.length <= 20) character = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("嚮導角色（選填）") },
                    placeholder = { Text("例：皮卡丘、柯南、卡皮巴拉") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(character.trim()) }) {
                Text(if (regenerate || onlyDay != null) "重新繪製" else "開始生成", color = DesignTokens.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ── 下載行程插圖至相簿 ──────────────────────────────────────────
private suspend fun downloadItineraryImage(
    context: android.content.Context,
    bitmap: Bitmap?,
    backgroundUrl: String?
): Boolean = withContext(Dispatchers.IO) {
    try {
        val bmp: Bitmap? = when {
            bitmap != null -> bitmap
            backgroundUrl?.startsWith("file://") == true ->
                android.graphics.BitmapFactory.decodeFile(
                    backgroundUrl.removePrefix("file://")
                )
            // 共編／雲端行程只有 Storage 網址（本機沒有檔案、也沒有剛生成的 bitmap）：
            // 過去直接回 false，這類行程的圖永遠存不了
            backgroundUrl?.startsWith("http") == true ->
                java.net.URL(backgroundUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            else -> null
        }
        if (bmp == null) {
            android.util.Log.w("TravelLink_Download", "拿不到圖片：bitmap=${bitmap != null}，url=${backgroundUrl?.take(60)}")
            return@withContext false
        }

        // 存檔邏輯統一走 MediaStoreSaver：原本這裡直接塞 RELATIVE_PATH，
        // 該欄位 API 29 才有，minSdk 26 的裝置會 insert 失敗。
        when (val r = MediaStoreSaver.saveJpeg(context, bmp, "TravelLink_${System.currentTimeMillis()}")) {
            is MediaStoreSaver.SaveResult.Gallery -> {
                android.util.Log.d("TravelLink_Download", "已存入相簿：${r.uri}")
                true
            }
            // 沒權限時已存進 App 私有目錄，但使用者在相簿找不到，不算成功
            is MediaStoreSaver.SaveResult.AppDirOnly -> false
            is MediaStoreSaver.SaveResult.Failed -> false
        }
    } catch (e: Exception) {
        android.util.Log.e("TravelLink_Download", "下載失敗", e)
        false
    }
}

// ── 分享行程插圖 ────────────────────────────────────────────────
private suspend fun shareItineraryImage(
    context: android.content.Context,
    bitmap: Bitmap?,
    backgroundUrl: String?
) = withContext(Dispatchers.IO) {
    try {
        val file: File? = when {
            bitmap != null -> {
                val f = File(context.cacheDir, "travellink_share.jpg")
                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                f
            }
            backgroundUrl?.startsWith("file://") == true ->
                File(backgroundUrl.removePrefix("file://"))
            // 旅伴或網頁畫的圖本機沒有檔案：先抓下來，分享出去才是圖片而不是一串網址
            backgroundUrl?.startsWith("http") == true -> runCatching {
                File(context.cacheDir, "travellink_share.jpg").also { f ->
                    java.net.URL(backgroundUrl).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }.getOrNull()
            else -> null
        }

        if (file?.exists() == true) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "分享行程插圖"))
            }
        } else if (!backgroundUrl.isNullOrEmpty()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, backgroundUrl)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "分享行程插圖"))
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("TravelLink_Share", "分享失敗: ${e.message}")
    }
}
