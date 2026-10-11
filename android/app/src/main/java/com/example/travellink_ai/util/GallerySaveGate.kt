package com.example.travellink_ai.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 存相簿前先拿權限：Android 10 以上走 MediaStore 不需要；9 以下要 WRITE_EXTERNAL_STORAGE。
 * 過去沒有任何地方要過這個權限，[MediaStoreSaver] 在 Android 9 的手機上每次都退回 App 私有目錄，
 * 行程圖、九宮格、回顧影片都存不進相簿。
 *
 * 用法：`val gate = rememberGallerySaveGate()`，按鈕裡呼叫 `gate { 真正的存檔 }`。
 * 已有權限就直接執行；沒有就先跳系統詢問，允許後才執行，拒絕則提示怎麼開啟。
 */
@Composable
fun rememberGallerySaveGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke()
        else Toast.makeText(
            context,
            "沒有儲存權限，無法存到相簿。可到系統設定 → 應用程式 → TravelLink 開啟。",
            Toast.LENGTH_LONG
        ).show()
    }
    return remember(launcher) {
        { action ->
            val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) action()
            else {
                pending = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
