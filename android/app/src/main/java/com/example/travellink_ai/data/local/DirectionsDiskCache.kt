package com.example.travellink_ai.data.local

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.example.travellink_ai.ui.map.PolyUtil

/**
 * 單段 Directions 結果的本機快取（座標級 key，見 ItineraryViewModel.directionsSegment）。
 *
 * 景點間車程已經存在 Room 的地圖快取裡，但開車模式「停車場→景點」的步行段沒有，
 * App 重開後每次開行程都要重查（實測一次 7 段）。路線幾乎不會變，存 30 天；
 * 超過上限就清掉最舊的一半，避免無限長大。
 */
class DirectionsDiskCache(context: Context) {
    private val prefs = context.getSharedPreferences("directions_segments", Context.MODE_PRIVATE)

    fun get(key: String): Pair<List<LatLng>, Long>? {
        val raw = prefs.getString(key, null) ?: return null
        // 格式：存入時間|分鐘|encoded polyline（polyline 字元在 ASCII 63–126，不含 |）
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > TTL_MS) {
            prefs.edit().remove(key).apply()
            return null
        }
        val mins = parts[1].toLongOrNull() ?: return null
        val pts = runCatching { PolyUtil.decode(parts[2]) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return pts to mins
    }

    fun put(key: String, value: Pair<List<LatLng>, Long>) {
        if (value.first.isEmpty()) return
        val all = prefs.all
        val editor = prefs.edit()
        if (all.size >= MAX_ENTRIES) {
            all.entries
                .sortedBy { (it.value as? String)?.substringBefore("|")?.toLongOrNull() ?: 0L }
                .take(all.size / 2)
                .forEach { editor.remove(it.key) }
        }
        editor.putString(key, "${System.currentTimeMillis()}|${value.second}|${PolyUtil.encode(value.first)}").apply()
    }

    private companion object {
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 400
    }
}
