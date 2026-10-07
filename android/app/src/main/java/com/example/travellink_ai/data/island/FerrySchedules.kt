package com.example.travellink_ai.data.island

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Calendar

/**
 * 船班靜態表（A5 Stage 5）。
 *
 * 為什麼是靜態表而不是 API：TDX 沒有船班資料（A7 已實測），交通部航港局的
 * 固定航班頁是動態頁、明載「僅供參考請洽業者」，也沒有可下載的開放資料集。
 * 唯一有逐班時刻與票價的地方是各船公司官網，只能人工抄。刻意不做爬蟲——
 * 班表是動態頁又常改，爬了反而更容易給出看起來精準的錯資料。
 *
 * 對應的原則與 A7 的回程台鐵卡一致：**寧可標示不確定，也不要給錯的班次。**
 * 所以資料超過 [staleAfterDays] 天就不再顯示時刻，只留電話讓使用者自己問。
 */
data class FerryFare(
    val oneWayFull: Int,
    val roundTripFull: Int,
    val roundTripBooking: Int?,
    val oneWayChild: Int?,
    val source: String
)

data class FerryDeparture(
    /** "mainland" = 本島開往離島；"island" = 離島開回本島 */
    val from: String,
    val time: String
)

data class FerrySeason(
    val name: String,
    val dateRange: String,
    val departures: List<FerryDeparture>,
    /** "official" = 官方公告；"third_party" = 代訂平台匯總，可信度較低，UI 要標示 */
    val sourceType: String = "official",
    val source: String = "",
    val sourceNote: String = ""
) {
    val isOfficial: Boolean get() = sourceType == "official"

    fun departuresFrom(from: String): List<String> =
        departures.filter { it.from == from }.map { it.time }.sorted()

    /**
     * 指定時間之後最早的一班（含 [afterHhmm] 當下）。回傳 null 代表當天沒有更晚的班次。
     * 與 A7 台鐵卡同樣的語意：抓不到就不顯示，不要猜。
     */
    fun nextDeparture(from: String, afterHhmm: String): String? =
        departuresFrom(from).firstOrNull { it >= afterHhmm }

    /** 當日最後一班（末班船） */
    fun lastDeparture(from: String): String? = departuresFrom(from).lastOrNull()
}

data class FerryOperator(
    val name: String,
    val phone: String,
    val bookingUrl: String,
    val fares: FerryFare?,
    val seasons: List<FerrySeason>,
    /** 尚未取得的欄位說明；非空代表這家的資料還不完整 */
    val todo: String
) {
    val hasSchedule: Boolean get() = seasons.any { it.departures.isNotEmpty() }
}

data class FerryRoute(
    val code: String,
    val islandCode: String,
    val mainlandPort: String,
    val islandPort: String,
    val sailingMins: Int,
    val note: String,
    val operators: List<FerryOperator>,
    /**
     * 聯營共用班次。綠島線由凱旋／天王星／綠島之星三家共同排班、班次共用，
     * 時刻屬於航線而不屬於某一家業者，記在業者底下反而會重複三份。
     */
    val sharedSeasons: List<FerrySeason> = emptyList()
) {
    /** 有票價可用的第一家業者（供費用卡估算船票） */
    val fareReference: FerryFare? get() = operators.firstNotNullOfOrNull { it.fares }

    /** 目前適用的班表：優先用聯營共用，其次任一家業者自己的 */
    val activeSeason: FerrySeason?
        get() = sharedSeasons.firstOrNull { it.departures.isNotEmpty() }
            ?: operators.firstNotNullOfOrNull { op -> op.seasons.firstOrNull { it.departures.isNotEmpty() } }

    /** 任何一家有逐班時刻嗎 */
    val hasAnySchedule: Boolean get() = activeSeason != null

    /** 有電話可撥的業者（資料過期時至少還能讓使用者自己問） */
    val contactable: List<FerryOperator> get() = operators.filter { it.phone.isNotBlank() }
}

class FerrySchedules private constructor(
    val dataAsOf: String,
    private val staleAfterDays: Int,
    val disclaimer: String,
    private val routes: List<FerryRoute>
) {
    fun routeForIsland(islandCode: String): FerryRoute? =
        routes.firstOrNull { it.islandCode == islandCode }

    /**
     * 資料是否已過期。過期時 UI 必須降級成「請洽船公司」而不顯示時刻——
     * 一份三個月前的旺季班表看起來精準，實際上可能整組改過。
     */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val asOf = parseDate(dataAsOf) ?: return true   // 日期壞掉一律當過期
        return (nowMs - asOf) / 86_400_000L > staleAfterDays
    }

    private fun parseDate(v: String): Long? {
        val p = v.trim().split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
    }

    companion object {
        private const val ASSET = "ferry_schedules.json"
        private const val TAG = "TravelLink_Debug"

        @Volatile private var instance: FerrySchedules? = null

        fun get(context: Context): FerrySchedules =
            instance ?: synchronized(this) {
                instance ?: load(context).also { instance = it }
            }

        private fun load(context: Context): FerrySchedules = try {
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 船班表讀取失敗，離島船班資訊將不顯示：${e.message}")
            FerrySchedules("", 0, "", emptyList())
        }

        private fun parseSeasons(arr: org.json.JSONArray?): List<FerrySeason> = buildList {
            for (k in 0 until (arr?.length() ?: 0)) {
                val s = arr!!.getJSONObject(k)
                val depArr = s.optJSONArray("departures")
                val deps = buildList {
                    for (m in 0 until (depArr?.length() ?: 0)) {
                        val d = depArr!!.getJSONObject(m)
                        add(FerryDeparture(d.optString("from"), d.optString("time")))
                    }
                }
                add(FerrySeason(
                    name = s.optString("name"),
                    dateRange = s.optString("dateRange"),
                    departures = deps,
                    sourceType = s.optString("sourceType", "official"),
                    source = s.optString("source"),
                    sourceNote = s.optString("sourceNote")
                ))
            }
        }

        /** 解析獨立成函式，方便單元測試直接餵 JSON 字串 */
        fun parse(json: String): FerrySchedules {
            val root = JSONObject(json)
            val routesArr = root.optJSONArray("routes")
            val routes = buildList {
                for (i in 0 until (routesArr?.length() ?: 0)) {
                    val r = routesArr!!.getJSONObject(i)
                    val opsArr = r.optJSONArray("operators")
                    val operators = buildList {
                        for (j in 0 until (opsArr?.length() ?: 0)) {
                            val o = opsArr!!.getJSONObject(j)
                            val f = o.optJSONObject("fares")
                            val seasons = parseSeasons(o.optJSONArray("seasons"))
                            add(FerryOperator(
                                name = o.optString("name"),
                                phone = o.optString("phone"),
                                bookingUrl = o.optString("bookingUrl"),
                                fares = f?.let {
                                    FerryFare(
                                        oneWayFull = it.optInt("oneWayFull"),
                                        roundTripFull = it.optInt("roundTripFull"),
                                        roundTripBooking = it.optInt("roundTripBooking")
                                            .takeIf { v -> v > 0 },
                                        oneWayChild = it.optInt("oneWayChild").takeIf { v -> v > 0 },
                                        source = it.optString("source")
                                    )
                                },
                                seasons = seasons,
                                todo = o.optString("todo")
                            ))
                        }
                    }
                    add(FerryRoute(
                        code = r.optString("code"),
                        islandCode = r.optString("islandCode"),
                        mainlandPort = r.optString("mainlandPort"),
                        islandPort = r.optString("islandPort"),
                        sailingMins = r.optInt("sailingMins"),
                        note = r.optString("note"),
                        operators = operators,
                        sharedSeasons = parseSeasons(r.optJSONArray("sharedSeasons"))
                    ))
                }
            }
            return FerrySchedules(
                dataAsOf = root.optString("dataAsOf"),
                staleAfterDays = root.optInt("staleAfterDays", 90),
                disclaimer = root.optString("disclaimer"),
                routes = routes
            )
        }
    }
}
