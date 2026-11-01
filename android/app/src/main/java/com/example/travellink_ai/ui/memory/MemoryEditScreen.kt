package com.example.travellink_ai.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.model.ownerLabels
import com.example.travellink_ai.ui.theme.DesignTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryEditScreen(
    item: LocalItinerary,
    onBack: () -> Unit,
    onMakeGrid: () -> Unit = {},
    onMakeRecap: () -> Unit = {},
    onRate: (() -> Unit)? = null,   // ⭐ 為這趟旅程評分（對齊網頁旅記的評分入口）
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val memory by viewModel.memory.collectAsState()
    val companions by viewModel.companions.collectAsState()
    val album by viewModel.photos.collectAsState()
    val canManageOthers by viewModel.canManageOthers.collectAsState()
    val stops by viewModel.orderedStops.collectAsState()
    val myUid = viewModel.myUid
    // 顯示名稱：撞名才加 uid 尾四碼（身分一律看 ownerUid）
    val labels = remember(album) { ownerLabels(album) }
    val photosByStop = remember(album) { album.groupBy { it.stopId } }
    // 站點已不存在（改過行程）或網頁端還沒分類的照片
    val unsorted = remember(album, stops) {
        val ids = stops.map { it.stopId }.toSet()
        album.filter { it.stopId !in ids }
    }
    val uploading by viewModel.uploading.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val batch by viewModel.batch.collectAsState()
    val sortReview by viewModel.sortReview.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(item.id, item.firestoreDocId) { viewModel.load(item) }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); viewModel.clearNotice() }
    }

    // 批次上傳並自動整理：Android 10 起先要 ACCESS_MEDIA_LOCATION（讀照片的拍攝地點），
    // 拒絕也照樣開選圖，只是只能依拍攝時間分類。
    // 這裡不用系統的相片挑選器（PickVisualMedia）：它回傳的 content://media/picker/… 網址
    // 系統不給原檔（setRequireOriginal 直接拋 UnsupportedOperationException），GPS 一律被遮掉。
    // 改用文件挑選器（OpenMultipleDocuments），回來的文件網址可轉成媒體庫網址再要原檔。
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.batchUpload(uris.take(BATCH_MAX)) }
    val launchBatchPicker = { batchPicker.launch(arrayOf("image/*")) }
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchBatchPicker() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val startBatch = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) mediaLocationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        else launchBatchPicker()
    }
    var showStudio by remember { mutableStateOf(false) }

    // 相簿多選 launcher（需知道目標站點，用暫存變數傳遞）
    var pickerStopId by remember { mutableStateOf("") }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty() && pickerStopId.isNotBlank()) {
            viewModel.addPhotos(pickerStopId, uris)
        }
    }

    // 放大檢視；刪除／改站權限：自己的照片，或我是 owner/editor（方案 B）。
    // 舊版 memories 的照片只有本人能刪，也還不能改站（搬進 photos 後才行）
    var previewPhoto by remember { mutableStateOf<TripPhoto?>(null) }
    var confirmDelete by remember { mutableStateOf<TripPhoto?>(null) }
    var movingPhoto by remember { mutableStateOf<TripPhoto?>(null) }
    val canDelete = { p: TripPhoto -> p.ownerUid == myUid || (!p.legacy && canManageOthers) }
    val canMove = { p: TripPhoto -> !p.legacy && (p.ownerUid == myUid || canManageOthers) }

    val save = { viewModel.save(); onBack() }
    // 先存好（九宮格/短片讀的是已儲存的照片），再進
    val makeGrid = { viewModel.save(); onMakeGrid() }
    val makeRecap = { viewModel.save(); onMakeRecap() }
    val tripTitle = memory.tripTitle.ifBlank { item.title.ifBlank { item.aiTitle } }
    val rate = { viewModel.save(); onRate?.invoke(); Unit }
    BackHandler2(onBack = save)

    Scaffold(
        containerColor = DesignTokens.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("旅程回憶", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
                        Text(
                            memory.tripTitle.ifBlank { item.title.ifBlank { item.aiTitle } },
                            fontSize = 13.sp, color = DesignTokens.Ink2, maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = save) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = DesignTokens.Ink)
                    }
                },
                actions = {
                    TextButton(onClick = save) {
                        Text("儲存", color = DesignTokens.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(
            // edge-to-edge 下鍵盤不會自動縮小內容，需 imePadding 讓聚焦的記事欄捲到鍵盤上方
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val companionCount = (album.map { it.ownerUid } + companions.map { it.ownerUid })
                    .filter { it.isNotBlank() && it != myUid }.distinct().size
                Text(
                    if (companionCount == 0)
                        "為每個造訪過的景點留下照片與心情紀錄 📸"
                    else
                        "為每個造訪過的景點留下照片與心情紀錄 📸\n" +
                            "這趟有 $companionCount 位旅伴也留了回憶，照片會一起收在同一個景點下",
                    fontSize = 14.sp, color = DesignTokens.Ink2,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // 順序對齊網頁旅記：評分 → 批次上傳並自動整理 → 製作旅程回憶
            if (onRate != null) item {
                OutlinedButton(
                    onClick = rate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Accent)
                ) {
                    Text(
                        if (item.feedbackSubmitted && item.overallRating > 0)
                            "⭐ 已評分 ${"★".repeat(item.overallRating)}（重新評分）"
                        else "⭐ 為這趟旅程評分",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = startBatch,
                    enabled = batch == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Accent)
                ) {
                    val b = batch
                    if (b != null) {
                        CircularProgressIndicator(
                            color = DesignTokens.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (b.reading) "讀取 ${b.total} 張照片的時間與地點…" else "上傳中 ${b.done}/${b.total}",
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text("📷 批次上傳並自動整理", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "一次選多張，依照片的拍攝時間與地點自動放到對應的景點",
                    fontSize = 12.sp, color = DesignTokens.Ink3,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
            item {
                // 九宮格與回顧短片合成一個入口（對齊網頁「✨ 製作旅程回憶」）
                Surface(
                    onClick = { showStudio = true },
                    shape = RoundedCornerShape(14.dp),
                    color = DesignTokens.Accent,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✨", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("製作旅程回憶", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                tripTitle + " · " + if (album.isEmpty()) "尚無照片" else "${album.size} 張照片",
                                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1
                            )
                        }
                        Text("→", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
            items(stops, key = { it.stopId }) { stop ->
                // 旅伴在這一站留下的短記（唯讀）
                val companionNotes = companions.mapNotNull { c ->
                    c.spots[stop.stopId]?.note?.takeIf { it.isNotBlank() }
                        ?.let { (labels[c.ownerUid] ?: c.ownerName) to it }
                }
                SpotMemoryCard(
                    stop = stop,
                    note = memory.spots[stop.stopId]?.note ?: "",
                    photos = photosByStop[stop.stopId].orEmpty(),
                    myUid = myUid,
                    labels = labels,
                    companionNotes = companionNotes,
                    isUploading = stop.stopId in uploading,
                    onNoteChange = { viewModel.setNote(stop.stopId, it) },
                    onAddPhoto = {
                        pickerStopId = stop.stopId
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPhotoClick = { previewPhoto = it }
                )
            }
            if (unsorted.isNotEmpty()) item(key = "unsorted") {
                UnsortedPhotosCard(
                    photos = unsorted, myUid = myUid, labels = labels,
                    onPhotoClick = { previewPhoto = it }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    previewPhoto?.let { target ->
        // 這站有兩張以上才需要挑代表照；已經排第一的就是代表照
        val stopPhotos = photosByStop[target.stopId].orEmpty()
        val isCover = stopPhotos.size > 1 && stopPhotos.first().dedupKey == target.dedupKey
        PhotoPreviewDialog(
            url = target.url,
            byName = if (target.ownerUid == myUid) null
                     else labels[target.ownerUid] ?: target.ownerName,
            canDelete = canDelete(target),
            canMove = canMove(target) && stops.size > 1,
            isCover = isCover,
            canSetCover = canMove(target) && stopPhotos.size > 1 && !isCover && target.stopId in stops.map { it.stopId },
            onSetCover = { previewPhoto = null; viewModel.setCover(target) },
            onDelete = {
                previewPhoto = null
                // 刪別人的照片要再確認一次，寫明是誰的
                if (target.ownerUid == myUid) viewModel.removePhoto(target) else confirmDelete = target
            },
            onMove = { previewPhoto = null; movingPhoto = target },
            onDismiss = { previewPhoto = null }
        )
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除旅伴的照片？") },
            text = {
                Text("這是 ${labels[target.ownerUid] ?: target.ownerName} 的照片，刪除後所有成員都看不到，且無法復原。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.removePhoto(target); confirmDelete = null }) {
                    Text("刪除", color = DesignTokens.Red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }

    movingPhoto?.let { target ->
        AlertDialog(
            onDismissRequest = { movingPhoto = null },
            title = { Text("移到哪個景點？") },
            text = {
                LazyColumn {
                    items(stops.filter { it.stopId != target.stopId }, key = { it.stopId }) { s ->
                        Text(
                            "${s.emoji.ifBlank { "📍" }}  ${s.name}",
                            fontSize = 15.sp, color = DesignTokens.Ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.movePhoto(target, s.stopId); movingPhoto = null }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movingPhoto = null }) { Text("取消") }
            }
        )
    }

    if (showStudio) {
        MemoryStudioSheet(
            title = tripTitle,
            photoCount = album.size,
            onGrid = { showStudio = false; makeGrid() },
            onRecap = { showStudio = false; makeRecap() },
            onDismiss = { showStudio = false }
        )
    }

    if (sortReview.isNotEmpty()) {
        PhotoSortReviewDialog(
            items = sortReview,
            stops = stops,
            onConfirm = viewModel::confirmSort,
            onDismiss = viewModel::dismissSort
        )
    }
}

/** 批次上傳一次最多選幾張（PickMultipleVisualMedia 上限不可超過系統的 100） */
private const val BATCH_MAX = 30

/** 「製作旅程回憶」：選 IG 個人檔案大圖或旅程回顧短片（對齊網頁 renderMemoryModes） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryStudioSheet(
    title: String,
    photoCount: Int,
    onGrid: () -> Unit,
    onRecap: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DesignTokens.Bg) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("製作旅程回憶", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "$title · " + if (photoCount == 0) "這趟目前還沒有照片" else "已找到 $photoCount 張照片",
                fontSize = 14.sp, color = DesignTokens.Ink2
            )
            Spacer(Modifier.height(16.dp))
            MemoryModeCard(
                icon = "▦",
                title = "IG 個人檔案大圖",
                desc = if (photoCount == 0) "至少需要一張照片，先替景點加照片吧"
                       else "產生 9 張獨立貼文，依順序發布後會在個人檔案組成一張大圖",
                enabled = photoCount > 0,
                onClick = onGrid
            )
            Spacer(Modifier.height(10.dp))
            MemoryModeCard(
                icon = "▶",
                title = "旅程回顧短片",
                desc = "用這趟的路線與照片自動生成直式短片",
                enabled = true,
                onClick = onRecap
            )
        }
    }
}

@Composable
private fun MemoryModeCard(icon: String, title: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) DesignTokens.AccentLight else DesignTokens.Surface2),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 22.sp, color = if (enabled) DesignTokens.Accent else DesignTokens.Ink3)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) DesignTokens.Ink else DesignTokens.Ink3)
                Spacer(Modifier.height(2.dp))
                Text(desc, fontSize = 13.sp, lineHeight = 18.sp, color = DesignTokens.Ink2)
            }
            if (enabled) Text("→", fontSize = 18.sp, color = DesignTokens.Accent)
        }
    }
}

/**
 * 自動分類沒把握的照片：逐張選景點（預設帶最可能的那站）。對齊網頁「確認照片所在景點」，
 * 但多了「不上傳」選項，網頁是留在本機稍後整理。
 */
@Composable
private fun PhotoSortReviewDialog(
    items: List<MemoryViewModel.SortItem>,
    stops: List<Stop>,
    onConfirm: (Map<android.net.Uri, String>) -> Unit,
    onDismiss: () -> Unit
) {
    // uri → stopId；null＝不上傳這張
    val choices = remember(items) { mutableStateMapOf<android.net.Uri, String?>().apply { items.forEach { put(it.uri, it.guessStopId) } } }
    val multiDay = stops.map { it.dayIndex }.distinct().size > 1
    val label = { s: Stop -> (if (multiDay) "第 ${s.dayIndex} 天 · " else "") + s.name }
    val chosen = choices.count { it.value != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("確認照片所在景點") },
        text = {
            Column {
                Text(
                    "這些照片缺少拍攝地點，或時間對不上行程，請選擇正確的景點。",
                    fontSize = 13.sp, color = DesignTokens.Ink2
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items, key = { it.uri.toString() }) { item ->
                        var open by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = item.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(DesignTokens.Surface2)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Box {
                                    val current = choices[item.uri]?.let { id -> stops.firstOrNull { it.stopId == id } }
                                    Text(
                                        (current?.let(label) ?: "不上傳這張") + " ▾",
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (current != null) DesignTokens.Accent else DesignTokens.Ink3,
                                        modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp)
                                    )
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        stops.forEach { s ->
                                            DropdownMenuItem(
                                                text = { Text(label(s)) },
                                                onClick = { choices[item.uri] = s.stopId; open = false }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("不上傳這張", color = DesignTokens.Ink3) },
                                            onClick = { choices[item.uri] = null; open = false }
                                        )
                                    }
                                }
                                Text(item.hint, fontSize = 12.sp, color = DesignTokens.Ink3)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen > 0,
                onClick = { onConfirm(choices.mapNotNull { (u, s) -> s?.let { u to it } }.toMap()) }
            ) { Text("上傳 $chosen 張", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("全部不上傳") }
        }
    )
}

/** 一張照片縮圖；別人的照片右下角標作者，這站的代表照（短片用）左上角標「代表」。 */
@Composable
private fun PhotoThumb(photo: TripPhoto, byName: String?, isCover: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DesignTokens.Surface2)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = photo.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isCover) {
            Text(
                "★ 代表",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DesignTokens.Accent2)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        if (byName != null) {
            Text(
                byName,
                fontSize = 10.sp,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

/** 還沒歸到任何現有景點的照片（網頁端待整理、或行程改過站）。 */
@Composable
private fun UnsortedPhotosCard(
    photos: List<TripPhoto>,
    myUid: String,
    labels: Map<String, String>,
    onPhotoClick: (TripPhoto) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗂", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "待整理", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = DesignTokens.Ink, modifier = Modifier.weight(1f)
                )
                Text("${photos.size} 張", fontSize = 13.sp, color = DesignTokens.Ink3)
            }
            Text(
                "點照片可移到對應的景點",
                fontSize = 12.sp, color = DesignTokens.Ink3,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.dedupKey }) { photo ->
                    PhotoThumb(
                        photo,
                        byName = if (photo.ownerUid == myUid) null else labels[photo.ownerUid] ?: photo.ownerName,
                        onClick = { onPhotoClick(photo) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotMemoryCard(
    stop: Stop,
    note: String,
    photos: List<TripPhoto>,
    myUid: String,
    labels: Map<String, String>,
    companionNotes: List<Pair<String, String>>,
    isUploading: Boolean,
    onNoteChange: (String) -> Unit,
    onAddPhoto: () -> Unit,
    onPhotoClick: (TripPhoto) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stop.emoji.ifBlank { "📍" }, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stop.name, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = DesignTokens.Ink, modifier = Modifier.weight(1f)
                )
                val total = photos.size
                val mineCount = photos.count { it.ownerUid == myUid }
                if (total > 0) {
                    Text(
                        if (mineCount == total) "$total 張"
                        else "$total 張（我 $mineCount）",
                        fontSize = 13.sp, color = DesignTokens.Ink3
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 照片列（整本相簿這一站的照片 + 新增磚）；別人的照片右下角標作者，
            // 兩張以上時第一張是代表照（回顧短片每站只用這張）
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.dedupKey }) { photo ->
                    PhotoThumb(
                        photo,
                        isCover = photos.size > 1 && photo.dedupKey == photos.first().dedupKey,
                        byName = if (photo.ownerUid == myUid) null else labels[photo.ownerUid] ?: photo.ownerName,
                        onClick = { onPhotoClick(photo) }
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DesignTokens.AccentLight)
                            .border(1.dp, DesignTokens.Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .clickable(enabled = !isUploading) { onAddPhoto() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                color = DesignTokens.Accent, strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AddAPhoto, contentDescription = "新增照片",
                                    tint = DesignTokens.Accent, modifier = Modifier.size(24.dp)
                                )
                                Text("加照片", fontSize = 11.sp, color = DesignTokens.Accent)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("寫下這裡的回憶…（最多 500 字）", color = DesignTokens.Ink3) },
                shape = RoundedCornerShape(12.dp),
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DesignTokens.Accent,
                    unfocusedBorderColor = DesignTokens.Border
                )
            )

            // 旅伴的短記（唯讀）
            companionNotes.forEach { (name, text) ->
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DesignTokens.Surface2)
                        .padding(10.dp)
                ) {
                    Text(
                        name, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = DesignTokens.Ink2, modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text, fontSize = 13.sp, color = DesignTokens.Ink2)
                }
            }
        }
    }
}

@Composable
private fun PhotoPreviewDialog(
    url: String,
    byName: String?,
    canDelete: Boolean,
    canMove: Boolean,
    isCover: Boolean = false,
    canSetCover: Boolean = false,
    onSetCover: () -> Unit = {},
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column {
                Box {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "關閉", tint = Color.White)
                    }
                }
                // 代表照：回顧短片每站只用一張（後端契約），用的就是這站排第一的照片
                if (isCover) {
                    Text(
                        "★ 這是這站的代表照，回顧短片會用這張",
                        fontSize = 13.sp, color = DesignTokens.Accent2Dark, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    )
                } else if (canSetCover) {
                    TextButton(onClick = onSetCover, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                        Text("★ 設為這站的代表照（回顧短片用這張）",
                            color = DesignTokens.Accent2Dark, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        byName?.let { "$it 拍的" } ?: "",
                        fontSize = 13.sp, color = DesignTokens.Ink3,
                        modifier = Modifier.weight(1f)
                    )
                    if (canMove) {
                        TextButton(onClick = onMove) {
                            Text("移到其他景點", color = DesignTokens.Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (canDelete) {
                        TextButton(onClick = onDelete) {
                            Text("刪除", color = DesignTokens.Red, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** 攔截系統返回鍵，離開前先存檔。 */
@Composable
private fun BackHandler2(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
