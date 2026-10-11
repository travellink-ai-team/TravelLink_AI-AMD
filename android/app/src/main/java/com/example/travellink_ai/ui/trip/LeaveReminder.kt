package com.example.travellink_ai.ui.trip

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.travellink_ai.MainActivity
import com.example.travellink_ai.util.NEARBY_STOP_CHANNEL_ID
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 第 6 項：到了目前站的預計離開時間，發通知問「要再待 15／30 分鐘嗎？」。
 *
 * - 用 AlarmManager 排程，App 在背景或被關掉也會響（畫面上的計時做不到）
 * - 通知上直接按「+15／+30 分」就更新預計離開時間，不用打開 App；點通知本身進這站的景點資訊頁
 * - 沒回應就當作照時間離開，什麼都不改——不要求使用者一直手動維護計時器
 * - 同一時間只有一個提醒（只有一個目前站），重排時直接覆蓋
 *
 * 排程與取消由 [TripProgressViewModel] 依目前站與預計離開時間自動同步。
 */
object LeaveReminder {
    const val ACTION_FIRE = "com.example.travellink_ai.LEAVE_REMINDER_FIRE"
    const val ACTION_EXTEND = "com.example.travellink_ai.LEAVE_REMINDER_EXTEND"
    /** 點通知打開 App：MainActivity 收到後跳到這站的景點資訊頁 */
    const val ACTION_OPEN_STOP = "com.example.travellink_ai.LEAVE_REMINDER_OPEN_STOP"

    const val EXTRA_DOC_ID = "leave_doc_id"
    const val EXTRA_STOP_ID = "leave_stop_id"
    const val EXTRA_STOP_NAME = "leave_stop_name"
    const val EXTRA_NEXT_NAME = "leave_next_name"
    const val EXTRA_LEAVE_AT = "leave_at"
    const val EXTRA_MINUTES = "leave_minutes"

    private const val REQUEST_ALARM = 7301
    private const val NOTIFICATION_ID = 7301
    private const val TAG = "LeaveReminder"

    private fun alarmIntent(
        context: Context, docId: String?, stopId: String, stopName: String, nextName: String?, leaveAt: Long
    ): PendingIntent {
        val intent = Intent(context, LeaveReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_DOC_ID, docId)
            putExtra(EXTRA_STOP_ID, stopId)
            putExtra(EXTRA_STOP_NAME, stopName)
            putExtra(EXTRA_NEXT_NAME, nextName)
            putExtra(EXTRA_LEAVE_AT, leaveAt)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 排（或覆蓋）提醒。時間已過就不排 */
    fun schedule(context: Context, docId: String?, stopId: String, stopName: String, nextName: String?, leaveAt: Long) {
        // leaveAt 是行程時間（demo 時鐘）；鬧鐘照真實時間響，要換回真實時間
        val now = com.example.travellink_ai.debug.DemoClock.now()
        val triggerAt = when {
            leaveAt > now -> com.example.travellink_ai.debug.DemoClock.toRealTime(leaveAt)
            // demo 快轉剛越過預計離開：馬上響，否則按了「+60 分」反而看不到提醒
            com.example.travellink_ai.debug.DemoClock.isShifted() && now - leaveAt < 2 * 60 * 60_000L ->
                System.currentTimeMillis() + 1_000L
            else -> { cancel(context); return }
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context, docId, stopId, stopName, nextName, leaveAt)
        try {
            // 沒有精準鬧鐘權限（Android 12+ 預設沒有）就用一般的，最多晚幾分鐘，對這個提醒夠用
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 取消排程與已跳出的通知（下一站打卡、結束或重設行程時） */
    fun cancel(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_ALARM, Intent(context, LeaveReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    internal fun notify(context: Context, extras: Intent) {
        val stopId = extras.getStringExtra(EXTRA_STOP_ID) ?: return
        val stopName = extras.getStringExtra(EXTRA_STOP_NAME).orEmpty()
        val nextName = extras.getStringExtra(EXTRA_NEXT_NAME)
        val docId = extras.getStringExtra(EXTRA_DOC_ID)
        val leaveAt = extras.getLongExtra(EXTRA_LEAVE_AT, 0L)
        val title = "預計離開「$stopName」的時間到了"
        val body = if (nextName.isNullOrBlank()) "要再待一下嗎？" else "要再待一下嗎？下一站：$nextName"
        // 沒有通知權限也記一筆，通知頁看得到；同一站延長後再響只留最新一則
        com.example.travellink_ai.util.SystemNotificationLog.record(
            context, com.example.travellink_ai.util.SystemNotificationLog.Kind.LEAVE_TIME, title, body,
            dedupeKey = "${docId.orEmpty()}:$stopId", docId = docId, stopId = stopId
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context, REQUEST_ALARM,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_STOP
                putExtra(EXTRA_STOP_ID, stopId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, NEARBY_STOP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏰ $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            // 半小時沒理就收掉：當作已照時間離開
            .setTimeoutAfter(30 * 60_000L)
        // 通知上直接延長只做得到雲端行程（寫 tripProgress.leaveAt）；純本機行程點進 App 再調
        if (!docId.isNullOrBlank() && leaveAt > 0) {
            listOf(15, 30).forEach { mins ->
                val extend = PendingIntent.getBroadcast(
                    context, REQUEST_ALARM + mins,
                    Intent(context, LeaveReminderReceiver::class.java).apply {
                        action = ACTION_EXTEND
                        putExtras(extras)
                        putExtra(EXTRA_MINUTES, mins)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "再待 $mins 分", extend)
            }
        }
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * 通知上按「再待 N 分」：直接寫 tripProgress.leaveAt（和 App 內調整同一個欄位），並排下一次提醒。
     * App 開著時 TripProgressViewModel 的即時監聽會收到這筆寫入，畫面跟著順延。
     */
    internal fun extend(context: Context, extras: Intent, onDone: () -> Unit) {
        val docId = extras.getStringExtra(EXTRA_DOC_ID)
        val stopId = extras.getStringExtra(EXTRA_STOP_ID)
        val leaveAt = extras.getLongExtra(EXTRA_LEAVE_AT, 0L)
        val mins = extras.getIntExtra(EXTRA_MINUTES, 0)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (docId.isNullOrBlank() || stopId.isNullOrBlank() || leaveAt <= 0 || mins <= 0) { onDone(); return }

        // 從「現在」和「原預計離開」取晚的那個往後加：通知可能過了一陣子才按
        val newAt = maxOf(leaveAt, com.example.travellink_ai.debug.DemoClock.now()) + mins * 60_000L
        val user = FirebaseAuth.getInstance().currentUser
        val name = user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "旅人"
        schedule(
            context, docId, stopId, extras.getStringExtra(EXTRA_STOP_NAME).orEmpty(),
            extras.getStringExtra(EXTRA_NEXT_NAME), newAt
        )
        FirebaseFirestore.getInstance().collection("micro_trips").document(docId)
            .update(
                "tripProgress.leaveAt.$stopId",
                mapOf("at" to newAt, "uid" to (user?.uid ?: ""), "displayName" to name)
            )
            .addOnCompleteListener { t ->
                if (!t.isSuccessful) Log.w(TAG, "⚠️ 延長預計離開失敗：${t.exception?.message}")
                onDone()
            }
    }
}

class LeaveReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LeaveReminder.ACTION_FIRE -> LeaveReminder.notify(context, intent)
            LeaveReminder.ACTION_EXTEND -> {
                // 等 Firestore 寫進本機快取／送出；離線時送不到伺服器，最多等 8 秒就放手（寫入會留在佇列）
                val pending = goAsync()
                var finished = false
                val finish = {
                    if (!finished) { finished = true; pending.finish() }
                }
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8_000L)
                LeaveReminder.extend(context, intent) { Handler(Looper.getMainLooper()).post { finish() } }
            }
        }
    }
}
