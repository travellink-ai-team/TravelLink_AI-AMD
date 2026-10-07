package com.example.travellink_ai.ui.poster

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.map.PolyUtil
import com.google.android.gms.maps.model.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主視覺渲染所需的行程資料。
 *
 * 全部來自 Room，**渲染時零 API 呼叫**：
 *   - 座標 → [Stop.lat] / [Stop.lng]（v8 起於生成當下快取）
 *   - 折線 → [LocalItinerary.roadSegmentsEncoded]
 *   - 車程 → [LocalItinerary.transitTimesJson]
 *
 * 這些欄位**都可能缺**（舊行程、編碼失敗、網頁端建立的行程），
 * 因此 [from] 一律回傳可畫的東西，並用 [quality] 告知呼叫端降到第幾級。
 */
data class TripPosterData(
    val title: String,
    val region: String,
    val dateLabel: String,
    val stops: List<PosterStop>,
    /** 相鄰兩站之間的實際道路折線；空 list 代表只能畫直線。 */
    val routeSegments: List<List<LatLng>>,
    val stats: TripStats,
    val quality: Quality
) {
    /** 資料完整度。決定畫面能呈現到什麼程度，也決定要不要提示使用者。 */
    enum class Quality {
        /** 有座標也有道路折線 —— 完整的「連續路線電影」視覺。 */
        FULL,
        /** 有座標但沒折線（或折線是舊格式）—— 退化成站點間直線。 */
        STRAIGHT_LINES,
        /** 連座標都沒有 —— 只能排版文字與照片，不畫地圖。 */
        NO_GEOMETRY
    }

    val hasGeometry: Boolean get() = quality != Quality.NO_GEOMETRY

    companion object {
        /**
         * @param item 來源行程
         */
        fun from(item: LocalItinerary): TripPosterData {
            // 車站站點（出發／回程）不進主視覺，與旅記頁的規則一致
            val visible = item.stops.filter { !it.isStation }

            val posterStops = visible.map {
                PosterStop(
                    stopId = it.stopId,
                    name = it.name,
                    emoji = it.emoji,
                    position = if (it.lat != null && it.lng != null) LatLng(it.lat, it.lng) else null
                )
            }

            val transitMins = item.transitTimesJson
                ?.split(",")
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?: emptyList()

            val segments = decodeSegments(item.roadSegmentsEncoded)

            val quality = when {
                posterStops.none { it.position != null } -> Quality.NO_GEOMETRY
                segments.isEmpty() -> Quality.STRAIGHT_LINES
                else -> Quality.FULL
            }

            return TripPosterData(
                title = item.title.ifBlank { item.aiTitle }.ifBlank { "我的旅程" },
                region = item.region,
                dateLabel = formatDate(item.createdAt),
                stops = posterStops,
                routeSegments = segments,
                stats = TripStats(
                    dayCount = parseDayCount(item.days),
                    stopCount = visible.size,
                    totalTransitMins = transitMins.sum(),
                    region = item.region
                ),
                quality = quality
            )
        }

        /**
         * 解析快取的多段 polyline。
         *
         * ★ 格式有兩種（見 ItineraryViewModel 的相同判斷）：
         *   - 新格式：以 `\n` 分隔
         *   - 舊格式：以 `|` 分隔 —— 但 `|` 本身是 polyline 編碼字元，無法安全分割，
         *     故舊資料一律視為不可用，退化成直線，不嘗試硬解。
         */
        private fun decodeSegments(encoded: String?): List<List<LatLng>> {
            if (encoded.isNullOrBlank()) return emptyList()
            if (!encoded.contains('\n')) return emptyList()   // 舊格式，放棄
            return runCatching {
                encoded.split('\n')
                    .filter { it.isNotBlank() }
                    .map { PolyUtil.decode(it) }
                    .filter { it.size >= 2 }
            }.getOrDefault(emptyList())
        }

        private fun formatDate(epochMs: Long): String =
            SimpleDateFormat("yyyy.MM.dd", Locale.TAIWAN).format(Date(epochMs))

        /**
         * ★ [LocalItinerary.days] 存的是**日期範圍字串**，不是天數
         * （`ItineraryViewModel` 寫入 `"$startDateTime - $endDateTime"`）。
         * 直接拿來當「N 天」會在海報上顯示成 `2026/08/0…`。
         *
         * 以正規表示式抓出頭尾日期算天數，格式不符時回 null，由呼叫端決定怎麼呈現。
         */
        private val DATE_PATTERN = Regex("""(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})""")

        internal fun parseDayCount(raw: String): Int? {
            val dates = DATE_PATTERN.findAll(raw).map { m ->
                val (y, mo, d) = m.destructured
                java.util.GregorianCalendar(y.toInt(), mo.toInt() - 1, d.toInt())
            }.toList()
            if (dates.isEmpty()) return null
            if (dates.size == 1) return 1

            val diffMs = dates.last().timeInMillis - dates.first().timeInMillis
            val days = (diffMs / 86_400_000L).toInt() + 1   // 含頭含尾
            return days.takeIf { it in 1..60 }              // 超出常識範圍視為解析錯誤
        }
    }
}

data class PosterStop(
    val stopId: String,
    val name: String,
    val emoji: String,
    /** 可能為 null：舊行程或座標解析失敗。 */
    val position: LatLng?
)

/**
 * 已定案的四項旅程數據（天數／景點數／總車程／地區）。
 *
 * @param dayCount 無法從行程資料解析出天數時為 null，此時該欄改顯示 "—"，
 *                 不猜也不拿日期字串充數。
 */
data class TripStats(
    val dayCount: Int?,
    val stopCount: Int,
    val totalTransitMins: Long,
    val region: String
) {
    fun asPairs(): List<Pair<String, String>> = listOf(
        (dayCount?.let { "$it 天" } ?: "—") to "行程",
        "$stopCount 站" to "景點",
        formatTransit() to "車程",
        region.ifBlank { "台灣" } to "地區"
    )

    /** 超過一小時改用「X 小時 Y 分」，避免海報上出現「385 分」這種讀不出感覺的數字。 */
    private fun formatTransit(): String = when {
        totalTransitMins <= 0 -> "—"
        totalTransitMins < 60 -> "$totalTransitMins 分"
        totalTransitMins % 60 == 0L -> "${totalTransitMins / 60} 小時"
        else -> "${totalTransitMins / 60} 小時 ${totalTransitMins % 60} 分"
    }
}
