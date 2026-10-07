package com.example.travellink_ai.data.model

/**
 * 共編成員資料（儲存在 micro_trips/{docId}.members Map）
 * role: "owner" | "editor" | "viewer"
 */
data class CollabMember(
    val uid: String = "",
    val displayName: String = "",
    val role: String = "viewer",
    val joinedAt: Long = 0L
)

/**
 * 成員面板用的完整成員資訊（role + 在線狀態合併）
 */
data class CollabMemberInfo(
    val uid: String,
    val displayName: String,
    val emoji: String,
    val role: String,   // "owner" | "editor" | "viewer"
    val isOnline: Boolean
)

/**
 * 景點編輯鎖（儲存在 micro_trips/{docId}.editingLocks.{stopId}）
 * expiresAt 超過目前時間視為已過期，其他人可覆蓋
 */
data class EditingLock(
    val uid: String = "",
    val displayName: String = "",
    val expiresAt: Long = 0L
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
}

/**
 * 線上在場資訊（儲存在 micro_trips/{docId}/presence/{uid}）
 * lastSeen 超過 60 秒視為離線
 */
data class PresenceInfo(
    val uid: String = "",
    val displayName: String = "",
    val lastSeen: Long = 0L,
    /** 在場者的 email：members map 有些項目沒有 uid 欄位，需靠 email 才對得上成員 */
    val email: String = ""
) {
    val isOnline: Boolean get() = System.currentTimeMillis() - lastSeen < 60_000L
}

// ══════════════════════════════════════════════════════════════
// 網頁端（WanderAI）文件格式相容工具
// 網頁端與 App 寫入同一個 micro_trips collection，但 schema 不同：
// stops 用 type:"start"/"end" 而非 isStation、沒有 stopId、
// members 以 email 消毒字串為 key、分享用 inviteCode 而非 joinPin
// ══════════════════════════════════════════════════════════════

/** Firestore field path 不允許的字元（. 會被當成路徑分隔）*/
private val fieldPathUnsafe = Regex("[.~*/\\[\\]]")

/**
 * 缺 stopId 時的確定性推導：**任何地方都不可以退回隨機 UUID**。
 *
 * 隨機 UUID 會讓同一份資料在不同裝置解析出不同 id，造成 syncKey 恆判有變更、
 * 編輯鎖對不上，而且 `memories.spots` 是以 stopId 為 key，隨機化等於照片直接脫落。
 *
 * 名稱中的 field-path 保留字元一律換成 _，確保能安全用於
 * editingLocks.{stopId} 這類 dot-notation 更新。
 *
 * ⚠️ 這只是「沒有真 stopId 時的權宜之計」：order 或 name 一改，推導出來的 id 就變了。
 * 真正的解法是來源端建立景點時就寫入不可變 stopId（見旅程故事計畫 §3.1，待網頁端配合）。
 * 在那之前，改名／換序造成的脫落由 [com.example.travellink_ai.ui.memory.MemoryViewModel]
 * 的 spot key 重新對應（reconcile）搶救。
 */
fun deterministicStopId(order: Long, name: String): String =
    "web_${order}_${name.replace(fieldPathUnsafe, "_")}"

/** 從確定性 id 反推當初的 order；不是該格式則回 null。 */
fun orderFromDeterministicStopId(stopId: String): Long? =
    Regex("^web_(\\d+)_").find(stopId)?.groupValues?.get(1)?.toLongOrNull()

/**
 * 站的身分（T4 規格第 5 節，兩端一致）：先用 Firestore 的 `collabStopId`，沒有才用 `stopId`；
 * 兩者都沒有時用與網頁 `getStableCollabStopId` 完全相同的演算法現算（不再用 `web_{order}_{站名}`），
 * 這樣兩端算出同一個 id；送 Agent 前會由 [com.example.travellink_ai.ui.planning.ItineraryViewModel]
 * 把它補寫進 Firestore，之後就固定了。
 *
 * @param index 這一站在 Firestore `stops` 陣列中的位置（網頁演算法的一部分）
 */
fun stableStopId(s: Map<*, *>, index: Int): String {
    (s["collabStopId"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    (s["stopId"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    return webCollabStopId(s["type"] as? String ?: "", s["name"] as? String ?: "", index)
}

/**
 * 網頁 `getStableCollabStopId` 的 Kotlin 版：FNV-1a 32-bit 雜湊 `type|name|index`（轉小寫、UTF-16），
 * 輸出 `cstop-` 加 36 進位。必須與網頁逐位元一致，否則同一站在兩端的 id 不同、提案與 pin 都對不上。
 */
fun webCollabStopId(type: String, name: String, index: Int): String {
    val seed = "$type|$name|$index".lowercase()
    var hash = 2166136261L.toInt()
    for (ch in seed) {
        hash = hash xor ch.code
        hash *= 16777619          // Int 溢位即 JS Math.imul 的 32 位元乘法
    }
    return "cstop-" + (hash.toLong() and 0xFFFFFFFFL).toString(36)
}

/**
 * 整個 `stops` 陣列一次算好 id（T4 規格第 5 節）：已有 collabStopId／stopId 的一律沿用；
 * 兩者都沒有的用網頁演算法現算，極少數雜湊碰撞時新的那站加 `-2`、`-3`，舊的不改。
 * 讀取、存檔合併、補寫、打卡進度都必須用這支，同一份資料才會得到同一組 id。
 */
fun stableStopIds(stops: List<*>): List<String> {
    val ids = MutableList(stops.size) { "" }
    val used = mutableSetOf<String>()
    stops.forEachIndexed { i, raw ->
        val s = raw as? Map<*, *> ?: return@forEachIndexed
        val stored = (s["collabStopId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (s["stopId"] as? String)?.takeIf { it.isNotBlank() }
        if (stored != null) { ids[i] = stored; used += stored }
    }
    stops.forEachIndexed { i, raw ->
        if (ids[i].isNotEmpty()) return@forEachIndexed
        val s = raw as? Map<*, *> ?: emptyMap<String, Any>()
        val base = stableStopId(s, i)
        var id = base
        var n = 2
        while (id in used) id = "$base-${n++}"
        ids[i] = id
        used += id
    }
    return ids
}

/** 網頁手動鎖定時間（從 0 點起算的分鐘，null＝沒鎖）：App 原樣保留、存檔時原樣寫回 */
fun parseManualMin(s: Map<*, *>, key: String): Int? = (s[key] as? Number)?.toInt()

/** 網頁端用 type: "start"/"end" 標記車站站點，App 用 isStation */
fun parseIsStation(s: Map<*, *>): Boolean =
    s["isStation"] as? Boolean
        ?: ((s["type"] as? String) == "start" || (s["type"] as? String) == "end")

/**
 * 讀網頁端 stop 的 dayIndex。**1-based**，缺欄位或髒值一律回 1。
 *
 * 網頁端多日文件寫的是 1、2（實例 my_1784460114502），單日文件則沒有這個欄位；
 * 兩者都要落在「第 1 天」才不會把單日行程算成第 0 天而多切出一日。
 */
fun parseDayIndex(s: Map<*, *>): Int =
    (s["dayIndex"] as? Number)?.toInt()?.takeIf { it >= 1 } ?: 1

/**
 * 讀網頁端「這一段手動改走路」。網頁只認 transitModeManual=true 的 walk
 * （舊行程曾把未指定的段存成 walk，那些要當成跟隨主要交通工具）。
 */
fun parseWalkNext(s: Map<*, *>): Boolean =
    s["transitMode"] == "walk" && s["transitModeManual"] == true

/**
 * 決定行程要用哪個 days 字串。優先序：
 *   ① [appDays]（App 生成時寫的「yyyy/MM/dd HH:mm - yyyy/MM/dd HH:mm」）
 *   ② 由 [wizard] 組出同格式 —— 網頁端行程走這條
 *   ③ 原始 [rawDays]（"8小時"、"2天"）
 *
 * 網頁端多日文件的 days 是「2天」，實際日期只存在 wizardData.departureDate／returnDate
 * （實例 my_1784460114502）。但 App 有六處以上是拿「days 開頭的 yyyy/MM/dd」當旅遊日期用：
 * 歷史頁的即將出發／已結束分區、地圖頁 tripDate、預覽頁天氣、回饋頁、台鐵班次查詢。
 * 拿到「2天」全部解析失敗——這些行程會沉到歷史頁最底、台鐵卡查不到日期。
 * 在讀取這一層補成 App 格式，下游六處不必各自特判。
 *
 * 組不出完整日期時回傳原值，不會拼出半截或猜測的日期。
 */
fun normalizeDaysField(appDays: String?, rawDays: String?, wizard: Map<*, *>?): String {
    appDays?.takeIf { it.isNotBlank() }?.let { return it }
    val raw = rawDays ?: ""
    // 已經是 App 格式（開頭 yyyy/MM/dd）就照用
    if (raw.length >= 10 && raw.getOrNull(4) == '/' && raw.getOrNull(7) == '/') return raw
    if (wizard == null) return raw

    fun str(key: String) = (wizard[key] as? String)?.takeIf { it.isNotBlank() }
    // yyyy-MM-dd → yyyy/MM/dd；格式不符就放棄（寧可留原值，也不要組出假日期）
    fun slash(d: String?) = d?.takeIf { it.length == 10 && it[4] == '-' && it[7] == '-' }
        ?.replace('-', '/')

    val startDate = slash(str("departureDate")) ?: return raw
    val startTime = str("startTime")?.takeIf { it.length == 5 && it[2] == ':' } ?: "09:00"
    val endDate   = slash(str("returnDate")) ?: startDate
    // 多日用 day2EndTime；單日（或缺值）用 day1Hours 由起始時間推算
    val endTime = str("day2EndTime")?.takeIf { endDate != startDate }
        ?: run {
            val hours = (wizard["day1Hours"] as? Number)?.toInt() ?: 8
            val startMins = startTime.split(":").let { p ->
                val h = p.getOrNull(0)?.toIntOrNull()
                val m = p.getOrNull(1)?.toIntOrNull()
                if (h != null && m != null) h * 60 + m else 9 * 60
            }
            val endMins = (startMins + hours * 60).coerceAtMost(23 * 60 + 59)
            "%02d:%02d".format(endMins / 60, endMins % 60)
        }
    return "$startDate $startTime - $endDate $endTime"
}

/**
 * 網頁端 members map 的 key：email 中非英數字元一律換成 _
 * （如 001@gmail.com → 001_gmail_com），需與網頁端規則一致
 */
fun sanitizeEmailKey(email: String): String =
    email.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")

/** 判斷文件是否為網頁端建立的協作格式 */
fun isWebCollabDoc(doc: com.google.firebase.firestore.DocumentSnapshot): Boolean =
    doc.contains("inviteCode") || doc.get("memberEmails") != null

// ── 邀請碼（與網頁端一致，App 全行程統一使用，取代舊 6 位 PIN）──────────

/** 去除易混淆字元（0/O、1/I）的邀請碼字元集，與網頁端一致 */
private const val INVITE_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** 產生格式 XXXX-XXXX 的邀請碼 */
fun generateInviteCode(): String {
    val raw = (1..8).map { INVITE_CHARSET.random() }.joinToString("")
    return "${raw.take(4)}-${raw.drop(4)}"
}

/** 邀請碼正規化（大寫、去符號）——invites 註冊表的 doc id 用這個 */
fun normalizeInviteCode(code: String): String =
    code.uppercase().filter { it.isLetterOrDigit() }
