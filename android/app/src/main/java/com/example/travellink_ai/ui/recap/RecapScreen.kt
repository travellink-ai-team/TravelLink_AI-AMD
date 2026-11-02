package com.example.travellink_ai.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import com.example.travellink_ai.ui.onboarding.coachTarget
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
    // 第一次打開：指向「旅程結束後自動做」的開關
    com.example.travellink_ai.ui.onboarding.CoachTour(com.example.travellink_ai.ui.onboarding.Tours.RECAP, enabled = !ui.loading)

    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(ui.toast) {
        ui.toast?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); vm.consumeToast() }
    }
    // 改過秒數或字幕之後換節奏、長度或主角：先問（推薦版會重排，改過的會清掉）
    var pendingChange by remember { mutableStateOf<(() -> Unit)?>(null) }
    pendingChange?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            text = { Text("這會重新排推薦版本，你改過的秒數與字幕會清掉。確定要換嗎？") },
            confirmButton = { TextButton(onClick = { action(); pendingChange = null }) { Text("換") } },
            dismissButton = { TextButton(onClick = { pendingChange = null }) { Text("取消") } }
        )
    }
    val confirmRebuild: (() -> Unit) -> Unit = { action -> if (ui.editor.edited) pendingChange = action else action() }
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
                    "可以選影片節奏、調整每段秒數與字幕。影片在雲端生成（1080×1920 直式），完成後存到相簿。",
                fontSize = 12.sp, color = DesignTokens.Ink2, lineHeight = 18.sp
            )

            // 影片節奏＋片段編輯器（推薦版時間軸，與網頁同一套）
            if (ui.editor.timeline != null) {
                TemplatePicker(ui.editor.templateId, enabled = !ui.busy) { id -> confirmRebuild { vm.setTemplate(id) } }
                if (ui.editor.isReels) ReelsOptions(ui.editor, enabled = !ui.busy, confirm = confirmRebuild, vm = vm)
                TimelineEditor(ui.editor, enabled = !ui.busy, vm = vm)
            }

            if (ui.autoFailed && !ui.hasVideo && !ui.busy) {
                StatusBox("旅程結束時自動產生沒有成功，可以按下方「產生回顧短片」手動再試。", DesignTokens.Accent2Light, DesignTokens.Accent2Dark)
            }

            // 旅程結束自動做（C7）：與側欄同一個設定
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DesignTokens.Surface)
                    .coachTarget(com.example.travellink_ai.ui.onboarding.Tours.T_RECAP_AUTO)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("旅程結束後自動做回顧短片", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                    Text("按「完成行程」後在背景做好、存到相簿並通知你；共編行程由擁有者做一次。",
                        fontSize = 12.sp, color = DesignTokens.Ink2, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.autoEnabled, onCheckedChange = { vm.setAutoEnabled(it) })
            }

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
                        MusicCard(ui, vm)
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
          // 分享到 IG 限動／Reels（短片生成完成後才出現）
          if (ui.hasVideo) {
              Spacer(Modifier.height(8.dp))
              ShareButtons(ui.videoUri, vm)
          }
          if (ui.stopCount in 1 until 2) {
              Text("這趟景點座標不足，無法產生短片", fontSize = 12.sp, color = DesignTokens.Ink3)
          }
        }
    }
}

/** 影片節奏：Reels（只在有照片的站之間播路線、照片當主角）＋四種第 1 版節奏（畫面相同，差在各段秒數；電影感開場的開頭不同） */
@Composable
private fun TemplatePicker(selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("影片節奏", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        RecapTimelines.TEMPLATES.forEach { t ->
            val active = t.id == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (active) DesignTokens.AccentLight else DesignTokens.Surface)
                    .border(1.dp, if (active) DesignTokens.Accent else DesignTokens.Border, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled && !active) { onPick(t.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = active, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                    Text(t.desc, fontSize = 12.sp, color = DesignTokens.Ink2, lineHeight = 16.sp)
                }
            }
        }
    }
}

/**
 * Reels 的選項：長度（放進全部照片／固定 15 秒）、主角景點、封面。
 * 長度與主角會重排推薦版（改過的先問）；封面只換開場，不影響其他片段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelsOptions(
    editor: RecapViewModel.EditorState, enabled: Boolean, confirm: (() -> Unit) -> Unit, vm: RecapViewModel
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DesignTokens.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("影片長度", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "放進全部照片（最長 30 秒）", "15" to "固定 15 秒").forEach { (id, label) ->
                FilterChip(
                    selected = editor.lengthMode == id, enabled = enabled,
                    onClick = { if (editor.lengthMode != id) confirm { vm.setLengthMode(id) } },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        Text(
            if (editor.lengthMode == "15") "放不下的照片會自動挑：主角景點最多 3 張、其他站各 1 張。"
            else "照片越多影片越長；超過 30 秒時會自動減少照片。",
            fontSize = 11.sp, color = DesignTokens.Ink3, lineHeight = 15.sp
        )
        if (editor.photoStops.isNotEmpty()) {
            StopPicker("主角景點", "自動挑（照片最多的站）", editor.heroStopId, editor.photoStops, enabled) { id -> confirm { vm.setHero(id) } }
            StopPicker("封面", "行程插圖", editor.coverStopId, editor.photoStops, enabled) { id -> vm.setCover(id) }
            if (editor.coverPhotos.size > 1) CoverPhotoPicker(editor.coverPhotos, editor.coverPhotoId, enabled) { vm.setCoverPhoto(it) }
            Text("主角景點停留比較久、照片可以放多張；封面可以點縮圖換成那站的任一張。沒有行程插圖時，封面用第一張照片。",
                fontSize = 11.sp, color = DesignTokens.Ink3, lineHeight = 15.sp)
        }
    }
}

/** 封面站的縮圖：點一張當封面（框起來的是目前用的那張） */
@Composable
private fun CoverPhotoPicker(photos: List<Pair<String, String>>, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    Row(Modifier.padding(start = 72.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEach { (id, url) ->
            val on = id == selected
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(DesignTokens.Border)
                    .border(if (on) 3.dp else 0.dp, if (on) DesignTokens.Accent else DesignTokens.Border, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && !on) { onPick(id) }
            ) {
                coil.compose.AsyncImage(model = url, contentDescription = if (on) "目前的封面" else "設為封面",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (on) 3.dp else 0.dp))
            }
        }
    }
}

/** 從有照片的站裡挑一站（第一項是預設值，選它＝null） */
@Composable
private fun StopPicker(
    title: String, defaultLabel: String, selected: String?, stops: List<RecapViewModel.PhotoStop>,
    enabled: Boolean, onPick: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(stops.firstOrNull { it.stopId == selected }?.name ?: defaultLabel, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("▾", fontSize = 12.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { open = false; if (selected != null) onPick(null) })
                stops.forEach { s ->
                    DropdownMenuItem(text = { Text("${s.name}（${s.count} 張）") },
                        onClick = { open = false; if (s.stopId != selected) onPick(s.stopId) })
                }
            }
        }
    }
}

private fun fmtSec(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/**
 * 片段卡片編輯器（與網頁相同：單欄卡片，不做多軌時間軸）：每段 ±0.5 秒、加字幕、移除照片片段、
 * 一步復原、還原推薦版本、AI 寫字幕。路線順序依行程固定。修改自動存在這台裝置。
 */
@Composable
private fun TimelineEditor(editor: RecapViewModel.EditorState, enabled: Boolean, vm: RecapViewModel) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DesignTokens.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (editor.edited) "已編輯版本" else "推薦版本"}：共 ${fmtSec(editor.totalSec)} 秒 · ${editor.rows.size} 段" +
                    if (editor.photoClips > 0) " · ${editor.photoClips} 張照片" else "（沒有打卡照片，以路線為主）",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink, modifier = Modifier.weight(1f)
            )
            Text(if (open) "收合 ▴" else "看片段 ▾", fontSize = 12.sp, color = DesignTokens.Accent)
        }
        if (editor.resetNotice) {
            Text("行程或照片有變動，已重新排推薦版本。", fontSize = 12.sp, color = DesignTokens.Accent2Dark)
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(6000); vm.dismissResetNotice() }
        }
        if (!open) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.aiCaptions() }, enabled = enabled && !editor.aiBusy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(if (editor.aiBusy) "撰寫中…" else "✨ AI 寫字幕", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.undo() }, enabled = enabled && editor.canUndo,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("↶ 復原", fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.resetTimeline() }, enabled = enabled && editor.edited,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("↺ 還原推薦", fontSize = 12.sp) }
        }
        editor.rows.forEach { row -> ClipCard(row, editor.revision, enabled, editor.photoUrls, vm) }
        Text("路線順序依行程固定；可調整每段秒數、加字幕或移除照片" +
            (if (editor.isReels) "（移除後前後的路線會自動接起來）" else "") + "。修改會自動保存在這支手機。" +
            "AI 字幕只根據景點、天數與旅記備註撰寫，不會編造沒發生的事。",
            fontSize = 11.sp, color = DesignTokens.Ink3, lineHeight = 15.sp)
    }
}

@Composable
private fun ClipCard(row: RecapTimelines.Row, revision: Int, enabled: Boolean, photoUrls: Map<String, String>, vm: RecapViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DesignTokens.Bg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.icon, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text(row.text, fontSize = 13.sp, color = DesignTokens.Ink, modifier = Modifier.weight(1f))
        }
        // Reels 一站多張：縮圖，可以個別拿掉（秒數依張數等比例縮短）
        if (row.type == "photo" && row.assetRefs.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.assetRefs.forEachIndexed { i, ref ->
                    val url = ref.photoId?.let { photoUrls[it] }
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(DesignTokens.Border)) {
                        if (url != null) coil.compose.AsyncImage(model = url, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text("${i + 1}", fontSize = 12.sp, color = DesignTokens.Ink2, modifier = Modifier.align(Alignment.Center))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(0x99000000))
                                .clickable(enabled = enabled) { vm.removeClipPhoto(row.clipId, i) },
                            contentAlignment = Alignment.Center
                        ) { Text("✕", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.stepClip(row.clipId, -500) }, enabled = enabled,
                modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 16.sp) }
            Text("${fmtSec(row.seconds)} 秒", fontSize = 13.sp, color = DesignTokens.Ink, modifier = Modifier.widthIn(min = 48.dp))
            OutlinedButton(onClick = { vm.stepClip(row.clipId, 500) }, enabled = enabled,
                modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) { Text("＋", fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            if (row.type == "photo") TextButton(onClick = { vm.removeClip(row.clipId) }, enabled = enabled) {
                Text("移除", fontSize = 12.sp, color = DesignTokens.Red)
            }
        }
        if (row.type in RecapTimelines.CAPTION_TYPES) {
            // 打字時用本地狀態（模型裡的字幕會去掉頭尾空白），AI／復原／還原時（revision 變了）才換成新內容
            var text by remember(row.clipId, revision) { mutableStateOf(row.caption) }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    if (v.codePointCount(0, v.length) <= RecapTimelines.CAPTION_MAX) { text = v; vm.captionClip(row.clipId, v) }
                },
                enabled = enabled,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                placeholder = { Text("字幕（選填，${RecapTimelines.CAPTION_MAX} 字內）", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 分享（C5）：限動先複製網址（到 IG 加「連結」貼紙貼上），Reels 先複製說明文案（說明欄的連結點不了，網址寫成文字）。
 * 沒裝 IG 就開系統分享選單；「其他 App」一直是系統選單。
 */
@Composable
private fun ShareButtons(videoUri: android.net.Uri?, vm: RecapViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun share(target: SocialShare.IgTarget?) {
        val u = videoUri ?: return
        val hint = when (target) {
            SocialShare.IgTarget.STORY -> { clipboard.setText(androidx.compose.ui.text.AnnotatedString(RecapShare.WEB_URL)); "已複製網址：到限動加「連結」貼紙貼上" }
            SocialShare.IgTarget.REEL -> { clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.shareCaption())); "已複製說明文字：貼到 Reels 說明欄" }
            null -> "準備分享影片…"
        }
        android.widget.Toast.makeText(ctx, hint, android.widget.Toast.LENGTH_LONG).show()
        scope.launch {
            val opened = target != null && SocialShare.shareVideoToInstagram(ctx, u, target)
            if (!opened) {
                if (target != null) android.widget.Toast.makeText(ctx, "這支手機沒有 Instagram，改用其他 App 分享", android.widget.Toast.LENGTH_SHORT).show()
                SocialShare.shareVideoSmart(ctx, u)
            }
        }
    }
    val border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.Accent)
    val colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { share(SocialShare.IgTarget.STORY) }, modifier = Modifier.weight(1f), colors = colors, border = border) {
            Text("分享到 IG 限動", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { share(SocialShare.IgTarget.REEL) }, modifier = Modifier.weight(1f), colors = colors, border = border) {
            Text("分享到 Reels", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
    TextButton(onClick = { share(null) }, modifier = Modifier.fillMaxWidth()) {
        Text("其他 App 分享…", fontSize = 13.sp, color = DesignTokens.Ink2)
    }
}

/** 配樂建議（C6）：氛圍＋理由＋3 個 IG 音樂庫搜尋關鍵字，點關鍵字就複製 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MusicCard(ui: RecapViewModel.UiState, vm: RecapViewModel) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DesignTokens.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🎵 配樂建議（到 IG 發布時加入）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
        val m = ui.music
        when {
            ui.musicBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("AI 看照片挑氛圍中…", fontSize = 13.sp, color = DesignTokens.Ink2)
            }
            m != null -> {
                Text(m.mood, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Accent)
                if (m.reason.isNotEmpty()) Text(m.reason, fontSize = 13.sp, color = DesignTokens.Ink2, lineHeight = 18.sp)
                Text("在 IG 加音樂時搜尋（點一下複製）：", fontSize = 12.sp, color = DesignTokens.Ink3)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.keywords.forEach { k ->
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(DesignTokens.AccentLight)
                                .border(1.dp, DesignTokens.Accent, RoundedCornerShape(16.dp))
                                .clickable {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(k))
                                    android.widget.Toast.makeText(ctx, "已複製「$k」", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text(k, fontSize = 13.sp, color = DesignTokens.AccentDark) }
                    }
                }
                ui.musicError?.let { Text(it, fontSize = 12.sp, color = DesignTokens.Red) }
                TextButton(onClick = { vm.suggestMusic(force = true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("↻ 換一組建議", fontSize = 12.sp)
                }
            }
            else -> {
                ui.musicError?.let { Text(it, fontSize = 12.sp, color = DesignTokens.Red) }
                OutlinedButton(onClick = { vm.suggestMusic() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("✨ 取得配樂建議", fontSize = 13.sp)
                }
            }
        }
        Text("只建議曲風與搜尋關鍵字，不指定歌名（IG 音樂庫依地區與帳號類型不同）。",
            fontSize = 11.sp, color = DesignTokens.Ink3, lineHeight = 15.sp)
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
