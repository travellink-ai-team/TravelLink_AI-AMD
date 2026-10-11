package com.example.travellink_ai.ui.agent

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
import com.example.travellink_ai.util.NEARBY_STOP_CHANNEL_ID
import com.example.travellink_ai.util.SystemNotificationLog

/**
 * 旅程應變 Agent 的主動通知（AMD 組）：行程進行中出現「後面排不下」的衝突時跳通知。
 *
 * T4 規格 §1.3「先通知，使用者按下才送出」：偵測衝突與跳通知都在手機上做，不呼叫後端、不吃限流；
 * 只有使用者按「讓 AI 處理」才送出一次請求（等同按行程頁的按鈕）。點通知本身只打開行程頁。
 */
object AgentAlert {
    /** 點通知：打開行程頁 */
    const val ACTION_OPEN = "com.example.travellink_ai.AGENT_ALERT_OPEN"
    /** 通知上的「讓 AI 處理」：打開行程頁並送出延誤請求 */
    const val ACTION_RUN = "com.example.travellink_ai.AGENT_ALERT_RUN"
    const val EXTRA_DOC_ID = "agent_alert_doc_id"

    private const val NOTIFICATION_ID = 7401

    fun notify(context: Context, docId: String?, messages: List<String>) {
        if (messages.isEmpty()) return
        val title = "行程可能排不下"
        val body = messages.take(2).joinToString("；") + "。AI 代理人可以幫你調整。"
        SystemNotificationLog.record(
            context, SystemNotificationLog.Kind.AGENT, title, body,
            dedupeKey = "agent:${docId.orEmpty()}", docId = docId
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        fun pending(action: String, request: Int) = PendingIntent.getActivity(
            context, request,
            Intent(context, MainActivity::class.java).apply {
                this.action = action
                putExtra(EXTRA_DOC_ID, docId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, NEARBY_STOP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚠️ $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending(ACTION_OPEN, NOTIFICATION_ID))
            .addAction(0, "讓 AI 處理", pending(ACTION_RUN, NOTIFICATION_ID + 1))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
