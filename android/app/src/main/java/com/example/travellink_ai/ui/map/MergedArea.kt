package com.example.travellink_ai.ui.map

import android.content.Context
import android.util.Log
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.PlaceNameMatch
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 大景區合併站在地圖上的範圍（照網頁 buildMergedAreaPath／fetchOsmBoundaryPath）：
 *  1. 先畫近似區塊：成員座標＋站點座標的凸包往外擴 120m（不連網、立刻有）
 *  2. 再換成真實邊界：常用的大景區內建在 App 裡（[BUILT_IN]），其他到 OpenStreetMap（Overpass）
 *     找同名的面，找到就存在手機上
 *
 * 2026-11-02 使用者回報原本的圓太大（台東森林公園半徑 1134m＝4.04 km²，真實公園 2.13 km²）。
 *
 * 跟網頁不同的地方（網頁那邊也該跟著改）：
 *  - 名稱查詢把「台／臺」都納入：行程寫「台東森林公園」、OSM 寫「臺東森林公園」，網頁的查詢比對不到
 *  - 查詢排除 route 類 relation：「經臺東森林公園」的公車路線也會命中，out geom 會抓回整條路線，常逾時
 *  - 只有兩個成員時凸包退化成線段，網頁會退回 1.8 公里的方塊；這裡改畫沿兩點外擴的膠囊形
 *  - 名稱完全相同的面優先（「臺東森林公園管理站」也包含公園名，不能只比誰的中心近）
 */
internal object MergedArea {
    private const val TAG = "MergedArea"
    private const val PAD_M = 120.0
    private const val LAT_M = 110_540.0
    private fun lngM(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    private const val PREFS = "osm_boundary_cache"

    /**
     * 內建邊界：2026-11-02 Overpass 主站 502、備援端點逾時，release 版一直只畫得出近似區塊。
     * 對照表（SubSpotMerge.AREAS）裡的大景區直接帶 OSM 邊界，不靠 Overpass、離線也畫得出來。
     * 「哈巴狗與睡美人岩」在 OSM 只有兩個點、沒有面，維持近似區塊。
     */
    private val BUILT_IN: Map<String, List<LatLng>> = mapOf(
        // OSM way 253330922「臺東森林公園」（leisure=park，2.13 km²），Douglas-Peucker 8m 簡化成 38 點
        "台東森林公園" to ring(
            22.77594, 121.14963, 22.77944, 121.14576, 22.77458, 121.14680, 22.77349, 121.14830, 22.77341, 121.14865,
            22.77415, 121.14937, 22.77268, 121.15027, 22.77152, 121.15073, 22.77077, 121.15017, 22.76956, 121.15119,
            22.76922, 121.15114, 22.76804, 121.15229, 22.76749, 121.15216, 22.76551, 121.15480, 22.76528, 121.15562,
            22.76418, 121.15672, 22.76383, 121.15758, 22.76263, 121.15869, 22.76172, 121.15889, 22.76099, 121.15933,
            22.76030, 121.15996, 22.76055, 121.16050, 22.75921, 121.16175, 22.75902, 121.16281, 22.75757, 121.16308,
            22.75663, 121.16354, 22.75473, 121.16484, 22.75403, 121.16440, 22.75353, 121.16582, 22.75678, 121.17159,
            22.75872, 121.17350, 22.75929, 121.17376, 22.76043, 121.17354, 22.76156, 121.17287, 22.76219, 121.17211,
            22.76653, 121.16343, 22.76870, 121.15968, 22.77206, 121.15471
        )
    ).mapKeys { PlaceNameMatch.normalize(it.key) }

    private fun ring(vararg latLng: Double) = latLng.toList().chunked(2) { LatLng(it[0], it[1]) }

    /** 名稱是內建大景區、而且站點在它附近（2 公里內，防同名的別處）就回內建邊界 */
    fun builtIn(name: String, center: LatLng): List<LatLng>? {
        val ring = BUILT_IN[PlaceNameMatch.normalize(name.replace(Regex("""[（(][^）)]*[）)]"""), ""))] ?: return null
        val cen = LatLng(ring.sumOf { it.latitude } / ring.size, ring.sumOf { it.longitude } / ring.size)
        return ring.takeIf { distanceMeters(cen, center) <= 2000 }
    }

    // ── 近似區塊（網頁 buildMergedAreaPath）──────────────────────

    fun approxPath(center: LatLng, members: List<LatLng>, radiusMeters: Int): List<LatLng> {
        // 以站點座標為基準丟離群點（中位數×3，350～1200m），避免被 geocode 到遠處的成員拉出尖刺
        var kept = members
        if (members.size >= 2) {
            val dists = members.map { distanceMeters(center, it) }
            val med = dists.sorted()[dists.size / 2]
            val thr = (med * 3).coerceIn(350.0, 1200.0)
            kept = members.filterIndexed { i, _ -> dists[i] <= thr }
        }
        val input = kept + center
        val hull = convexHull(input)
        val far = farthestPair(input)
        // 凸包太扁（兩個成員＋兩者中間的站點，浮點誤差下可能剩一個細三角形）：外擴後只是一根針，改畫膠囊
        val flat = far != null && areaM2(hull) < distanceMeters(far.first, far.second) * PAD_M / 2
        if (hull.size >= 3 && !flat) {
            val ref = LatLng(input.sumOf { it.latitude } / input.size, input.sumOf { it.longitude } / input.size)
            return padOutward(hull, ref, PAD_M)
        }
        if (far != null) return capsule(far.first, far.second, PAD_M)
        val half = radiusMeters.toDouble().coerceIn(180.0, 900.0)
        return box(center, half)
    }

    fun convexHull(points: List<LatLng>): List<LatLng> {
        val pts = points.distinct().sortedWith(compareBy({ it.longitude }, { it.latitude }))
        if (pts.size < 3) return pts
        fun cross(o: LatLng, a: LatLng, b: LatLng) =
            (a.longitude - o.longitude) * (b.latitude - o.latitude) - (a.latitude - o.latitude) * (b.longitude - o.longitude)
        val lower = ArrayList<LatLng>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<LatLng>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** 每個頂點沿「離 ref 的方向」往外推 padMeters */
    fun padOutward(poly: List<LatLng>, ref: LatLng, padMeters: Double): List<LatLng> {
        val lngScale = lngM(ref.latitude)
        return poly.map { p ->
            val dLat = p.latitude - ref.latitude; val dLng = p.longitude - ref.longitude
            val d = hypot(dLat * LAT_M, dLng * lngScale)
            if (d < 1.0) return@map p   // 幾乎就在 ref 上：沒有方向可推，推了會飛到遠處
            val f = (d + padMeters) / d
            LatLng(ref.latitude + dLat * f, ref.longitude + dLng * f)
        }
    }

    private fun areaM2(poly: List<LatLng>): Double {
        if (poly.size < 3) return 0.0
        val lngScale = lngM(poly[0].latitude)
        var a = 0.0
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            a += p.longitude * lngScale * q.latitude * LAT_M - q.longitude * lngScale * p.latitude * LAT_M
        }
        return kotlin.math.abs(a) / 2
    }

    /** a、b 兩點連線往外擴 padMeters 的膠囊形（兩端半圓） */
    fun capsule(a: LatLng, b: LatLng, padMeters: Double): List<LatLng> {
        val lngScale = lngM((a.latitude + b.latitude) / 2)
        val ax = a.longitude * lngScale; val ay = a.latitude * LAT_M
        val bx = b.longitude * lngScale; val by = b.latitude * LAT_M
        val dir = Math.atan2(by - ay, bx - ax)
        val out = ArrayList<LatLng>()
        fun arc(cx: Double, cy: Double, from: Double) {
            for (k in 0..12) {
                val t = from + Math.PI * k / 12
                out += LatLng((cy + padMeters * Math.sin(t)) / LAT_M, (cx + padMeters * Math.cos(t)) / lngScale)
            }
        }
        arc(bx, by, dir - Math.PI / 2)   // b 端半圓
        arc(ax, ay, dir + Math.PI / 2)   // a 端半圓
        return out
    }

    fun box(center: LatLng, halfMeters: Double): List<LatLng> {
        val dLat = halfMeters / LAT_M; val dLng = halfMeters / lngM(center.latitude)
        return listOf(
            LatLng(center.latitude + dLat, center.longitude - dLng),
            LatLng(center.latitude + dLat, center.longitude + dLng),
            LatLng(center.latitude - dLat, center.longitude + dLng),
            LatLng(center.latitude - dLat, center.longitude - dLng)
        )
    }

    private fun farthestPair(pts: List<LatLng>): Pair<LatLng, LatLng>? {
        var best: Pair<LatLng, LatLng>? = null; var bestD = 1.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = distanceMeters(pts[i], pts[j])
            if (d > bestD) { bestD = d; best = pts[i] to pts[j] }
        }
        return best
    }

    // ── OSM 真實邊界（網頁 fetchOsmBoundaryPath／pickOsmBoundary）────────

    /** Overpass 名稱正規表達式：去括號附註、跳脫特殊字元，台／臺都比對 */
    fun nameRegex(name: String): String {
        val s = name.replace(Regex("""[（(][^）)]*[）)]"""), "").trim()
        if (s.length < 2) return ""
        return s.replace(Regex("""[\\^$.*+?()\[\]{}|"]""")) { "\\" + it.value }
            .replace(Regex("[台臺]"), "[台臺]")
    }

    fun query(name: String, center: LatLng, radiusMeters: Int): String? {
        val term = nameRegex(name).ifEmpty { return null }
        val r = (radiusMeters * 2).coerceIn(1200, 3000)
        val around = "around:$r,${center.latitude},${center.longitude}"
        return "[out:json][timeout:25];(way[\"name\"~\"$term\"]($around);" +
            "relation[\"name\"~\"$term\"][\"type\"!~\"route\"]($around););out geom;"
    }

    private val BAD_NATURAL = Regex("^(water|bay|strait|coastline|wetland|reef|shoal|cape|peninsula)$", RegexOption.IGNORE_CASE)

    /** 從 Overpass 結果挑出名稱相符、大小合理、離站點最近的面；名稱完全相同的優先 */
    fun pick(data: JSONObject, name: String, center: LatLng, radiusMeters: Int): List<LatLng>? {
        val els = data.optJSONArray("elements") ?: return null
        val maxCentroidDist = (radiusMeters * 1.5).coerceIn(600.0, 2000.0)
        val maxRingRadius = (radiusMeters * 2.5).coerceIn(700.0, 2500.0)
        val target = PlaceNameMatch.normalize(name.replace(Regex("""[（(][^）)]*[）)]"""), ""))
        var best: List<LatLng>? = null
        var bestRank = Int.MAX_VALUE; var bestDist = Double.MAX_VALUE
        for (i in 0 until els.length()) {
            val el = els.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val elName = tags.optString("name")
            if (elName.isEmpty()) continue
            // 網頁 placeNameMatchesStrict：一方完整包含另一方（臺／台視為相同）
            if (PlaceNameMatch.score(name, elName) < 12 && PlaceNameMatch.score(elName, name) < 12) continue
            if (tags.has("boundary") || tags.has("place") || tags.has("waterway") || tags.has("water")) continue
            if (BAD_NATURAL.matches(tags.optString("natural"))) continue
            val ring = when (el.optString("type")) {
                "way" -> ringOf(el.optJSONArray("geometry"))?.takeIf { it.size >= 4 && it.first() == it.last() }?.dropLast(1)
                "relation" -> {
                    val members = el.optJSONArray("members") ?: JSONArray()
                    (0 until members.length()).mapNotNull { members.optJSONObject(it) }
                        .filter { it.optString("role") == "outer" }
                        .mapNotNull { ringOf(it.optJSONArray("geometry")) }
                        .filter { it.size >= 3 }
                        .maxByOrNull { ringArea(it) }
                }
                else -> null
            } ?: continue
            if (ring.size < 3) continue
            val cen = LatLng(ring.sumOf { it.latitude } / ring.size, ring.sumOf { it.longitude } / ring.size)
            val dist = distanceMeters(cen, center)
            if (dist > maxCentroidDist) continue
            if (ring.maxOf { distanceMeters(cen, it) } > maxRingRadius) continue
            val rank = if (PlaceNameMatch.normalize(elName) == target) 0 else 1
            if (rank < bestRank || (rank == bestRank && dist < bestDist)) {
                best = ring; bestRank = rank; bestDist = dist
            }
        }
        return best?.let { simplify(it, 120) }
    }

    private fun ringOf(geom: JSONArray?): List<LatLng>? {
        geom ?: return null
        return (0 until geom.length()).mapNotNull { geom.optJSONObject(it) }
            .filter { it.has("lat") && it.has("lon") }
            .map { LatLng(it.getDouble("lat"), it.getDouble("lon")) }
    }

    private fun ringArea(r: List<LatLng>): Double {
        var a = 0.0
        for (i in r.indices) { val p = r[i]; val q = r[(i + 1) % r.size]; a += p.longitude * q.latitude - q.longitude * p.latitude }
        return kotlin.math.abs(a) / 2
    }

    private fun simplify(ring: List<LatLng>, maxPts: Int): List<LatLng> =
        if (ring.size <= maxPts) ring else ring.filterIndexed { i, _ -> i % ((ring.size + maxPts - 1) / maxPts) == 0 }

    // ── 查詢與快取 ──────────────────────────────────────────

    /** 快取鍵同網頁：正規化名稱＠座標取到小數 3 位 */
    fun cacheKey(name: String, center: LatLng) =
        "${PlaceNameMatch.normalize(name)}@${"%.3f".format(center.latitude)},${"%.3f".format(center.longitude)}"

    private val mutex = Mutex()   // 一次只送一支查詢，避免被 Overpass 限流
    private val memory = HashMap<String, List<LatLng>?>()

    /** 已知結果：有邊界回清單、查過確定沒有回空清單、還沒查過（或上次失敗）回 null */
    fun cached(context: Context, key: String): List<LatLng>? {
        memory[key]?.let { return it }
        if (memory.containsKey(key)) return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val ring = if (raw == "none") null else decode(raw)
        memory[key] = ring
        return ring ?: emptyList()
    }

    /**
     * 抓真實邊界：內建的優先，再看快取，都沒有才問 Overpass（三個端點輪流，每個最多等 [READ_TIMEOUT_MS]）。
     * 查到或確定沒有都存起來；全部端點失敗不存，下次開地圖再試。
     */
    suspend fun fetchBoundary(context: Context, stop: Stop, center: LatLng): List<LatLng>? = withContext(Dispatchers.IO) {
        builtIn(stop.name, center)?.let { return@withContext it }
        val key = cacheKey(stop.name, center)
        cached(context, key)?.let { return@withContext it.ifEmpty { null } }
        val q = query(stop.name, center, stop.mergedRadiusMeters) ?: return@withContext null
        mutex.withLock {
            cached(context, key)?.let { return@withLock it.ifEmpty { null } }
            val data = request(q) ?: return@withLock null
            val ring = pick(data, stop.name, center, stop.mergedRadiusMeters)
            memory[key] = ring
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, ring?.let { encode(it) } ?: "none").apply()
            Log.d(TAG, "🗺️ 「${stop.name}」OSM 邊界：${ring?.let { "${it.size} 點" } ?: "找不到，用近似區塊"}")
            ring
        }
    }

    /**
     * 查詢本身允許伺服器跑 25 秒（[timeout:25]），再加上排隊；原本只等 15 秒，伺服器還沒回 App 就先放棄了。
     * 2026-11-02 實測主站 34 秒才回。這是背景查詢，地圖先畫近似區塊，等久一點不會卡畫面。
     */
    private const val READ_TIMEOUT_MS = 40_000

    private fun request(q: String): JSONObject? {
        for (ep in ENDPOINTS) {
            try {
                val conn = (URL(ep).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = READ_TIMEOUT_MS; requestMethod = "POST"; doOutput = true
                    // Overpass 沒帶 User-Agent 會回 406
                    setRequestProperty("User-Agent", "TravelLinkAI-Android/1.0")
                    setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                }
                conn.outputStream.use { it.write(q.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code != 200 || text.contains("rate_limited") || text.contains("Too Many Requests")) {
                    Log.w(TAG, "Overpass $ep 回應 $code，換下一個")
                    continue
                }
                return JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "Overpass $ep 失敗：${e.message}，換下一個")
            }
        }
        return null
    }

    private fun encode(ring: List<LatLng>): String =
        JSONArray(ring.map { JSONArray(listOf(it.latitude, it.longitude)) }).toString()

    private fun decode(raw: String): List<LatLng>? = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getJSONArray(it).let { p -> LatLng(p.getDouble(0), p.getDouble(1)) } }
    } catch (e: Exception) { null }

    fun distanceMeters(a: LatLng, b: LatLng): Double =
        hypot((a.latitude - b.latitude) * LAT_M, (a.longitude - b.longitude) * lngM((a.latitude + b.latitude) / 2))
}
