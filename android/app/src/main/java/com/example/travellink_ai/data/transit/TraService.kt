package com.example.travellink_ai.data.transit

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import java.io.File

/** 台鐵車站（離線 assets/tra_stations.json，全台 245 站，執行期不打 API）。 */
data class TraStation(
    val id: String,
    val name: String,
    val city: String
)

/**
 * 一班可搭乘的車次。
 *
 * - 車站模式（未指定最終目的地）：只有 departureTime + destinationName，arrivalTime/durationMins 為 null
 * - OD 模式（有指定最終目的地）：arrivalTime/durationMins 才有值
 */
data class TraDeparture(
    val trainNo: String,
    val trainType: String,          // 自強(3000)／普悠瑪／區間…
    val departureTime: String,      // HH:mm，於出發站發車
    val destinationName: String,    // 該車次終點站
    val direction: Int,             // 0=順行（南下）1=逆行（北上）
    val arrivalTime: String? = null,
    val durationMins: Int? = null,
    val delayMins: Int? = null      // 由 LiveBoard 併入；null=無即時資料
) {
    val directionLabel: String get() = if (direction == 1) "北上" else "南下"
}

/**
 * 回程班次卡的完整資料。
 * 回程地點對不上任何台鐵站名時（例如使用者填「台東大學」）整個為 null，寧可不顯示也不對錯站。
 */
data class ReturnTrainPlan(
    val stationName: String,
    val stationId: String,
    val arriveTime: String,           // 預計抵達車站的時間 HH:mm
    val finalDestination: String,     // 空字串＝未指定最終目的地，此時南下北上都列
    val departures: List<TraDeparture>
) {
    /** 抵達車站後第一班還來得及搭的車（保留 10 分鐘走進站的緩衝）。 */
    val recommended: TraDeparture? get() = departures.firstOrNull()
}

/**
 * 台鐵班次查詢（A7 ②）。資料源 TDX，三個端點皆已實測：
 *
 * - `GeneralStationTimetable/Station/{id}`：該站定期時刻表（南下／北上兩組），
 *   一份可用一個多月（EffectiveDate~ExpireDate），落地磁碟快取
 * - `DailyTrainTimetable/OD/{from}/to/{to}/{date}`：指定終點時的精準班次與抵達時間
 * - `StationLiveBoard`：即時誤點（DelayTime 分鐘），只在行程進行中查，30 秒快取
 *
 * 臺東縣沒有即時車位/公車資料，但台鐵 14 站涵蓋完整，故 A7 以此為主軸。
 * 所有失敗一律回空清單，呼叫端以「不顯示」處理，絕不阻塞主流程。
 */
object TraService {
    private const val BASE = "https://tdx.transportdata.tw/api/basic/v3/Rail/TRA"
    private const val TAG  = "TraService"
    private const val CACHE_DIR = "tra_cache"

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis  = 15000
            }
        }
    }

    // ── 站點表（assets 一次載入，之後全記憶體）─────────────────────────

    @Volatile private var stationsCache: List<TraStation>? = null

    fun stations(context: Context): List<TraStation> {
        stationsCache?.let { return it }
        val loaded = try {
            val raw = context.assets.open("tra_stations.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONObject(raw).getJSONArray("stations")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TraStation(
                    id   = o.getString("id"),
                    name = o.getString("name"),
                    city = o.optString("city")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 車站清單載入失敗: ${e.message}")
            emptyList()
        }
        stationsCache = loaded
        return loaded
    }

    /**
     * 把使用者輸入的地點文字對應到車站 ID。
     * 「台東火車站」「臺東車站」「台東站」→ 6000；對不上（例如「台東大學」）回 null，
     * 由呼叫端決定不顯示班次資訊，寧可不顯示也不要對錯站。
     */
    fun findStationId(context: Context, placeName: String): String? {
        val key = normalize(placeName)
        if (key.isBlank()) return null
        return stations(context).firstOrNull { normalize(it.name) == key }?.id
    }

    fun stationName(context: Context, stationId: String): String? =
        stations(context).firstOrNull { it.id == stationId }?.name

    /** 台東縣境內車站，供精靈的出發／回程地點 chips 使用。 */
    fun taitungStations(context: Context): List<TraStation> =
        stations(context).filter { it.city.contains("臺東") || it.city.contains("台東") }

    private fun normalize(s: String): String =
        s.trim()
            .replace("台", "臺")
            .replace("臺鐵", "")
            .replace("火車站", "")
            .replace("車站", "")
            .removeSuffix("站")
            .replace(" ", "")

    // ── 車站定期時刻表（未指定最終目的地時使用）──────────────────────

    /**
     * 該站在 [afterHHmm] 之後的班次，南下北上都給（各取 [perDirection] 班）。
     * 定期時刻表變動以「改點」為單位，磁碟快取到 API 給的 ExpireDate。
     */
    suspend fun stationDepartures(
        context: Context,
        stationId: String,
        afterHHmm: String,
        perDirection: Int = 3
    ): List<TraDeparture> {
        val body = cachedGet(
            context,
            cacheKey = "station_$stationId",
            url = "$BASE/GeneralStationTimetable/Station/$stationId"
        ) ?: return emptyList()

        return try {
            val root = JSONObject(body)
            val groups = root.optJSONArray("StationTimetables") ?: return emptyList()
            val out = mutableListOf<TraDeparture>()
            for (g in 0 until groups.length()) {
                val group = groups.getJSONObject(g)
                val direction = group.optInt("Direction", 0)
                val list = group.optJSONArray("Timetables") ?: continue
                val ofDirection = mutableListOf<TraDeparture>()
                for (i in 0 until list.length()) {
                    val t = list.getJSONObject(i)
                    val dep = t.optString("DepartureTime").take(5)
                    if (dep.isBlank() || !isAfter(dep, afterHHmm)) continue
                    // 終點站就是本站的列車是「到站」不是「發車」，不可搭乘。
                    // 臺東是南迴／花東線端點站，這種班次佔定期時刻表 41%，不濾掉會推薦
                    // 「17:23 往臺東」這種人已經在臺東卻叫他搭的荒謬班次。
                    if (t.optString("DestinationStationID") == stationId) continue
                    ofDirection += TraDeparture(
                        trainNo         = t.optString("TrainNo"),
                        trainType       = t.optJSONObject("TrainTypeName")?.optString("Zh_tw").orEmpty(),
                        departureTime   = dep,
                        destinationName = t.optJSONObject("DestinationStationName")?.optString("Zh_tw").orEmpty(),
                        direction       = direction
                    )
                }
                out += ofDirection.sortedBy { it.departureTime }.take(perDirection)
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "❌ 車站時刻表解析失敗($stationId): ${e.message}")
            emptyList()
        }
    }

    // ── OD 時刻表（有指定最終目的地時使用）────────────────────────────

    /**
     * [fromId] → [toId] 在 [afterHHmm] 之後的班次，含抵達時間與乘車時長。
     * 每日時刻表按日期快取（同一天只查一次）。
     */
    suspend fun odDepartures(
        context: Context,
        fromId: String,
        toId: String,
        date: String,          // yyyy-MM-dd
        afterHHmm: String,
        limit: Int = 4
    ): List<TraDeparture> {
        val body = cachedGet(
            context,
            cacheKey = "od_${fromId}_${toId}_$date",
            url = "$BASE/DailyTrainTimetable/OD/$fromId/to/$toId/$date",
            ttlMillis = 12 * 60 * 60 * 1000L
        ) ?: return emptyList()

        return try {
            val list = JSONObject(body).optJSONArray("TrainTimetables") ?: return emptyList()
            val out = mutableListOf<TraDeparture>()
            for (i in 0 until list.length()) {
                val tt = list.getJSONObject(i)
                val info = tt.optJSONObject("TrainInfo") ?: continue
                val stops = tt.optJSONArray("StopTimes") ?: continue

                var depTime: String? = null
                var arrTime: String? = null
                for (s in 0 until stops.length()) {
                    val st = stops.getJSONObject(s)
                    when (st.optString("StationID")) {
                        fromId -> depTime = st.optString("DepartureTime").take(5)
                        toId   -> arrTime = st.optString("ArrivalTime").take(5)
                    }
                }
                val dep = depTime?.takeIf { it.isNotBlank() } ?: continue
                if (!isAfter(dep, afterHHmm)) continue

                out += TraDeparture(
                    trainNo         = info.optString("TrainNo"),
                    trainType       = info.optJSONObject("TrainTypeName")?.optString("Zh_tw").orEmpty(),
                    departureTime   = dep,
                    destinationName = info.optJSONObject("EndingStationName")?.optString("Zh_tw").orEmpty(),
                    direction       = info.optInt("Direction", 0),
                    arrivalTime     = arrTime?.takeIf { it.isNotBlank() },
                    durationMins    = arrTime?.takeIf { it.isNotBlank() }?.let { minutesBetween(dep, it) }
                )
            }
            out.sortedBy { it.departureTime }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ OD 時刻表解析失敗($fromId→$toId): ${e.message}")
            emptyList()
        }
    }

    // ── 即時誤點（只在行程進行中查）──────────────────────────────────

    private var liveCacheKey: String = ""
    private var liveCacheAt: Long = 0L
    private var liveCache: Map<String, Int> = emptyMap()

    /** 該站目前各車次的誤點分鐘（車次號 → 分鐘）。30 秒內重複呼叫走記憶體快取。 */
    suspend fun liveDelays(stationId: String): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (liveCacheKey == stationId && now - liveCacheAt < 30_000L) return liveCache

        val token = TdxAuth.getToken() ?: return emptyMap()
        return try {
            TdxAuth.awaitTurn()
            val resp: HttpResponse = client.get("$BASE/StationLiveBoard") {
                parameter("\$filter", "StationID eq '$stationId'")
                parameter("\$format", "JSON")
                header("Authorization", "Bearer $token")
            }
            val arr = JSONObject(resp.bodyAsText()).optJSONArray("StationLiveBoards")
                ?: return emptyMap()
            val map = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.optString("TrainNo")] = o.optInt("DelayTime", 0)
            }
            liveCacheKey = stationId
            liveCacheAt  = now
            liveCache    = map
            map
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 即時誤點查詢失敗($stationId): ${e.message}")
            emptyMap()
        }
    }

    /** 把即時誤點併進班次清單；查不到誤點的班次 delayMins 維持 null。 */
    fun withDelays(departures: List<TraDeparture>, delays: Map<String, Int>): List<TraDeparture> =
        departures.map { d -> delays[d.trainNo]?.let { d.copy(delayMins = it) } ?: d }

    // ── 共用：帶磁碟快取的 GET ───────────────────────────────────────

    /**
     * 三層：記憶體不做（資料量大）→ 磁碟 filesDir/tra_cache/ → TDX API。
     * [ttlMillis] 為 null 時改用回應裡的 ExpireDate 判定（定期時刻表用）。
     * rate limit 被擋或網路錯誤時不寫快取，下次重試。
     */
    private suspend fun cachedGet(
        context: Context,
        cacheKey: String,
        url: String,
        ttlMillis: Long? = null
    ): String? {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "$cacheKey.json")

        if (file.exists()) {
            try {
                val cached = file.readText()
                val stillValid = if (ttlMillis != null) {
                    System.currentTimeMillis() - file.lastModified() < ttlMillis
                } else {
                    val expire = JSONObject(cached).optString("ExpireDate")
                    expire.isBlank() || expire.take(10) >= today()
                }
                if (stillValid) {
                    Log.d(TAG, "🗄️ tra_cache 命中：$cacheKey")
                    return cached
                }
            } catch (e: Exception) {
                Log.w(TAG, "tra_cache 讀取失敗，改打 API：${e.message}")
            }
        }

        val token = TdxAuth.getToken() ?: return null
        return try {
            TdxAuth.awaitTurn()
            val resp: HttpResponse = client.get(url) {
                parameter("\$format", "JSON")
                header("Authorization", "Bearer $token")
            }
            val body = resp.bodyAsText()
            // rate limit / 查無資源時回應是 {"message":...}，不可寫入快取
            if (body.contains("\"message\"") && !body.contains("Timetable")) {
                Log.w(TAG, "⚠️ TDX 非預期回應（不快取）：${body.take(120)}")
                return null
            }
            runCatching { file.writeText(body) }
                .onFailure { Log.w(TAG, "tra_cache 寫入失敗：${it.message}") }
            body
        } catch (e: Exception) {
            Log.e(TAG, "❌ TDX 查詢失敗($url): ${e.message}")
            null
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.TAIWAN)
            .format(java.util.Date())

    /** HH:mm 字串比較；跨午夜不處理（行程不會排到隔日班次）。 */
    private fun isAfter(time: String, ref: String): Boolean = time >= ref

    private fun minutesBetween(from: String, to: String): Int {
        val f = from.split(":").mapNotNull { it.toIntOrNull() }
        val t = to.split(":").mapNotNull { it.toIntOrNull() }
        if (f.size < 2 || t.size < 2) return 0
        var diff = (t[0] * 60 + t[1]) - (f[0] * 60 + f[1])
        if (diff < 0) diff += 24 * 60   // 跨午夜
        return diff
    }
}
