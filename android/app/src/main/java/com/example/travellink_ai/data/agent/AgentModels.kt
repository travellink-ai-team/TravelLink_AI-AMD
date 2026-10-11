package com.example.travellink_ai.data.agent

import org.json.JSONArray
import org.json.JSONObject

/*
 * 旅程應變 Agent（AMD 組）的請求與事件。後端在 TravelLink_AI-AMD 的 server/agent/，
 * 格式以 docs/amd-agent/frontend-handoff.md 與 server/agent/loop.js 為準；網頁與 App 共用同一個後端。
 */

// ── 請求 ───────────────────────────────────────────────────────────

/**
 * 送出的一站（T4 規格第 2 節）。id 是 Firestore 的 collabStopId，兩端必須一樣，提案才套得回去。
 */
data class AgentStop(
    val id: String,
    val day: Int,                 // 1-based
    val time: String,             // HH:mm，原定計畫的時間；順延一律由後端算
    val stayMin: Int,
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    /** scenic｜food｜transit */
    val stopType: String? = null,
    /** 錨點站：station（出發／回程車站）｜lodging（住宿）｜ferry（船班港口），一律固定 */
    val anchor: String? = null,
    /** 營業時間原文：後端優先用它判斷公休，兩端依據一致 */
    val businessHours: String? = null,
    /** 使用者手動改過時間（Firestore manualStartMin 不是 null） */
    val timeLocked: Boolean = false,
    val keepReason: String? = null,
    /** 到陣列裡下一站的移動分鐘數（App 排程用的同一份車程）；不相鄰、估算或跨日就不送 */
    val transitToNextMin: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("day", day); put("time", time); put("stayMin", stayMin); put("name", name)
        if (lat != null && lng != null) { put("lat", lat); put("lng", lng) }
        if (!stopType.isNullOrBlank()) put("stopType", stopType)
        if (!anchor.isNullOrBlank()) put("anchor", anchor)
        if (!businessHours.isNullOrBlank()) put("businessHours", businessHours)
        if (timeLocked) put("timeLocked", true)
        if (!keepReason.isNullOrBlank()) put("keepReason", keepReason)
        transitToNextMin?.let { put("transitToNextMin", it) }
    }
}

data class AgentTrip(
    val title: String,
    /** 台東／綠島／蘭嶼：離島行程後端靠 region 與港口站判斷搭船 */
    val region: String,
    val startDate: String,        // yyyy-MM-dd
    val endTime: String,          // HH:mm，每天的結束時間
    val people: Int,
    val budgetPerPerson: Int? = null,
    val stops: List<AgentStop>,
    /** Firestore updatedAt 的毫秒（Timestamp.toMillis）；套用前比對，不同就代表行程被改過 */
    val updatedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title); put("region", region); put("startDate", startDate)
        put("endTime", endTime); put("people", people)
        if (budgetPerPerson != null) put("budgetPerPerson", budgetPerPerson)
        if (updatedAt != null) put("updatedAt", updatedAt)
        put("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } })
    }
}

sealed class AgentTrigger {
    /** 「檢查天氣並調整」：程式先檢查，沒問題就不呼叫 AI */
    object Weather : AgentTrigger()
    /** 使用者打字說需求，例如「好累，想早點回飯店」；回答 question 時把原問題一起帶上 */
    data class User(val message: String) : AgentTrigger()

    /**
     * 旅途中延誤（T4 規格第 2 節）：只處理 [day] 這一天。時間一律帶日期與 +08:00；
     * now 用用戶端時間（demo 時是假時鐘）。
     */
    data class Delay(
        val day: Int,
        val now: String,
        val from: DelayFrom,
        val returnTrain: Deadline? = null,
        val lastFerry: Deadline? = null,
        val appConflicts: List<AppConflict> = emptyList()
    ) : AgentTrigger()

    fun toJson(): JSONObject = when (this) {
        Weather -> JSONObject().put("type", "weather")
        is User -> JSONObject().put("type", "user").put("message", message.take(300))
        is Delay -> JSONObject().apply {
            put("type", "delay"); put("source", "app"); put("day", day); put("now", now)
            put("from", JSONObject().apply {
                put("stopId", from.stopId); put("name", from.name)
                if (from.lat != null && from.lng != null) { put("lat", from.lat); put("lng", from.lng) }
                put("leaveAt", from.leaveAt); put("delayMin", from.delayMin)
                from.transitToNextMin?.let { put("transitToNextMin", it) }
            })
            returnTrain?.let { put("returnTrain", JSONObject().put("departAt", it.departAt).put("station", it.place)) }
            lastFerry?.let { put("lastFerry", JSONObject().put("departAt", it.departAt).put("harbor", it.place)) }
            if (appConflicts.isNotEmpty()) put("appConflicts", JSONArray().apply {
                appConflicts.forEach { c ->
                    put(JSONObject().put("kind", c.kind).put("message", c.message).apply { c.stopId?.let { put("stopId", it) } })
                }
            })
        }
    }
}

/** 目前所在、尚未離開的站。delayMin＝leaveAt − 原定離開時間 */
data class DelayFrom(
    val stopId: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val leaveAt: String,
    val delayMin: Int,
    /** 目前所在站到 stops[0] 的移動分鐘數；stops[0] 不是它在 App 行程裡的下一站就不送 */
    val transitToNextMin: Int? = null
)

/** 回程火車（place＝車站）或末班船（place＝港口）的開車時間 */
data class Deadline(val departAt: String, val place: String)

/** 用戶端自己算出的衝突：後端只記錄，以後端重算為準 */
data class AppConflict(val kind: String, val stopId: String?, val message: String)

/** demo 情境注入：只替換工具回傳的天氣／海象，Agent 流程照常真實執行，提案會標 simulated */
data class RainScenario(val date: String, val from: String, val to: String, val pop: Int = 80)
data class SeaScenario(val date: String, val waveMaxM: Double = 3.5, val gustMax: Int = 9)

data class AgentRequest(
    val trip: AgentTrip,
    val trigger: AgentTrigger,
    val rain: RainScenario? = null,
    val sea: SeaScenario? = null,
    /** demo：固定延誤 N 分鐘（後端忽略 from.leaveAt），提案標 simulated */
    val delayMinutes: Int? = null
) {
    val simulated: Boolean get() = rain != null || sea != null || delayMinutes != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("trip", trip.toJson())
        put("trigger", trigger.toJson())
        if (simulated) put("scenario", JSONObject().apply {
            rain?.let { put("rain", JSONObject().put("date", it.date).put("from", it.from).put("to", it.to).put("pop", it.pop)) }
            sea?.let { put("sea", JSONObject().put("date", it.date).put("waveMaxM", it.waveMaxM).put("gustMax", it.gustMax)) }
            delayMinutes?.let { put("delay", JSONObject().put("minutes", it)) }
        })
    }
}

// ── 事件 ───────────────────────────────────────────────────────────

/** 結尾事件附帶的執行資訊。model 是常數標籤；upstreamModels 才是端點實際回報的模型（證據用） */
data class RunMeta(
    val llmCalls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val steps: Int,
    val model: String,
    val upstreamModels: List<String>
)

/** 提案的修改前後對照。type：replace／remove／insert／retime／move */
data class AgentChange(
    val type: String,
    val day: Int?,
    val fromDay: Int?,            // move：原本在第幾天
    val time: String?,
    val name: String?,            // retime／move 的站名
    val from: String?,            // replace／remove：原站名；retime／move：原時間
    val to: String?,              // replace／insert：新站名；retime／move：新時間
    val stayFrom: Int?,
    val stayTo: Int?
)

data class CostDelta(val perPersonBefore: Int, val perPersonAfter: Int, val diff: Int, val note: String)
data class FerryLeg(val direction: String, val day: Int, val depart: String)
data class FerryChange(val before: List<FerryLeg>, val after: List<FerryLeg>, val note: String)

/** 套用用的完整草稿站。replaces＝取代的原站 id；agentAdded＝Agent 新加的站 */
data class DraftStop(
    val id: String,
    val day: Int,
    val time: String,
    val stayMin: Int,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val kind: String?,
    val replaces: String?,
    val agentAdded: Boolean,
    // 新增的站後端會附上這些（T4 規格第 3 節第 4 點），App 套用後直接顯示、不必再查 Google
    val stopType: String? = null,
    val businessHours: String? = null,
    val desc: String? = null,
    val emoji: String? = null,
    val anchor: String? = null
)

sealed class AgentEvent {
    /** 從開始算起的毫秒 */
    abstract val ms: Long
    /** 結尾事件之後串流就結束 */
    open val isTerminal: Boolean get() = false

    data class Start(val trigger: String?, val simulated: Boolean, val stops: Int, override val ms: Long) : AgentEvent()
    data class Check(val label: String, override val ms: Long) : AgentEvent()
    data class CheckResult(val label: String, val issues: List<String>, override val ms: Long) : AgentEvent()
    data class ToolCall(val tool: String, val label: String, override val ms: Long) : AgentEvent()
    data class ToolResult(val tool: String, val ok: Boolean, val detail: String, override val ms: Long) : AgentEvent()
    data class Fallback(val label: String, override val ms: Long) : AgentEvent()

    data class Proposal(
        val summary: String,
        val reasons: List<String>,
        val changes: List<AgentChange>,
        val costDelta: CostDelta?,
        val warnings: List<String>,
        val fallback: Boolean,
        val simulated: Boolean,
        val ferry: FerryChange?,
        val extraNights: Int,
        val draftStops: List<DraftStop>,
        val meta: RunMeta?,
        override val ms: Long,
        /** T4：只要順延就排得下，changes 只有 retime */
        val retimeOnly: Boolean = false,
        /** 程式檢查就解決了，沒有呼叫 AI */
        val llmSkipped: Boolean = false
    ) : AgentEvent() { override val isTerminal get() = true }

    data class Question(val question: String, val options: List<String>, val meta: RunMeta?, override val ms: Long) : AgentEvent() {
        override val isTerminal get() = true
    }
    data class NoChange(val reason: String, val llmSkipped: Boolean, val meta: RunMeta?, override val ms: Long) : AgentEvent() {
        override val isTerminal get() = true
    }
    /** status：HTTP 狀態碼（後端回的 error 事件為 null） */
    data class Error(val message: String, val status: Int? = null, val meta: RunMeta? = null, override val ms: Long = 0) : AgentEvent() {
        override val isTerminal get() = true
    }
    /** 後端新加、App 還不認得的事件：略過即可，不要讓整個面板壞掉 */
    data class Unknown(val type: String, override val ms: Long) : AgentEvent()
}

object AgentEvents {
    fun parse(json: String): AgentEvent? = runCatching { parse(JSONObject(json)) }.getOrNull()

    fun parse(o: JSONObject): AgentEvent {
        val ms = o.optLong("ms")
        val meta = o.optJSONObject("usage")?.let { u ->
            RunMeta(
                llmCalls = u.optInt("llmCalls"),
                promptTokens = u.optLong("promptTokens"),
                completionTokens = u.optLong("completionTokens"),
                steps = o.optInt("steps"),
                model = o.optString("model"),
                upstreamModels = o.optJSONArray("upstreamModels").strings()
            )
        }
        return when (val type = o.optString("type")) {
            "start" -> AgentEvent.Start(o.optStringOrNull("trigger"), o.optBoolean("simulated"), o.optInt("stops"), ms)
            "check" -> AgentEvent.Check(o.optString("label"), ms)
            "check_result" -> AgentEvent.CheckResult(o.optString("label"), o.optJSONArray("issues").strings(), ms)
            "tool_call" -> AgentEvent.ToolCall(o.optString("tool"), o.optString("label"), ms)
            "tool_result" -> AgentEvent.ToolResult(o.optString("tool"), o.optBoolean("ok", true), o.optString("detail"), ms)
            "fallback" -> AgentEvent.Fallback(o.optString("label"), ms)
            "proposal" -> AgentEvent.Proposal(
                summary = o.optString("summary"),
                reasons = o.optJSONArray("reasons").strings(),
                changes = o.optJSONArray("changes").objects().map { c ->
                    AgentChange(
                        type = c.optString("type"),
                        day = c.optIntOrNull("day"),
                        fromDay = c.optIntOrNull("fromDay"),
                        time = c.optStringOrNull("time"),
                        name = c.optStringOrNull("name"),
                        from = c.optStringOrNull("from"),
                        to = c.optStringOrNull("to"),
                        stayFrom = c.optIntOrNull("stayFrom"),
                        stayTo = c.optIntOrNull("stayTo")
                    )
                },
                costDelta = o.optJSONObject("costDelta")?.let {
                    CostDelta(it.optInt("perPersonBefore"), it.optInt("perPersonAfter"), it.optInt("diff"), it.optString("note"))
                },
                warnings = o.optJSONArray("warnings").strings(),
                fallback = o.optBoolean("fallback"),
                simulated = o.optBoolean("simulated"),
                ferry = o.optJSONObject("ferry")?.let { f ->
                    fun legs(k: String) = f.optJSONArray(k).objects().map {
                        FerryLeg(it.optString("direction"), it.optInt("day"), it.optString("depart"))
                    }
                    FerryChange(legs("before"), legs("after"), f.optString("note"))
                },
                extraNights = o.optInt("extraNights"),
                draftStops = o.optJSONObject("draft")?.optJSONArray("stops").objects().map { s ->
                    DraftStop(
                        id = s.optString("id"),
                        day = s.optInt("day", 1),
                        time = s.optString("time"),
                        stayMin = s.optInt("stayMin"),
                        name = s.optString("name"),
                        lat = s.optDoubleOrNull("lat"),
                        lng = s.optDoubleOrNull("lng"),
                        kind = s.optStringOrNull("kind"),
                        replaces = s.optStringOrNull("replaces"),
                        agentAdded = s.optBoolean("agentAdded"),
                        stopType = s.optStringOrNull("stopType"),
                        businessHours = s.optStringOrNull("businessHours"),
                        desc = s.optStringOrNull("desc"),
                        emoji = s.optStringOrNull("emoji"),
                        anchor = s.optStringOrNull("anchor")
                    )
                },
                meta = meta,
                ms = ms,
                retimeOnly = o.optBoolean("retimeOnly"),
                llmSkipped = o.optBoolean("llmSkipped")
            )
            "question" -> AgentEvent.Question(o.optString("question"), o.optJSONArray("options").strings(), meta, ms)
            "no_change" -> AgentEvent.NoChange(o.optString("reason"), o.optBoolean("llmSkipped"), meta, ms)
            "error" -> AgentEvent.Error(o.optString("message").ifBlank { "AI 代理人發生錯誤" }, null, meta, ms)
            else -> AgentEvent.Unknown(type, ms)
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) optString(k) else null

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (has(k) && !isNull(k)) optInt(k) else null

    private fun JSONObject.optDoubleOrNull(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
}
