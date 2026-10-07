package com.example.travellink_ai.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手機系統通知的紀錄，給首頁「通知」頁顯示。
 *
 * 系統通知一被滑掉就不見了，使用者回頭找不到；而且使用者若關掉通知權限，系統通知根本不會跳。
 * 所以每次發系統通知（或本來要發、但沒有權限）都在這裡記一筆，通知頁一律看得到。
 * 只存在這支手機（SharedPreferences），與雲端的站內通知（user_notifications）分開。
 */
object SystemNotificationLog {

    enum class Kind(val emoji: String) {
        UPCOMING("🧳"),       // 今天／明天出發（UpcomingTripReceiver）
        LEAVE_TIME("⏰"),     // 預計離開時間到了（LeaveReminder）
        NEARBY_STOP("📍"),    // 已到達附近（notifyNearbyStop）
        LEFT_NO_CHECKIN("❓"),// 離開沒打卡（notifyLeftWithoutCheckIn）
        FEEDBACK("📝"),       // 行程結束填回饋（FeedbackNotificationReceiver）
        AGENT("🤖")           // 行程可能排不下，AI 代理人可以調整（AgentAlert）
    }

    data class Entry(
        val id: String,
        val kind: Kind,
        val title: String,
        val body: String,
        val createdAt: Long,
        val read: Boolean = false,
        val docId: String? = null,     // 行程的 Firestore docId（點開行程用）
        val localId: Long = -1L,       // 本機行程 id（回饋用）
        val stopId: String? = null     // 離開時間提醒的那一站
    )

    private const val PREFS = "system_notification_log"
    private const val KEY = "entries"
    /** 只留最近這麼多筆，舊的自動丟掉 */
    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var loaded = false

    /** 通知頁開啟時呼叫；之後的新增會即時推到 [entries] */
    @Synchronized fun load(context: Context) {
        if (loaded) return
        _entries.value = read(context)
        loaded = true
    }

    /**
     * 記一筆。[dedupeKey] 相同的舊紀錄會被這筆取代（例如同一站反覆進出 300m，只留最新一則，
     * 與系統通知用同一個 id 覆蓋的行為一致）。
     */
    @Synchronized fun record(
        context: Context, kind: Kind, title: String, body: String, dedupeKey: String,
        docId: String? = null, localId: Long = -1L, stopId: String? = null
    ) {
        val current = if (loaded) _entries.value else read(context)
        val id = "${kind.name}:$dedupeKey"
        val entry = Entry(id, kind, title, body, System.currentTimeMillis(), false, docId, localId, stopId)
        val next = (listOf(entry) + current.filterNot { it.id == id }).take(MAX_ENTRIES)
        write(context, next)
        _entries.value = next
    }

    @Synchronized fun markRead(context: Context, id: String) = update(context) { list ->
        list.map { if (it.id == id) it.copy(read = true) else it }
    }

    @Synchronized fun markAllRead(context: Context) = update(context) { list -> list.map { it.copy(read = true) } }

    private fun update(context: Context, change: (List<Entry>) -> List<Entry>) {
        val next = change(if (loaded) _entries.value else read(context))
        write(context, next)
        _entries.value = next
    }

    private fun read(context: Context): List<Entry> = try {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                id = o.optString("id"),
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null,
                title = o.optString("title"),
                body = o.optString("body"),
                createdAt = o.optLong("createdAt"),
                read = o.optBoolean("read"),
                docId = o.optString("docId").takeIf { it.isNotBlank() },
                localId = o.optLong("localId", -1L),
                stopId = o.optString("stopId").takeIf { it.isNotBlank() }
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun write(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject()
                .put("id", e.id).put("kind", e.kind.name)
                .put("title", e.title).put("body", e.body)
                .put("createdAt", e.createdAt).put("read", e.read)
                .put("docId", e.docId ?: "").put("localId", e.localId)
                .put("stopId", e.stopId ?: ""))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
