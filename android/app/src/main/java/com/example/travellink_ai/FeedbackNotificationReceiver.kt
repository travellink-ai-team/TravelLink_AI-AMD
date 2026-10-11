package com.example.travellink_ai

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 行程結束時由 AlarmManager 觸發，建立「填寫回饋」通知。
 * MainActivity 收到 Intent 後導向 FeedbackScreen。
 */
class FeedbackNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val itineraryTitle = intent.getStringExtra(EXTRA_TITLE) ?: "您的行程結束了"
        val firestoreDocId  = intent.getStringExtra(EXTRA_DOC_ID)
        val itineraryId     = intent.getLongExtra(EXTRA_LOCAL_ID, -1L)

        // 通知頁也看得到
        com.example.travellink_ai.util.SystemNotificationLog.record(
            context, com.example.travellink_ai.util.SystemNotificationLog.Kind.FEEDBACK,
            "旅程結束，留下回饋吧！", "「$itineraryTitle」行程結束，花一點時間分享您的旅遊心得 🌟",
            dedupeKey = firestoreDocId ?: itineraryId.toString(),
            docId = firestoreDocId, localId = itineraryId
        )

        // 點通知後打開 MainActivity 並帶入 feedback 參數
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action  = ACTION_OPEN_FEEDBACK
            putExtra(EXTRA_TITLE,    itineraryTitle)
            putExtra(EXTRA_DOC_ID,   firestoreDocId)
            putExtra(EXTRA_LOCAL_ID, itineraryId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            itineraryId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("旅程結束，留下回饋吧！")
            .setContentText("「$itineraryTitle」行程結束，花一點時間分享您的旅遊心得 🌟")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("「$itineraryTitle」行程已結束！\n花一點時間分享您的旅遊心得，幫助 AI 給您更好的推薦 🌟"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(itineraryId.toInt().coerceIn(1, Int.MAX_VALUE), notification)
    }

    companion object {
        const val CHANNEL_ID          = "travellink_feedback"
        const val ACTION_OPEN_FEEDBACK = "com.example.travellink_ai.OPEN_FEEDBACK"
        const val EXTRA_TITLE          = "itinerary_title"
        const val EXTRA_DOC_ID         = "firestore_doc_id"
        const val EXTRA_LOCAL_ID       = "local_id"
    }
}
