package com.example.travellink_ai.ui.recap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.travellink_ai.MainActivity
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.LocalItinerary
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * 旅程結束自動做回顧短片（C7）的本機紀錄：開關（每個帳號各一個、只在這支手機）、
 * 每趟只排一次、失敗了沒、排程當下的行程快照（背景工作與點通知時用）。
 */
class AutoRecapStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recap_auto", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 預設關閉：沒經過使用者同意，不把照片送去產生影片 */
    fun isEnabled(uid: String?): Boolean = uid != null && prefs.getBoolean("enabled_$uid", false)
    fun setEnabled(uid: String, on: Boolean) { prefs.edit().putBoolean("enabled_$uid", on).apply() }

    /** 標記這趟已排過；已排過回 false（一趟只自動產生一次，失敗也不重排） */
    fun markScheduled(key: String): Boolean {
        if (prefs.getBoolean("scheduled_$key", false)) return false
        prefs.edit().putBoolean("scheduled_$key", true).apply()
        return true
    }

    fun setFailed(key: String) { prefs.edit().putBoolean("failed_$key", true).apply() }
    fun hasFailed(key: String): Boolean = prefs.getBoolean("failed_$key", false)
    fun clearFailed(key: String) { prefs.edit().remove("failed_$key").apply() }

    fun saveSnapshot(key: String, item: LocalItinerary) { prefs.edit().putString("snap_$key", gson.toJson(item)).apply() }
    fun snapshot(key: String): LocalItinerary? =
        prefs.getString("snap_$key", null)?.let { runCatching { gson.fromJson(it, LocalItinerary::class.java) }.getOrNull() }
}

object AutoRecap {
    private const val TAG = "TravelLink_Recap"
    const val CHANNEL_ID = "travellink_recap"
    const val ACTION_OPEN_RECAP = "com.example.travellink_ai.OPEN_RECAP"
    const val EXTRA_KEY = "recap_trip_key"
    private const val KEY = "key"
    private const val LOCAL_ID = "localId"
    /** 按完成行程後等一下再做：最後一站剛拍的照片還在上傳 */
    private const val DELAY_MIN = 2L

    /**
     * 按「完成行程」時呼叫：有開開關、這趟還沒排過，就排一個背景工作（App 關掉也會做完）。
     * 是不是行程擁有者、雲端是不是已經有短片，在背景工作裡查（要連網）。
     */
    fun onTripFinished(context: Context, uid: String?, item: LocalItinerary) {
        val store = AutoRecapStore(context)
        if (!store.isEnabled(uid)) return
        if (item.firestoreDocId.isNullOrBlank() && item.id <= 0) return   // 沒存過的行程找不回來
        val key = recapTripKey(item)
        if (!store.markScheduled(key)) return
        store.saveSnapshot(key, item)
        val req = OneTimeWorkRequestBuilder<AutoRecapWorker>()
            .setInitialDelay(DELAY_MIN, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY to key, LOCAL_ID to item.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("auto_recap_$key", ExistingWorkPolicy.KEEP, req)
        Log.d(TAG, "🎬 旅程結束：$DELAY_MIN 分鐘後自動做回顧短片（$key）")
    }

    internal fun keyOf(data: androidx.work.Data) = data.getString(KEY)
    internal fun localIdOf(data: androidx.work.Data) = data.getLong(LOCAL_ID, -1L)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "回顧短片", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "旅程結束後自動做好的回顧短片" }
        )
    }

    /** 「你的回顧短片好了」：點了直接開回顧短片頁 */
    fun notifyReady(context: Context, key: String, title: String) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_RECAP
            putExtra(EXTRA_KEY, key)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(context, key.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("你的回顧短片好了 🎬")
            .setContentText("「$title」的回顧短片已存到相簿，點開就能分享到 IG")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
        }.onFailure { Log.w(TAG, "回顧短片通知發不出去：${it.message}") }
    }
}

/** 這趟在本機存檔用的 key：雲端行程用 docId，純本地行程用 Room id（與回顧短片頁、行程進度同一套） */
fun recapTripKey(item: LocalItinerary): String =
    item.firestoreDocId?.takeIf { it.isNotBlank() } ?: "local_${item.id}"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoRecapEntryPoint {
    fun generator(): RecapGenerator
    fun repository(): RecapRepository
    fun database(): AppDatabase
    fun firestore(): FirebaseFirestore
    fun auth(): FirebaseAuth
}

/**
 * 背景產生回顧短片。失敗不重試、不推播錯誤，只記下來，使用者打開回顧短片頁時才說「自動產生沒有成功」。
 * 共編行程只有擁有者的手機會做；雲端已經有短片（有人先做了）就不做。
 */
class AutoRecapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val tag = "TravelLink_Recap"

    override suspend fun doWork(): Result {
        val key = AutoRecap.keyOf(inputData) ?: return Result.success()
        val ctx = applicationContext
        val store = AutoRecapStore(ctx)
        val ep = EntryPointAccessors.fromApplication(ctx, AutoRecapEntryPoint::class.java)
        try {
            val uid = ep.auth().currentUser?.uid ?: error("沒有登入")
            val snap = store.snapshot(key) ?: error("找不到行程快照")
            // Room 的那筆有交通方式與路線折線；站以按完成行程當下的為準
            val row = AutoRecap.localIdOf(inputData).takeIf { it > 0 }
                ?.let { ep.database().itineraryDao().getItineraryById(it) }
            val item = row?.copy(stops = snap.stops, firestoreDocId = snap.firestoreDocId ?: row.firestoreDocId) ?: snap

            val docId = item.firestoreDocId?.takeIf { it.isNotBlank() }
            if (docId != null) {
                val doc = ep.firestore().collection("micro_trips").document(docId).get().await()
                val owner = doc.getString("ownerUid") ?: doc.getString("createdBy")
                if (owner != null && owner != uid) {
                    Log.d(tag, "⏭ 自動回顧短片：不是行程擁有者，交給擁有者做（$key）")
                    return Result.success()
                }
                if (ep.repository().readCloudRecap(docId) != null) {
                    Log.d(tag, "⏭ 自動回顧短片：雲端已經有短片（$key）")
                    return Result.success()
                }
            }

            // 用使用者上次選的節奏（與長度、主角、封面）；這趟在這支手機有編輯過、且還適用就用編輯版，否則推薦版
            val editor = RecapEditorStore(ctx)
            val settings = editor.settings(key)
            val out = ep.generator().render(item, settings, timelineFor = { trip, photos, _ ->
                editor.load(key, settings.signature(trip, photos)).first?.let { settings.withCover(it, trip, photos) }
                    ?: if (trip.stops.size >= 2) settings.recommended(trip, photos) else null
            })
            store.clearFailed(key)
            Log.d(tag, "✅ 自動回顧短片完成（$key）：${out.saveMessage}")
            AutoRecap.notifyReady(ctx, key, out.trip.title)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "自動回顧短片失敗（$key）：${e.message}")
            store.setFailed(key)
        }
        return Result.success()
    }
}
