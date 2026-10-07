package com.example.travellink_ai.data.model

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val emoji: String = "🌟",
    val interests: List<String> = emptyList(),
    val pace: String = "平衡",
    val theme: String = "經典旅人",
    val visitedSpots: List<String> = emptyList()
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "uid"          to uid,
        "email"        to email,
        "name"         to name,
        "emoji"        to emoji,
        "preferences"  to mapOf(
            "interests" to interests,
            "pace"      to pace,
            "theme"     to theme
        ),
        "visitedSpots" to visitedSpots
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromFirestore(data: Map<String, Any?>): UserProfile {
            val prefs = data["preferences"] as? Map<String, Any?> ?: emptyMap()
            return UserProfile(
                uid          = data["uid"] as? String ?: "",
                email        = data["email"] as? String ?: "",
                name         = data["name"] as? String ?: "",
                emoji        = data["emoji"] as? String ?: "🌟",
                interests    = (prefs["interests"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                pace         = prefs["pace"] as? String ?: "平衡",
                theme        = prefs["theme"] as? String ?: "經典旅人",
                visitedSpots = (data["visitedSpots"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            )
        }
    }
}

// 與網頁端 WanderAI 六大興趣標籤對齊（2026-07-12）：
// 資料值不含 emoji（與網頁 members prefs 一致，跨端聯集聚合才對得起來），上限 3 個。
val ALL_TRAVEL_PREFERENCES = listOf("美食", "文化", "自然", "打卡", "運動", "放鬆")

const val MAX_TRAVEL_PREFERENCES = 3

val TRAVEL_PREFERENCE_EMOJI = mapOf(
    "美食" to "🍜", "文化" to "🏛️", "自然" to "🌿",
    "打卡" to "📸", "運動" to "🏃", "放鬆" to "😌"
)

/** 顯示用：emoji + 標籤（資料層一律存純標籤） */
fun displayInterest(value: String): String =
    TRAVEL_PREFERENCE_EMOJI[value]?.let { "$it $value" } ?: value

/**
 * 舊 App 14 標籤（含 emoji 前綴）→ 網頁對齊後的 6 標籤。
 * 不認得的值回傳 null（呼叫端過濾），避免舊資料污染聯集聚合。
 */
fun normalizeInterest(value: String): String? {
    val v = value.substringAfter(" ").ifBlank { value }.trim()
    return when (v) {
        in ALL_TRAVEL_PREFERENCES -> v
        "咖啡廳"                       -> "美食"
        "文化歷史", "藝術"             -> "文化"
        "海灘", "自然生態", "山岳健行"  -> "自然"
        "網美打卡", "購物"             -> "打卡"
        "自行車", "冒險刺激"           -> "運動"
        "溫泉", "親子活動", "放鬆悠閒"  -> "放鬆"
        else -> null
    }
}

/**
 * 批次正規化 + 去重（讀舊 DataStore／Firestore profile／共編成員偏好時套用）。
 * 個人喜好與網頁一致「可多選」不設上限；精靈/成員偏好的「最多 3 個」由呼叫端裁切。
 */
fun normalizeInterests(values: Collection<String>): List<String> =
    values.mapNotNull(::normalizeInterest).distinct()

// 與網頁 avoid-tags.js 對齊的「飲食禁忌／想避免的事物」固定標籤字典。
// 資料值＝label（如「素食」）；送 AI prompt 時轉 hashtag（#素食）以穩定辨識硬性禁忌。
val AVOID_TAGS_DIET = listOf("素食", "不吃辣", "不吃牛", "不吃豬", "海鮮過敏", "堅果過敏")
val AVOID_TAGS_TRIP = listOf("避開人潮", "避開高消費", "避免大量步行", "避免水上活動")
