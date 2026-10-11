package com.example.travellink_ai.util

import com.example.travellink_ai.data.local.LocalItinerary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 從 LocalItinerary 取出「旅遊日期」的毫秒；取不到回 null。
 *
 * 目前沒有獨立的日期欄位，出發日是塞在 days 字串裡的
 * （ItineraryViewModel 寫 `"$startDateTime - $endDateTime"`，例 "2026/05/18 09:00 - ..."），
 * 與 StopDetailScreen / ItineraryMapScreen 取 tripDate 的作法一致：取第一個空白前的 yyyy/MM/dd。
 * 共編群組文件的 days 是「8小時」這種時數字串，網頁端文件也可能只有時數，這些會回 null。
 */
fun tripStartMs(item: LocalItinerary): Long? {
    val datePart = item.days.substringBefore(" ").trim()
    if (datePart.length != 10) return null
    return try {
        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).apply { isLenient = false }
            .parse(datePart)?.time
    } catch (e: Exception) { null }
}

/** 今日 00:00 的毫秒，用來分「即將出發／已結束」——當天出發的行程仍算即將出發 */
fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** 「即將開始」＝今天或明天出發（使用者 2026-09-30 決定：當天＋前一天提醒） */
fun isStartingSoon(item: LocalItinerary, todayStartMs: Long = startOfToday()): Boolean {
    val start = tripStartMs(item) ?: return false
    val dayAfterTomorrow = Calendar.getInstance().apply {
        timeInMillis = todayStartMs; add(Calendar.DAY_OF_YEAR, 2)
    }.timeInMillis
    return start in todayStartMs until dayAfterTomorrow
}

/** 同一趟行程的穩定識別（雲端 docId 優先），給「已看過／已通知」記錄用 */
fun LocalItinerary.soonKey(): String =
    firestoreDocId?.takeIf(String::isNotBlank) ?: "local_${id}_$createdAt"
