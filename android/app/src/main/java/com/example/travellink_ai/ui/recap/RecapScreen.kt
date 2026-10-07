package com.example.travellink_ai.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.util.SocialShare
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(
    item: LocalItinerary,
    onBack: () -> Unit,
    vm: RecapViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(item.id) { vm.load(item) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            // 完成/錯誤已在畫面內顯示，snackbar 只用來提示一次性訊息（如未登入）
            if (ui.phase == RecapViewModel.Phase.IDLE) { snackbar.showSnackbar(it); vm.clearMessage() }
        }
    }

    Scaffold(
        containerColor = DesignTokens.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("旅程回顧短片", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignTokens.Surface)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
          Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 素材摘要
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(DesignTokens.Surface).padding(16.dp)
            ) {
                Text("▶  ${ui.tripTitle.ifBlank { "旅程回顧" }}", fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                Spacer(Modifier.height(4.dp))
                Text("${ui.region.ifBlank { "—" }} · ${ui.dateLabel.ifBlank { "—" }}",
                    fontSize = 13.sp, color = DesignTokens.Ink2)
                Spacer(Modifier.height(8.dp))
                Text("${ui.stopCount} 個景點 · ${ui.photoCount} 張打卡照片",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Accent)
            }

            Text(
                "依景點順序、交通工具與旅程數據自動生成；有打卡照片的景點會在到站時插入。" +
                    "影片在雲端生成（1080×1920 直式），完成後存到相簿。",
                fontSize = 12.sp, color = DesignTokens.Ink2, lineHeight = 18.sp
            )

            if (!ui.configured && !ui.loading) {
                StatusBox("回顧短片功能尚未啟用（後端未設定）", DesignTokens.Accent2Light, DesignTokens.Accent2Dark)
            }

            // 狀態 / 進度
            when (ui.phase) {
                RecapViewModel.Phase.COLLECTING ->
                    ProgressLine("準備素材中…", null)
                RecapViewModel.Phase.RENDERING ->
                    ProgressLine("雲端產生中…  ${ui.progress}%", ui.progress / 100f)
                RecapViewModel.Phase.DOWNLOADING ->
                    ProgressLine("下載中…", null)
                RecapViewModel.Phase.DONE -> {
                    ui.message?.let { StatusBox(it, DesignTokens.AccentLight, DesignTokens.AccentDark) }
                    ui.videoUri?.let { uri ->
                        Spacer(Modifier.height(10.dp))
                        key(uri) { VideoPreview(uri) }
                    }
                }
                RecapViewModel.Phase.ERROR ->
                    ui.message?.let { StatusBox("⚠ $it", DesignTokens.RedLight, DesignTokens.Red) }
                RecapViewModel.Phase.IDLE -> {}
            }
          } // 內容捲動區結束

          Spacer(Modifier.height(12.dp))
          // 按鈕固定底部，不被高高的預覽推出畫面
          // 影片產生完會直接存相簿：Android 9 以下先拿儲存權限，免得做完才發現存不進去
          val saveGate = com.example.travellink_ai.util.rememberGallerySaveGate()
          Button(
              onClick = { saveGate { vm.generate(item) } },
              enabled = ui.configured && !ui.busy && ui.stopCount >= 2,
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
          ) {
              Text(
                  when {
                      ui.busy -> "產生中…"
                      ui.hasVideo -> "重新生成"
                      else -> "產生回顧短片"
                  },
                  fontSize = 15.sp, fontWeight = FontWeight.Bold
              )
          }
          // 分享到 Instagram 及其他社群（短片生成完成後才出現）
          if (ui.hasVideo) {
              val shareCtx = LocalContext.current
              val shareScope = rememberCoroutineScope()
              Spacer(Modifier.height(8.dp))
              OutlinedButton(
                  onClick = {
                      ui.videoUri?.let { u ->
                          android.widget.Toast.makeText(shareCtx, "準備分享影片…", android.widget.Toast.LENGTH_SHORT).show()
                          shareScope.launch { SocialShare.shareVideoSmart(shareCtx, u) }
                      }
                  },
                  modifier = Modifier.fillMaxWidth(),
                  colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                  border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Accent)
              ) {
                  Text("分享到 Instagram 及其他社群", fontSize = 15.sp, fontWeight = FontWeight.Bold)
              }
          }
          if (ui.stopCount in 1 until 2) {
              Text("這趟景點座標不足，無法產生短片", fontSize = 12.sp, color = DesignTokens.Ink3)
          }
        }
    }
}

/** 短片預覽（直式 9:16，靜音循環播放；點擊放大全螢幕）。 */
@Composable
private fun VideoPreview(uri: android.net.Uri) {
    var fullscreen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.Black)
                .clickable { fullscreen = true }
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoURI(uri)
                        // 小預覽靜音循環，不搶焦點；點擊才進全螢幕看
                        setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // 透明層攔截點擊（VideoView 本身會吃觸控）
            Box(Modifier.fillMaxSize().clickable { fullscreen = true })
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("⛶ 點擊放大", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp)
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            val mc = android.widget.MediaController(ctx).also { it.setAnchorView(this) }
                            setMediaController(mc)
                            setVideoURI(uri)
                            setOnPreparedListener { mp -> mp.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(9f / 16f)
                )
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Icon(Icons.Default.Close, "關閉", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String, progress: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, fontSize = 13.sp, color = DesignTokens.Ink)
        Spacer(Modifier.height(6.dp))
        if (progress != null) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusBox(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(12.dp)) {
        Text(text, fontSize = 13.sp, color = fg, lineHeight = 18.sp)
    }
}
