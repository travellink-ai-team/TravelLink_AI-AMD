package com.example.travellink_ai.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 插圖：使用者自備金鑰（BYOK），與網頁「畫圖服務」同一套做法（ai-travel-planner-v8.js 的 posterByok）。
 *
 * - 只套用在插圖的底圖（最貴的一項，約 US$0.13／張）；畫面描述、行程、管家等文字仍走我們的後端。
 * - 用自己的金鑰畫的不算每日免費額度（不經過我們的後端）。
 * - 金鑰只存在這支手機：用 Android Keystore 的 AES 金鑰加密後放 SharedPreferences（[PREFS]，
 *   已排除在雲端備份與換機之外），不上傳 Firestore、不經過我們的後端、不寫進 log。登出時清除。
 * - 由手機直接呼叫 Google AI Studio／OpenAI，金鑰放 header 不放網址。
 */
object IllustrationByok {
    enum class Provider(val id: String, val label: String, val keyHint: String, val keyUrl: String) {
        GEMINI("gemini", "Gemini", "AIza…", "https://aistudio.google.com/apikey"),
        OPENAI("openai", "OpenAI", "sk-…", "https://platform.openai.com/api-keys")
    }

    data class Entry(val key: String, val model: String)

    /** [provider]＝目前選的畫圖服務（null＝預設，用我們的後端）；[saved]＝這支手機存了哪些金鑰 */
    data class State(val provider: Provider? = null, val saved: Map<Provider, Entry> = emptyMap()) {
        /** 選了自己的金鑰而且存好了：畫圖改用它 */
        val active: Entry? get() = provider?.let { saved[it] }
        /** 選了自己的金鑰但還沒存（不能畫，要先存金鑰或改回預設） */
        val missingKey: Boolean get() = provider != null && active == null
    }

    class ByokException(message: String) : Exception(message)

    private const val PREFS = "illustration_byok"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_ALIAS = "travellink_illustration_byok"
    private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    private var appContext: Context? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = prefs() ?: return
        val saved = Provider.entries.mapNotNull { pr ->
            p.getString(pr.id, null)?.let { decrypt(it) }?.let { json ->
                runCatching { JSONObject(json) }.getOrNull()?.let { o ->
                    o.optString("key").takeIf { it.isNotBlank() }?.let { pr to Entry(it, o.optString("model")) }
                }
            }
        }.toMap()
        val provider = Provider.entries.firstOrNull { it.id == p.getString(KEY_PROVIDER, null) }
        _state.value = State(provider, saved)
    }

    /** 選畫圖服務（null＝預設）；還沒存金鑰的服務也可以先選，畫圖前會要求先存 */
    fun setProvider(provider: Provider?) {
        prefs()?.edit()?.putString(KEY_PROVIDER, provider?.id ?: "default")?.apply()
        _state.value = _state.value.copy(provider = provider)
    }

    /** 測試成功後儲存，並改用這個服務 */
    fun save(provider: Provider, entry: Entry) {
        val json = JSONObject().put("key", entry.key).put("model", entry.model).toString()
        val enc = encrypt(json) ?: throw ByokException("這支手機無法安全儲存金鑰")
        prefs()?.edit()?.putString(provider.id, enc)?.putString(KEY_PROVIDER, provider.id)?.apply()
        _state.value = State(provider, _state.value.saved + (provider to entry))
    }

    fun remove(provider: Provider) {
        prefs()?.edit()?.remove(provider.id)?.apply()
        _state.value = _state.value.copy(saved = _state.value.saved - provider)
    }

    /** 登出：金鑰只屬於登入中的這個人，一併清除（與網頁 doLogout 相同） */
    fun clearAll() {
        prefs()?.edit()?.clear()?.apply()
        _state.value = State()
    }

    /** 畫面上只顯示頭尾：「sk-…a3kQ」（與網頁 maskApiKey 相同） */
    fun mask(key: String): String {
        if (key.length <= 8) return "••••"
        val head = if (key.startsWith("sk-")) "sk-" else key.take(4)
        return "$head…${key.takeLast(4)}"
    }

    /** 這次用自己的金鑰畫的標示：「由 gpt-image-1（你的 OpenAI 金鑰）生成」 */
    fun label(provider: Provider, entry: Entry): String =
        "由 ${entry.model.ifBlank { provider.label }}（你的 ${provider.label} 金鑰）生成"

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 加密：Keystore 的金鑰拿不出手機，備份或複製出去的密文也解不開 ──

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) { null }

    /** 解不開（換了手機、Keystore 被清掉）就當作沒存 */
    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (e: Exception) { null }

    // ── 呼叫 Google AI Studio／OpenAI ──

    /**
     * 測試金鑰：只呼叫免費的「列出模型」，順便挑出這把金鑰能用的畫圖模型（與網頁 testPosterByokKey 同順序）。
     * 回傳模型名稱；不能用就丟 [ByokException]（訊息可直接給使用者看）。
     */
    suspend fun testKey(provider: Provider, key: String): String = withContext(Dispatchers.IO) {
        when (provider) {
            Provider.OPENAI -> {
                val body = request(provider, "https://api.openai.com/v1/models", "GET",
                    mapOf("Authorization" to "Bearer $key"), null, null, 20_000)
                val ids = body.optJSONArray("data").strings("id")
                listOf("gpt-image-1.5", "gpt-image-1", "gpt-image-1-mini").firstOrNull { it in ids }
                    ?: ids.firstOrNull { it.startsWith("gpt-image") }
                    ?: throw ByokException("這把 OpenAI 金鑰沒有可用的畫圖模型（gpt-image），請把畫圖服務改回預設。")
            }
            Provider.GEMINI -> {
                val body = request(provider, "$GEMINI_API_BASE?pageSize=1000", "GET",
                    mapOf("x-goog-api-key" to key), null, null, 20_000)
                val models = body.optJSONArray("models") ?: JSONArray()
                val names = (0 until models.length()).mapNotNull { i ->
                    val m = models.optJSONObject(i) ?: return@mapNotNull null
                    val methods = m.optJSONArray("supportedGenerationMethods").strings(null)
                    m.optString("name").removePrefix("models/").takeIf { "generateContent" in methods }
                }
                // 與預設路徑同一個模型家族（gemini-3-pro-image），沒有才退到其他畫圖模型
                names.firstOrNull { it == "gemini-3-pro-image" }
                    ?: names.firstOrNull { it.startsWith("gemini-3-pro-image") }
                    ?: names.firstOrNull { Regex("^gemini-.*-image").containsMatchIn(it) }
                    ?: throw ByokException("這把 Gemini 金鑰找不到可用的畫圖模型，請把畫圖服務改回預設。")
            }
        }
    }

    /**
     * 用自己的金鑰畫底圖，回圖片位元組。[sketchPng]＝構圖草稿；OpenAI 用 images/edits 帶草稿，
     * 只有 2:3，所以版面要用 PosterLayout.OPENAI_W×OPENAI_H 排（草稿和成品比例一致，疊字位置才對得上）。
     */
    suspend fun generate(provider: Provider, entry: Entry, prompt: String, sketchPng: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            when (provider) {
                Provider.GEMINI -> {
                    val model = entry.model.ifBlank { "gemini-3-pro-image" }
                    val req = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                            .put(JSONObject().put("inlineData", JSONObject()
                                .put("mimeType", "image/png")
                                .put("data", Base64.encodeToString(sketchPng, Base64.NO_WRAP))))
                            .put(JSONObject().put("text", prompt)))))
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                            .put("imageConfig", JSONObject().put("aspectRatio", "9:16")))
                    val body = request(provider, "$GEMINI_API_BASE/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent",
                        "POST", mapOf("x-goog-api-key" to entry.key), req.toString().toByteArray(Charsets.UTF_8),
                        "application/json; charset=utf-8", 150_000)
                    val parts = body.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    val data = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
                        .firstOrNull { !it.optBoolean("thought") && it.optJSONObject("inlineData")?.optString("data").orEmpty().isNotEmpty() }
                        ?.optJSONObject("inlineData")?.optString("data")
                        ?: throw ByokException("Gemini 沒有回傳圖片，可能是內容被安全機制擋下，請換個角色再試。")
                    Base64.decode(data, Base64.DEFAULT)
                }
                Provider.OPENAI -> {
                    val boundary = "----TravelLink${System.currentTimeMillis()}"
                    val form = java.io.ByteArrayOutputStream()
                    fun field(name: String, value: String) = form.write(
                        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                    field("model", entry.model.ifBlank { "gpt-image-1" })
                    field("prompt", prompt)
                    field("size", "1024x1536")
                    field("n", "1")
                    form.write(("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"sketch.png\"\r\n" +
                        "Content-Type: image/png\r\n\r\n").toByteArray(Charsets.UTF_8))
                    form.write(sketchPng)
                    form.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                    val body = request(provider, "https://api.openai.com/v1/images/edits", "POST",
                        mapOf("Authorization" to "Bearer ${entry.key}"), form.toByteArray(),
                        "multipart/form-data; boundary=$boundary", 240_000)
                    val b64 = body.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
                    if (b64.isEmpty()) throw ByokException("OpenAI 沒有回傳圖片資料。")
                    Base64.decode(b64, Base64.DEFAULT)
                }
            }
        }

    private fun request(
        provider: Provider, url: String, method: String, headers: Map<String, String>,
        body: ByteArray?, contentType: String?, timeoutMs: Int
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = timeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw ByokException(errorMessage(provider, status, json))
            return json
        } catch (e: ByokException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ByokException("等待太久沒有回應，請稍後再試。")
        } catch (e: java.io.IOException) {
            throw ByokException("連不上服務，請檢查網路後再試。")
        } finally {
            conn.disconnect()
        }
    }

    /** 把各家的錯誤翻成使用者看得懂、知道下一步怎麼做的話（與網頁 byokErrorMessage 相同） */
    internal fun errorMessage(provider: Provider, status: Int, body: JSONObject): String {
        val err = body.optJSONObject("error") ?: JSONObject()
        val msg = err.optString("message")
        val code = err.optString("code") + err.optString("type")
        when (provider) {
            Provider.OPENAI -> when {
                status == 401 -> return "OpenAI 金鑰無效或已被撤銷，請重新貼上金鑰。"
                status == 403 && Regex("verif", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
                    return "這把 OpenAI 金鑰的組織還沒通過驗證，不能使用畫圖模型。請到 OpenAI 後台完成組織驗證，或把畫圖服務改回預設。"
                status == 403 -> return "OpenAI 拒絕這次請求（金鑰沒有畫圖權限）。"
                status == 429 && Regex("insufficient_quota|billing", RegexOption.IGNORE_CASE).containsMatchIn(code + msg) ->
                    return "OpenAI 帳戶額度用完或尚未設定付款方式，請到 OpenAI 後台儲值。"
                status == 429 -> return "OpenAI 請求太頻繁，請稍等一下再試。"
                status == 400 && Regex("safety|moderation|content_policy", RegexOption.IGNORE_CASE).containsMatchIn(code + msg) ->
                    return "OpenAI 認為這次內容不適合生成（常見原因是指定的卡通人物有版權限制），請換個角色再試。"
            }
            Provider.GEMINI -> when {
                status == 400 && Regex("api key", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
                    return "Gemini 金鑰無效，請到 Google AI Studio 確認後重新貼上。"
                status == 401 || status == 403 ->
                    return "Gemini 金鑰沒有權限（可能是 API 未啟用或金鑰有限制），請到 Google AI Studio 確認。"
                status == 429 -> return "Gemini 金鑰的額度用完了。畫圖模型沒有免費額度，需要在 Google AI Studio 為專案開通帳單。"
                // AI Studio 預付制的專案儲值用完（2026-10-19 實測：「Your prepayment credits are depleted」）
                status == 402 -> return "這把 Gemini 金鑰所屬專案的預付額度用完了，請到 Google AI Studio 儲值，或換一把金鑰。"
                status == 404 -> return "這把金鑰找不到可用的 Gemini 畫圖模型，請重新按「測試並儲存」。"
            }
        }
        return "${provider.label} 回應錯誤（$status）" + if (msg.isNotBlank()) "：${msg.take(120)}" else ""
    }

    private fun JSONArray?.strings(field: String?): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            if (field == null) optString(i).takeIf { it.isNotEmpty() }
            else optJSONObject(i)?.optString(field)?.takeIf { it.isNotEmpty() }
        }
    }
}
