package com.example.travellink_ai.data.weather

import android.util.Log
import com.example.travellink_ai.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 行程日期的天氣預報摘要（C5），顯示於行程預覽摘要卡。
 */
data class WeatherInfo(
    val townshipName: String,   // 預報鄉鎮，如「太麻里鄉」
    val date: String,           // 行程日期 yyyy/MM/dd
    val minTemp: Int,
    val maxTemp: Int,
    val rainProbability: Int,   // 降雨機率 %，-1 表示該時段無資料
    val description: String,    // 天氣現象，如「多雲時晴」
    val emoji: String
)

/**
 * 中央氣象署（CWA）opendata 預報服務。
 *
 * 依行程日期距今天數選擇資料集：
 * - 3 天內 → F-D0047-037（臺東縣逐 3 小時預報）
 * - 3–6 天 → F-D0047-039（臺東縣一週逐 12 小時預報）
 * - 超過 6 天 → 無預報可用，回傳 null
 *
 * 注意：F-D0047-089/091 是「全臺」彙整資料集，直接以 LocationName 過濾鄉鎮
 * 會回空的 Location（需另帶 LocationId），臺東縣鄉鎮務必用縣專屬 037/039。
 *
 * CWA 預報以鄉鎮為單位，用景點座標對應最近的台東縣鄉鎮查詢。
 */
object CwaWeatherService {
    private const val BASE_URL = "https://opendata.cwa.gov.tw/api/v1/rest/datastore"
    private const val DATASET_3HR  = "F-D0047-037"
    private const val DATASET_WEEK = "F-D0047-039"

    private val apiKey get() = BuildConfig.CWA_API_KEY

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Session 級快取：同一資料集＋鄉鎮＋日期只成功查一次
    private val cache = mutableMapOf<String, WeatherInfo>()

    private data class Township(val name: String, val lat: Double, val lng: Double)

    // 台東縣 16 鄉鎮參考座標（鄉鎮公所），名稱需與 CWA LocationName 完全一致（用「臺」）
    private val townships = listOf(
        Township("臺東市",   22.7554, 121.1465),
        Township("卑南鄉",   22.7861, 121.0851),
        Township("延平鄉",   22.9027, 121.0846),
        Township("鹿野鄉",   22.9132, 121.1355),
        Township("關山鎮",   23.0473, 121.1639),
        Township("海端鄉",   23.1019, 121.1720),
        Township("池上鄉",   23.1226, 121.2153),
        Township("東河鄉",   22.9690, 121.3030),
        Township("成功鎮",   23.0996, 121.3736),
        Township("長濱鄉",   23.3155, 121.4510),
        Township("太麻里鄉", 22.6157, 121.0075),
        Township("金峰鄉",   22.6316, 120.9584),
        Township("大武鄉",   22.3395, 120.8905),
        Township("達仁鄉",   22.2944, 120.8853),
        Township("綠島鄉",   22.6617, 121.4926),
        Township("蘭嶼鄉",   22.0567, 121.5320),
    )

    /**
     * 查詢指定座標所屬鄉鎮在行程日期當天的天氣預報。
     * @param tripDate 行程首日，格式 yyyy/MM/dd
     * @return 無 API Key、日期超出預報範圍、或查詢失敗時回傳 null
     */
    private const val TAG = "TravelLink_Debug"

    suspend fun fetchForecast(lat: Double, lng: Double, tripDate: String): WeatherInfo? {
        if (apiKey.isBlank()) { Log.w(TAG, "🌦 CWA 略過：CWA_API_KEY 空白"); return null }
        val daysAhead = daysFromToday(tripDate)
        if (daysAhead == null) { Log.w(TAG, "🌦 CWA 略過：日期解析失敗「$tripDate」（需 yyyy/MM/dd）"); return null }
        if (daysAhead < 0 || daysAhead > 6) { Log.d(TAG, "🌦 CWA 略過：$tripDate 距今 $daysAhead 天（範圍 0–6）"); return null }

        val township = nearestTownship(lat, lng)
        // 靠近 3 天邊界時 089 可能缺當天後段資料，null 時自動退回一週資料集
        val datasets = if (daysAhead <= 2) listOf(DATASET_3HR, DATASET_WEEK) else listOf(DATASET_WEEK)

        for (dataset in datasets) {
            val cacheKey = "$dataset|${township.name}|$tripDate"
            // 快取命中不記 log：畫面停在預覽頁時，Firestore 監聽器的每次重組都會重跑
            // 這個 produceState，命中也印的話 logcat 會被同一行洗版（網路並沒有重打）
            cache[cacheKey]?.let { return it }
            Log.d(TAG, "🌦 CWA 查詢：$tripDate（+$daysAhead 天）鄉鎮=${township.name} dataset=$dataset")
            val info = try {
                val body = client.get("$BASE_URL/$dataset") {
                    parameter("Authorization", apiKey)
                    parameter("LocationName", township.name)
                    parameter("format", "JSON")
                }.bodyAsText()
                parseForecast(body, township.name, tripDate).also {
                    if (it == null) Log.w(TAG, "🌦 CWA $dataset 解析無結果（回應 ${body.length} 字元）：${body.take(200)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "🌦 CWA $dataset 請求失敗：${e.message}")
                null
            }
            if (info != null) {
                Log.d(TAG, "🌦 CWA 成功：${info.townshipName} ${info.date} ${info.minTemp}–${info.maxTemp}° 降雨${info.rainProbability}% ${info.description}")
                cache[cacheKey] = info
                return info
            }
        }
        return null
    }

    private fun nearestTownship(lat: Double, lng: Double): Township =
        townships.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }!!

    private fun daysFromToday(tripDate: String): Int? = try {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val target = Calendar.getInstance().apply { time = sdf.parse(tripDate)!! }.startOfDay()
        val today = Calendar.getInstance().startOfDay()
        ((target.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
    } catch (e: Exception) {
        null
    }

    private fun Calendar.startOfDay(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    // CWA 在 2024 年後的 API 版本將欄位名改為大寫開頭（locations → Locations 等），
    // 這裡同時容忍新舊兩種 schema
    private fun JsonObject.firstOf(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { this[it] }

    private fun parseForecast(body: String, townshipName: String, tripDate: String): WeatherInfo? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val records = root.firstOf("records")?.jsonObject ?: return null
            val locations = records.firstOf("Locations", "locations")
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val location = locations.firstOf("Location", "location")
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val elements = location.firstOf("WeatherElement", "weatherElement")
                ?.jsonArray ?: return null

            val targetDatePrefix = tripDate.replace("/", "-")  // CWA 時間格式 yyyy-MM-ddTHH:mm:ss

            val temps = mutableListOf<Int>()
            val pops = mutableListOf<Int>()
            val wxDescriptions = mutableListOf<String>()

            for (element in elements) {
                val obj = element.jsonObject
                val name = obj.firstOf("ElementName", "elementName")?.jsonPrimitive?.content ?: continue
                val times = obj.firstOf("Time", "time")?.jsonArray ?: continue
                for (t in times) {
                    val tObj = t.jsonObject
                    val start = tObj.firstOf("DataTime", "dataTime", "StartTime", "startTime")
                        ?.jsonPrimitive?.content ?: continue
                    if (!start.startsWith(targetDatePrefix)) continue
                    val valueObj = tObj.firstOf("ElementValue", "elementValue")
                        ?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    // 新舊 schema 的值欄位名不同（Temperature / value / Weather...），取所有 primitive 值判斷
                    val rawValues = valueObj.values
                        .mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                        .filter { it.isNotBlank() && it != "-" }
                    val raw = rawValues.firstOrNull() ?: continue
                    when {
                        // 排除露點/體感，避免拉低顯示溫度範圍
                        name.contains("溫度") && !name.contains("露點") && !name.contains("體感") ->
                            raw.toIntOrNull()?.let { temps.add(it) }
                        name.contains("降雨機率") ->
                            raw.toIntOrNull()?.let { pops.add(it) }
                        name.contains("天氣現象") -> {
                            // ElementValue 內同時有描述與數字代碼（WeatherCode），取非數字的描述
                            val desc = rawValues.firstOrNull { it.toIntOrNull() == null }
                            if (desc != null) wxDescriptions.add(desc)
                        }
                    }
                }
            }

            if (temps.isEmpty()) return null
            val description = wxDescriptions.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: ""
            WeatherInfo(
                townshipName = townshipName,
                date = tripDate,
                minTemp = temps.min(),
                maxTemp = temps.max(),
                rainProbability = pops.maxOrNull() ?: -1,
                description = description,
                emoji = emojiFor(description)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun emojiFor(desc: String): String = when {
        desc.contains("雷") -> "⛈️"
        desc.contains("雨") -> "🌧️"
        desc.contains("陰") -> "☁️"
        desc.contains("雲") && desc.contains("晴") -> "⛅"
        desc.contains("雲") -> "🌥️"
        desc.contains("晴") -> "☀️"
        else -> "🌤️"
    }
}
