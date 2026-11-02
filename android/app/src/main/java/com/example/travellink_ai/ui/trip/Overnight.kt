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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.travellink_ai.MainActivity
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.DayPlanner
import com.example.travellink_ai.util.NEARBY_STOP_CHANNEL_ID

/** 下一天在第一站排定時間前幾分鐘自動開始（2026-10-19 使用者定案：30 分） */
internal const val DAY_START_LEAD_MINUTES = 30

/**
 * 多日行程兩天之間的「今天結束」狀態：當天的景點都打卡或跳過了，下一天還沒開始。
 * 過去第 1 天最後一站一打卡就把第 2 天第一站當成下一站，晚上還會跳到站通知、Demo 直接開過去。
 */
internal data class OvernightInfo(
    /** 剛結束的那天 */
    val finishedDay: Int,
    /** 下一天 */
    val nextDay: Int,
    /** 下一天第一站的排定時間（epoch ms，行程時間＝demo 時鐘） */
    val nextStartMs: Long,
    /** 自動開始下一天的時間（第一站前 [DAY_START_LEAD_MINUTES] 分） */
    val switchAtMs: Long,
    /** 下一天第一個景點（不是住宿、車站的那站）；沒有就是下一天第一站 */
    val nextFirst: Stop,
    /** 下一天有幾個景點 */
    val nextDayCount: Int,
    /** 剛結束那天走了幾站（打卡的） */
    val doneCount: Int,
    /** 今晚的住宿（剛結束那天最後一站是住宿時） */
    val lodging: Stop?
)

/**
 * 是不是在兩天之間。下一天已經開始的條件（任一）：有人按了「提早開始」（tripProgress.dayStartedAt.{N}）、
 * 下一天已有站打卡或跳過、時間到了第一站前 30 分。算不出日期（沒有出發日）就當作不在兩天之間，照舊。
 */
internal fun overnightState(
    stops: List<Stop>,
    progress: TripProgress,
    derived: TripDerived,
    nowMs: Long,
    tripStartDate: String
): OvernightInfo? {
    if (!progress.isOngoing) return null
    val next = derived.currentStop ?: return null
    val nextDay = next.dayIndex
    val finishedDay = stops.filter { it.dayIndex < nextDay }.maxOfOrNull { it.dayIndex } ?: return null
    if (nextDay in progress.dayStartedAt) return null
    val dayStops = stops.filter { it.dayIndex == nextDay }
    if (dayStops.any { !it.isStation && progress.isDone(it.stopId) }) return null
    val firstMin = dayStops.firstNotNullOfOrNull { parseClockToMinutes(it.time) } ?: return null
    if (tripStartDate.length != 10) return null
    val dayMs = runCatching {
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .parse(DayPlanner.addDays(tripStartDate, nextDay - 1))?.time
    }.getOrNull() ?: return null
    val startMs = dayMs + firstMin * 60_000L
    val switchAt = startMs - DAY_START_LEAD_MINUTES * 60_000L
    if (nowMs >= switchAt) return null
    val finished = stops.filter { it.dayIndex == finishedDay }
    return OvernightInfo(
        finishedDay = finishedDay,
        nextDay = nextDay,
        nextStartMs = startMs,
        switchAtMs = switchAt,
        nextFirst = dayStops.firstOrNull { !it.isStation && !it.isLodging } ?: next,
        nextDayCount = dayStops.count { !it.isStation && !it.isLodging },
        doneCount = finished.count { !it.isStation && it.stopId in progress.checkIns },
        // 入住後還可以再出門（晚上加的站排在入住之後），住宿不一定是當天最後一站
        lodging = finished.lastOrNull { it.isLodging }
    )
}

/**
 * 下一天開始的提醒：在自動開始的時間（第一站前 30 分）發通知「第 N 天的行程 30 分鐘後開始」。
 * 用 AlarmManager，App 在背景或被關掉也會響；由 [TripProgressViewModel] 依狀態排程與取消。
 */
object DayStartReminder {
    private const val ACTION_FIRE = "com.example.travellink_ai.DAY_START_FIRE"
    private const val EXTRA_TITLE = "day_start_title"
    private const val EXTRA_BODY = "day_start_body"
    private const val EXTRA_DOC_ID = "day_start_doc_id"
    private const val REQUEST = 7401
    private const val NOTIFICATION_ID = 7401

    private fun intent(context: Context, docId: String?, title: String, body: String) =
        PendingIntent.getBroadcast(
            context, REQUEST,
            Intent(context, DayStartReminderReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_DOC_ID, docId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun schedule(context: Context, docId: String?, info: OvernightInfo) {
        val now = com.example.travellink_ai.debug.DemoClock.now()
        if (info.switchAtMs <= now) { cancel(context); return }
        val triggerAt = com.example.travellink_ai.debug.DemoClock.toRealTime(info.switchAtMs)
        val title = "第 ${info.nextDay} 天的行程 $DAY_START_LEAD_MINUTES 分鐘後開始"
        val body = "${fmtClock(minutesOfDay(info.nextStartMs))} 從「${info.nextFirst.name}」開始，共 ${info.nextDayCount} 站"
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, docId, title, body)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, DayStartReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
    }

    internal fun notify(context: Context, extras: Intent) {
        val title = extras.getStringExtra(EXTRA_TITLE) ?: return
        val body = extras.getStringExtra(EXTRA_BODY).orEmpty()
        val docId = extras.getStringExtra(EXTRA_DOC_ID)
        com.example.travellink_ai.util.SystemNotificationLog.record(
            context, com.example.travellink_ai.util.SystemNotificationLog.Kind.UPCOMING, title, body,
            dedupeKey = "${docId.orEmpty()}:day_start:$title", docId = docId
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, REQUEST,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, NEARBY_STOP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("☀️ $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }
}

class DayStartReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DayStartReminder.notify(context, intent)
    }
}
