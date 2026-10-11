package com.example.travellink_ai.ui.agent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.agent.AgentClient
import com.example.travellink_ai.data.agent.AgentEvent
import com.example.travellink_ai.data.agent.AgentMock
import com.example.travellink_ai.data.agent.AgentRequest
import com.example.travellink_ai.data.agent.AgentTrigger
import com.example.travellink_ai.data.agent.AgentRouting
import com.example.travellink_ai.data.agent.AgentTripMapper
import com.example.travellink_ai.data.agent.Deadline
import com.example.travellink_ai.data.agent.DelayFrom
import com.example.travellink_ai.data.agent.DraftStop
import com.example.travellink_ai.data.agent.RainScenario
import com.example.travellink_ai.data.agent.SeaScenario
import com.example.travellink_ai.data.model.Itinerary
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** 一次旅程應變 Agent 的執行（畫面上的動態面板＋結尾卡片） */
data class AgentRun(
    val request: AgentRequest?,
    val sentIds: Set<String>,
    /** null＝真後端；否則是重播的 mock 名稱 */
    val mock: String?,
    /** mock 是後端實際錄下的（false＝App 合成，只為測畫面） */
    val mockRecorded: Boolean,
    val events: List<AgentEvent> = emptyList(),
    val running: Boolean = true,
    val stoppedByUser: Boolean = false,
    val applying: Boolean = false,
    val applied: Boolean = false,
    /** 套用被拒絕的原因（被鎖、行程被改過…） */
    val applyError: String? = null,
    val startedAtMs: Long = System.currentTimeMillis()
) {
    val terminal: AgentEvent? get() = events.lastOrNull()?.takeIf { it.isTerminal }
}

/**
 * 旅程應變 Agent（AMD 組）：送出前準備、送出請求、逐筆接收事件、套用。
 * 準備與套用（collabStopId 補寫、共編鎖、updatedAt 比對）由 ItineraryViewModel 負責。
 * 開發版可改成重播 mock（[mockChoice]），demo 情境（模擬下雨／停航／延誤）也在這裡設定。
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    application: Application,
    private val auth: FirebaseAuth
) : AndroidViewModel(application) {

    private val tag = "TravelLink_Agent"

    private val _run = MutableStateFlow<AgentRun?>(null)
    val run: StateFlow<AgentRun?> = _run.asStateFlow()

    /** 開發版：null＝真後端，"auto"＝依觸發自動挑錄製的 mock，其他＝指定 mock 名稱 */
    private val _mockChoice = MutableStateFlow<String?>(null)
    val mockChoice: StateFlow<String?> = _mockChoice.asStateFlow()
    fun setMockChoice(v: String?) { if (BuildConfig.DEBUG) _mockChoice.value = v }

    /** demo 情境注入：模擬下雨（當天 10:00–17:00、80%）、回程日浪高 3.5 公尺、固定延誤 60 分鐘 */
    private val _simRain = MutableStateFlow(false)
    val simRain: StateFlow<Boolean> = _simRain.asStateFlow()
    private val _simSea = MutableStateFlow(false)
    val simSea: StateFlow<Boolean> = _simSea.asStateFlow()
    private val _simDelay = MutableStateFlow(false)
    val simDelay: StateFlow<Boolean> = _simDelay.asStateFlow()
    fun setSimRain(v: Boolean) { _simRain.value = v }
    fun setSimSea(v: Boolean) { _simSea.value = v }
    fun setSimDelay(v: Boolean) { _simDelay.value = v }

    private var job: Job? = null

    // ── 主動通知（AgentAlert）───────────────────────────────────────────
    private val notifiedConflictKeys = mutableSetOf<String>()

    /** 這組衝突裡有沒有還沒通知過的；有就記下來並回 true（同一組衝突只通知一次） */
    fun shouldNotify(conflictKeys: Set<String>): Boolean {
        val fresh = conflictKeys - notifiedConflictKeys
        if (fresh.isEmpty()) return false
        notifiedConflictKeys += conflictKeys
        return true
    }

    // ── 隨行管家整合：行程頁登記目前的行程，管家／延誤提醒／超時警告／通知都從這裡啟動 ──

    /**
     * 行程頁交給 Agent 的資料（只有 AMD 模式、非唯讀成員才登記）。
     * @param doneIds 行程進行中已打卡／跳過的站（不送給 Agent、套用時不動）
     * @param delay 行程進行中且有目前所在的站時才有（T4 延誤）
     * @param returnTrain 查「必須搭上的那班」回程火車：參數是當天日期 yyyy-MM-dd，查不到回 null
     * @param apply 套用提案（共編鎖、updatedAt 比對、固定時間規則），回傳 null＝成功，否則是拒絕原因
     */
    class AgentHost(
        val itinerary: Itinerary,
        val doneIds: Set<String>,
        val delay: DelayInput?,
        val prepare: suspend () -> ItineraryViewModel.AgentPrep,
        val returnTrain: suspend (dateIso: String) -> Deadline?,
        val apply: suspend (Set<String>, List<DraftStop>, Long?, AgentTripMapper.TimeLock?) -> String?
    )

    /** 聊天室要知道的：能不能調整、離島（停航模擬）、有沒有延誤資訊（延誤模擬） */
    data class HostInfo(val canAdjust: Boolean, val isIsland: Boolean, val hasDelay: Boolean)

    @Volatile private var host: AgentHost? = null
    private val _hostInfo = MutableStateFlow<HostInfo?>(null)
    val hostInfo: StateFlow<HostInfo?> = _hostInfo.asStateFlow()

    fun setHost(h: AgentHost?) {
        host = h
        _hostInfo.value = h?.let {
            val built = AgentTripMapper.build(it.itinerary, it.doneIds)
            HostInfo(
                canAdjust = built != null,
                isIsland = built?.trip?.region.let { r -> r == "綠島" || r == "蘭嶼" },
                hasDelay = it.delay != null
            )
        }
    }

    /**
     * 管家聊天室裡的一次調整：使用者那句話（或按的按鈕）＋這次執行。
     * 正在跑的那次由畫面直接顯示 [run]；被下一次取代或放棄後，留 [summary] 一句話。
     */
    data class ChatEntry(
        val ts: Long,
        val userText: String?,
        val runStartedAt: Long? = null,
        val summary: String? = null
    )
    private val _chat = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chat: StateFlow<List<ChatEntry>> = _chat.asStateFlow()

    private fun note(userText: String?, text: String) {
        _chat.value = _chat.value + ChatEntry(System.currentTimeMillis(), userText, summary = text)
    }

    /** 目前這次執行收尾成一句話（畫面不再顯示它的面板） */
    private fun closeCurrentEntry(summary: String? = null) {
        val r = _run.value ?: return
        val text = summary ?: summaryOf(r)
        _chat.value = _chat.value.map { if (it.runStartedAt == r.startedAtMs && it.summary == null) it.copy(summary = text) else it }
    }

    private fun summaryOf(r: AgentRun): String = when (val t = r.terminal) {
        is AgentEvent.Proposal ->
            if (r.applied) "✓ 已套用：${AgentRouting.displayText(t.summary)}"
            else "提過方案：${AgentRouting.displayText(t.summary)}（沒有套用）"
        is AgentEvent.Question -> "問過：${AgentRouting.displayText(t.question)}"
        is AgentEvent.NoChange -> "✅ ${AgentRouting.displayText(t.reason)}"
        is AgentEvent.Error -> "⚠️ ${AgentRouting.displayText(t.message)}"
        else -> if (r.stoppedByUser) "已停止這次調整" else "這次調整沒有完成"
    }

    /** 開始前的共同檢查；可以開始回 host，不行就在聊天室說明原因並回 null */
    private fun readyHost(userText: String?): AgentHost? {
        val h = host
        when {
            h == null -> note(userText, "目前沒有開啟行程，沒辦法調整。先打開一份行程再試試。")
            _run.value?.running == true -> note(userText, "上一個調整還在進行中，等它跑完再試一次。")
            _hostInfo.value?.canAdjust != true -> note(userText, "這份行程沒有日期或可以調整的站，暫時沒辦法調整。")
            else -> return h
        }
        return null
    }

    private fun todayDay(h: AgentHost) =
        h.itinerary.stops.firstOrNull { AgentTripMapper.isSendable(it) && it.stopId !in h.doneIds }?.dayIndex ?: 1

    private fun recordStart(userText: String?) {
        val r = _run.value ?: return
        // 沒有真的開始新的一次（例如回答時找不到原本的請求）：不要把舊的那次再記一筆
        if (_chat.value.any { it.runStartedAt == r.startedAtMs }) return
        _chat.value = _chat.value + ChatEntry(System.currentTimeMillis(), userText, runStartedAt = r.startedAtMs)
    }

    /** 管家：「🌦️ 檢查天氣並調整」 */
    fun startWeather(userText: String = "🌦️ 檢查天氣並調整") {
        val h = readyHost(userText) ?: return
        val built = AgentTripMapper.build(h.itinerary, h.doneIds) ?: return
        closeCurrentEntry()
        start(built, AgentTrigger.Weather, h.prepare, todayDay(h))
        recordStart(userText)
    }

    /**
     * 管家：要改行程的一句話（好累、刪站…），或超時警告的「讓 AI 調整」。
     * @param endTime 改送的收工時間 HH:mm（超時時送使用者設定的，否則後端看不出超時）
     */
    fun startUser(message: String, userText: String = message, endTime: String? = null) {
        val h = readyHost(userText) ?: return
        val built = AgentTripMapper.build(h.itinerary, h.doneIds) ?: return
        closeCurrentEntry()
        val b = if (endTime == null) built else built.copy(trip = built.trip.copy(endTime = endTime))
        start(b, AgentTrigger.User(message.take(300)), h.prepare, todayDay(h))
        recordStart(userText)
    }

    /** 延誤提醒「請 AI 處理延誤」或通知的「讓 AI 處理」（T4） */
    fun startDelay(userText: String = "⏱️ 請 AI 處理延誤") {
        val h = readyHost(userText) ?: return
        val d = h.delay ?: return note(userText, "要在行程進行中、已經打卡到某一站，才能處理延誤。")
        val built = AgentTripMapper.buildDelay(h.itinerary, h.doneIds, d.here, d.day)
            ?: return note(userText, "這一站之後沒有可以調整的站了。")
        closeCurrentEntry()
        val date = AgentTripMapper.dateOfDay(built.trip.startDate, d.day)
        start(built, delayTrigger(d, built.trip.startDate), h.prepare) { t ->
            // 回程火車要查時刻表，送出前才補（只在回程那天）
            if (t is AgentTrigger.Delay && d.isLastDay) t.copy(returnTrain = h.returnTrain(date)) else t
        }
        recordStart(userText)
    }

    /** 提問的選項：後端不保存對話，帶著原問題重送（聊天室多一則使用者的回答） */
    fun answerInChat(option: String) {
        val h = host ?: return
        closeCurrentEntry()
        answer(option, h.prepare)
        recordStart(option)
    }

    /** 提案卡「套用」 */
    fun applyCurrent() {
        val h = host ?: return
        apply(h.apply)
    }

    /** 提案卡「放棄」／面板「關閉」：聊天室留一句話，行程不動 */
    fun closeInChat() {
        val r = _run.value ?: return
        closeCurrentEntry(
            if (r.terminal is AgentEvent.Proposal && !r.applied) "已放棄這個方案，行程沒有更動" else null
        )
        dismiss()
    }

    /** T4 請求的 trigger：delayMin＝預計離開 − 原定離開（原定時間＋原定停留） */
    private fun delayTrigger(d: DelayInput, startDate: String): AgentTrigger.Delay {
        val date = AgentTripMapper.dateOfDay(startDate, d.day)
        val plannedLeave = (com.example.travellink_ai.ui.planning.DayPlanner.parseHhMm(d.here.time) ?: d.leaveMin) +
            d.here.duration.toInt()
        return AgentTrigger.Delay(
            day = d.day,
            now = AgentTripMapper.isoNow(d.nowMs),
            from = DelayFrom(
                stopId = d.here.stopId, name = d.here.name, lat = d.here.lat, lng = d.here.lng,
                leaveAt = AgentTripMapper.isoAt(date, d.leaveMin),
                delayMin = d.leaveMin - plannedLeave
            ),
            lastFerry = d.lastFerry,
            appConflicts = d.conflicts
        )
    }

    /** 通知上按了「讓 AI 處理」：行程頁的助理看到後送出延誤請求（使用者已按下，符合規格 §1.3） */
    private val _autoDelay = MutableStateFlow(false)
    val autoDelay: StateFlow<Boolean> = _autoDelay.asStateFlow()
    fun requestAutoDelay() { _autoDelay.value = true }
    fun consumeAutoDelay() { _autoDelay.value = false }

    /**
     * @param prepare 送出前的準備（補寫 collabStopId、取 updatedAt），見 ItineraryViewModel.prepareAgentRequest
     * @param rainDay 模擬下雨的日子（第幾天，1-based）；行程進行中傳今天
     */
    /**
     * @param finalize 送出前最後補上要非同步查的欄位（T4 的 returnTrain 要查時刻表）
     */
    fun start(
        built: AgentTripMapper.Built,
        trigger: AgentTrigger,
        prepare: suspend () -> ItineraryViewModel.AgentPrep,
        rainDay: Int = 1,
        finalize: suspend (AgentTrigger) -> AgentTrigger = { it }
    ) {
        val startDate = built.trip.startDate
        val lastDay = built.trip.stops.maxOfOrNull { it.day } ?: 1
        val isDelay = trigger is AgentTrigger.Delay
        launchRun(built.sentIds, prepare, trigger, finalize) { updatedAt, finalTrigger ->
            AgentRequest(
                trip = built.trip.copy(updatedAt = updatedAt),
                trigger = finalTrigger,
                rain = if (_simRain.value && !isDelay) RainScenario(plusDays(startDate, rainDay - 1), RAIN_FROM, "17:00", 80) else null,
                // 停航看的是回程那天
                sea = if (_simSea.value && !isDelay) SeaScenario(plusDays(startDate, lastDay - 1)) else null,
                delayMinutes = if (_simDelay.value && isDelay) SIM_DELAY_MIN else null
            )
        }
    }

    /** 回答 question：後端不保存對話，把原問題一起帶上重送（同網頁做法） */
    fun answer(option: String, prepare: suspend () -> ItineraryViewModel.AgentPrep) {
        val prev = _run.value ?: return
        val req = prev.request ?: return
        val q = prev.terminal as? AgentEvent.Question ?: return
        val answer = AgentTrigger.User("${q.question}。我的選擇：$option")
        launchRun(prev.sentIds, prepare, answer, { it }) { updatedAt, t ->
            req.copy(trip = req.trip.copy(updatedAt = updatedAt), trigger = t)
        }
    }

    /**
     * 套用提案。[apply] 回傳 null＝成功，否則是拒絕原因。
     * 延誤提案不鎖時間；天氣／文字需求提案明確改的時間要固定（T4 規格 6.4，見 TimeLock）
     */
    fun apply(apply: suspend (Set<String>, List<DraftStop>, Long?, AgentTripMapper.TimeLock?) -> String?) {
        val r = _run.value ?: return
        val p = r.terminal as? AgentEvent.Proposal ?: return
        if (r.applying || r.applied) return
        _run.value = r.copy(applying = true, applyError = null)
        val lock = r.request?.let { AgentTripMapper.TimeLock.of(it, p.changes) }
        viewModelScope.launch {
            val err = runCatching { apply(r.sentIds, p.draftStops, r.request?.trip?.updatedAt, lock) }
                .getOrElse { "套用失敗：${it.message}" }
            _run.value = _run.value?.copy(applying = false, applied = err == null, applyError = err)
        }
    }

    fun stop() {
        job?.cancel()
        _run.value = _run.value?.copy(running = false, stoppedByUser = true)
    }

    fun dismiss() {
        job?.cancel()
        _run.value = null
    }

    private fun launchRun(
        sentIds: Set<String>,
        prepare: suspend () -> ItineraryViewModel.AgentPrep,
        trigger: AgentTrigger,
        finalize: suspend (AgentTrigger) -> AgentTrigger,
        makeRequest: (updatedAt: Long?, trigger: AgentTrigger) -> AgentRequest
    ) {
        job?.cancel()
        val ctx = getApplication<Application>()
        // mock 依觸發自動挑，需要先有請求的形狀；updatedAt 與補上的欄位不影響挑選
        val probe = makeRequest(null, trigger)
        val mock = resolveMock(probe)
        _run.value = AgentRun(
            request = null, sentIds = sentIds, mock = mock,
            mockRecorded = mock != null && AgentMock.isRecorded(ctx, mock)
        )
        job = viewModelScope.launch {
            val flow = if (mock != null) {
                _run.value = _run.value?.copy(request = probe)
                AgentMock.replay(ctx, mock)
            } else {
                // 送出前：補寫缺的 collabStopId（後端缺 id 會回 400）、取 updatedAt（套用前比對）
                val prep = prepare()
                val token = runCatching { auth.currentUser?.getIdToken(false)?.await()?.token }.getOrNull()
                when {
                    prep.error != null -> flowOf(AgentEvent.Error(prep.error))
                    token == null -> flowOf(AgentEvent.Error("請先登入再使用 AI 代理人。"))
                    else -> {
                        // transitToNextMin：送出當下的車程，站序不對或有估算段時 legs 是空的（不送）
                        val req = AgentTripMapper.withTransit(makeRequest(prep.updatedAt, finalize(trigger)), prep.legs)
                        _run.value = _run.value?.copy(request = req)
                        AgentClient.replan(req, token)
                    }
                }
            }
            flow.collect { e ->
                Log.d(tag, "🤖 ${e::class.simpleName} +${e.ms}ms")
                _run.value = _run.value?.let { it.copy(events = it.events + e, running = !e.isTerminal) }
            }
            _run.value = _run.value?.copy(running = false)
        }
    }

    private fun resolveMock(request: AgentRequest): String? {
        if (!BuildConfig.DEBUG) return null
        return when (val c = _mockChoice.value) {
            null -> null
            "auto" -> when {
                request.sea != null -> "ferry"
                request.rain != null -> "rain"
                request.trigger is AgentTrigger.User -> "tired"
                else -> "no-issue"
            }
            else -> c
        }
    }

    private fun plusDays(isoDate: String, days: Int): String =
        runCatching { java.time.LocalDate.parse(isoDate).plusDays(days.toLong()).toString() }.getOrDefault(isoDate)

    companion object {
        /** demo「模擬延誤」固定的分鐘數（T4 規格 scenario.delay）；與網頁成果影片同為 60 */
        const val SIM_DELAY_MIN = 60
        /** demo「模擬下午大雨」的開始時間：同網頁成果影片（10:00–17:00），上午後段的戶外站也會被換掉 */
        const val RAIN_FROM = "10:00"
    }
}
