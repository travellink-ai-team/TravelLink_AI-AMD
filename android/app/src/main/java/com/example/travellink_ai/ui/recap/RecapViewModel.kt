package com.example.travellink_ai.ui.recap

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.util.MediaStoreSaver
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 回顧短片：收集 → 呼叫後端 render → 輪詢 → 下載 mp4 → 存相簿。
 * 所有渲染在後端，App 只串流程（見 RecapRepository / RecapApiClient）。
 */
@HiltViewModel
class RecapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RecapRepository,
    private val api: RecapApiClient,
    private val auth: FirebaseAuth
) : ViewModel() {

    enum class Phase { IDLE, COLLECTING, RENDERING, DOWNLOADING, DONE, ERROR }

    data class UiState(
        val loading: Boolean = true,
        val configured: Boolean = false,
        val tripTitle: String = "",
        val region: String = "",
        val dateLabel: String = "",
        val stopCount: Int = 0,
        val photoCount: Int = 0,
        val phase: Phase = Phase.IDLE,
        val progress: Int = 0,
        /** 影片位置（雲端已存的、或剛生成的本機），供下方預覽播放。 */
        val videoUri: Uri? = null,
        /** 進頁時讀到的雲端短片；outdated 用它的 sig 比對當前行程。 */
        val existingRecap: CloudRecap? = null,
        val currentSig: String = "",
        val message: String? = null
    ) {
        val busy: Boolean get() = phase == Phase.COLLECTING || phase == Phase.RENDERING || phase == Phase.DOWNLOADING
        /** 有影片可看（雲端或剛生成）。 */
        val hasVideo: Boolean get() = videoUri != null
        /** 雲端影片是舊的（行程已改，站點簽章不符）。 */
        val outdated: Boolean get() = existingRecap != null && existingRecap.sig != currentSig
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun load(item: LocalItinerary) {
        viewModelScope.launch {
            val trip = repo.buildTrip(item)
            val cloud = item.firestoreDocId?.takeIf { it.isNotBlank() }.orEmpty()
            val room = cloud.ifBlank { "local_${item.id}" }
            val photos = runCatching { repo.buildPhotos(trip, cloud, room) }.getOrDefault(emptyList())
            // 進頁讀雲端已存的短片，離開再回來就直接看得到
            val cloudRecap = if (cloud.isNotBlank())
                runCatching { repo.readCloudRecap(cloud) }.getOrNull() else null
            val currentSig = repo.sigOf(trip)
            _ui.value = UiState(
                loading = false,
                configured = api.isConfigured,
                tripTitle = trip.title,
                region = trip.region,
                dateLabel = trip.dateLabel,
                stopCount = trip.stops.size,
                photoCount = photos.size,
                existingRecap = cloudRecap,
                currentSig = currentSig,
                videoUri = cloudRecap?.videoUrl?.let { Uri.parse(it) },
                phase = if (cloudRecap != null) Phase.DONE else Phase.IDLE,
                message = when {
                    cloudRecap != null && cloudRecap.sig != currentSig -> "行程有更新，可按「重新生成」更新短片"
                    cloudRecap != null -> "這是先前生成的短片"
                    else -> null
                }
            )
        }
    }

    fun generate(item: LocalItinerary) {
        val cur = _ui.value
        if (cur.busy) return
        if (!api.isConfigured) {
            _ui.value = cur.copy(message = "回顧短片功能尚未啟用（後端未設定）")
            return
        }
        val user = auth.currentUser
        if (user == null) {
            _ui.value = cur.copy(message = "請先登入再產生回顧短片")
            return
        }

        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(phase = Phase.COLLECTING, progress = 0, message = null)
                val trip = repo.buildTrip(item)
                if (trip.stops.size < 2) {
                    _ui.value = _ui.value.copy(phase = Phase.ERROR, message = "這趟景點座標不足，無法產生")
                    return@launch
                }
                val cloud = item.firestoreDocId?.takeIf { it.isNotBlank() }.orEmpty()
                val room = cloud.ifBlank { "local_${item.id}" }
                val photos = repo.buildPhotos(trip, cloud, room)
                val routePoints = repo.buildRoutePoints(trip, item, cloud)
                val token = user.getIdToken(false).await().token
                    ?: error("取得登入憑證失敗")

                _ui.value = _ui.value.copy(phase = Phase.RENDERING, progress = 0)
                val job = withContext(Dispatchers.IO) {
                    api.createJob(RecapRenderRequest(trip, photos, routePoints), token)
                }

                // 輪詢（約 1.2 秒一次，最多 6 分鐘）
                var tries = 0
                var status = RecapJobStatus(status = "queued")
                while (!status.isTerminal && tries < 300) {
                    delay(1200)
                    status = withContext(Dispatchers.IO) { api.getStatus(job.jobId, token) }
                    _ui.value = _ui.value.copy(progress = status.progress)
                    tries++
                }
                if (status.isError) error(status.error ?: "後端產生失敗")
                if (!status.isDone) error("產生逾時，請稍後再試")

                _ui.value = _ui.value.copy(phase = Phase.DOWNLOADING)
                val bytes = withContext(Dispatchers.IO) { api.download(job.jobId, token) }
                val name = "TravelLink_${trip.region.ifBlank { "旅程" }}_回顧短片_${System.currentTimeMillis()}"
                val result = withContext(Dispatchers.IO) { MediaStoreSaver.saveVideo(context, bytes, name) }

                val localUri = when (result) {
                    is MediaStoreSaver.SaveResult.Gallery -> result.uri
                    is MediaStoreSaver.SaveResult.AppDirOnly -> Uri.fromFile(result.file)
                    is MediaStoreSaver.SaveResult.Failed -> null
                }
                val saveMsg = when (result) {
                    is MediaStoreSaver.SaveResult.Gallery -> "✅ 已存到相簿（Movies/TravelLink）"
                    is MediaStoreSaver.SaveResult.AppDirOnly -> "已產生，但相簿權限被拒，暫存在 App 內"
                    is MediaStoreSaver.SaveResult.Failed -> "影片已下載但存檔失敗：${result.reason}"
                }
                // 上雲：換裝置/組員/網頁都看得到；失敗不擋（本機已存）
                val sig = repo.sigOf(trip)
                val cloudUrl = if (cloud.isNotBlank())
                    withContext(Dispatchers.IO) { repo.uploadRecap(cloud, bytes, sig, trip.title) } else null

                _ui.value = _ui.value.copy(
                    phase = Phase.DONE,
                    videoUri = localUri ?: cloudUrl?.let { Uri.parse(it) },
                    existingRecap = cloudUrl?.let {
                        CloudRecap(it, sig, trip.title, "", System.currentTimeMillis())
                    } ?: _ui.value.existingRecap,
                    currentSig = sig,
                    message = saveMsg + if (cloudUrl != null) " · 已同步雲端" else ""
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(phase = Phase.ERROR, message = e.message ?: "產生失敗")
            }
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
