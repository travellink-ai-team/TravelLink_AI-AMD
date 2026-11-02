package com.example.travellink_ai.ui.recap

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import com.example.travellink_ai.util.MediaStoreSaver
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 產生一支回顧短片：收集素材 → 後端 render → 輪詢 → 下載 → 存相簿 → 上雲。
 * 回顧短片頁（[RecapViewModel]）與旅程結束自動產生（[AutoRecapWorker]）共用，兩邊送出去的內容才會一樣。
 */
@Singleton
class RecapGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RecapRepository,
    private val api: RecapApiClient,
    private val auth: FirebaseAuth
) {
    private val tag = "TravelLink_Recap"

    enum class Stage { RENDERING, DOWNLOADING }

    class Output(
        val trip: RecapTrip,
        val photos: List<RecapPhoto>,
        val localUri: Uri?,
        val cloudUrl: String?,
        val sig: String,
        val saveMessage: String
    )

    /**
     * @param timelineFor 拿到最新的 trip／要送的 photos（第三個參數是這趟全部照片）之後決定要送哪一份時間軸（畫面上的編輯版或推薦版）
     * @throws Exception 訊息可直接顯示給使用者
     */
    suspend fun render(
        item: LocalItinerary,
        settings: RecapSettings,
        timelineFor: (RecapTrip, List<RecapPhoto>, List<RecapPhoto>) -> RecapTimeline?,
        onProgress: (Stage, Int) -> Unit = { _, _ -> }
    ): Output {
        if (!api.isConfigured) error("回顧短片功能尚未啟用（後端未設定）")
        val user = auth.currentUser ?: error("請先登入再產生回顧短片")
        val trip = repo.buildTrip(item)
        if (trip.stops.size < 2) error("這趟景點座標不足，無法產生")
        val cloud = item.firestoreDocId?.takeIf { it.isNotBlank() }.orEmpty()
        val all = repo.buildPhotos(trip, cloud, recapTripKey(item))
        val photos = recapPhotosFor(all, settings.templateId)
        val timeline = timelineFor(trip, photos, all)
        val routePoints = repo.buildRoutePoints(trip, item, cloud)
        // 攻略卡的每人花費與主角景點（後端只在畫面上用，不影響時間軸）
        val sendTrip = trip.copy(costPerPerson = withContext(Dispatchers.IO) { repo.costPerPerson(item, cloud) },
            highlightStopId = settings.hero(trip))
        val token = user.getIdToken(false).await().token ?: error("取得登入憑證失敗")

        onProgress(Stage.RENDERING, 0)
        val request = RecapRenderRequest(sendTrip, settings.requestPhotos(trip, photos), routePoints, settings.templateId, timeline,
            lengthMode = if (settings.isReels) settings.lengthMode else null,
            tripId = cloud.ifBlank { null },
            coverStopIndex = settings.coverIndex(trip, photos))
        val job = withContext(Dispatchers.IO) { api.createJob(request, token) }
        Log.d(tag, "🎬 送出回顧短片：節奏=${settings.templateId}${if (settings.isReels) "（${settings.lengthMode}）" else ""}，" +
            "${photos.size} 張照片、${timeline?.clips?.size ?: 0} 段" +
            "（${if (timeline?.edited == true) "已編輯" else "推薦版"}，字幕 ${timeline?.clips?.count { !it.caption.isNullOrEmpty() } ?: 0} 則）" +
            "，每人花費 ${sendTrip.costPerPerson ?: "—"}")

        // 輪詢（約 1.2 秒一次，最多 6 分鐘）
        var tries = 0
        var status = RecapJobStatus(status = "queued")
        while (!status.isTerminal && tries < 300) {
            delay(1200)
            status = withContext(Dispatchers.IO) { api.getStatus(job.jobId, token) }
            onProgress(Stage.RENDERING, status.progress)
            tries++
        }
        if (status.isError) error(status.error ?: "後端產生失敗")
        if (!status.isDone) error("產生逾時，請稍後再試")

        onProgress(Stage.DOWNLOADING, 100)
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
        return Output(trip, photos, localUri, cloudUrl, sig, saveMsg + if (cloudUrl != null) " · 已同步雲端" else "")
    }
}
