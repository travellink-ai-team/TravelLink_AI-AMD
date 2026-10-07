package com.example.travellink_ai.ui.poster

import android.graphics.PointF
import com.google.android.gms.maps.model.LatLng
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * 把行程的經緯度投影到主視覺座標系。
 *
 * 用 Web Mercator 是為了讓路線形狀與使用者在 Google Maps 上看到的一致 ——
 * 若直接拿經緯度當平面座標，台灣緯度下橫向會被壓縮約 8%，路線會歪。
 *
 * 投影後自動 fit 進指定範圍（等比例，不變形），因此不同行程（市區短程 vs 縱谷長程）
 * 都能填滿版面。
 */
class RouteProjector private constructor(
    private val minX: Double,
    private val maxY: Double,
    private val scale: Double,
    private val offsetX: Float,
    private val offsetY: Float
) {

    fun project(p: LatLng): PointF = PointF(
        (offsetX + (mercatorX(p.longitude) - minX) * scale).toFloat(),
        // Mercator y 向北增加，畫布 y 向下增加 → 以 maxY 為基準翻轉
        (offsetY + (maxY - mercatorY(p.latitude)) * scale).toFloat()
    )

    companion object {
        /**
         * ★ 必須與 [mercatorY] 同單位（弧度）。
         *
         * 曾經直接回傳「度」而 y 是 ln(tan(...)) 的弧度基底，兩者差 π/180 ≈ 57 倍，
         * 導致路線被橫向拉伸約 14 倍、壓成一條水平線。
         * 單元測試只驗「有沒有超出框」與「南北順序」，兩者在錯誤比例下依然成立，
         * 因此漏掉 —— 見 `RouteProjector.fit` 的長寬比測試。
         */
        private fun mercatorX(lon: Double) = Math.toRadians(lon)

        private fun mercatorY(lat: Double): Double {
            // 夾在 ±85.05° 避免極區發散；台灣不會觸及，但防呆
            val clamped = lat.coerceIn(-85.05112878, 85.05112878)
            return ln(tan(PI / 4 + Math.toRadians(clamped) / 2))
        }

        /**
         * 建立投影器，把 [points] 等比例 fit 進 ([left],[top])–([right],[bottom]) 的框內。
         *
         * @return null 表示點數不足或範圍退化（例如全部同一點），呼叫端應改走無地圖版面
         */
        fun fit(
            points: List<LatLng>,
            left: Float, top: Float, right: Float, bottom: Float
        ): RouteProjector? {
            if (points.isEmpty()) return null

            val xs = points.map { mercatorX(it.longitude) }
            val ys = points.map { mercatorY(it.latitude) }
            val minX = xs.min(); val maxX = xs.max()
            val minY = ys.min(); val maxY = ys.max()

            val spanX = maxX - minX
            val spanY = maxY - minY
            val boxW = (right - left).toDouble()
            val boxH = (bottom - top).toDouble()
            if (boxW <= 0 || boxH <= 0) return null

            // 單點或共線到幾乎沒範圍 → 給一個最小跨距，避免除以零後 scale 爆掉
            val safeSpanX = if (spanX > 1e-9) spanX else 1e-4
            val safeSpanY = if (spanY > 1e-9) spanY else 1e-4

            val scale = minOf(boxW / safeSpanX, boxH / safeSpanY)

            // 置中：把縮放後的實際尺寸與框的差額平分到兩側
            val drawnW = safeSpanX * scale
            val drawnH = safeSpanY * scale
            val offsetX = left + ((boxW - drawnW) / 2).toFloat()
            val offsetY = top + ((boxH - drawnH) / 2).toFloat()

            return RouteProjector(minX, maxY, scale, offsetX, offsetY)
        }
    }
}
