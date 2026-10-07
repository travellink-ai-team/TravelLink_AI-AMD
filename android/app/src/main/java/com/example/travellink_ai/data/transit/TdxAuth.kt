package com.example.travellink_ai.data.transit

import android.util.Log
import com.example.travellink_ai.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * TDX OAuth2 token 的單一持有者（process 級）。
 *
 * TDX 免費帳號的 rate limit 很緊（實測約十幾次呼叫就會回 API rate limit exceeded），
 * 所以停車場查詢與台鐵班次查詢必須共用同一份 token，不能各自去換。
 * ItineraryViewModel.getTdxToken() 也委派到這裡。
 */
object TdxAuth {
    private const val TOKEN_URL =
        "https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token"
    private const val TAG = "TdxAuth"

    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt: Long = 0L

    // ── 節流：所有 TDX 呼叫（換 token、停車場、台鐵）共用同一個節奏 ─────────────
    // 實測 2026-09-24：開行程頁時換 token＋停車場（每站 2 支）約 1.5 秒內打了 7 次就被擋，
    // 之後每一站都回「API rate limit exceeded」，8 站只查到 2 站。原本每站之間 delay 300ms，
    // 但一站有兩支 API、台鐵查詢又是另一條路，彼此不知道對方，所以改成全域排隊。
    private val paceMutex = Mutex()
    private var lastCallAt = 0L
    const val MIN_INTERVAL_MS = 600L
    /** 被限流後等多久再重試（只重試一次） */
    const val RATE_LIMIT_BACKOFF_MS = 3000L

    /** 每次打 TDX 前呼叫：距離上一次呼叫不足 [MIN_INTERVAL_MS] 就先等 */
    suspend fun awaitTurn() = paceMutex.withLock {
        val wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCallAt = System.currentTimeMillis()
    }

    /** 回應是不是「被限流」（{"message":"API rate limit exceeded"}） */
    fun isRateLimited(body: String) = body.contains("rate limit", ignoreCase = true)

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis  = 15000
            }
        }
    }

    /** 取得或續用 access token；未設定金鑰或取得失敗時回 null（呼叫端一律以「不顯示」處理）。 */
    suspend fun getToken(): String? {
        val id     = BuildConfig.TDX_CLIENT_ID
        val secret = BuildConfig.TDX_CLIENT_SECRET
        if (id.isBlank() || secret.isBlank()) return null

        // 快速路徑：token 仍有效，不必進鎖
        if (token != null && System.currentTimeMillis() < expiresAt) return token

        return mutex.withLock {
            // 再次檢查，另一個協程可能已在鎖內換好
            if (token != null && System.currentTimeMillis() < expiresAt) return@withLock token
            try {
                awaitTurn()
                val response: HttpResponse = client.submitForm(
                    url = TOKEN_URL,
                    formParameters = io.ktor.http.parameters {
                        append("grant_type",    "client_credentials")
                        append("client_id",     id)
                        append("client_secret", secret)
                    }
                )
                val json = JSONObject(response.bodyAsText())
                val newToken = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: return@withLock null
                val expiresIn = json.optLong("expires_in", 300L)
                token = newToken
                expiresAt = System.currentTimeMillis() + (expiresIn - 30) * 1000L
                Log.d(TAG, "✅ TDX token 取得成功")
                newToken
            } catch (e: Exception) {
                Log.e(TAG, "❌ TDX token 取得失敗: ${e.message}")
                null
            }
        }
    }
}
