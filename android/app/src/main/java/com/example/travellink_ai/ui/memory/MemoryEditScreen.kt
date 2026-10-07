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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(item.id, item.firestoreDocId) { viewModel.load(item) }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

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
            item {
                Surface(
                    onClick = makeGrid,
                    shape = RoundedCornerShape(14.dp),
                    color = DesignTokens.Accent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🖼", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("製作 IG 九宮格", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("把這趟照片排成主頁大圖", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        }
                        Text("→", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
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
                Surface(
                    onClick = makeRecap,
                    shape = RoundedCornerShape(14.dp),
                    color = DesignTokens.Accent2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎬", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("製作回顧短片", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("路線＋照片自動生成直式短片", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
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
        PhotoPreviewDialog(
            url = target.url,
            byName = if (target.ownerUid == myUid) null
                     else labels[target.ownerUid] ?: target.ownerName,
            canDelete = canDelete(target),
            canMove = canMove(target) && stops.size > 1,
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
}

/** 一張照片縮圖；別人的照片右下角標作者。 */
@Composable
private fun PhotoThumb(photo: TripPhoto, byName: String?, onClick: () -> Unit) {
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

            // 照片列（整本相簿這一站的照片 + 新增磚）；別人的照片右下角標作者
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.dedupKey }) { photo ->
                    PhotoThumb(
                        photo,
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
