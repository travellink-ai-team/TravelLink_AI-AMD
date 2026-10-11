package com.example.travellink_ai.data.local

import android.content.Context

/**
 * 行程節奏資料值與網頁端（WanderAI）統一為「輕快／平衡／悠閒」。
 * 本函式將舊版 App 值（輕鬆→悠閒、緊湊→輕快）與未知值正規化，
 * 讀取本地偏好、Firestore UserProfile、共編成員偏好時都應套用，
 * 確保兩端共編同一行程時 AI 生成行為一致。
 */
fun normalizePace(v: String?): String = when (v) {
    "輕快", "平衡", "悠閒" -> v
    "輕鬆" -> "悠閒"
    "緊湊" -> "輕快"
    else   -> "平衡"
}

/**
 * 使用 SharedPreferences 儲存使用者的旅遊偏好設定。
 * 這些設定作為 PlanningBottomSheet 的預設值，讓使用者不必每次重新填寫。
 */
class UserPreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    /** 預設出發車站 */
    var departureStation: String
        get() = prefs.getString("departureStation", "台東車站") ?: "台東車站"
        set(v) { prefs.edit().putString("departureStation", v).apply() }

    /** 預設行程節奏（輕快/平衡/悠閒，與網頁端一致；讀取時自動轉換舊值） */
    var pace: String
        get() = normalizePace(prefs.getString("pace", "平衡"))
        set(v) { prefs.edit().putString("pace", normalizePace(v)).apply() }

    /** 旅遊興趣標籤（多選 Set） */
    var interests: Set<String>
        get() = prefs.getStringSet("interests", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("interests", v).apply() }

    /** 飲食禁忌／想避免的事物（固定標籤，值與網頁 avoid-tags.js 對齊，每趟行程自動套用） */
    var avoidTags: Set<String>
        get() = prefs.getStringSet("avoidTags", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("avoidTags", v).apply() }

    /** 其他想避免的（自由填寫，每趟行程自動套用） */
    var avoidNote: String
        get() = prefs.getString("avoidNote", "") ?: ""
        set(v) { prefs.edit().putString("avoidNote", v).apply() }

    /**
     * 最終目的地車站（A7 ②）：行程結束後要搭火車回哪裡，例如「臺北」。
     * 留空＝不指定，回程班次改列該站南下／北上的下幾班。
     * 這是使用者的長期習慣（多半每次都從同一地來回），故存本地而非綁在單一行程上。
     */
    var finalDestinationStation: String
        get() = prefs.getString("finalDestinationStation", "") ?: ""
        set(v) { prefs.edit().putString("finalDestinationStation", v).apply() }

    /** 是否啟用行程結束回饋通知 */
    var feedbackNotificationEnabled: Boolean
        get() = prefs.getBoolean("feedbackNotificationEnabled", true)
        set(v) { prefs.edit().putBoolean("feedbackNotificationEnabled", v).apply() }

    /** 是否啟用「行程即將開始」提醒（今天／明天出發） */
    var upcomingTripNotificationEnabled: Boolean
        get() = prefs.getBoolean("upcomingTripNotificationEnabled", true)
        set(v) { prefs.edit().putBoolean("upcomingTripNotificationEnabled", v).apply() }

    /** AI 隨行管家頭像貼哪邊：0=左，1=右（存邊+比例，跨裝置解析度都安全）。 */
    var assistantBubbleSide: Int
        get() = prefs.getInt("assistantBubbleSide", 1)
        set(v) { prefs.edit().putInt("assistantBubbleSide", v).apply() }

    /** AI 隨行管家頭像垂直位置比例（0=頂，1=底）。 */
    var assistantBubbleY: Float
        get() = prefs.getFloat("assistantBubbleY", 0.82f)
        set(v) { prefs.edit().putFloat("assistantBubbleY", v.coerceIn(0f, 1f)).apply() }
}
