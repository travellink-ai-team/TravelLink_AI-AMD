package com.example.travellink_ai.util

import android.content.Context
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * 台東離線底圖（PMTiles 向量圖磚，OpenStreetMap 圖資，Protomaps 切出）。
 *
 * 取代 Static Maps 預抓的長期方案：Google 條款不允許長期快取它的地圖，
 * 改用 ODbL 開放圖資，離線時由 MapLibre 直接讀本機檔，可縮放、平移、疊路線與目前位置。
 *
 * 檔案放 Storage `offline_maps/taitung-<版本>.pmtiles`（規則：登入可讀、App 不可寫），
 * 下載到 filesDir/offline_maps/taitung.pmtiles。換新版圖資時改 [VERSION] 並上傳新檔，
 * 已下載舊版的使用者會看到「有新版」。
 */
object OfflineBasemap {
    private const val TAG = "OfflineBasemap"
    const val VERSION = "20260929"
    private const val REMOTE_PATH = "offline_maps/taitung-$VERSION.pmtiles"
    const val APPROX_SIZE_MB = 19
    /** 圖資範圍（西、南、東、北），含綠島、蘭嶼 */
    val BOUNDS = doubleArrayOf(120.70, 21.90, 121.65, 23.46)

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val progress: Float) : State
        /** outdated＝已下載但不是目前版本（仍可用） */
        data class Ready(val outdated: Boolean) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "offline_maps").apply { if (!exists()) mkdirs() }
    fun file(context: Context) = File(dir(context), "taitung.pmtiles")
    private fun versionFile(context: Context) = File(dir(context), "taitung.version")

    fun isReady(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    /** 由檔案系統重算狀態（App 啟動、開側欄時呼叫；下載中不覆蓋） */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (isReady(context)) {
            val v = runCatching { versionFile(context).readText().trim() }.getOrDefault("")
            State.Ready(outdated = v != VERSION)
        } else State.NotDownloaded
    }

    // 下載不綁畫面：離開側欄或換頁都不會中斷
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startDownload(context: Context) {
        val app = context.applicationContext
        scope.launch { download(app) }
    }

    /** 下載到暫存檔，完成才換名，中途失敗不會留下半個檔案 */
    private suspend fun download(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0f)
        val tmp = File(dir(context), "taitung.pmtiles.part")
        try {
            val task = FirebaseStorage.getInstance().reference.child(REMOTE_PATH).getFile(tmp)
            task.addOnProgressListener { snap ->
                if (snap.totalByteCount > 0) {
                    _state.value = State.Downloading(snap.bytesTransferred.toFloat() / snap.totalByteCount)
                }
            }
            task.await()
            val dest = file(context)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) error("無法寫入離線地圖檔")
            versionFile(context).writeText(VERSION)
            _state.value = State.Ready(outdated = false)
            Log.d(TAG, "✅ 離線地圖已下載（${dest.length()} bytes）")
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "離線地圖下載失敗：${e.message}")
            _state.value = State.Failed(e.message ?: "下載失敗")
        }
    }

    fun delete(context: Context) {
        if (_state.value is State.Downloading) return
        file(context).delete()
        versionFile(context).delete()
        _state.value = State.NotDownloaded
    }
}
