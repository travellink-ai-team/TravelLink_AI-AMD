package com.example.travellink_ai.data.agent

import android.content.Context
import com.example.travellink_ai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/**
 * 開發版限定：重播 Agent 事件，不打後端（連不到 AI、不想吃限流、或 demo 要保證結果時用）。
 *
 * fixture 在 app/src/debug/assets/agent_fixtures/（正式版不打包）：
 *  - rain／tired／no-issue／ferry：後端 server/test/fixtures 由 gpt-oss-120b 實際執行錄下的事件
 *  - question／fallback／error：App 端合成，只為了測畫面（fixture 裡標 synthetic: true）
 * 照每個事件的 ms 間隔送出，與 [AgentClient] 是同一種 Flow，畫面不用分兩套。
 */
object AgentMock {
    val NAMES = listOf("rain", "tired", "no-issue", "ferry", "question", "fallback", "error")

    /** 錄製的事件（非合成）；畫面可據此標「重播的真實執行」或「合成事件」 */
    fun isRecorded(context: Context, name: String): Boolean =
        runCatching { !load(context, name).optBoolean("synthetic") }.getOrDefault(false)

    /**
     * @param speed 播放倍速（1＝真實節奏）
     * @param maxGapMs 兩個事件之間最多等多久（fallback 合成事件裡有 60 秒逾時，demo 不必真的等）
     */
    fun replay(context: Context, name: String, speed: Double = 1.0, maxGapMs: Long = 3_000): Flow<AgentEvent> = flow {
        if (!BuildConfig.DEBUG) {
            emit(AgentEvent.Error("mock 只在開發版提供"))
            return@flow
        }
        val events = runCatching { load(context, name).getJSONArray("events") }.getOrElse {
            emit(AgentEvent.Error("找不到 mock「$name」：${it.message}"))
            return@flow
        }
        var prevMs = 0L
        for (i in 0 until events.length()) {
            val e = AgentEvents.parse(events.getJSONObject(i))
            delay(((e.ms - prevMs).coerceAtLeast(0) / speed).toLong().coerceAtMost(maxGapMs))
            prevMs = e.ms
            emit(e)
            if (e.isTerminal) return@flow
        }
        emit(AgentEvent.Error("mock「$name」沒有結尾事件"))
    }.flowOn(Dispatchers.IO)

    private fun load(context: Context, name: String): JSONObject =
        JSONObject(context.assets.open("agent_fixtures/$name.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
}
