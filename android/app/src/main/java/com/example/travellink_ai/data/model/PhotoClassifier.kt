package com.example.travellink_ai.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 依照片的拍攝時間與 GPS 判斷屬於哪一站（「批次上傳並自動整理」用）。
 *
 * 規則照搬網頁 trip-photo-manager.js 的 classifyPhoto（2026-10-05 版），兩端分類結果才會一致：
 * - 位置分數：距離 0 → 1，線性降到 3×半徑（350m×3）→ 0；權重 0.55
 * - 時間分數：落在站點時間窗內 → 1，窗外每差 1 分鐘扣 1/90；權重 0.45（只有檔案修改時間時 0.2）
 * - 只比同一天的站；總分 < 0.2 或兩種證據都沒有 → 未分類
 * - 信心：時間＋位置都有且 ≥ 0.72 高、≥ 0.38 中、其餘低（低的要使用者確認）
 */
object PhotoClassifier {
    const val RADIUS_M = 350.0
    const val TIME_MARGIN_MIN = 90.0
    private const val WEIGHT_LOCATION = 0.55
    private const val WEIGHT_TIME = 0.45
    private const val WEIGHT_WEAK_TIME = 0.2
    private const val MIN_SCORE = 0.2

    enum class Confidence { HIGH, MEDIUM, LOW, NONE }

    /** 照片上讀得到的證據。[capturedAt] 為 null＝不知道拍攝時間 */
    data class Evidence(
        val capturedAt: Long?,
        /** 拍攝時間只來自檔案修改時間（不可靠，權重降低） */
        val weakTime: Boolean = false,
        val lat: Double? = null,
        val lng: Double? = null
    )

    /** 一站的時間窗（毫秒）與座標 */
    data class StopWindow(
        val stopId: String,
        val index: Int,
        val dayKey: String?,
        val start: Long?,
        val end: Long?,
        val lat: Double?,
        val lng: Double?
    )

    data class Result(
        /** 最可能的站（信心低時也會給，當作預設選項）；完全沒證據為 null */
        val stopId: String?,
        val confidence: Confidence,
        val score: Double,
        val distanceMeters: Int? = null,
        val timeDistanceMinutes: Int? = null
    ) {
        /** 可以不經確認直接歸站 */
        val autoAssign: Boolean get() = stopId != null && (confidence == Confidence.HIGH || confidence == Confidence.MEDIUM)
    }

    fun classify(photo: Evidence, stops: List<StopWindow>, zone: ZoneId): Result {
        val dayKey = photo.capturedAt?.let { dayKeyOf(it, zone) }
        val hasCoords = photo.lat != null && photo.lng != null
        val candidates = stops.map { s ->
            val distance = if (hasCoords && s.lat != null && s.lng != null)
                haversineMeters(photo.lat!!, photo.lng!!, s.lat, s.lng) else Double.POSITIVE_INFINITY
            val locationScore = if (distance.isFinite()) max(0.0, 1 - distance / (RADIUS_M * 3)) else 0.0
            val timeDistance = timeDistanceMinutes(photo.capturedAt, s)
            val timeScore = if (timeDistance.isFinite()) max(0.0, 1 - timeDistance / TIME_MARGIN_MIN) else 0.0
            val score = locationScore * WEIGHT_LOCATION + timeScore * (if (photo.weakTime) WEIGHT_WEAK_TIME else WEIGHT_TIME)
            Candidate(s, score, locationScore > 0, timeScore > 0,
                distance.takeIf { it.isFinite() }?.roundToInt(), timeDistance.takeIf { it.isFinite() }?.roundToInt())
        }
            // 只比同一天的站（任一方不知道日期就不擋）
            .filter { dayKey == null || it.stop.dayKey == null || it.stop.dayKey == dayKey }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.stop.index })
        val best = candidates.firstOrNull()
            ?: return Result(null, Confidence.NONE, 0.0)
        if (best.score < MIN_SCORE || (!best.hasLocation && !best.hasTime)) {
            // 分數太低：仍把最接近的那站當預設選項（有任何證據時）
            return Result(best.stop.stopId.takeIf { best.score > 0 }, Confidence.NONE, best.score,
                best.distanceMeters, best.timeDistanceMinutes)
        }
        val both = best.hasLocation && best.hasTime
        // 照片有 GPS、卻離這站超過位置計分範圍（3×半徑）：拍攝地點本身就是反證，
        // 時間再吻合也不自動歸站（實測：台北拍的照片因時間落在藝文中心的時段被歸進去）。
        // 網頁 classifyPhoto 沒有這條，距離遠只是位置 0 分；這裡比網頁嚴格。
        val gpsContradicts = hasCoords && !best.hasLocation
        val confidence = when {
            gpsContradicts -> Confidence.LOW
            both && best.score >= 0.72 -> Confidence.HIGH
            best.score >= 0.38 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Result(best.stop.stopId, confidence, best.score, best.distanceMeters, best.timeDistanceMinutes)
    }

    private data class Candidate(
        val stop: StopWindow,
        val score: Double,
        val hasLocation: Boolean,
        val hasTime: Boolean,
        val distanceMeters: Int?,
        val timeDistanceMinutes: Int?
    )

    /** 拍攝時間離站點時間窗多遠（分鐘）；窗內為 0，資料不足為無限大 */
    private fun timeDistanceMinutes(capturedAt: Long?, s: StopWindow): Double {
        if (capturedAt == null || s.start == null) return Double.POSITIVE_INFINITY
        val end = s.end ?: s.start
        return when {
            capturedAt < s.start -> (s.start - capturedAt) / 60_000.0
            capturedAt > end -> (capturedAt - end) / 60_000.0
            else -> 0.0
        }
    }

    /**
     * 把行程站點換成時間窗：第 dayIndex 天（1 起算）的 `time`（HH:mm）開始，停留 duration 分鐘
     * （沒有就 60 分，同網頁）。[tripStart] 不知道時（共編文件只有時數）回傳沒有時間的窗，只靠位置判斷。
     */
    fun windowsOf(stops: List<Stop>, tripStart: LocalDate?, zone: ZoneId): List<StopWindow> =
        stops.mapIndexed { i, s ->
            val day = tripStart?.plusDays((s.dayIndex.coerceAtLeast(1) - 1).toLong())
            val clock = parseClock(s.time)
            val start = if (day != null && clock != null) day.atTime(clock).atZone(zone).toInstant().toEpochMilli() else null
            val stay = (if (s.duration > 0) s.duration else 60L) * 60_000
            StopWindow(
                stopId = s.stopId, index = i,
                dayKey = day?.toString(),
                start = start, end = start?.plus(stay),
                lat = s.lat, lng = s.lng
            )
        }

    private fun parseClock(time: String): LocalTime? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(time) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun dayKeyOf(epoch: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate().toString()

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val rad = Math.PI / 180
        val dLat = (lat2 - lat1) * rad
        val dLng = (lng2 - lng1) * rad
        val p = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 6_371_000 * 2 * atan2(sqrt(p), sqrt(1 - p))
    }

    /** 經緯度是否合理（EXIF 讀到 0,0 常是沒有定位） */
    fun validCoords(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && abs(lat) <= 90 && abs(lng) <= 180 && !(lat == 0.0 && lng == 0.0)
}
