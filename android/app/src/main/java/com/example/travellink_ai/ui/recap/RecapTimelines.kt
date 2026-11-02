package com.example.travellink_ai.ui.recap

import com.example.travellink_ai.ui.recap.model.RecapAssetRef
import com.example.travellink_ai.ui.recap.model.RecapClip
import com.example.travellink_ai.ui.recap.model.RecapOutroStats
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 回顧短片的節奏樣板與推薦版時間軸：網頁 app/recap-templates.js、recap-manifest.js、recap-timeline.js
 * （泓廷 2026-10-19，2d2de15／1610c0b；2026-11-01 加 Reels 與時間軸第 2 版，見 [RecapReels]）的 Kotlin 版，
 * **算法照抄**——App 編輯器上顯示的秒數要等於後端實際渲染的秒數（後端用同一份 JS 重新驗證、夾住時長），
 * 所以改動時兩邊要一起改；網頁改了就重跑 scripts/gen_recap_fixtures.js 產生對照資料（RecapReelsParityTest）。
 *
 * 推薦版用規則產生、不呼叫 AI；AI 只用來寫字幕與建議照片長短（[applyScriptSuggestion]），
 * 不新增片段、不改順序。時間單位一律 ms。
 */
object RecapTimelines {

    // ── 節奏樣板（recap-templates.js）────────────────────────────

    /**
     * 片長目標依真實素材數分四級（0／1-2／3-5／6+ 張照片）；素材不足就縮短影片，不用假畫面補長度。
     * segment：站間路線時間依距離比例，但每段至少 [segMinMs]、最多佔全部路線時間的 [segMaxShare]。
     * coverStyle：title（台灣全景俯衝＋標題卡）／photoCut（先放實景照片，硬切到地圖、停半拍）。
     */
    data class Template(
        val id: String, val label: String, val desc: String,
        val coverMs: Double, val statsMs: Double, val dwellMs: Double, val photoMs: Double, val dayTransitionMs: Double,
        val targetMs: Map<String, Double>, val minRouteMs: Double,
        val segMinMs: Double, val segMaxShare: Double, val segEase: String,
        val coverStyle: String = "title", val coverPhotoMs: Double = 1430.0, val coverBeatMs: Double = 320.0,
        /** Reels 類樣板（structure 'anchors'）才有：只在有照片的站之間播路線，產時間軸第 2 版 */
        val anchors: Anchors? = null
    )

    /** Reels 的秒數規則（recap-templates.js 的 reels）；fixed15 是「固定 15 秒」時主角照片的秒數 */
    data class Anchors(
        val revealMs: Double, val heroMs: Double, val heroSingleMs: Double, val hopMs: Double, val summaryMs: Double,
        val routeOnlyMs: Double, val featuredRouteMs: Double, val focusMs: Double, val focusMax: Int, val maxMs: Double,
        val fixed15TotalMs: Double, val fixed15HeroSingleMs: Double, val fixed15HeroMs: Double,
        val fixed15HeroManyMs: Double, val fixed15HeroManyCount: Int
    )

    /** 請求沒帶樣板時後端用的（舊版 App 的請求維持「標準」） */
    const val DEFAULT_TEMPLATE_ID = "standard"
    /** 選單的預設值（IG 分享優化提案 A2）：沒選過節奏的人用 Reels */
    const val RECOMMENDED_TEMPLATE_ID = "reels"
    /** Reels 的長度：all＝放進全部照片（上限 30 秒）；15＝固定 15 秒，放不下的照片站略過 */
    val LENGTH_MODES = listOf("all", "15")

    val TEMPLATES: List<Template> = listOf(
        Template("reels", "Reels", "照片當主角、節奏明快，適合 IG Reels 與限時動態。",
            1200.0, 3300.0, 0.0, 1400.0, 0.0,
            mapOf("0" to 15000.0, "1-2" to 15000.0, "3-5" to 20000.0, "6+" to 30000.0), 3000.0,
            300.0, 0.45, "smooth",
            anchors = Anchors(revealMs = 1000.0, heroMs = 1000.0, heroSingleMs = 2000.0, hopMs = 700.0, summaryMs = 1500.0,
                routeOnlyMs = 6000.0, featuredRouteMs = 5000.0, focusMs = 600.0, focusMax = 4, maxMs = 30000.0,
                fixed15TotalMs = 15000.0, fixed15HeroSingleMs = 2400.0, fixed15HeroMs = 1000.0,
                fixed15HeroManyMs = 800.0, fixed15HeroManyCount = 3)),
        // 換日卡（第 5 個數字）2026-11-01 縮短（A10）：標準 1600→800、快節奏 1100→600、慢遊 2200→800、電影感 1500→700
        Template("standard", "標準", "從台灣全景俯衝到路線，到站停一下看照片。",
            3400.0, 3000.0, 200.0, 2600.0, 800.0,
            mapOf("0" to 14500.0, "1-2" to 18000.0, "3-5" to 23000.0, "6+" to 32000.0), 6000.0,
            900.0, 0.45, "smooth"),
        Template("brisk", "快節奏", "更短、更緊湊，適合 Reels 與限時動態。",
            2200.0, 2200.0, 120.0, 1800.0, 600.0,
            mapOf("0" to 11000.0, "1-2" to 13500.0, "3-5" to 17000.0, "6+" to 24000.0), 4500.0,
            700.0, 0.4, "smooth"),
        Template("leisurely", "慢遊", "節奏放慢，照片停留更久，適合多日長途。",
            4200.0, 3600.0, 400.0, 3600.0, 800.0,
            mapOf("0" to 18000.0, "1-2" to 22000.0, "3-5" to 29000.0, "6+" to 40000.0), 8000.0,
            1200.0, 0.45, "smooth"),
        Template("cinematic", "電影感開場", "先放一張實景照片，硬切到地圖、停半拍再開始畫路線。",
            2600.0, 2800.0, 200.0, 2400.0, 700.0,
            mapOf("0" to 14000.0, "1-2" to 17500.0, "3-5" to 22500.0, "6+" to 31000.0), 6000.0,
            900.0, 0.45, "linear", coverStyle = "photoCut", coverPhotoMs = 1430.0, coverBeatMs = 320.0)
    )

    fun hasTemplate(id: String?) = TEMPLATES.any { it.id == id }
    fun template(id: String?): Template = TEMPLATES.firstOrNull { it.id == id } ?: TEMPLATES.first { it.id == DEFAULT_TEMPLATE_ID }
    /** Reels 類樣板（時間軸第 2 版） */
    fun isAnchor(id: String?): Boolean = template(id).anchors != null

    // ── manifest（recap-manifest.js：只移植算時間軸需要的部分）──────────

    data class ManifestOptions(
        val template: String? = null,
        val coverMs: Double? = null,
        val statsMs: Double? = null,
        val mediaCount: Int = 0,
        val mediaStopIndices: List<Int> = emptyList(),
        /** 第 i 段路線的時長（編輯過的時間軸） */
        val segmentMs: Map<Int, Double> = emptyMap(),
        /** 第 i 站照片的停留時長 */
        val holdMsByStop: Map<Int, Double> = emptyMap()
    )

    data class Segment(val fromIndex: Int, val toIndex: Int, val geoDistanceKm: Double, val travelMs: Double,
                       val dayBreak: Boolean, val toDay: Int)

    data class Manifest(
        val templateId: String, val coverStyle: String, val coverPhotoStopIndex: Int?,
        val segments: List<Segment>,
        val coverMs: Double, val routeMs: Double, val statsMs: Double,
        val holdMs: List<Double>, val arriveMs: List<Double>, val totalMs: Double,
        val adaptiveTier: String, val dayCount: Int
    )

    internal fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = Math.PI / 180
        val sinLat = sin((lat2 - lat1) * r / 2)
        val sinLng = sin((lng2 - lng1) * r / 2)
        val a = sinLat * sinLat + cos(lat1 * r) * cos(lat2 * r) * sinLng * sinLng
        return 6371 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 站間路線時間：依距離比例分配，但每段至少 minMs、最多 maxMs（再把多出／不足的時間分給其他段）。
     * 站多時下限最多取平均的 60%，仍依距離分配。
     */
    internal fun allocateSegmentMs(
        distances: List<Double>, indices: List<Int>, totalMs: Double, segMinMs: Double, segMaxShare: Double
    ): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        val count = indices.size
        if (count == 0) return out
        val minMs = min(max(0.0, segMinMs), totalMs / count * 0.6)
        val maxShare = if (segMaxShare > 0) segMaxShare else 1.0
        val maxMs = maxOf(minMs, totalMs * maxShare, totalMs / count)
        val fixed = HashMap<Int, Double>()
        for (round in 0..count) {
            var remaining = totalMs
            val free = mutableListOf<Int>()
            var freeDist = 0.0
            indices.forEach { i -> val f = fixed[i]; if (f != null) remaining -= f else { free += i; freeDist += distances[i] } }
            if (free.isEmpty()) break
            var changed = false
            free.forEach { i ->
                val v = if (freeDist > 0) remaining * (distances[i] / freeDist) else remaining / free.size
                when {
                    v < minMs -> { fixed[i] = minMs; changed = true }
                    v > maxMs -> { fixed[i] = maxMs; changed = true }
                    else -> out[i] = v
                }
            }
            if (!changed) break
            free.forEach { out.remove(it) }
        }
        indices.forEach { i -> fixed[i]?.let { out[i] = it } }
        // 每段都卡在上下限時總和可能不到 totalMs：剩下的按比例加回（JS 版有段沒分到時總和是 NaN、不縮放）
        if (indices.all { out.containsKey(it) }) {
            val sum = indices.sumOf { out.getValue(it) }
            if (sum > 0 && sum < totalMs - 1) {
                val scale = totalMs / sum
                indices.forEach { out[it] = out.getValue(it) * scale }
            }
        }
        return out
    }

    fun buildManifest(trip: RecapTrip, opts: ManifestOptions): Manifest {
        val t = template(opts.template)
        val stops = trip.stops
        val mediaIn = opts.mediaStopIndices
        // 電影感開場需要一張實景照片；沒有照片就退回標題卡開場
        val usePhotoCut = t.coverStyle == "photoCut" && mediaIn.isNotEmpty()
        val coverMs = opts.coverMs ?: if (usePhotoCut) t.coverPhotoMs + t.coverBeatMs else t.coverMs
        val statsMs = opts.statsMs ?: t.statsMs
        val tier = when { opts.mediaCount == 0 -> "0"; opts.mediaCount <= 2 -> "1-2"; opts.mediaCount <= 5 -> "3-5"; else -> "6+" }
        val target = t.targetMs[tier] ?: 0.0
        val routeMs = max(t.minRouteMs, target - coverMs - statsMs)

        val distances = (0 until stops.size - 1).map { i ->
            haversineKm(stops[i].lat, stops[i].lng, stops[i + 1].lat, stops[i + 1].lng)
        }
        // 多日：跨天段是固定時長的「換日轉場」，不參與距離分配
        val breaks = distances.indices.map { i -> stops[i].dayIndex != stops[i + 1].dayIndex }
        val breakCount = breaks.count { it }
        val drivingMs = max(3000.0, routeMs - breakCount * t.dayTransitionMs)
        val drivingIndices = distances.indices.filter { !breaks[it] }
        val allocated = allocateSegmentMs(distances, drivingIndices, drivingMs, t.segMinMs, t.segMaxShare)

        val segments = distances.indices.map { i ->
            var travel = if (breaks[i]) t.dayTransitionMs
                else allocated[i] ?: (drivingMs / max(1, drivingIndices.size))
            opts.segmentMs[i]?.takeIf { it.isFinite() && it > 0 }?.let { travel = it }
            Segment(i, i + 1, distances[i], travel, breaks[i], stops[i + 1].dayIndex)
        }

        val mediaSet = mediaIn.toSet()
        val last = stops.size - 1
        val holdMs = stops.indices.map { i ->
            val middle = i in 1 until last
            var h = if (i in mediaSet) t.photoMs else if (middle) t.dwellMs else 0.0
            if (i in mediaSet) opts.holdMsByStop[i]?.takeIf { it.isFinite() && it > 0 }?.let { h = it }
            h
        }
        val arriveMs = mutableListOf<Double>()
        if (stops.isNotEmpty()) {
            var cur = coverMs
            arriveMs += cur
            cur += holdMs[0]
            segments.forEach { s ->
                cur += s.travelMs
                arriveMs += cur
                cur += holdMs[s.toIndex]
            }
        }
        val totalMs = coverMs + segments.sumOf { it.travelMs } + holdMs.sum() + statsMs
        return Manifest(
            templateId = t.id,
            coverStyle = if (usePhotoCut) "photoCut" else "title",
            coverPhotoStopIndex = if (usePhotoCut) mediaIn.first() else null,
            segments = segments, coverMs = coverMs, routeMs = routeMs, statsMs = statsMs,
            holdMs = holdMs, arriveMs = arriveMs, totalMs = totalMs,
            adaptiveTier = tier, dayCount = max(1, stops.maxOfOrNull { it.dayIndex } ?: 1)
        )
    }

    // ── 時間軸（recap-timeline.js）──────────────────────────────

    /**
     * 每種片段可接受的時長範圍（ms）：編輯器與後端共用同一組限制。
     * photo：第 1 版單張照片的範圍；photoEach：第 2 版「每張」的範圍，整段上下限＝張數 × photoEach（最多 30 秒）。
     */
    val LIMITS: Map<String, LongRange> = mapOf(
        "cover" to 1000L..6000L,
        "route" to 300L..15000L,
        "reveal" to 500L..3000L,
        "photo" to 800L..8000L,
        "photoEach" to 500L..4000L,
        "timeline" to 600L..4000L,
        "summary" to 800L..4000L,
        "outro" to 1000L..6000L
    )
    /** 字幕每則最多幾個字 */
    const val CAPTION_MAX = 14
    val CAPTION_TYPES = setOf("cover", "route", "photo", "summary", "outro")

    private fun jsRound(v: Double): Long = if (v.isFinite()) Math.round(v) else 0L

    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f\\u2028\\u2029]")
    // 等同 JS 的 \s（網頁 sanitizeCaption 用的）。不能寫 (?U)\s：Android 的 ICU regex 不支援這個旗標，一打字就閃退
    private val JS_WHITESPACE = Regex("[\\t\\n\\u000b\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

    fun sanitizeCaption(value: String?): String {
        val text = (value ?: "")
            .replace(CONTROL_CHARS, " ")
            .replace(JS_WHITESPACE, " ")
            .trim()
        val cps = text.codePointCount(0, text.length)
        return if (cps <= CAPTION_MAX) text else text.substring(0, text.offsetByCodePoints(0, CAPTION_MAX))
    }

    /** @param count photo 片段的張數（第 2 版）；0＝第 1 版的單張照片 */
    fun clampMs(type: String, value: Double, count: Int = 0): Long {
        val range = LIMITS[type] ?: 0L..60000L
        val n = jsRound(value)
        if (type == "photo" && count > 0) {
            val each = LIMITS.getValue("photoEach")
            val per = count.coerceIn(1, 20)
            return max(each.first * per, min(min(each.last * per, 30000L), n))
        }
        return n.coerceIn(range.first, range.last)
    }

    private fun photoStopIndices(photos: List<RecapPhoto>, stopCount: Int): List<Int> =
        photos.map { it.stopIndex }.filter { it in 0 until stopCount }.distinct().sorted()

    /**
     * 依行程＋實際照片排出推薦版。Reels 類樣板另吃 [lengthMode]、[highlightStopId]（主角景點）、
     * [coverStopIndex]（自選封面），產第 2 版（見 [RecapReels]）
     */
    fun buildRecommended(
        trip: RecapTrip, templateId: String?, photos: List<RecapPhoto>,
        lengthMode: String = "all", highlightStopId: String? = null, coverStopIndex: Int? = null
    ): RecapTimeline {
        if (isAnchor(templateId)) return RecapReels.buildRecommended(trip, templateId, photos, lengthMode, highlightStopId, coverStopIndex)
        val stops = trip.stops
        val firstPhotoId = HashMap<Int, String?>()
        photos.forEach { if (!firstPhotoId.containsKey(it.stopIndex)) firstPhotoId[it.stopIndex] = it.photoId?.takeIf { id -> id.isNotEmpty() } }
        val mediaIdx = photoStopIndices(photos, stops.size)
        val m = buildManifest(trip, ManifestOptions(template = templateId, mediaCount = mediaIdx.size, mediaStopIndices = mediaIdx))
        val clips = mutableListOf<RecapClip>()
        fun push(c: RecapClip) { clips += c.copy(order = clips.size) }
        fun photoClip(i: Int) {
            val stop = stops[i]
            push(RecapClip(clipId = "photo-$i", type = "photo", stopIndex = i, label = stop.name,
                assetRef = RecapAssetRef(stopId = stop.stopId, photoId = firstPhotoId[i]), durationMs = jsRound(m.holdMs[i])))
        }
        push(RecapClip(clipId = "cover", type = "cover", style = m.coverStyle, durationMs = jsRound(m.coverMs)))
        if (m.holdMs.firstOrNull()?.let { it > 0 } == true && 0 in mediaIdx) photoClip(0)
        m.segments.forEachIndexed { i, seg ->
            if (seg.dayBreak) {
                push(RecapClip(clipId = "day-${seg.toDay}", type = "timeline", day = seg.toDay, durationMs = jsRound(seg.travelMs)))
            } else {
                val from = stops[seg.fromIndex]; val to = stops[seg.toIndex]
                push(RecapClip(clipId = "route-$i", type = "route", segmentIndex = i,
                    fromStopId = from.stopId, toStopId = to.stopId, label = "${from.name} → ${to.name}",
                    distanceKm = Math.round(seg.geoDistanceKm * 10) / 10.0, durationMs = jsRound(seg.travelMs)))
            }
            if (seg.toIndex in mediaIdx) photoClip(seg.toIndex)
        }
        push(RecapClip(clipId = "outro", type = "outro",
            stats = RecapOutroStats(max(0, stops.size - 1), trip.distanceKm, m.dayCount), durationMs = jsRound(m.statsMs)))
        return RecapTimeline(templateId = m.templateId, adaptiveTier = m.adaptiveTier, totalMs = jsRound(m.totalMs), clips = clips)
    }

    /** 時間軸 → manifest 參數。只採用對得回行程的片段，時長夾在 [LIMITS] 內（與後端同一套驗證） */
    fun compile(trip: RecapTrip, timeline: RecapTimeline, photos: List<RecapPhoto>): ManifestOptions {
        val stops = trip.stops
        val available = photoStopIndices(photos, stops.size)
        val idIndex = stops.withIndex().associate { (i, s) -> s.stopId to i }
        val segmentMs = HashMap<Int, Double>()
        val holdMs = HashMap<Int, Double>()
        val media = mutableListOf<Int>()
        var coverMs: Double? = null
        var statsMs: Double? = null
        timeline.clips.forEach { c ->
            when (c.type) {
                "cover" -> coverMs = clampMs("cover", c.durationMs.toDouble()).toDouble()
                "outro" -> statsMs = clampMs("outro", c.durationMs.toDouble()).toDouble()
                "route" -> {
                    val from = idIndex[c.fromStopId ?: ""] ?: return@forEach
                    if (idIndex[c.toStopId ?: ""] != from + 1) return@forEach
                    segmentMs[from] = clampMs("route", c.durationMs.toDouble()).toDouble()
                }
                "photo" -> {
                    val idx = idIndex[c.assetRef?.stopId ?: ""] ?: return@forEach
                    if (idx !in available || idx in media) return@forEach
                    media += idx
                    holdMs[idx] = clampMs("photo", c.durationMs.toDouble()).toDouble()
                }
            }
        }
        media.sort()
        return ManifestOptions(template = timeline.templateId, coverMs = coverMs, statsMs = statsMs,
            mediaCount = media.size, mediaStopIndices = media, segmentMs = segmentMs, holdMsByStop = holdMs)
    }

    /** 依目前片段重新算總長（與後端同一套 manifest 計算） */
    fun totalMs(trip: RecapTrip, timeline: RecapTimeline, photos: List<RecapPhoto>): Long =
        if (isAnchor(timeline.templateId)) RecapReels.totalMs(trip, timeline, photos)
        else jsRound(buildManifest(trip, compile(trip, timeline, photos)).totalMs)

    /** 片段卡片上顯示的一列；第 2 版照片片段帶 [assetRefs]（一站多張，可個別移除） */
    data class Row(
        val clipId: String, val type: String, val icon: String, val text: String, val caption: String, val seconds: Double,
        val photoCount: Int = 0, val hero: Boolean = false, val assetRefs: List<RecapAssetRef> = emptyList()
    )

    fun summarize(timeline: RecapTimeline): List<Row> {
        val v2 = timeline.schemaVersion == 2
        return timeline.clips.map { c ->
            val count = c.assetRefs?.size ?: 1
            val icon = when {
                c.type == "route" && c.reveal == true -> "✨"
                c.type == "photo" && c.hero == true -> "⭐"
                else -> when (c.type) { "cover" -> "🎬"; "route" -> "🚗"; "photo" -> "📷"; "timeline" -> "🌙"; "summary" -> "🗺️"; "outro" -> "📊"; else -> "•" }
            }
            val text = when (c.type) {
                "cover" -> if (v2) (if (c.source == "photo") "開場：自選封面照片" else "開場：行程插圖＋標題")
                    else if (c.style == "photoCut") "開場：實景照片硬切到地圖" else "開場：台灣全景俯衝到路線"
                "route" -> if (c.reveal == true) "路線揭曉：從起點畫到終點"
                    else (c.label ?: "") + (c.distanceKm?.takeIf { it != 0.0 }?.let { "（${fmtKm(it)} km）" } ?: "")
                "photo" -> (if (c.hero == true) "主角景點：" else "照片：") + (c.label ?: "") +
                    (if (count > 1) "（$count 張）" else "") + (c.dayLabel?.let { "・第 $it 天" } ?: "")
                "timeline" -> "換日：第 ${c.day} 天"
                "summary" -> "旅程收束：完整路線與數據"
                else -> if (v2) "結尾：攻略卡" else "結尾：旅程數據"
            }
            Row(c.clipId, c.type, icon, text, c.caption ?: "", Math.round(c.durationMs / 100.0) / 10.0,
                photoCount = if (c.type == "photo") count else 0, hero = c.hero == true, assetRefs = c.assetRefs.orEmpty())
        }
    }

    private fun fmtKm(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    // ── 編輯器（都回傳新的時間軸，不改原物件，方便「一步復原」）──

    fun setDuration(timeline: RecapTimeline, clipId: String, durationMs: Long) = timeline.copy(
        clips = timeline.clips.map {
            if (it.clipId == clipId) it.copy(durationMs = clampMs(if (it.reveal == true) "reveal" else it.type, durationMs.toDouble(), it.assetRefs?.size ?: 0))
            else it
        },
        edited = true
    )

    fun setCaption(timeline: RecapTimeline, clipId: String, caption: String) = timeline.copy(
        clips = timeline.clips.map { c ->
            if (c.clipId != clipId || c.type !in CAPTION_TYPES) c
            else c.copy(caption = sanitizeCaption(caption).ifEmpty { null })
        },
        edited = true
    )

    /** 只能移除照片片段（路線與開場結尾是影片骨架，拿掉會讓地圖跳站）；第 2 版會把前後的路線接起來 */
    fun removePhoto(timeline: RecapTimeline, clipId: String): RecapTimeline =
        if (timeline.schemaVersion == 2) RecapReels.removePhotoClip(timeline, clipId)
        else timeline.copy(
            clips = timeline.clips.filterNot { it.clipId == clipId && it.type == "photo" }.mapIndexed { i, c -> c.copy(order = i) },
            edited = true
        )

    /** 第 2 版：一站多張的照片片段個別移除第 [index] 張；只剩一張再移除＝移除整個片段 */
    fun removeClipPhoto(timeline: RecapTimeline, clipId: String, index: Int): RecapTimeline =
        RecapReels.removeClipPhoto(timeline, clipId, index)

    /**
     * 第 2 版：換封面（[stopIndex] 那站的照片；null＝行程插圖）。只改開場片段、不算「編輯過」——
     * 封面另外存在這台裝置，推薦版重排時會再套上去。
     */
    fun setCover(timeline: RecapTimeline, stopIndex: Int?): RecapTimeline = timeline.copy(
        clips = timeline.clips.map { c ->
            if (c.type != "cover" || timeline.schemaVersion != 2) c
            else c.copy(source = if (stopIndex != null) "photo" else "illustration", coverStopIndex = stopIndex)
        }
    )

    /**
     * AI 劇本建議 → 新時間軸：只填字幕、調整照片片段長短；不新增片段、不改順序。
     * suggestion：{ cover, outro, clips: { [clipId]: { caption, emphasis: long|normal|short } } }
     * [keepExisting]：使用者已經打好的字幕不覆蓋（只填空白的）
     */
    fun applyScriptSuggestion(timeline: RecapTimeline, suggestion: JSONObject?, keepExisting: Boolean): RecapTimeline {
        val s = suggestion ?: JSONObject()
        val byClip = s.optJSONObject("clips") ?: JSONObject()
        val factor = mapOf("long" to 1.35, "normal" to 1.0, "short" to 0.75)
        val clips = timeline.clips.map { c ->
            val sug: JSONObject = when (c.type) {
                "cover" -> JSONObject().put("caption", s.opt("cover"))
                "outro" -> JSONObject().put("caption", s.opt("outro"))
                else -> byClip.optJSONObject(c.clipId) ?: return@map c
            }
            var next = c
            if (c.type in CAPTION_TYPES && !(keepExisting && !c.caption.isNullOrEmpty())) {
                val text = sanitizeCaption(sug.opt("caption")?.takeIf { it != JSONObject.NULL }?.toString())
                if (text.isNotEmpty()) next = next.copy(caption = text)
            }
            val f = factor[sug.optString("emphasis")]
            // 固定 15 秒的版本不改長短（改了總長就不是 15 秒）
            if (c.type == "photo" && f != null && timeline.lengthMode != "15") next = next.copy(durationMs = clampMs("photo", c.durationMs * f, c.assetRefs?.size ?: 0))
            next
        }
        return timeline.copy(clips = clips, edited = true, scriptSource = "ai")
    }

    /**
     * AI 寫字幕的指令（與網頁 recapAiScript 同一份）：只能根據站名、天數、距離、旅記備註寫，
     * 不可編造天氣、同行者、心情或事件。回 JSON：{cover, outro, clips: {片段id: {caption, emphasis}}}
     * @param notes stopId → 旅記備註
     * @param facts stopId → 實用資訊（營業時間、門票／餐費、停車步行，見 [RecapFacts]），字幕可以照抄其中一項
     */
    fun captionPrompt(
        trip: RecapTrip, timeline: RecapTimeline, title: String, notes: Map<String, String>,
        facts: Map<String, String> = emptyMap()
    ): String {
        val byId = trip.stops.associateBy { it.stopId }
        val lines = timeline.clips.filter { it.type == "route" || it.type == "photo" || it.type == "summary" }.map { c ->
            when {
                // 第 2 版：路線揭曉、跨好幾站的過場、旅程收束（與網頁 recapAiScript 同格式）
                c.type == "route" && c.reveal == true ->
                    "${c.clipId}｜路線揭曉｜從「${byId[c.fromStopId]?.name ?: ""}」到「${byId[c.toStopId]?.name ?: ""}」（全程 ${fmtKm(Math.round(trip.distanceKm * 10) / 10.0)} km）"
                c.type == "summary" ->
                    "${c.clipId}｜旅程收束｜完整路線（${trip.stops.size} 個景點，${fmtKm(Math.round(trip.distanceKm * 10) / 10.0)} km）"
                c.type == "route" -> {
                    val to = byId[c.toStopId]
                    val via = c.viaStopIds.orEmpty().mapNotNull { byId[it]?.name }
                    "${c.clipId}｜路線｜前往「${to?.name ?: ""}」（第 ${to?.dayIndex ?: 1} 天，${fmtKm(c.distanceKm ?: 0.0)} km" +
                        (if (via.isNotEmpty()) "，經過${via.joinToString("、") { "「$it」" }}" else "") + "）"
                }
                else -> {
                    val st = byId[c.assetRefs?.firstOrNull()?.stopId ?: c.assetRef?.stopId]
                    val note = st?.let { notes[it.stopId] }
                    val fact = st?.let { facts[it.stopId] }
                    val count = c.assetRefs?.size ?: 1
                    "${c.clipId}｜照片｜「${st?.name ?: ""}」（第 ${st?.dayIndex ?: 1} 天，停留 ${st?.stayMin ?: 0} 分鐘" +
                        (if (count > 1) "，$count 張照片" else "") + (if (c.hero == true) "，主角景點" else "") + "）" +
                        (note?.let { "｜旅記備註：$it" } ?: "") + (fact?.let { "｜實用資訊：$it" } ?: "")
                }
            }
        }
        return (listOf(
            "你是旅遊回顧短片的剪輯助手，要替每個片段寫一句字幕。",
            "旅程：${title.take(40)}；地區：${trip.region}；日期：${trip.dateLabel}；交通：${trip.transportMode}",
            "片段（片段id｜類型｜內容）："
        ) + lines + listOf(
            "",
            "規則：",
            "1. 每則字幕不超過 $CAPTION_MAX 個中文字，口語、溫暖、不要 emoji 與 hashtag。",
            "2. 只能根據上面提供的景點名稱、天數、距離、旅記備註與實用資訊寫；不可編造天氣、同行者、心情、吃了什麼或發生的事件。",
            "3. 路線片段寫移動感（例如「前往某某」「沿著海岸到某某」），照片片段寫該景點；有旅記備註時優先用備註的內容。路線揭曉寫整趟從哪到哪，旅程收束寫一句回顧整條路線。",
            "4. 不要寫資料裡沒有的方位（東南西北）、數字或景點特色描述，寧可樸素也不要猜。數字只能是距離或實用資訊裡寫的。",
            "5. 有實用資訊的照片片段，可以挑一項最有用的寫進字幕（例如「營業到 22:00」「門票 50 元」「停好車走 3 分鐘」），數字照抄、不可換算或推測；同一則字幕最多一項，不必每則都寫。",
            "6. emphasis 只用於照片片段：有旅記備註或停留較久的寫 long，其餘 normal，最多一半寫 short。",
            "只回 JSON（不要多餘文字）：{\"cover\":\"開場字幕\",\"outro\":\"結尾字幕\",\"clips\":{\"片段id\":{\"caption\":\"字幕\",\"emphasis\":\"long|normal|short\"}}}"
        )).joinToString("\n")
    }

    /**
     * 推薦版／編輯版適不適用：樣板、站（stopId 順序）、有照片的站任一改變就要重排。
     * Reels 一站多張、時間軸用 photoId 對照片，所以再加上長度、主角與每張照片
     */
    fun signature(
        templateId: String, trip: RecapTrip, photos: List<RecapPhoto>, lengthMode: String = "all", heroStopId: String? = null
    ): String =
        if (!isAnchor(templateId))
            listOf(templateId, trip.stops.joinToString(",") { it.stopId }, photos.map { it.stopIndex }.sorted().joinToString(","))
                .joinToString("|")
        else listOf(templateId, lengthMode, heroStopId.orEmpty(), trip.stops.joinToString(",") { it.stopId },
            photos.joinToString(",") { "${it.stopIndex}:${it.photoId ?: it.url}" }).joinToString("|")

}
