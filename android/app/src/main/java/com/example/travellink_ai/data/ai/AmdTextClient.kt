package com.example.travellink_ai.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AMD 模式的文字生成：送 Gemini 格式到 AMD 版網站（travel-link-amd）的 /api/vertex 代理。
 *
 * 代理在 AI_PROVIDER=amd 時，把 `gemini-3-flash-preview` 的請求轉成 OpenAI 格式送給工研院的
 * gpt-oss-120b，再把回應轉回 Gemini 格式（見 TravelLink_AI-AMD 的 server/amd-llm.js）。
 * 只有這個模型名稱會被轉到 AMD，送其他名稱會被代理的白名單擋下。
 *
 * 工研院端點網址只放在代理伺服器的 .env，App 只認得公開的網站網域。
 * 代理要求 Firebase ID token（與網頁同一個 Firebase 專案），並限制每 IP 10 分鐘 20 次。
 */
object AmdTextClient {
    private const val ENDPOINT = "https://travel-link-amd.duckdns.org/api/vertex/v1/publishers/google/models/" +
        "gemini-3-flash-preview:generateContent"

    data class Result(
        val text: String,
        /** 端點實際回報的模型（應為 openai/gpt-oss-120b）：真的打到 AMD 的證據 */
        val modelVersion: String,
        val promptTokens: Long,
        val outputTokens: Long,
        val thoughtsTokens: Long
    )

    class AmdTextException(message: String, val status: Int) : Exception(message)

    suspend fun generate(
        prompt: String,
        idToken: String,
        temperature: Double,
        responseMimeType: String?
    ): Result = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                if (responseMimeType != null) put("responseMimeType", responseMimeType)
            })
        }
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 150_000       // gpt-oss 會先推理，長 prompt 可能要數十秒
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $idToken")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val msg = runCatching { JSONObject(raw).optString("message") }.getOrNull().orEmpty()
                throw AmdTextException(
                    when (status) {
                        401 -> "AMD 代理：登入狀態失效（$msg）"
                        429 -> "AMD 代理：請求太頻繁，請稍後再試"
                        else -> "AMD 代理 HTTP $status：${msg.ifBlank { raw.take(200) }}"
                    }, status
                )
            }
            val json = JSONObject(raw)
            val cand = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AmdTextException("AMD 代理回應沒有 candidates", status)
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
                for (i in 0 until (parts?.length() ?: 0)) append(parts!!.optJSONObject(i)?.optString("text").orEmpty())
            }
            if (text.isBlank()) throw AmdTextException("AMD 代理回應為空（finishReason=${cand.optString("finishReason")}）", status)
            val usage = json.optJSONObject("usageMetadata")
            Result(
                text = text,
                modelVersion = json.optString("modelVersion"),
                promptTokens = usage?.optLong("promptTokenCount") ?: 0,
                outputTokens = usage?.optLong("candidatesTokenCount") ?: 0,
                thoughtsTokens = usage?.optLong("thoughtsTokenCount") ?: 0
            )
        } finally {
            conn.disconnect()
        }
    }
}
