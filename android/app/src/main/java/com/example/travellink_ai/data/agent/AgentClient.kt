package com.example.travellink_ai.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 呼叫 AMD 版網站的旅程應變 Agent（POST /api/agent/replan，回應是 SSE 串流）。
 *
 * 回傳的 Flow 依序送出每個事件，最後一個一定是結尾事件（proposal／question／no_change／error）。
 * HTTP 錯誤、連不上、串流中斷都轉成 [AgentEvent.Error]，畫面只要處理一種結尾；
 * 取消收集（使用者按停止、離開畫面）會立刻斷線。
 *
 * 工研院端點網址只在代理伺服器，App 只認得公開的網站網域。代理要求 Firebase ID token，
 * 並限制每 IP 10 分鐘 15 次執行。
 */
object AgentClient {
    private const val ENDPOINT = "https://travel-link-amd.duckdns.org/api/agent/replan"

    fun replan(request: AgentRequest, idToken: String): Flow<AgentEvent> = callbackFlow {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000          // 後端整次執行上限 60 秒，事件之間不會隔這麼久
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $idToken")
        }
        val job = launch(Dispatchers.IO) {
            var ended = false
            try {
                conn.outputStream.use { it.write(request.toJson().toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                if (status !in 200..299) {
                    val raw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    send(httpError(status, raw))
                    ended = true
                    return@launch
                }
                val parser = SseParser()
                InputStreamReader(conn.inputStream, Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (!ended) {
                        val n = reader.read(buf)
                        val payloads = if (n < 0) parser.finish() else parser.feed(String(buf, 0, n))
                        for (p in payloads) {
                            val e = AgentEvents.parse(p) ?: continue
                            send(e)
                            if (e.isTerminal) { ended = true; break }
                        }
                        if (n < 0) break
                    }
                }
                if (!ended) { send(AgentEvent.Error("和 AI 代理人的連線中斷了，請再試一次。")); ended = true }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!ended) runCatching { send(AgentEvent.Error("連不上 AI 代理人：${e.message ?: e.javaClass.simpleName}")) }
            } finally {
                close()
            }
        }
        awaitClose {
            job.cancel()
            conn.disconnect()   // 中斷阻塞中的 read
        }
    }

    private fun httpError(status: Int, raw: String): AgentEvent.Error {
        val msg = runCatching { JSONObject(raw).optString("message") }.getOrNull().orEmpty()
        return AgentEvent.Error(
            when (status) {
                401 -> msg.ifBlank { "登入狀態失效，請重新登入。" }
                429 -> msg.ifBlank { "AI 代理人使用太頻繁，請稍後再試。" }
                // 後端的 400 message 可以直接顯示（例如缺 id、時間沒帶時區）
                400 -> msg.ifBlank { "送出的行程格式有誤（${runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()}）" }
                else -> "AI 代理人暫時無法使用（HTTP $status）"
            },
            status = status
        )
    }
}
