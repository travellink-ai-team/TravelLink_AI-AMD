package com.example.travellink_ai.ui.recap

import com.example.travellink_ai.BuildConfig
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回顧短片後端 client。對齊網頁端三個 endpoint（POST /recap/render、
 * GET /recap/jobs/{id}、GET /recap/jobs/{id}/download），Bearer Firebase idToken。
 *
 * ★ base URL 來自 BuildConfig.RECAP_API_BASE（local.properties 的 RECAP_API_BASE）。
 *   目前為空 → [isConfigured] false，UI 應顯示「功能尚未啟用」而非讓使用者按了報錯。
 *   等組員給後端公開 URL，填進 local.properties 即可接上，程式不動。
 */
@Singleton
class RecapApiClient @Inject constructor() {

    private val gson = Gson()
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000   // 下載 mp4 可能較久
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    val isConfigured: Boolean get() = BuildConfig.RECAP_API_BASE.isNotBlank()

    private fun base() = BuildConfig.RECAP_API_BASE.trimEnd('/')

    /** POST /recap/render → jobId。 */
    suspend fun createJob(request: RecapRenderRequest, idToken: String): RecapJobCreated {
        val resp = http.post("${base()}/recap/render") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(gson.toJson(request))
        }
        check(resp.status.isSuccess()) { "建立影片失敗（${resp.status.value}）" }
        return gson.fromJson(resp.bodyAsText(), RecapJobCreated::class.java)
    }

    /** GET /recap/jobs/{jobId} → 狀態。 */
    suspend fun getStatus(jobId: String, idToken: String): RecapJobStatus {
        val resp = http.get("${base()}/recap/jobs/$jobId") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
        }
        check(resp.status.isSuccess()) { "查詢狀態失敗（${resp.status.value}）" }
        return gson.fromJson(resp.bodyAsText(), RecapJobStatus::class.java)
    }

    /** GET /recap/jobs/{jobId}/download → mp4 bytes。 */
    suspend fun download(jobId: String, idToken: String): ByteArray {
        val resp = http.get("${base()}/recap/jobs/$jobId/download") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
        }
        check(resp.status.isSuccess()) { "下載影片失敗（${resp.status.value}）" }
        return resp.readBytes()
    }
}
