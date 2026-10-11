package com.example.travellink_ai.ui.map

import com.google.android.gms.maps.model.LatLng

object PolyUtil {
    fun decode(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
        }
        return poly
    }

    /**
     * 將座標串編碼成 Google polyline 演算法字串（decode 的反向操作）。
     * 用途：把生成時算好的路線線段（segments）壓縮儲存進 Room，
     * 供歷史行程重新開啟時直接解碼重用，不必重新呼叫 Directions API。
     */
    fun encode(path: List<LatLng>): String {
        var lastLat = 0L
        var lastLng = 0L
        val result = StringBuilder()

        for (point in path) {
            val lat = Math.round(point.latitude * 1E5)
            val lng = Math.round(point.longitude * 1E5)

            encodeValue(lat - lastLat, result)
            encodeValue(lng - lastLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(value: Long, result: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            result.append(((0x20 or (v.toInt() and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v.toInt() + 63).toChar())
    }
}