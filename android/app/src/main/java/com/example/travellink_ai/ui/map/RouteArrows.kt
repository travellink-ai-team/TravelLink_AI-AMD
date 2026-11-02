package com.example.travellink_ai.ui.map

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 路線方向箭頭的位置（對齊網頁 Polyline icons：FORWARD_CLOSED_ARROW、offset 15%、repeat 80px）。
 * maps-compose 的 Polyline 沒有重複圖示，改成沿路線每隔一段距離放一個 flat marker。
 * 間距以螢幕 dp 換算公尺（隨縮放層級變），一段路最多 [MAX_PER_SEGMENT] 個，太多就拉開間距。
 */
internal object RouteArrows {
    const val SPACING_DP = 90.0
    const val MAX_PER_SEGMENT = 24

    data class Arrow(val position: LatLng, val bearing: Float)

    /** 縮放層級 zoom 下 1 dp 等於幾公尺（Google 地圖 256dp 圖磚、Web Mercator） */
    fun metersPerDp(zoom: Float, lat: Double): Double =
        156_543.033_92 * cos(Math.toRadians(lat)) / 2.0.pow(zoom.toDouble())

    fun place(path: List<LatLng>, spacingMeters: Double, max: Int = MAX_PER_SEGMENT): List<Arrow> {
        if (path.size < 2 || spacingMeters <= 0) return emptyList()
        val legs = path.zipWithNext().map { (a, b) -> distanceMeters(a, b) }
        val total = legs.sum()
        if (total <= 0) return emptyList()
        // 太短的路（不到一個間距）只在中間放一個；太長就拉開間距，免得一段路幾百個 marker
        val step = maxOf(spacingMeters, total / max)
        val firstAt = if (total < step) total / 2 else step / 2
        val out = ArrayList<Arrow>()
        var target = firstAt
        var walked = 0.0
        for (i in legs.indices) {
            val len = legs[i]
            if (len <= 0) continue
            val a = path[i]; val b = path[i + 1]
            while (target <= walked + len && out.size < max) {
                val t = (target - walked) / len
                out += Arrow(
                    LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t),
                    bearing(a, b)
                )
                target += step
            }
            walked += len
        }
        return out
    }

    /** a→b 的方位角（度，北＝0、順時針），flat marker 的 rotation 直接用 */
    fun bearing(a: LatLng, b: LatLng): Float {
        val la1 = Math.toRadians(a.latitude); val la2 = Math.toRadians(b.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
