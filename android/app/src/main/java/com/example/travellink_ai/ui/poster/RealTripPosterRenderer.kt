package com.example.travellink_ai.ui.poster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * 用真實行程資料繪製主視覺。
 *
 * ⚠️ **版面是暫定的。** 最終版面要等 Web 端 Phase -1 原型產出後才會定案
 * （見 `docs/UI設計_製作旅程回憶.md` 第 10 節「待補」）。
 * 這一版的目的是驗證**資料管線**：真實座標與折線能不能畫出合理的路線、
 * 各種降級情況會長什麼樣，與美術無關。
 *
 * 已定案並實作的部分：
 *   - 旅程數據四項（天數／景點數／總車程／地區）
 *   - 品牌簽名放在不跨切線的位置，預設關閉
 *   - 素材不足時以路線、標題、日期補畫面，不生成假照片（計畫 §5.2）
 */
class RealTripPosterRenderer(
    private val data: TripPosterData,
    private val showBrandSignature: Boolean = false
) : TripPosterRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 版面比例（相對主視覺長寬，故與輸出尺寸無關）
    private companion object {
        const val MAP_TOP = 0.14f
        const val MAP_BOTTOM = 0.88f
        const val SIDE_PAD = 0.08f
    }

    override fun draw(canvas: Canvas, masterW: Float, masterH: Float) {
        drawBackground(canvas, masterW, masterH)

        if (data.hasGeometry) {
            drawRouteAndStops(canvas, masterW, masterH)
        } else {
            drawNoGeometryFallback(canvas, masterW, masterH)
        }

        drawTitle(canvas, masterW, masterH)
        drawStats(canvas, masterW, masterH)
        if (showBrandSignature) drawBrandSignature(canvas, masterW, masterH)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.parseColor("#1F5448"),   // DesignTokens.AccentDark
            Color.parseColor("#0D2621"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawRouteAndStops(canvas: Canvas, w: Float, h: Float) {
        val positioned = data.stops.filter { it.position != null }

        // fit 的候選點要包含折線本身，否則路線會超出版面
        val allPoints = buildList {
            data.routeSegments.forEach { addAll(it) }
            positioned.forEach { add(it.position!!) }
        }
        val projector = RouteProjector.fit(
            allPoints,
            left = w * SIDE_PAD, top = h * MAP_TOP,
            right = w * (1 - SIDE_PAD), bottom = h * MAP_BOTTOM
        ) ?: run { drawNoGeometryFallback(canvas, w, h); return }

        // ── 路線 ──
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.006f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.parseColor("#F5E8C0")   // DesignTokens.GoldLight

        if (data.routeSegments.isNotEmpty()) {
            data.routeSegments.forEach { seg ->
                val path = Path()
                seg.forEachIndexed { i, p ->
                    val pt = projector.project(p)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                canvas.drawPath(path, paint)
            }
        } else {
            // 沒有道路折線時的連線。
            //
            // ★ 這條路徑比想像中常見：roadSegmentsEncoded 只在使用者開過地圖頁後
            //   才寫進 Room（ItineraryViewModel.loadMapData），所以剛建立完行程就來做
            //   九宮格的話，一定走這裡。不能當成「湊合能看」的降級。
            //
            //   用平滑曲線而非直線折角，讓它看起來像刻意的設計；仍保留虛線，
            //   誠實表示這是示意連線而不是實際道路。
            paint.pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(w * 0.012f, w * 0.010f), 0f
            )
            canvas.drawPath(smoothPath(positioned.map { projector.project(it.position!!) }), paint)
            paint.pathEffect = null
        }

        // ── 站點 ──
        // 站點密集時標籤會疊成一團（實測 6 站的台東行程就已經看不清楚），
        // 因此記錄已佔用的標籤矩形，重疊就只畫圓點不畫名稱。
        val takenLabels = mutableListOf<android.graphics.RectF>()
        positioned.forEachIndexed { i, stop ->
            drawStopMarker(canvas, w, projector.project(stop.position!!), i + 1, stop.name, takenLabels)
        }
    }

    private fun drawStopMarker(
        canvas: Canvas,
        w: Float,
        pt: PointF,
        index: Int,
        name: String,
        takenLabels: MutableList<android.graphics.RectF>
    ) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        paint.color = Color.parseColor("#F7F5F0")            // DesignTokens.Bg
        canvas.drawCircle(pt.x, pt.y, w * 0.018f, paint)
        paint.color = Color.parseColor("#2A6B5E")            // DesignTokens.Accent
        canvas.drawCircle(pt.x, pt.y, w * 0.013f, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = w * 0.016f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("$index", pt.x, pt.y + w * 0.006f, paint)

        // 景點名：單行，過長截斷加省略號（計畫 §5.2）
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = w * 0.021f
        paint.color = Color.parseColor("#E8E4DC")

        val label = ellipsize(name, paint, w * 0.30f)
        val textW = paint.measureText(label)
        val gap = w * 0.028f
        val ly = pt.y + w * 0.008f

        // 先試放右側，不行再翻到左側。只放棄兩邊都擺不下的。
        // （第一版只試右側，結果中央附近的站幾乎全被切線判掉，海報上只剩兩個名字）
        val candidates = listOf(pt.x + gap, pt.x - gap - textW)

        for (lx in candidates) {
            val rect = android.graphics.RectF(
                lx, ly - paint.textSize, lx + textW, ly + paint.textSize * 0.3f
            )
            // 出界
            if (rect.left < 0f || rect.right > MASTER_W) continue
            // 被切線切開 → 單張發布時會變成讀不懂的碎片（計畫 §5.2）
            if (CutLines.crossesVertical(rect.left, rect.right)) continue
            // 與已畫過的標籤重疊 → 寧可少字也不要疊成一團
            if (takenLabels.any { android.graphics.RectF.intersects(it, rect) }) continue

            takenLabels += rect
            canvas.drawText(label, lx, ly, paint)
            return
        }
        // 兩側都不行：只留編號圓點
    }

    /** 沒有任何座標時：不畫地圖，改用大字排版撐版面，不生成假內容。 */
    private fun drawNoGeometryFallback(canvas: Canvas, w: Float, h: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = Color.parseColor("#3A6B60")
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT

        val names = data.stops.take(8)
        val lineH = h * 0.062f
        var y = h * MAP_TOP + lineH
        paint.textSize = w * 0.048f
        names.forEachIndexed { i, s ->
            paint.color = if (i % 2 == 0) Color.parseColor("#E8E4DC") else Color.parseColor("#9FBDB4")
            canvas.drawText(ellipsize(s.name, paint, w * (1 - SIDE_PAD * 2)), w * SIDE_PAD, y, paint)
            y += lineH
        }
    }

    /**
     * 標題與日期放在**左上格內**（r1c1），確保不被任何切線切開（計畫 §5.2）。
     * 位置是暫定的，但「不跨切線」是硬性規則，最終版面也必須守住。
     */
    private fun drawTitle(canvas: Canvas, w: Float, h: Float) {
        val (left, top, right, _) = CutLines.safeTextBox(0, 0).let {
            arrayOf(it[0], it[1], it[2], it[3])
        }
        val maxW = right - left

        paint.reset(); paint.isAntiAlias = true
        paint.textAlign = Paint.Align.LEFT

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = w * 0.060f
        canvas.drawText(ellipsize(data.title, paint, maxW), left, top + w * 0.062f, paint)

        paint.color = Color.parseColor("#C9C4BC")
        paint.typeface = Typeface.DEFAULT
        paint.textSize = w * 0.026f
        canvas.drawText(ellipsize(data.dateLabel, paint, maxW), left, top + w * 0.100f, paint)
    }

    /**
     * 四項數據排成 2×2 放進**右下格內**（r3c3），同樣不跨切線。
     * 原本是橫跨整個底部的一列，會被兩條垂直切線各切一刀。
     */
    private fun drawStats(canvas: Canvas, w: Float, h: Float) {
        val box = CutLines.safeTextBox(GRID - 1, GRID - 1)
        val left = box[0]; val bottom = box[3]
        val cellW = (box[2] - box[0]) / 2f
        val rowH = w * 0.075f

        data.stats.asPairs().forEachIndexed { i, (value, label) ->
            val x = left + cellW * (i % 2)
            val y = bottom - rowH * (1 - i / 2)

            paint.reset(); paint.isAntiAlias = true
            paint.textAlign = Paint.Align.LEFT

            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = w * 0.032f
            canvas.drawText(ellipsize(value, paint, cellW * 0.92f), x, y, paint)

            paint.color = Color.parseColor("#9A9590")   // DesignTokens.Ink3
            paint.typeface = Typeface.DEFAULT
            paint.textSize = w * 0.018f
            canvas.drawText(label, x, y + w * 0.024f, paint)
        }
    }

    /** 放右下角格內、離切線夠遠（計畫 §5.6：低干擾、不跨切線）。 */
    private fun drawBrandSignature(canvas: Canvas, w: Float, h: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = Color.parseColor("#7A9E94")
        paint.typeface = Typeface.DEFAULT
        paint.textSize = w * 0.018f
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("TravelLinkAI", w * (1 - SIDE_PAD), h * 0.995f, paint)
    }

    /**
     * 用中點做二次貝茲，把折角磨成連續曲線。
     * 只有兩點時退回直線；點數不足回空 Path。
     */
    private fun smoothPath(pts: List<PointF>): Path {
        val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) return path
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
            return path
        }
        // 依序以「前一點」為控制點、以「前後中點」為端點，接出平滑曲線
        for (i in 1 until pts.size - 1) {
            val midX = (pts[i].x + pts[i + 1].x) / 2f
            val midY = (pts[i].y + pts[i + 1].y) / 2f
            path.quadTo(pts[i].x, pts[i].y, midX, midY)
        }
        val last = pts.last()
        path.lineTo(last.x, last.y)
        return path
    }

    /** 單行截斷加省略號。中文不做斷行（計畫 §5.2：短標籤保持單行）。 */
    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val budget = maxWidth - p.measureText(ellipsis)
        if (budget <= 0f) return ellipsis
        var end = text.length
        while (end > 0 && p.measureText(text, 0, end) > budget) end--
        return text.substring(0, end) + ellipsis
    }
}
