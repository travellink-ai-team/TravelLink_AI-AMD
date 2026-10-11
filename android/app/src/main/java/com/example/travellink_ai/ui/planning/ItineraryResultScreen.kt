package com.example.travellink_ai.ui.planning

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.map.ItineraryMapScreen
import com.example.travellink_ai.util.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── 設計語言 ────────────────────────────────────────────────────
private val SAccent      = DesignTokens.Accent
private val SIndigo      = Color(0xFF6366F1)
private val SOverlayBg   = Color(0xFF1A1A2E).copy(alpha = 0.72f)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ItineraryResultScreen(
    viewModel: ItineraryViewModel,
    docId: String,
    initialShowImage: Boolean = false,
    openExport: Boolean = false
) {
    val collabVm: CollabViewModel = hiltViewModel()

    LaunchedEffect(docId) {
        // "current" 表示剛生成的行程，需用真實 docId 啟動 Firestore listener
        // 讓 owner 也能即時收到 joiner 的編輯更新
        val realDocId = if (docId == "current")
            viewModel.currentFirestoreDocIdPublic ?: docId
        else docId
        collabVm.loadItinerary(realDocId)
    }

    var isMapView by remember { mutableStateOf(!initialShowImage) }
    var showImageConfirmDialog by remember { mutableStateOf(false) }
    var posterCharacter by remember { mutableStateOf("") }   // 行程圖主角（卡通人物，對齊網頁 posterCharacterInput）
    var isDownloading by remember { mutableStateOf(false) }
    // Android 9 以下存相簿要先拿儲存權限（見 rememberGallerySaveGate）
    val saveGate = com.example.travellink_ai.util.rememberGallerySaveGate()
    var showShareSheet by remember { mutableStateOf(false) }

    val itinerary      by viewModel.itinerary.collectAsState()
    val visualState    by viewModel.visualState.collectAsState()
    val backgroundUrl  by viewModel.backgroundUrl.collectAsState()
    val onlineMembers  by collabVm.onlineMembers.collectAsState()
    val joinPin        by collabVm.joinPin.collectAsState()
    val hasImage = backgroundUrl != null
    val stops = itinerary?.stops ?: emptyList()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 從預覽頁「匯出行程圖」進來：已有插圖就直接顯示，沒有就開生成對話框。
    // 插圖網址由 confirmAndGenerateVisuals 非同步載入，先等一下再判斷，免得誤開生成對話框。
    LaunchedEffect(openExport) {
        if (!openExport) return@LaunchedEffect
        val url = kotlinx.coroutines.withTimeoutOrNull(1500) {
            androidx.compose.runtime.snapshotFlow { backgroundUrl }.first { it != null }
        }
        if (url != null) isMapView = false else showImageConfirmDialog = true
        // 用過即清，免得之後重進結果頁又自動跳出生成對話框
        viewModel.resultInitialView = if (url != null) "image" else "map"
    }

    // 系統返回鍵：在此頁攔截，否則會直接退出 App（而非回上一頁）。
    // 層次：對話框 → 插圖回地圖 → 地圖回預覽（與「編輯行程」按鈕一致）。
    BackHandler {
        when {
            showShareSheet         -> showShareSheet = false
            showImageConfirmDialog -> showImageConfirmDialog = false
            !isMapView             -> isMapView = true
            else                   -> viewModel.goBackToPreview()
        }
    }

    // ── 歷史行程資料新鮮度提醒（超過半年才出現一次）─────────────────
    val staleDataNotice by viewModel.staleDataNotice.collectAsState()
    LaunchedEffect(staleDataNotice) {
        val notice = staleDataNotice
        if (notice != null) {
            snackbarHostState.showSnackbar(
                message  = "⏳ $notice",
                duration = SnackbarDuration.Long
            )
            viewModel.dismissStaleDataNotice()
        }
    }

    // ── 邀請共編 Dialog（共用元件）──────────────────────────────
    if (showShareSheet) {
        CollabInviteDialog(
            shareLink    = collabVm.getShareLink(),
            joinPin      = joinPin,
            onlineMembers = onlineMembers,
            onDismiss    = { showShareSheet = false }
        )
    }

    // ── 生成插圖確認 Dialog（含卡通人物輸入，對齊網頁行程圖功能）──
    if (showImageConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImageConfirmDialog = false },
            title = { Text("生成行程插圖", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("請確認行程安排沒有問題後再進行圖片的生成！")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "輸入一個卡通人物，它會以嚮導身分出現在行程圖中",
                        fontSize = 13.sp,
                        color = Color(0xFF5A5750)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = posterCharacter,
                        onValueChange = { if (it.length <= 20) posterCharacter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("卡通人物名稱") },
                        placeholder = { Text("例：皮卡丘、柯南、卡皮巴拉") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = posterCharacter.isNotBlank(),
                    onClick = {
                        showImageConfirmDialog = false
                        isMapView = false
                        viewModel.generateImageIfNeeded(posterCharacter.trim())
                    }
                ) {
                    Text("確認生成", color = SIndigo, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImageConfirmDialog = false }) {
                    Text("再檢查一下")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── 主內容（地圖 / 插圖）─────────────────────────────
            AnimatedContent(
                targetState = isMapView,
                transitionSpec = { fadeIn() with fadeOut() }
            ) { targetIsMapView ->
                if (targetIsMapView) {
                    ItineraryMapScreen(viewModel = viewModel, stops = stops)
                } else {
                    AiItineraryScreen(viewModel = viewModel, docId = docId)
                }
            }

            // ── 載入中 spinner ───────────────────────────────────
            if (visualState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = SIndigo
                )
            }

            // ── 右上：按鈕群組（邀請共編 + 編輯行程）──────────────
            Row(
                modifier = Modifier
                    .padding(top = 12.dp, end = 16.dp)
                    .statusBarsPadding()
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 邀請共編圖示按鈕
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { showShareSheet = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color.White.copy(alpha = 0.88f),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = "邀請共編",
                            modifier = Modifier.size(18.dp),
                            tint = SIndigo
                        )
                    }
                }

                // 編輯行程按鈕
                Surface(
                    modifier = Modifier.clickable { viewModel.goBackToPreview() },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.88f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "編輯行程",
                            modifier = Modifier.size(13.dp),
                            tint = SAccent
                        )
                        Text(
                            "編輯行程",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SAccent
                        )
                    }
                }
            }

            // ── 底部操作列 ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                if (isMapView) {
                    // 地圖頁：置中「查看插圖/生成插圖」按鈕
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (hasImage) isMapView = false
                            else showImageConfirmDialog = true
                        },
                        modifier = Modifier.align(Alignment.Center),
                        containerColor = SIndigo,
                        contentColor = Color.White,
                        icon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        },
                        text = {
                            Text(if (hasImage) "查看插圖" else "生成插圖")
                        }
                    )
                } else {
                    // 插圖頁：左側「查看地圖」+ 右側「下載 + 分享」
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左：查看地圖 pill 按鈕
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = SOverlayBg,
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { isMapView = true }
                                    .padding(horizontal = 18.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "查看地圖",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // 右：下載 + 分享（僅有圖片時顯示）
                        if (hasImage) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 下載按鈕
                                Surface(
                                    shape = CircleShape,
                                    color = SOverlayBg,
                                    shadowElevation = 6.dp
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable(enabled = !isDownloading) {
                                                saveGate { coroutineScope.launch {
                                                    isDownloading = true
                                                    val success = downloadItineraryImage(
                                                        context,
                                                        visualState.bitmap,
                                                        backgroundUrl
                                                    )
                                                    isDownloading = false
                                                    snackbarHostState.showSnackbar(
                                                        if (success) "✅ 已儲存至相簿" else "❌ 儲存失敗，請重試"
                                                    )
                                                } }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "下載圖片",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                // 分享按鈕
                                Surface(
                                    shape = CircleShape,
                                    color = SOverlayBg,
                                    shadowElevation = 6.dp
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable {
                                                coroutineScope.launch {
                                                    shareItineraryImage(
                                                        context,
                                                        visualState.bitmap,
                                                        backgroundUrl
                                                    )
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "分享插圖",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
