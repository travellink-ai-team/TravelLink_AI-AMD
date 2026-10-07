package com.example.travellink_ai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.map.PolyUtil
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * E1-B 離線行程快取。
 *
 * Google Maps SDK 本身不支援離線圖磚（ToS + 技術限制），所以離線時互動地圖必然是空白。
 * 這裡改快取「整趟路線的一張靜態圖」，離線時由 [ItineraryMapScreen] fallback 顯示：
 *
 *  1. 上線時：預抓 Google Static Maps 真實底圖 PNG 存 filesDir（方案 B）。
 *  2. 離線且沒有預抓圖時：用 Room 已存的折線在本地 Canvas 畫出路線草圖（0 網路，必有結果）。
 *
 * 座標與折線本來就已由 loadFromHistory 從 Room 純本地還原（roadSegmentsEncoded），
 * 所以本地草圖不需要任何 API 呼叫。
 */
/**
 * 離線路線圖＋它的投影：[project] 把經緯度換成 bitmap 上的像素座標，
 * 用來在圖上疊「目前位置」。舊版快取沒有投影資訊時 [project] 為 null（只顯示圖）。
 */
class OfflineMap(val bitmap: Bitmap, val project: ((LatLng) -> Pair<Float, Float>)?)

object OfflineMapCache {

    /**
     * 要畫的路線：逐段有道路折線就用，沒有的段畫兩站直線。過去只在「整份清單是空的」才退回直線，
     * 沒網路時 roadSegments 是「每段都是空的」清單，結果整條路線都沒畫。
     */
    internal fun routeOrStraight(stopPts: List<LatLng>, roadSegments: List<List<LatLng>>): List<List<LatLng>> = when {
        roadSegments.size == stopPts.size - 1 ->
            roadSegments.mapIndexed { i, seg -> seg.ifEmpty { listOf(stopPts[i], stopPts[i + 1]) } }
        roadSegments.any { it.size >= 2 } -> roadSegments
        else -> listOf(stopPts)
    }

    private const val TAG = "OfflineMapCache"
    private const val DIR = "offline_maps"
    private const val ACCENT = 0xFF2A6B5E.toInt()   // 與地圖折線同色（DesignTokens.Accent）

    // ① 防護：Static Maps API 未啟用（403 / REQUEST_DENIED）時，本次進程不再重複嘗試，
    //         避免每次開地圖都打一次注定失敗的請求。啟用 API 後重開 App 即恢復嘗試。
    @Volatile private var staticMapsUnavailable = false

    /** 目前是否有可用（已驗證）的網路連線 */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 以 docId（優先）或站點座標雜湊組出穩定快取鍵，讓同一趟行程對到同一個檔案 */
    fun keyFor(docId: String?, stops: List<Stop>): String {
        if (!docId.isNullOrBlank()) return "doc_" + docId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val sig = stops.joinToString("|") { s ->
            "${s.name}:${s.lat?.let { "%.4f".format(it) }},${s.lng?.let { "%.4f".format(it) }}"
        }
        return "sig_" + Integer.toHexString(sig.hashCode())
    }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        return File(dir, "$key.png")
    }

    /** Static Maps 的中心與縮放（自己算好再指定，才知道圖上每個像素對應的經緯度） */
    private fun metaFile(context: Context, key: String): File =
        File(cacheFile(context, key).parentFile, "$key.json")

    fun isCached(context: Context, key: String): Boolean =
        cacheFile(context, key).let { it.exists() && it.length() > 0 } && metaFile(context, key).exists()

    // ── Web Mercator（Google 圖磚座標）：zoom z 時全世界寬 256·2^z 個邏輯像素 ──────
    private const val STATIC_SIZE = 640     // Static Maps size（邏輯像素）
    private const val STATIC_SCALE = 2      // scale=2 → 實際 1280×1280
    private const val STATIC_PAD = 48       // 四周留白，避免標記貼邊被裁

    private fun worldX(lng: Double, z: Int) = (lng + 180.0) / 360.0 * 256.0 * (1 shl z)
    private fun worldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * 256.0 * (1 shl z)
    }
    private fun latOfWorldY(y: Double, z: Int): Double =
        Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / (256.0 * (1 shl z))))))

    /** 能把所有點框進 640×640（扣留白）的最大縮放，與框的中心 */
    private fun fitCamera(pts: List<LatLng>): Triple<Double, Double, Int> {
        val minLat = pts.minOf { it.latitude }; val maxLat = pts.maxOf { it.latitude }
        val minLng = pts.minOf { it.longitude }; val maxLng = pts.maxOf { it.longitude }
        val avail = (STATIC_SIZE - 2 * STATIC_PAD).toDouble()
        var z = 17
        while (z > 3 && (worldX(maxLng, z) - worldX(minLng, z) > avail ||
                         worldY(minLat, z) - worldY(maxLat, z) > avail)) z--
        val cy = (worldY(minLat, z) + worldY(maxLat, z)) / 2.0
        return Triple(latOfWorldY(cy, z), (minLng + maxLng) / 2.0, z)
    }

    private fun staticProjection(cLat: Double, cLng: Double, z: Int): (LatLng) -> Pair<Float, Float> {
        val cx = worldX(cLng, z); val cy = worldY(cLat, z)
        val half = STATIC_SIZE / 2.0
        return { ll ->
            ((half + worldX(ll.longitude, z) - cx) * STATIC_SCALE).toFloat() to
                ((half + worldY(ll.latitude, z) - cy) * STATIC_SCALE).toFloat()
        }
    }

    /** 收集所有可畫的座標點（優先用道路折線，否則退回站點連線） */
    private fun pointsOf(stops: List<Stop>, roadSegments: List<List<LatLng>>): List<LatLng> {
        val stopPts = stops.mapNotNull { s ->
            val la = s.lat; val ln = s.lng
            if (la != null && ln != null) LatLng(la, ln) else null
        }
        val routePts = roadSegments.flatten()
        return if (routePts.isNotEmpty()) routePts + stopPts else stopPts
    }

    /**
     * 上線時預抓整趟 Static Maps 路線圖存本地。已快取／無座標／無 API key 時直接略過。
     * 失敗不影響功能（離線時會 fallback 本地草圖），只回傳是否成功。
     */
    suspend fun prefetchStaticMap(
        context: Context,
        key: String,
        stops: List<Stop>,
        roadSegments: List<List<LatLng>>
    ): Boolean = withContext(Dispatchers.IO) {
        if (staticMapsUnavailable) return@withContext false
        val file = cacheFile(context, key)
        if (isCached(context, key)) return@withContext true
        val stopPts = stops.mapNotNull { s ->
            val la = s.lat; val ln = s.lng
            if (la != null && ln != null) LatLng(la, ln) else null
        }
        if (stopPts.isEmpty()) return@withContext false
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isBlank()) return@withContext false
        val (cLat, cLng, zoom) = fitCamera(pointsOf(stops, roadSegments))
        val url = buildStaticMapUrl(stopPts, roadSegments, apiKey, cLat, cLng, zoom)
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                // 403 / 400 多為「Maps Static API 未啟用」或金鑰限制 → 本次進程停止重試
                if (conn.responseCode == 403 || conn.responseCode == 400) {
                    staticMapsUnavailable = true
                    Log.w(TAG, "Static Maps 回應 ${conn.responseCode}（API 未啟用？），本次進程改全用本地草圖")
                } else {
                    Log.w(TAG, "Static Maps 回應 ${conn.responseCode}，改用本地草圖")
                }
                conn.disconnect()
                return@withContext false
            }
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            metaFile(context, key).writeText(
                org.json.JSONObject().put("lat", cLat).put("lng", cLng).put("zoom", zoom).toString()
            )
            Log.d(TAG, "✅ 已快取離線路線圖：${file.name}（${file.length()} bytes）")
            true
        } catch (e: Exception) {
            Log.w(TAG, "離線路線圖快取失敗，離線時改用本地草圖：${e.message}")
            if (file.exists()) file.delete()
            false
        }
    }

    /**
     * 取得離線可顯示的路線圖：
     *  - 有預抓的 Static Maps PNG → 直接讀檔（真實底圖）
     *  - 否則 → 用折線在本地 Canvas 畫路線草圖（0 網路）
     * 都拿不到（無座標）時回傳 null，畫面改顯示純站點清單。
     */
    suspend fun loadOfflineBitmap(
        context: Context,
        key: String,
        stops: List<Stop>,
        roadSegments: List<List<LatLng>>,
        region: String = ""
    ): OfflineMap? = withContext(Dispatchers.IO) {
        val file = cacheFile(context, key)
        if (file.exists() && file.length() > 0) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.let { bmp ->
                // 舊版快取是 Static Maps 自動取景，沒有中心／縮放記錄 → 只能顯示圖、不疊位置
                val proj = runCatching {
                    val m = org.json.JSONObject(metaFile(context, key).readText())
                    staticProjection(m.getDouble("lat"), m.getDouble("lng"), m.getInt("zoom"))
                }.getOrNull()
                return@withContext OfflineMap(bmp, proj)
            }
        }
        renderSketch(context, stops, roadSegments, region)
    }

    // ── Static Maps URL ────────────────────────────────────────────────
    private fun buildStaticMapUrl(
        stops: List<LatLng>, roadSegments: List<List<LatLng>>, apiKey: String,
        centerLat: Double, centerLng: Double, zoom: Int
    ): String {
        val sb = StringBuilder("https://maps.googleapis.com/maps/api/staticmap?size=${STATIC_SIZE}x$STATIC_SIZE&scale=$STATIC_SCALE")
            .append("&center=").append("%.6f,%.6f".format(centerLat, centerLng))
            .append("&zoom=").append(zoom)
        val segs = routeOrStraight(stops, roadSegments)
        for (seg in segs) {
            if (seg.size < 2) continue
            val enc = URLEncoder.encode(PolyUtil.encode(seg), "UTF-8")
            sb.append("&path=color:0x2A6B5Eff%7Cweight:4%7Cenc:").append(enc)
        }
        stops.forEachIndexed { i, ll ->
            sb.append("&markers=color:0x2A6B5E")
            if (i < 9) sb.append("%7Clabel:").append(i + 1)   // Static Maps label 僅支援單一字元
            sb.append("%7C").append("%.6f".format(ll.latitude)).append(",").append("%.6f".format(ll.longitude))
        }
        sb.append("&key=").append(apiKey)
        return sb.toString()
    }

    // ── 本地路線草圖（無網路）──────────────────────────────────────────
    // ② 地理框架：經緯格線 + 比例尺 + 指北針 + 地區標籤（皆為正確資訊，不捏造）
    //    另保留海岸線掛勾：assets/region_outline.json 若存在（GeoJSON）則畫在底層。
    private fun renderSketch(
        context: Context,
        stops: List<Stop>,
        roadSegments: List<List<LatLng>>,
        region: String
    ): OfflineMap? {
        val coastline = loadCoastline(context)
        val pts = pointsOf(stops, roadSegments) + coastline.flatten()
        if (pts.isEmpty()) return null

        val size = 1080
        val pad = 110f
        val minLat = pts.minOf { it.latitude }
        val maxLat = pts.maxOf { it.latitude }
        val minLng = pts.minOf { it.longitude }
        val maxLng = pts.maxOf { it.longitude }
        val centerLat = (minLat + maxLat) / 2.0
        val lngK = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)  // 經度依緯度壓縮，避免變形

        // 以等距投影 + 等比縮放把經緯度落到畫布（維持長寬比）
        val spanX = ((maxLng - minLng) * lngK).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = ((size - 2 * pad) / maxOf(spanX, spanY)).toFloat()  // 每「度緯度」對應的像素
        val drawW = (spanX * scale).toFloat()
        val drawH = (spanY * scale).toFloat()
        val offX = (size - drawW) / 2f
        val offY = (size - drawH) / 2f

        fun px(ll: LatLng): Pair<Float, Float> {
            val x = offX + (((ll.longitude - minLng) * lngK) * scale).toFloat()
            val y = offY + (((maxLat - ll.latitude)) * scale).toFloat()  // 緯度上北下南翻轉
            return x to y
        }

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEFECE3.toInt())  // 米白底，貼近 App 背景色

        // 1) 經緯格線（地理框架，一定正確）
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22000000; strokeWidth = 1.5f; style = Paint.Style.STROKE
        }
        val gridText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000; textSize = 20f
        }
        val stepLat = niceStep(maxLat - minLat)
        var la = Math.ceil(minLat / stepLat) * stepLat
        while (la <= maxLat) {
            val (_, y) = px(LatLng(la, minLng))
            canvas.drawLine(0f, y, size.toFloat(), y, gridPaint)
            canvas.drawText("%.2f°N".format(la), 8f, y - 6f, gridText)
            la += stepLat
        }
        val stepLng = niceStep((maxLng - minLng) * lngK) / lngK
        var ln = Math.ceil(minLng / stepLng) * stepLng
        while (ln <= maxLng) {
            val (x, _) = px(LatLng(minLat, ln))
            canvas.drawLine(x, 0f, x, size.toFloat(), gridPaint)
            canvas.drawText("%.2f°E".format(ln), x + 6f, size - 12f, gridText)
            ln += stepLng
        }

        // 2) 海岸線 / 區域輪廓（若有 asset），畫在路線底層
        if (coastline.isNotEmpty()) {
            val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF9DB9B0.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }
            for (line in coastline) {
                if (line.size < 2) continue
                val path = android.graphics.Path()
                line.forEachIndexed { i, ll -> val (x, y) = px(ll); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                canvas.drawPath(path, coastPaint)
            }
        }

        // 3) 道路折線（沒有 roadSegments 時退回站點直線）
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT; strokeWidth = 10f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val stopPts = stops.mapNotNull { s ->
            val laa = s.lat; val lnn = s.lng
            if (laa != null && lnn != null) LatLng(laa, lnn) else null
        }
        val segs = routeOrStraight(stopPts, roadSegments)
        for (seg in segs) {
            if (seg.size < 2) continue
            val path = android.graphics.Path()
            seg.forEachIndexed { i, ll ->
                val (x, y) = px(ll)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // 4) 站點圓點 + 序號
        val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.FILL }
        val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 34f; isFakeBoldText = true
        }
        stopPts.forEachIndexed { i, ll ->
            val (x, y) = px(ll)
            canvas.drawCircle(x, y, 30f, dotFill)
            canvas.drawCircle(x, y, 30f, dotRing)
            canvas.drawText("${i + 1}", x, y + 12f, label)
        }

        // 5) 地區標籤（左上）
        if (region.isNotBlank()) {
            val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT; textSize = 40f; isFakeBoldText = true
            }
            canvas.drawText(region, 24f, 52f, regionPaint)
        }

        // 6) 指北針（右上）
        val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC333333.toInt(); style = Paint.Style.FILL }
        val nx = size - 60f; val ny = 60f
        val tri = android.graphics.Path().apply {
            moveTo(nx, ny - 26f); lineTo(nx - 14f, ny + 14f); lineTo(nx + 14f, ny + 14f); close()
        }
        canvas.drawPath(tri, northPaint)
        canvas.drawText("N", nx, ny + 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC333333.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        })

        // 7) 比例尺（左下）
        val metersPerPx = 111320.0 / scale
        val targetKm = (260.0 * metersPerPx) / 1000.0
        val km = niceKm(targetKm)
        val barPx = ((km * 1000.0) / metersPerPx).toFloat()
        val bx = 30f; val by = size - 40f
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC333333.toInt(); strokeWidth = 5f }
        canvas.drawLine(bx, by, bx + barPx, by, barPaint)
        canvas.drawLine(bx, by - 10f, bx, by + 10f, barPaint)
        canvas.drawLine(bx + barPx, by - 10f, bx + barPx, by + 10f, barPaint)
        canvas.drawText("${km.toInt()} km", bx + barPx + 12f, by + 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC333333.toInt(); textSize = 24f
        })

        return OfflineMap(bmp) { ll -> px(ll) }
    }

    /** 依經緯跨度挑「好看」的格線間隔（度） */
    private fun niceStep(span: Double): Double {
        val candidates = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0)
        // 目標畫面上約 3~5 條線
        for (c in candidates) if (span / c <= 5) return c
        return candidates.last()
    }

    /** 挑不超過上限的「整數」公里數當比例尺長度 */
    private fun niceKm(maxKm: Double): Double {
        val candidates = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)
        var best = candidates.first()
        for (c in candidates) if (c <= maxKm) best = c
        return best
    }

    /**
     * 讀取可選的海岸線／區域輪廓 GeoJSON（assets/region_outline.json）。
     * 支援 FeatureCollection / Feature / geometry，型別 LineString / MultiLineString / Polygon / MultiPolygon。
     * 檔案不存在（預設）時回傳空清單，草圖只畫格線+路線。
     */
    private fun loadCoastline(context: Context): List<List<LatLng>> {
        return try {
            val text = context.assets.open("region_outline.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(text)
            val lines = mutableListOf<List<LatLng>>()
            fun ringFrom(coords: org.json.JSONArray): List<LatLng> =
                (0 until coords.length()).mapNotNull { i ->
                    val p = coords.optJSONArray(i) ?: return@mapNotNull null
                    LatLng(p.optDouble(1), p.optDouble(0))  // GeoJSON 是 [lng, lat]
                }
            fun addGeometry(geom: org.json.JSONObject) {
                val type = geom.optString("type")
                val c = geom.optJSONArray("coordinates") ?: return
                when (type) {
                    "LineString" -> lines.add(ringFrom(c))
                    "MultiLineString", "Polygon" ->
                        (0 until c.length()).forEach { c.optJSONArray(it)?.let { r -> lines.add(ringFrom(r)) } }
                    "MultiPolygon" ->
                        (0 until c.length()).forEach { poly ->
                            c.optJSONArray(poly)?.let { rings ->
                                (0 until rings.length()).forEach { rings.optJSONArray(it)?.let { r -> lines.add(ringFrom(r)) } }
                            }
                        }
                }
            }
            when (root.optString("type")) {
                "FeatureCollection" -> root.optJSONArray("features")?.let { fs ->
                    (0 until fs.length()).forEach { fs.optJSONObject(it)?.optJSONObject("geometry")?.let(::addGeometry) }
                }
                "Feature" -> root.optJSONObject("geometry")?.let(::addGeometry)
                else -> addGeometry(root)
            }
            lines.filter { it.size >= 2 }
        } catch (e: Exception) {
            emptyList()  // 沒有 asset 或解析失敗 → 不畫海岸線
        }
    }
}
