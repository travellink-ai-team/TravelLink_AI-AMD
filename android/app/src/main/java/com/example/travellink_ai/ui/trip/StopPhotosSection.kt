package com.example.travellink_ai.ui.trip

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.model.ownerLabels
import com.example.travellink_ai.ui.theme.DesignTokens
import java.io.File

/** 景點資訊頁的拍照與選照片入口（打卡提示的「📸 拍照」也用同一個） */
class StopPhotoActions(val takePhoto: () -> Unit, val pickPhotos: () -> Unit)

@Composable
fun rememberStopPhotoActions(vm: StopPhotosViewModel, stop: Stop): StopPhotoActions {
    val context = LocalContext.current
    val currentStop by rememberUpdatedState(stop)
    // 相機開著時 Activity 可能被系統回收，暫存檔路徑要能跨重建保留
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = capturePath?.let(::File)
        capturePath = null
        if (file != null) {
            if (ok) vm.uploadCaptured(currentStop, file) else file.delete()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        vm.uploadPicked(currentStop, uris)
    }
    fun openCamera() {
        val file = vm.newCaptureFile()
        capturePath = file.absolutePath
        try {
            camera.launch(vm.uriFor(file))
        } catch (e: Exception) {   // 沒有相機 App，或相機權限仍被拒（SecurityException）
            capturePath = null
            file.delete()
            Toast.makeText(
                context, if (e is ActivityNotFoundException) "找不到相機 App" else "無法開啟相機", Toast.LENGTH_SHORT
            ).show()
        }
    }
    // App 的 manifest（QR 掃碼套件帶進來的）宣告了 CAMERA：有宣告就必須先拿到權限，
    // 否則呼叫系統相機會丟 SecurityException
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
        else Toast.makeText(context, "需要相機權限才能拍照，可到系統設定開啟。", Toast.LENGTH_LONG).show()
    }
    return remember(camera, picker, cameraPermission) {
        StopPhotoActions(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    openCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            pickPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }
}

/**
 * 「📸 這站的照片」：拍照、從相簿選、這一站的縮圖（自己和旅伴的），上傳中轉圈、失敗可重試。
 * 刪除與改分類留給旅程回憶頁（那裡有權限判斷）。
 *
 * @param cloudReady 行程有雲端文件才能寫共同相簿；純本地行程請使用者到旅程回憶上傳
 */
@Composable
fun StopPhotosSection(stop: Stop, vm: StopPhotosViewModel, actions: StopPhotoActions, cloudReady: Boolean) {
    val all by vm.photos.collectAsState()
    val pendingAll by vm.pending.collectAsState()
    val photos = remember(all, stop.stopId) {
        all.filter { it.stopId == stop.stopId }.sortedByDescending { it.capturedAt }
    }
    val labels = remember(all) { ownerLabels(all) }
    val pending = pendingAll.filter { it.stop.stopId == stop.stopId }
    val failedCount = pending.count { it.failed }
    var preview by remember { mutableStateOf<TripPhoto?>(null) }

    Text(
        "📸 這站的照片" + if (photos.isNotEmpty()) "（${photos.size} 張）" else "",
        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink
    )
    Spacer(Modifier.height(8.dp))
    if (!cloudReady) {
        Text("這份行程還沒存到雲端，照片請到「旅程回憶」上傳。", fontSize = 13.sp, color = DesignTokens.Ink2)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.takePhoto,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
        ) { Text("📸 拍照", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = actions.pickPhotos,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("🖼️ 從相簿選", fontSize = 14.sp, color = DesignTokens.Accent, fontWeight = FontWeight.Bold) }
    }
    Spacer(Modifier.height(10.dp))
    if (photos.isEmpty() && pending.isEmpty()) {
        Text("還沒有照片，拍一張留下回憶吧。旅伴拍的也會出現在這裡。", fontSize = 12.sp, color = DesignTokens.Ink3)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pending.forEach { p -> PendingThumb(p, onRetry = { vm.retry(p.id) }, onDiscard = { vm.discard(p.id) }) }
        photos.forEach { ph ->
            Thumb(ph, byName = labels[ph.ownerUid]?.takeIf { ph.ownerUid != vm.myUid }) { preview = ph }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (failedCount > 0) "有 $failedCount 張上傳失敗，點照片重試，按 × 放棄。"
        else "要刪除或移到其他景點，請到「旅程回憶」。",
        fontSize = 12.sp,
        color = if (failedCount > 0) DesignTokens.Red else DesignTokens.Ink3
    )

    preview?.let { ph ->
        PhotoDialog(ph, byName = labels[ph.ownerUid]?.takeIf { ph.ownerUid != vm.myUid }) { preview = null }
    }
}

private val THUMB = 84.dp

@Composable
private fun Thumb(photo: TripPhoto, byName: String?, onClick: () -> Unit) {
    Box(
        Modifier.size(THUMB).clip(RoundedCornerShape(12.dp)).background(DesignTokens.Surface2).clickable(onClick = onClick)
    ) {
        AsyncImage(model = photo.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (byName != null) {
            Text(
                byName, fontSize = 10.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PendingThumb(p: StopPhotosViewModel.PendingUpload, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Box(
        Modifier.size(THUMB).clip(RoundedCornerShape(12.dp)).background(DesignTokens.Surface2)
            .clickable(enabled = p.failed, onClick = onRetry)
    ) {
        AsyncImage(
            model = p.uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.5f)
        )
        if (p.failed) {
            Text(
                "重試", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .background(DesignTokens.Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDiscard),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, contentDescription = "放棄這張", tint = Color.White, modifier = Modifier.size(14.dp)) }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp, color = DesignTokens.Accent
            )
        }
    }
}

@Composable
private fun PhotoDialog(photo: TripPhoto, byName: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column {
                Box {
                    AsyncImage(
                        model = photo.url, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.Close, contentDescription = "關閉", tint = Color.White)
                    }
                }
                Text(
                    byName?.let { "$it 拍的" } ?: "我拍的",
                    fontSize = 13.sp, color = DesignTokens.Ink3,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
