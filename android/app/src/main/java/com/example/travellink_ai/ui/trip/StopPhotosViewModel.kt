package com.example.travellink_ai.ui.trip

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.repository.TripPhotoRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 景點資訊頁的拍照／選照片：直接上傳到共同相簿並歸到這一站（stopId），網頁與旅伴都看得到。
 *
 * 上傳中與失敗的照片留在 [pending]，畫面上顯示轉圈或「重試」，不默默失敗；
 * 成功後從 pending 移除，相簿監聽會把正式那張帶回來。ViewModel 跟著 Activity，
 * 離開這頁上傳也會繼續。
 */
@HiltViewModel
class StopPhotosViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepo: TripPhotoRepository,
    private val auth: FirebaseAuth
) : ViewModel() {

    private val tag = "TravelLink_StopPhotos"

    data class PendingUpload(
        val id: String,
        val stop: Stop,
        val uri: Uri,
        /** 相機拍的暫存檔，上傳成功或放棄後刪掉；從相簿選的是 null */
        val tempFile: File?,
        val failed: Boolean = false,
        val error: String? = null
    )

    private var tripId = ""
    private var listenJob: Job? = null

    private val _photos = MutableStateFlow<List<TripPhoto>>(emptyList())
    /** 整趟的共同相簿（畫面再依 stopId 篩） */
    val photos: StateFlow<List<TripPhoto>> = _photos.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingUpload>>(emptyList())
    val pending: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

    val myUid: String get() = auth.currentUser?.uid.orEmpty()

    /** 開始監聽這趟的相簿；同一趟重複呼叫不會重掛 */
    fun bind(tripId: String) {
        if (tripId.isBlank() || tripId == this.tripId) return
        this.tripId = tripId
        _photos.value = emptyList()
        listenJob?.cancel()
        listenJob = viewModelScope.launch { photoRepo.listen(tripId).collect { _photos.value = it } }
    }

    /** 給系統相機寫入的暫存檔（cacheDir/camera，FileProvider 已涵蓋 cacheDir） */
    fun newCaptureFile(): File =
        File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "cap_${System.currentTimeMillis()}.jpg") }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun uploadCaptured(stop: Stop, file: File) {
        if (!file.exists() || file.length() == 0L) { file.delete(); return }
        enqueue(stop, uriFor(file), file)
    }

    fun uploadPicked(stop: Stop, uris: List<Uri>) = uris.forEach { enqueue(stop, it, null) }

    fun retry(id: String) {
        val p = _pending.value.firstOrNull { it.id == id && it.failed } ?: return
        update(id) { it.copy(failed = false, error = null) }
        start(p.copy(failed = false, error = null))
    }

    /** 放棄一張上傳失敗的照片 */
    fun discard(id: String) {
        val p = _pending.value.firstOrNull { it.id == id } ?: return
        if (!p.failed) return
        p.tempFile?.delete()
        _pending.value = _pending.value.filterNot { it.id == id }
    }

    private fun enqueue(stop: Stop, uri: Uri, tempFile: File?) {
        val p = PendingUpload(UUID.randomUUID().toString(), stop, uri, tempFile)
        _pending.value = _pending.value + p
        start(p)
    }

    private fun start(p: PendingUpload) {
        val trip = tripId
        viewModelScope.launch {
            runCatching {
                if (auth.currentUser == null) error("請先登入才能上傳照片")
                if (trip.isBlank()) error("這份行程還沒存到雲端")
                photoRepo.upload(trip, p.stop, p.uri)
            }.onSuccess {
                p.tempFile?.delete()
                _pending.value = _pending.value.filterNot { it.id == p.id }
            }.onFailure { e ->
                Log.w(tag, "照片上傳失敗（${p.stop.name}）", e)
                update(p.id) { it.copy(failed = true, error = e.message) }
            }
        }
    }

    private fun update(id: String, f: (PendingUpload) -> PendingUpload) {
        _pending.value = _pending.value.map { if (it.id == id) f(it) else it }
    }
}
