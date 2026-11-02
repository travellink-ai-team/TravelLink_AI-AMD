package com.example.travellink_ai.ui.recap

import com.example.travellink_ai.ui.recap.model.RecapAssetRef
import com.example.travellink_ai.ui.recap.model.RecapClip
import com.example.travellink_ai.ui.recap.model.RecapOutroStats
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import kotlin.math.max
import kotlin.math.min

/**
 * Reels 節奏（樣板 structure 'anchors'，時間軸第 2 版）：網頁 recap-manifest.js 的 buildAnchorManifest 與
 * recap-timeline.js 第 2 版函式的 Kotlin 版（泓廷 2026-11-01，docs/travel-story/RECAP_IG_V2_ALIGNMENT.md）。
 * **算法照抄**——後端用同一份 JS 重新排，App 編輯器上的秒數才會等於影片。對照資料由
 * scripts/gen_recap_fixtures.js 用網頁部署的 JS 產生，RecapReelsParityTest 逐段比對。
 *
 * 只在有照片的站（錨點）之間播路線，中段依照片張數切三種結構：
 *   photoStory（3 張以上）：開場 → 路線揭曉 → [過場 → 照片] × N → 旅程收束 → 攻略卡
 *   mapFeatured（1～2 張）：開場 → 整條路線開一遍、到錨點放滿版照片 → 旅程收束 → 攻略卡
 *   routeStory（0 張）：開場 → 路線揭曉 → 整條路線開一遍、依序聚焦幾站 → 旅程收束 → 攻略卡
 * 換日不出換日卡，改在當天第一張照片標 dayLabel；跨天的段不畫車（0 秒直接跳到隔天）。
 */
internal object RecapReels {

    /** 錨點計畫的一項：放這站前要不要先播一段過場（hopBefore），過場秒數（hopMs，null＝樣板預設） */
    class PlanItem(
        val stopIndex: Int, val count: Int, val hero: Boolean = false, var photoMs: Double,
        var hopBefore: Boolean = true, var hopMs: Double? = null
    )

    /** buildAnchorManifest 的參數（時間軸 compile 出來的，或推薦版用的） */
    class Options(
        val template: String? = null,
        val lengthMode: String = "all",
        val photoCounts: List<Int>? = null,
        val mediaStopIndices: List<Int>? = null,
        /** null＝依規則挑；-1＝明確不設主角（時間軸上沒有標 hero 的照片片段） */
        val heroStopIndex: Int? = null,
        val coverMs: Double? = null,
        val statsMs: Double? = null,
        val summaryMs: Double? = null,
        val revealMs: Double? = null,
        val anchorPlan: List<PlanItem>? = null,
        val photoSelection: Map<Int, List<Int>> = emptyMap(),
        val driveMs: Double? = null,
        val coverStopIndex: Int? = null
    )

    class Phase(
        val type: String, val startMs: Double, var endMs: Double,
        val fromIndex: Int = -1, val toIndex: Int = -1, val viaIndices: List<Int> = emptyList(),
        val stopIndex: Int = -1, val count: Int = 0, val hero: Boolean = false, val dayLabel: Int = 0
    )

    class Media(val stopIndex: Int, val photoIndex: Int)

    class Manifest(
        val templateId: String, val structure: String, val lengthMode: String, val heroStopIndex: Int,
        val segments: List<RecapTimelines.Segment>, val holdMs: List<Double>, val arriveMs: List<Double>,
        val phases: List<Phase>, val media: List<Media>, val totalMs: Double, val adaptiveTier: String, val dayCount: Int
    )

    private fun dayOf(s: RecapStop) = if (s.dayIndex == 0) 1 else s.dayIndex
    private fun jsRound(v: Double): Long = if (v.isFinite()) Math.round(v) else 0L

    fun structureFor(photoTotal: Int) = if (photoTotal >= 3) "photoStory" else if (photoTotal >= 1) "mapFeatured" else "routeStory"

    /** 主角景點沒指定時：照片最多（至少 2 張）的站；都只有 1 張時取停留最久的有照片站；資料不足就不設（-1） */
    fun pickHeroStop(stops: List<RecapStop>, counts: IntArray): Int {
        var best = -1
        for (i in counts.indices) if (counts[i] >= 2 && (best < 0 || counts[i] > counts[best])) best = i
        if (best >= 0) return best
        for (i in counts.indices) {
            val stay = stops.getOrNull(i)?.stayMin ?: 0
            if (counts[i] > 0 && stay > 0 && (best < 0 || stay > stops[best].stayMin)) best = i
        }
        return best
    }

    /** 一個錨點的照片總長：主角每張 heroMs（只有 1 張時 heroSingleMs），一般每張 photoMs；固定 15 秒另一組 */
    private fun anchorPhotoMs(count: Int, isHero: Boolean, t: RecapTimelines.Template, lengthMode: String): Double {
        val a = t.anchors!!
        if (!isHero) return count * t.photoMs
        if (lengthMode == "15") return when (count) {
            1 -> a.fixed15HeroSingleMs
            2 -> 2 * a.fixed15HeroMs
            else -> a.fixed15HeroManyCount * a.fixed15HeroManyMs
        }
        return if (count == 1) a.heroSingleMs else count * a.heroMs
    }

    /** 錨點 → 分組：相鄰兩站都有照片時兩站一組連續放（中間不播路線），主角景點自己一組 */
    private fun groupAnchors(anchors: List<Int>, hero: Int): List<MutableList<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        anchors.forEach { idx ->
            val last = groups.lastOrNull()
            val prev = last?.last()
            if (last != null && last.size < 2 && prev == idx - 1 && idx != hero && prev != hero) last += idx
            else groups += mutableListOf(idx)
        }
        return groups
    }

    private fun planFromAnchors(anchors: List<Int>, counts: IntArray, hero: Int, t: RecapTimelines.Template, lengthMode: String): List<PlanItem> {
        val plan = mutableListOf<PlanItem>()
        groupAnchors(anchors, hero).forEachIndexed { gi, g ->
            g.forEachIndexed { k, idx ->
                val isHero = idx == hero
                plan += PlanItem(idx, counts[idx], isHero, anchorPhotoMs(counts[idx], isHero, t, lengthMode),
                    hopBefore = k == 0 && !(gi == 0 && idx == 0))
            }
        }
        return plan
    }

    private fun planMs(plan: List<PlanItem>, hopMs: Double) = plan.sumOf { it.photoMs + if (it.hopBefore) hopMs else 0.0 }

    /** 放不下時依序：先減照片多的一般站 → 主角減到 3 張 → 拿掉一個一般錨點（挑拿掉後空隙最小的，讓照片分布平均） */
    private fun fitAnchorsToBudget(
        anchors: List<Int>, counts: IntArray, hero: Int, t: RecapTimelines.Template, lengthMode: String, budgetMs: Double
    ): List<PlanItem> {
        val hopMs = t.anchors!!.hopMs
        val list = anchors.toMutableList()
        val c = counts.copyOf()
        var guard = 0
        while (guard < 400) {
            guard += 1
            val plan = planFromAnchors(list, c, hero, t, lengthMode)
            if (planMs(plan, hopMs) <= budgetMs || list.isEmpty()) return plan
            var most = -1
            list.forEach { idx -> if (idx != hero && c[idx] > 1 && (most < 0 || c[idx] > c[most])) most = idx }
            if (most >= 0) { c[most] -= 1; continue }
            if (hero >= 0 && c[hero] > 3) { c[hero] -= 1; continue }
            if (list.none { it != hero }) return plan
            var bestPos = -1
            var bestGap = Int.MAX_VALUE
            list.forEachIndexed { pos, idx ->
                if (idx == hero) return@forEachIndexed
                val left = if (pos > 0) list[pos - 1] else 0
                val right = if (pos < list.size - 1) list[pos + 1] else counts.size - 1
                if (right - left < bestGap) { bestGap = right - left; bestPos = pos }
            }
            c[list[bestPos]] = 0
            list.removeAt(bestPos)
        }
        return planFromAnchors(list, c, hero, t, lengthMode)
    }

    /** 一段過場（from → to，可跨好幾站）的總時長依距離分給中間各段；跨天段不畫車（0ms，直接跳到隔天） */
    private fun spreadHop(travel: DoubleArray, distances: List<Double>, breaks: List<Boolean>, from: Int, to: Int, ms: Double, segMinMs: Double) {
        val idx = mutableListOf<Int>()
        for (i in from until to) { if (!breaks[i]) idx += i else travel[i] = 0.0 }
        if (idx.isEmpty()) return
        val alloc = RecapTimelines.allocateSegmentMs(distances, idx, ms, min(segMinMs, ms / idx.size * 0.6), 1.0)
        idx.forEach { k -> travel[k] = alloc[k] ?: 0.0 }
    }

    fun buildManifest(trip: RecapTrip, o: Options): Manifest {
        val tpl = RecapTimelines.template(o.template)
        val a = tpl.anchors ?: error("${tpl.id} 不是 Reels 類樣板")
        val src = trip.stops
        val n = src.size
        val lengthMode = if (o.lengthMode == "15") "15" else "all"

        // 每站可用的照片張數：photoCounts 優先；舊參數 mediaStopIndices 視為每站 1 張
        val counts = IntArray(n)
        if (o.photoCounts != null) o.photoCounts.forEachIndexed { k, v -> if (k < n) counts[k] = v.coerceIn(0, 20) }
        else o.mediaStopIndices?.forEach { k -> if (k in 0 until n) counts[k] = max(counts[k], 1) }
        val photoTotal = counts.sum()
        val structure = structureFor(photoTotal)

        var hero = -1
        if (photoTotal > 0 && o.heroStopIndex != -1) {
            val h = o.heroStopIndex
            hero = if (h != null && h in 0 until n && counts[h] > 0) h else pickHeroStop(src, counts)
        }

        val coverMs = o.coverMs ?: tpl.coverMs
        val statsMs = o.statsMs ?: tpl.statsMs
        val summaryMs = o.summaryMs ?: a.summaryMs
        val revealMs = if (structure == "mapFeatured") 0.0 else (o.revealMs ?: a.revealMs)
        val hopMs = a.hopMs

        val distances = (0 until max(0, n - 1)).map { i -> RecapTimelines.haversineKm(src[i].lat, src[i].lng, src[i + 1].lat, src[i + 1].lng) }
        val breaks = distances.indices.map { i -> dayOf(src[i]) != dayOf(src[i + 1]) }
        val travel = DoubleArray(distances.size)
        val holdMs = DoubleArray(n)
        val photoSel = o.photoSelection

        // 錨點計畫：時間軸 compile 來的 anchorPlan 直接用（使用者編輯過的版本），否則依規則排
        val anchors = (0 until n).filter { counts[it] > 0 }
        val plan: List<PlanItem> = when {
            o.anchorPlan != null -> o.anchorPlan.filter { it.stopIndex in 0 until n && counts[it.stopIndex] > 0 }.map { p ->
                val cnt = max(1, min(counts[p.stopIndex], if (p.count != 0) p.count else counts[p.stopIndex]))
                val isHero = p.stopIndex == hero
                PlanItem(p.stopIndex, cnt, isHero,
                    if (p.photoMs > 0) p.photoMs else anchorPhotoMs(cnt, isHero, tpl, lengthMode),
                    hopBefore = p.hopBefore, hopMs = p.hopMs?.takeIf { it > 0 })
            }
            structure == "photoStory" -> {
                val start = counts.copyOf()
                if (lengthMode == "15") anchors.forEach { idx -> start[idx] = if (idx == hero) min(start[idx], a.fixed15HeroManyCount) else 1 }
                val budget = (if (lengthMode == "15") a.fixed15TotalMs else a.maxMs) - coverMs - revealMs - summaryMs - statsMs
                val fitted = fitAnchorsToBudget(anchors, start, hero, tpl, lengthMode, budget)
                // 固定 15 秒：剩下的時間按比例加到一般照片上（沒有一般照片就加到主角），總長剛好 15 秒
                if (lengthMode == "15") {
                    val spare = budget - planMs(fitted, hopMs)
                    val normal = fitted.filter { !it.hero }
                    val target = normal.ifEmpty { fitted }
                    val base = target.sumOf { it.photoMs }
                    if (spare > 0 && base > 0) target.forEach { it.photoMs += spare * (it.photoMs / base) }
                }
                fitted
            }
            else -> planFromAnchors(anchors, counts, hero, tpl, lengthMode).onEach { it.hopBefore = false }
        }

        // 時間排程
        val phases = mutableListOf<Phase>()
        val media = mutableListOf<Media>()
        var now = 0.0
        phases += Phase("cover", 0.0, coverMs)
        now = coverMs
        if (revealMs > 0) { phases += Phase("reveal", now, now + revealMs); now += revealMs }
        val routeStartMs = now
        val dayCountAll = max(1, src.maxOfOrNull { it.dayIndex } ?: 1)
        var lastDayShown = 0

        fun addPhotoPhase(p: PlanItem) {
            val start = now
            val idxs = photoSel[p.stopIndex]?.take(p.count)
            val each = p.photoMs / p.count
            val day = dayOf(src[p.stopIndex])
            val label = if (dayCountAll > 1 && day != lastDayShown) day else 0
            if (label != 0) lastDayShown = day
            for (k in 0 until p.count) media += Media(p.stopIndex, idxs?.getOrNull(k) ?: k)
            phases += Phase("photo", start, start + p.photoMs, stopIndex = p.stopIndex, count = p.count, hero = p.hero, dayLabel = label)
            holdMs[p.stopIndex] = p.photoMs
            now += p.photoMs
        }

        if (structure == "photoStory") {
            var cur = 0
            plan.forEach { p ->
                if (p.stopIndex > cur && p.hopBefore) {
                    val ms = p.hopMs ?: hopMs
                    spreadHop(travel, distances, breaks, cur, p.stopIndex, ms, tpl.segMinMs)
                    phases += Phase("hop", now, now + ms, fromIndex = cur, toIndex = p.stopIndex, viaIndices = (cur + 1 until p.stopIndex).toList())
                    now += ms
                }
                // 同組相鄰站（或沒有過場）：車直接跳到下一站
                cur = p.stopIndex
                addPhotoPhase(p)
            }
        } else {
            // 整條路線開一遍：mapFeatured 在錨點停下放照片；routeStory 依序聚焦幾站
            val photoSum = plan.sumOf { it.photoMs }
            val focus = mutableListOf<Int>()
            if (structure == "routeStory") {
                val middle = (1 until n - 1).toList()
                val want = min(a.focusMax, middle.size)
                for (i in 0 until want) focus += middle[((i + 0.5) * middle.size / want).toInt()]
            }
            val focusSum = focus.size * a.focusMs
            var driveMs = if (structure == "routeStory") a.routeOnlyMs else a.featuredRouteMs
            if ((o.driveMs ?: 0.0) > 0) driveMs = o.driveMs!!
            else if (lengthMode == "15") driveMs = max(3000.0, a.fixed15TotalMs - coverMs - revealMs - summaryMs - statsMs - photoSum - focusSum)
            spreadHop(travel, distances, breaks, 0, n - 1, driveMs, tpl.segMinMs)
            val planBy = plan.associateBy { it.stopIndex }
            val drive = Phase("drive", now, now, fromIndex = 0, toIndex = n - 1)
            phases += drive
            for (i in 0 until n) {
                if (i > 0) now += travel[i - 1]
                val p = planBy[i]
                if (p != null) addPhotoPhase(p)
                else if (i in focus) {
                    phases += Phase("focus", now, now + a.focusMs, stopIndex = i)
                    holdMs[i] = a.focusMs
                    now += a.focusMs
                }
            }
            drive.endMs = now
        }

        // 抵達時間：從路線開始依 travel/hold 累加
        val arriveMs = DoubleArray(n)
        var acc = routeStartMs
        for (i in 0 until n) {
            if (i > 0) acc += holdMs[i - 1] + travel[i - 1]
            arriveMs[i] = acc
        }
        // photoStory 的過場與照片穿插，arriveMs 以 phases 為準（錨點＝照片開始、經過站＝過場中按距離內插）
        if (structure == "photoStory" && n > 0) {
            val hops = phases.filter { it.type == "hop" }
            val photoStart = HashMap<Int, Double>()
            phases.forEach { if (it.type == "photo") photoStart[it.stopIndex] = it.startMs }
            arriveMs[0] = routeStartMs
            for (i in 1 until n) {
                val ps = photoStart[i]
                if (ps != null) { arriveMs[i] = ps; continue }
                val hp = hops.firstOrNull { it.fromIndex < i && it.toIndex > i }
                if (hp != null) {
                    var total = 0.0
                    var upto = 0.0
                    for (s in hp.fromIndex until hp.toIndex) { total += travel[s]; if (s < i) upto += travel[s] }
                    arriveMs[i] = hp.startMs + if (total > 0) (hp.endMs - hp.startMs) * upto / total else 0.0
                } else {
                    arriveMs[i] = arriveMs[i - 1] + holdMs[i - 1]   // 最後一個錨點之後的站：收束時一次畫完
                }
            }
        }

        phases += Phase("summary", now, now + summaryMs)
        now += summaryMs
        phases += Phase("outro", now, now + statsMs)
        now += statsMs

        val segments = distances.indices.map { k ->
            RecapTimelines.Segment(k, k + 1, distances[k], travel[k], breaks[k], dayOf(src[k + 1]))
        }
        val tier = when { photoTotal == 0 -> "0"; photoTotal <= 2 -> "1-2"; photoTotal <= 5 -> "3-5"; else -> "6+" }
        return Manifest(tpl.id, structure, lengthMode, hero, segments, holdMs.toList(), arriveMs.toList(),
            phases, media, jsRound(now).toDouble(), tier, dayCountAll)
    }

    // ── 時間軸第 2 版（recap-timeline.js）──────────────────────────

    private class StopPhoto(val photoId: String, val k: Int)

    /** 每站的照片（依送來的順序＝共同相簿的「手動排序 → 拍攝時間」） */
    private fun photosByStop(photos: List<RecapPhoto>, stopCount: Int): List<List<StopPhoto>> {
        val out = List(stopCount) { mutableListOf<StopPhoto>() }
        photos.forEach { p -> if (p.stopIndex in 0 until stopCount) out[p.stopIndex] += StopPhoto(p.photoId.orEmpty(), out[p.stopIndex].size) }
        return out
    }

    private fun stopIdOf(stops: List<RecapStop>, i: Int) = stops.getOrNull(i)?.stopId.orEmpty()

    private fun routeLabel(stops: List<RecapStop>, from: Int, to: Int, via: List<Int>): String {
        val names = via.map { stops.getOrNull(it)?.name.orEmpty() }.filter { it.isNotEmpty() }
        return stops.getOrNull(from)?.name.orEmpty() + " → " + stops.getOrNull(to)?.name.orEmpty() +
            if (names.isNotEmpty()) "（經過 ${names.joinToString("、")}）" else ""
    }

    private fun legKm(m: Manifest, from: Int, to: Int): Double {
        var km = 0.0
        for (s in from until to) km += m.segments.getOrNull(s)?.geoDistanceKm ?: 0.0
        return Math.round(km * 10) / 10.0
    }

    /** 推薦版：依行程＋實際照片排。highlightStopId＝使用者選的主角，coverStopIndex＝自選封面（那站要有照片） */
    fun buildRecommended(
        trip: RecapTrip, templateId: String?, photos: List<RecapPhoto>, lengthMode: String,
        highlightStopId: String?, coverStopIndex: Int?
    ): RecapTimeline {
        val stops = trip.stops
        val byStop = photosByStop(photos, stops.size)
        val counts = byStop.map { it.size }
        val heroIdx = highlightStopId?.takeIf { it.isNotEmpty() }?.let { id -> stops.indexOfFirst { it.stopId == id }.takeIf { it >= 0 } }
        val m = buildManifest(trip, Options(template = templateId, photoCounts = counts, heroStopIndex = heroIdx, lengthMode = lengthMode))
        val clips = mutableListOf<RecapClip>()
        fun push(c: RecapClip) { clips += c.copy(order = clips.size) }
        val coverIsPhoto = coverStopIndex != null && (counts.getOrNull(coverStopIndex) ?: 0) > 0
        val mediaByStop = m.media.groupBy { it.stopIndex }
        val middle = (1 until stops.size - 1).toList()
        var lastPoint = 0

        fun pushLeg(from: Int, to: Int) {
            var ms = 0.0
            val via = mutableListOf<Int>()
            for (s in from until to) { ms += m.segments[s].travelMs; if (s > from) via += s }
            push(RecapClip(clipId = "route-$from-$to", type = "route", fromStopId = stopIdOf(stops, from), toStopId = stopIdOf(stops, to),
                viaStopIds = via.map { stopIdOf(stops, it) }, label = routeLabel(stops, from, to, via),
                distanceKm = legKm(m, from, to), durationMs = jsRound(ms)))
        }

        m.phases.forEach { ph ->
            val dur = jsRound(ph.endMs - ph.startMs)
            when (ph.type) {
                "cover" -> push(RecapClip(clipId = "cover", type = "cover", source = if (coverIsPhoto) "photo" else "illustration",
                    coverStopIndex = if (coverIsPhoto) coverStopIndex else null, durationMs = dur))
                "reveal" -> push(RecapClip(clipId = "reveal", type = "route", reveal = true,
                    fromStopId = stopIdOf(stops, 0), toStopId = stopIdOf(stops, stops.size - 1),
                    viaStopIds = middle.map { stopIdOf(stops, it) }, label = "路線揭曉", durationMs = dur))
                "hop" -> push(RecapClip(clipId = "route-${ph.fromIndex}-${ph.toIndex}", type = "route",
                    fromStopId = stopIdOf(stops, ph.fromIndex), toStopId = stopIdOf(stops, ph.toIndex),
                    viaStopIds = ph.viaIndices.map { stopIdOf(stops, it) },
                    label = routeLabel(stops, ph.fromIndex, ph.toIndex, ph.viaIndices),
                    distanceKm = legKm(m, ph.fromIndex, ph.toIndex), durationMs = dur))
                "photo" -> {
                    // 整條路線開一遍的結構：照片前補一段「上一個停點 → 這站」的路線片段（只算開車時間）
                    if (m.structure != "photoStory" && ph.stopIndex > lastPoint) pushLeg(lastPoint, ph.stopIndex)
                    lastPoint = max(lastPoint, ph.stopIndex)
                    val refs = mediaByStop[ph.stopIndex].orEmpty().map { mm ->
                        RecapAssetRef(stopId = stopIdOf(stops, ph.stopIndex),
                            photoId = byStop[ph.stopIndex].getOrNull(mm.photoIndex)?.photoId?.takeIf { it.isNotEmpty() })
                    }
                    push(RecapClip(clipId = "photo-${ph.stopIndex}", type = "photo", stopIndex = ph.stopIndex,
                        label = stops.getOrNull(ph.stopIndex)?.name.orEmpty(), assetRefs = refs, durationMs = dur,
                        hero = if (ph.hero) true else null, dayLabel = ph.dayLabel.takeIf { it != 0 }))
                }
                "summary" -> {
                    if (m.structure != "photoStory" && lastPoint < stops.size - 1) pushLeg(lastPoint, stops.size - 1)
                    push(RecapClip(clipId = "summary", type = "summary",
                        stats = RecapOutroStats(stops.size, trip.distanceKm, m.dayCount), durationMs = dur))
                }
                "outro" -> push(RecapClip(clipId = "outro", type = "outro",
                    stats = RecapOutroStats(stops.size, trip.distanceKm, m.dayCount), durationMs = dur))
            }
        }
        return RecapTimeline(schemaVersion = 2, templateId = m.templateId, lengthMode = m.lengthMode, structure = m.structure,
            adaptiveTier = m.adaptiveTier, totalMs = jsRound(m.totalMs), clips = clips)
    }

    private class Route(val from: Int, val to: Int)

    /** 路線片段驗證：起訖可以不相鄰，但中間經過的站必須正好是 viaStopIds（依序） */
    private fun validRoute(c: RecapClip, idIndex: Map<String, Int>, stops: List<RecapStop>): Route? {
        val from = idIndex[c.fromStopId.orEmpty()] ?: return null
        val to = idIndex[c.toStopId.orEmpty()] ?: return null
        if (to <= from) return null
        val via = c.viaStopIds.orEmpty()
        if (via.size != to - from - 1) return null
        for (k in via.indices) if (via[k] != stopIdOf(stops, from + 1 + k)) return null
        return Route(from, to)
    }

    /** 時間軸 → manifest 參數（與後端 compileTimeline 同一套驗證；不合格的片段忽略） */
    fun compile(trip: RecapTrip, timeline: RecapTimeline, photos: List<RecapPhoto>): Options {
        val stops = trip.stops
        val n = stops.size
        val byStop = photosByStop(photos, n)
        val idIndex = stops.withIndex().associate { (i, s) -> s.stopId to i }
        var coverMs: Double? = null
        var coverStopIndex: Int? = null
        var statsMs: Double? = null
        var summaryMs: Double? = null
        var revealMs: Double? = null
        val photoCounts = IntArray(n)
        val selection = HashMap<Int, List<Int>>()
        val plan = mutableListOf<PlanItem>()
        val hopMs = HashMap<String, Double>()
        var driveSum = 0.0
        var hasDrive = false
        var hero = -1
        var lastAnchor = -1

        timeline.clips.forEach { c ->
            val d = c.durationMs.toDouble()
            when (c.type) {
                "cover" -> {
                    coverMs = RecapTimelines.clampMs("cover", d).toDouble()
                    val ci = c.coverStopIndex
                    if (c.source == "photo" && ci != null && byStop.getOrNull(ci)?.isNotEmpty() == true) coverStopIndex = ci
                }
                "outro" -> statsMs = RecapTimelines.clampMs("outro", d).toDouble()
                "summary" -> summaryMs = RecapTimelines.clampMs("summary", d).toDouble()
                "route" -> {
                    val r = validRoute(c, idIndex, stops) ?: return@forEach
                    if (c.reveal == true) {
                        if (r.from == 0 && r.to == n - 1) revealMs = RecapTimelines.clampMs("reveal", d).toDouble()
                        return@forEach
                    }
                    val v = RecapTimelines.clampMs("route", d).toDouble()
                    hopMs["${r.from}>${r.to}"] = v
                    driveSum += v
                    hasDrive = true
                }
                "photo" -> {
                    val refs = c.assetRefs ?: listOfNotNull(c.assetRef)
                    val stopIdx = idIndex[refs.firstOrNull()?.stopId.orEmpty()] ?: return@forEach
                    // 錨點只能依行程順序往後、每站一個片段、這站要真的有照片
                    if (stopIdx <= lastAnchor || byStop[stopIdx].isEmpty()) return@forEach
                    val avail = byStop[stopIdx]
                    val picked = mutableListOf<Int>()
                    refs.forEachIndexed { pos, ref ->
                        if (ref.stopId != stopIdOf(stops, stopIdx)) return@forEachIndexed
                        val hit = if (!ref.photoId.isNullOrEmpty()) avail.firstOrNull { it.photoId == ref.photoId } else avail.getOrNull(pos)
                        if (hit != null && hit.k !in picked) picked += hit.k
                    }
                    if (picked.isEmpty()) return@forEach
                    lastAnchor = stopIdx
                    photoCounts[stopIdx] = picked.size
                    selection[stopIdx] = picked
                    if (c.hero == true && hero < 0) hero = stopIdx
                    plan += PlanItem(stopIdx, picked.size, photoMs = RecapTimelines.clampMs("photo", d, picked.size).toDouble())
                }
            }
        }

        // 過場：上一個錨點（第一個錨點從起點算）→ 這個錨點；有對得上的路線片段就用它的秒數，
        // 沒有時：相鄰兩站＝同組連續放，不相鄰＝用樣板預設的過場秒數
        var prev = 0
        plan.forEachIndexed { i, p ->
            val from = if (i == 0) 0 else prev
            val hm = hopMs["$from>${p.stopIndex}"]
            when {
                p.stopIndex == from -> p.hopBefore = false
                hm != null -> { p.hopBefore = true; p.hopMs = hm }
                else -> p.hopBefore = !(i > 0 && p.stopIndex == from + 1)
            }
            prev = p.stopIndex
        }

        return Options(
            template = timeline.templateId, lengthMode = if (timeline.lengthMode == "15") "15" else "all",
            photoCounts = photoCounts.toList(), heroStopIndex = hero,
            coverMs = coverMs, statsMs = statsMs, summaryMs = summaryMs, revealMs = revealMs,
            anchorPlan = plan, photoSelection = selection,
            driveMs = if (hasDrive) max(RecapTimelines.LIMITS.getValue("route").first.toDouble(), driveSum) else null,
            coverStopIndex = coverStopIndex
        )
    }

    fun totalMs(trip: RecapTrip, timeline: RecapTimeline, photos: List<RecapPhoto>): Long =
        jsRound(buildManifest(trip, compile(trip, timeline, photos)).totalMs)

    // ── 編輯器 ───────────────────────────────────────────────

    /**
     * 移除照片片段後重接路線：兩個照片片段之間的路線片段合併成一段（起訖＝前後錨點、經過的站接起來），
     * 最後一張照片之後的路線拿掉（旅程收束會畫完整條路線）。被移除的照片先換成標記（marker，起訖都是那一站），
     * 讓同組相鄰、原本沒有路線的站也接得起來；合併後的秒數＝原本路線秒數相加，都沒有路線時用樣板的過場秒數。
     */
    private fun relinkRoutes(clips: List<RecapClip>, defaultMs: Double): List<RecapClip> {
        val out = mutableListOf<RecapClip>()
        var pending = mutableListOf<RecapClip>()
        var lastPhotoStop: String? = null
        fun photoStop(c: RecapClip) = c.assetRefs?.firstOrNull()?.stopId.orEmpty()
        fun flush(toStop: String) {
            val fromStop = lastPhotoStop ?: pending[0].fromStopId.orEmpty()
            val only = pending.singleOrNull()
            if (only != null && only.fromStopId == fromStop && only.toStopId == toStop) {
                out += only
                pending = mutableListOf()
                return
            }
            val seq = mutableListOf<String>()
            pending.forEach { r ->
                (listOf(r.fromStopId.orEmpty()) + r.viaStopIds.orEmpty() + listOf(r.toStopId.orEmpty())).forEach { id ->
                    if (seq.lastOrNull() != id) seq += id
                }
            }
            if (seq.firstOrNull() != fromStop) seq.add(0, fromStop)
            if (seq.last() != toStop) seq += toStop
            val b = seq.size - 1
            val real = pending.filter { it.marker != true }
            if (b > 0) {
                val dur = real.sumOf { it.durationMs.toDouble() }.takeIf { it != 0.0 } ?: defaultMs
                out += RecapClip(clipId = "route-$fromStop-$toStop", type = "route", fromStopId = fromStop, toStopId = toStop,
                    viaStopIds = seq.subList(1, b).toList(),
                    label = real.joinToString("＋") { it.label.orEmpty() }.ifEmpty { "$fromStop → $toStop" },
                    distanceKm = Math.round(real.sumOf { it.distanceKm ?: 0.0 } * 10) / 10.0,
                    durationMs = RecapTimelines.clampMs("route", dur))
            }
            pending = mutableListOf()
        }
        clips.forEach { c ->
            if (c.type == "route" && c.reveal != true) { pending += c; return@forEach }
            if (c.type == "photo") {
                val stop = photoStop(c)
                if (pending.isNotEmpty()) flush(stop)
                lastPhotoStop = stop
                out += c
                return@forEach
            }
            if (c.type == "summary" || c.type == "outro") pending = mutableListOf()
            out += c
        }
        return out
    }

    fun removePhotoClip(timeline: RecapTimeline, clipId: String): RecapTimeline {
        val marked = timeline.clips.map { c ->
            if (!(c.clipId == clipId && c.type == "photo")) c
            else {
                val id = c.assetRefs?.firstOrNull()?.stopId.orEmpty()
                RecapClip(type = "route", marker = true, clipId = "marker", fromStopId = id, toStopId = id, viaStopIds = emptyList(), durationMs = 0)
            }
        }
        val hop = RecapTimelines.template(timeline.templateId).anchors?.hopMs ?: 700.0
        return timeline.copy(clips = relinkRoutes(marked, hop).mapIndexed { i, c -> c.copy(order = i) }, edited = true)
    }

    /** 主角景點（或一站多張）的卡片個別移除照片；只剩一張再移除＝移除整個片段。片段長度依張數等比例縮短 */
    fun removeClipPhoto(timeline: RecapTimeline, clipId: String, refIndex: Int): RecapTimeline {
        val target = timeline.clips.firstOrNull { it.clipId == clipId && it.type == "photo" }
        val refs = target?.assetRefs ?: return timeline
        if (refs.size <= 1) return removePhotoClip(timeline, clipId)
        return timeline.copy(clips = timeline.clips.map { c ->
            if (c.clipId != clipId) return@map c
            val list = c.assetRefs ?: return@map c
            val n = list.size
            if (refIndex !in 0 until n) return@map c
            c.copy(assetRefs = list.filterIndexed { i, _ -> i != refIndex },
                durationMs = RecapTimelines.clampMs("photo", c.durationMs.toDouble() * (n - 1) / n, n - 1))
        }, edited = true)
    }
}
