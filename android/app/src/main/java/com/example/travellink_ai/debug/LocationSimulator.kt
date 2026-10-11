package com.example.travellink_ai.debug

import com.example.travellink_ai.ui.trip.haversineMeters
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Debug-only 假 GPS：沿著給定路線點位移動，定時發出座標，取代 FusedLocationProviderClient
 * 供 demo 時模擬「開始行程後移動」。呼叫端須自行以 BuildConfig.DEBUG 把關，不在正式環境啟用。
 *
 * 路線可以只有起訖兩點（直線），也可以是 Directions 的整條道路折線（幾百個點）。
 * 不管幾個點、距離多遠，整段都在 [durationMs] 內走完，速度依沿路距離平均分配——
 * 過去每一小段各自算時間，折線點一多整段要走好幾分鐘。
 */
class LocationSimulator(private val scope: CoroutineScope) {

    private val _location = MutableStateFlow<LatLng?>(null)
    val location: StateFlow<LatLng?> = _location

    private var job: Job? = null

    /**
     * @param route 依序要走過的座標點（至少 2 點才會移動，否則直接定在該點）
     * @param durationMs 整段走完的時間（demo 用，固定 10 秒）
     * @param tickMs 座標更新間隔
     */
    fun start(route: List<LatLng>, durationMs: Long = 10_000L, tickMs: Long = 500L) {
        stop()
        if (route.size < 2) {
            _location.value = route.firstOrNull()
            return
        }
        // 每個點距起點的累計距離，用來把「經過的時間比例」換成「沿路的位置」
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) cumulative[i] = cumulative[i - 1] + haversineMeters(route[i - 1], route[i])
        val total = cumulative.last()
        if (total <= 0.0) { _location.value = route.last(); return }

        job = scope.launch {
            var elapsed = 0L
            var seg = 0
            while (elapsed < durationMs) {
                val target = total * elapsed / durationMs
                while (seg < route.size - 2 && cumulative[seg + 1] < target) seg++
                val segLen = cumulative[seg + 1] - cumulative[seg]
                val t = if (segLen <= 0.0) 0.0 else (target - cumulative[seg]) / segLen
                val a = route[seg]; val b = route[seg + 1]
                _location.value = LatLng(
                    a.latitude + (b.latitude - a.latitude) * t,
                    a.longitude + (b.longitude - a.longitude) * t
                )
                delay(tickMs)
                elapsed += tickMs
            }
            _location.value = route.last()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** 道路折線的端點離站點多遠內算「這兩站之間的那段」（開車模式的端點是停車場，可能離景點數百公尺） */
        private const val SEGMENT_END_TOLERANCE_M = 1_500.0

        /**
         * 從行程已查好的逐段道路折線（ItineraryViewModel.roadSegments，不必再打 Directions）找出
         * from→to 那一段，回傳 from＋沿路點＋to。找不到（路線還沒載入、站序剛改）就退回直線。
         */
        fun roadPath(segments: List<List<LatLng>>, from: LatLng, to: LatLng): List<LatLng> {
            val best = segments.filter { it.size >= 2 }
                .map { seg -> seg to (haversineMeters(seg.first(), from) to haversineMeters(seg.last(), to)) }
                .filter { (_, d) -> d.first <= SEGMENT_END_TOLERANCE_M && d.second <= SEGMENT_END_TOLERANCE_M }
                .minByOrNull { (_, d) -> d.first + d.second }
                ?.first
            return if (best == null) listOf(from, to) else listOf(from) + best + listOf(to)
        }

        /** 只取路線的前 [meters] 公尺（「離開本站」：沿路往下一站走一小段就停） */
        fun truncate(path: List<LatLng>, meters: Double): List<LatLng> {
            if (path.size < 2) return path
            val out = mutableListOf(path.first())
            var acc = 0.0
            for (i in 1 until path.size) {
                val d = haversineMeters(path[i - 1], path[i])
                if (acc + d >= meters) {
                    val t = if (d <= 0.0) 0.0 else (meters - acc) / d
                    val a = path[i - 1]; val b = path[i]
                    out += LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
                    return out
                }
                acc += d
                out += path[i]
            }
            return out
        }
    }
}
