package com.example.travellink_ai

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.UserPreferencesManager
import com.example.travellink_ai.util.isStartingSoon
import com.example.travellink_ai.util.soonKey
import com.example.travellink_ai.util.startOfToday
import com.example.travellink_ai.util.tripStartMs
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 「行程即將開始」提醒：每天早上 8 點檢查一次，今天或明天出發的行程各發一則通知
 * （使用者 2026-09-30 決定：當天＋前一天）。
 *
 * 用不精確的每日鬧鐘（不需要 SCHEDULE_EXACT_ALARM 權限），開機後由 BOOT_COMPLETED 重新排程。
 * 開關在側欄「行程提醒」，關掉時鬧鐘照跑但不發通知，重新打開不必再排程。
 */
class UpcomingTripReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            schedule(context)
            return
        }
        if (intent.action != ACTION_CHECK) return
        if (!UserPreferencesManager(context).upcomingTripNotificationEnabled) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = startOfToday()
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // 已通知記錄只留今天的，同一趟行程同一天不重複發（鬧鐘偶爾會重複觸發）
                val notified = prefs.getStringSet("notified_$today", emptySet())!!.toMutableSet()
                val soon = AppDatabase.getDatabase(context).itineraryDao()
                    .getAllItinerariesSyncByUser(uid)
                    .filter { isStartingSoon(it, today) }
                    .distinctBy { it.soonKey() }
                    .filter { it.soonKey() !in notified }
                soon.forEach { item ->
                    val isToday = (tripStartMs(item) ?: 0L) < today + DAY_MS
                    notify(context, item.title.ifBlank { item.aiTitle }, isToday,
                        item.firestoreDocId, item.soonKey().hashCode())
                    notified += item.soonKey()
                }
                prefs.edit().clear().putStringSet("notified_$today", notified).apply()
            } finally {
                pending.finish()
            }
        }
    }

    internal fun notify(context: Context, title: String, isToday: Boolean, docId: String?, id: Int) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TRIP
            putExtra(EXTRA_DOC_ID, docId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val heading = if (isToday) "今天出發！" else "明天就要出發囉"
        val body = if (isToday) "「$title」今天開始，出門前再看一眼行程吧 🧳"
                   else "「$title」明天開始，記得確認天氣與交通 🗺"
        // 通知頁也看得到（今天、明天各留一則）
        com.example.travellink_ai.util.SystemNotificationLog.record(
            context, com.example.travellink_ai.util.SystemNotificationLog.Kind.UPCOMING, heading, body,
            dedupeKey = "$id:$isToday", docId = docId
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    companion object {
        const val CHANNEL_ID      = "travellink_upcoming_trip"
        const val ACTION_CHECK    = "com.example.travellink_ai.CHECK_UPCOMING_TRIPS"
        const val ACTION_OPEN_TRIP = "com.example.travellink_ai.OPEN_UPCOMING_TRIP"
        const val EXTRA_DOC_ID    = "firestore_doc_id"
        private const val PREFS   = "upcoming_trip_notify"
        private const val DAY_MS  = 24L * 60 * 60 * 1000

        /** 排每日 08:00 的檢查鬧鐘；重複呼叫會以同一個 PendingIntent 覆蓋，不會疊加 */
        fun schedule(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, UpcomingTripReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val first = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            context.getSystemService(AlarmManager::class.java)
                .setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}
