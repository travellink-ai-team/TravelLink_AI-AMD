package com.example.travellink_ai.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.travellink_ai.MainActivity

/** 行程進行中·景點提醒的通知頻道（跟「行程回饋」頻道分開，語意不同） */
const val NEARBY_STOP_CHANNEL_ID = "travellink_nearby_stop"

private fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openAppIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** GPS 進景點 300m 內：提醒打卡（不管 App 在不在前景都發，前景時純粹是多一個提示）。 */
fun notifyNearbyStop(context: Context, stopName: String, docId: String? = null) {
    // 沒有通知權限也記一筆，通知頁看得到
    SystemNotificationLog.record(
        context, SystemNotificationLog.Kind.NEARBY_STOP, "已到達附近", "$stopName 就在附近，記得打卡！",
        dedupeKey = "${docId.orEmpty()}:$stopName", docId = docId
    )
    if (!canNotify(context)) return
    val notification = NotificationCompat.Builder(context, NEARBY_STOP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("📍 已到達附近")
        .setContentText("$stopName 就在附近，記得打卡！")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .build()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(stopName.hashCode(), notification)
}

/** 曾經靠近某站但離開時還沒打卡／跳過：提醒補處理，避免後續站的預估時間被拖著錯下去。 */
fun notifyLeftWithoutCheckIn(context: Context, stopName: String, docId: String? = null) {
    SystemNotificationLog.record(
        context, SystemNotificationLog.Kind.LEFT_NO_CHECKIN, "忘記打卡了嗎？",
        "你好像已經離開「$stopName」了，記得回去補打卡或標記跳過",
        dedupeKey = "${docId.orEmpty()}:$stopName", docId = docId
    )
    if (!canNotify(context)) return
    val notification = NotificationCompat.Builder(context, NEARBY_STOP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("忘記打卡了嗎？")
        .setContentText("你好像已經離開「$stopName」了，記得回去補打卡或標記跳過")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .build()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(stopName.hashCode() + 1, notification)
}
