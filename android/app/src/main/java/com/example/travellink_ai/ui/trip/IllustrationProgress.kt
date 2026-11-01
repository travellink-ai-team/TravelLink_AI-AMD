package com.example.travellink_ai.ui.trip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.debug.DemoClock
import com.example.travellink_ai.debug.LocationSimulator
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlin.math.cos

/**
 * 行程進行中，「我」在互動插圖上的位置。
 *
 * 插圖不對應真實地理，所以只表達「進度」：在站上就標那一站；移動中就是上一站框到下一站框之間
 * 走了幾成（[ratio]）。進度有 GPS 時用「投影到這段道路折線上的比例」，沒有 GPS 退回
 * 「離開上一站後經過的時間 ÷ 預計車程」。到站前最多 [MAX_RATIO_BEFORE_ARRIVAL]，
 * 打卡或進到 300m 內才到 1（[arrived]）。只前進不後退由畫面那一層負責（見 InteractiveIllustration）。
 */
internal data class IllustrationPosition(
    /** 在這一站上（已打卡、還沒離開） */
    val atStop: Stop? = null,
    /** 移動中：從哪一站出發（行程第一段之前沒有上一站時是 null） */
    val fromStop: Stop? = null,
    /** 移動中：要去的那一站 */
    val toStop: Stop? = null,
    val ratio: Float = 0f,
    /** 已經進到下一站 300m 內、還沒打卡 */
    val arrived: Boolean = false,
    /** 預計抵達下一站的時間（當日分鐘數） */
    val etaMin: Int? = null,
    /** 這次的進度是用 GPS 算的（false＝時間比例） */
    val byGps: Boolean = false
)

internal const val MAX_RATIO_BEFORE_ARRIVAL = 0.95f

/** 有 GPS 時離已打卡的站多遠內還算「在站上」：比到站判定寬，GPS 會飄 */
private const val STILL_HERE_METERS = 500.0

/**
 * 插圖頁用的「我在哪」：行程進行中才有值。GPS 只在已有定位權限時接（不在插圖頁跳權限要求，
 * 沒權限就用時間比例）；demo 模式改吃假 GPS。每 30 秒用時鐘重算一次，時間比例才會往前走。
 */
@Composable
internal fun rememberIllustrationPosition(
    viewModel: ItineraryViewModel,
    tripVm: TripProgressViewModel
): IllustrationPosition? {
    val progress by tripVm.progress.collectAsState()
    val itinerary by viewModel.itinerary.collectAsState()
    val transitTimes by viewModel.transitTimes.collectAsState()
    val stopLocations by viewModel.stopLocations.collectAsState()
    val roadSegments by viewModel.roadSegments.collectAsState()
    val simulated by tripVm.locationSimulator.location.collectAsState()
    val demoOffset by DemoClock.offsetMs.collectAsState()
    val ongoing = progress.isOngoing
    val stops = itinerary?.stops.orEmpty()
    val derived = remember(stops, progress, transitTimes) { deriveTripState(stops, progress, transitTimes) }

    val context = LocalContext.current
    var realLocation by remember { mutableStateOf(tripVm.lastKnownLocation) }
    DisposableEffect(ongoing) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!ongoing || !granted) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    val loc = LatLng(it.latitude, it.longitude)
                    realLocation = loc
                    tripVm.lastKnownLocation = loc
                }
            }
        }
        try {
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build(),
                callback, Looper.getMainLooper())
        } catch (_: SecurityException) { }
        onDispose { client.removeLocationUpdates(callback) }
    }
    val userLocation = if (BuildConfig.DEBUG && tripVm.demoModeOn) simulated else realLocation

    var now by remember { mutableLongStateOf(DemoClock.now()) }
    LaunchedEffect(demoOffset, ongoing) {
        while (ongoing) {
            now = DemoClock.now()
            delay(30_000L)
        }
    }

    val tripStart = itinerary?.days?.substringBefore(" ")?.takeIf { it.length == 10 } ?: ""
    return remember(ongoing, stops, progress, derived, now, userLocation, stopLocations, roadSegments, transitTimes, tripStart) {
        if (!ongoing) null
        else computeIllustrationPosition(
            stops, progress, derived, now, userLocation,
            coordOf = { s -> if (s.lat != null && s.lng != null) LatLng(s.lat, s.lng) else stopLocations[s.name] },
            roadSegments = roadSegments, transitTimes = transitTimes,
            overnight = overnightState(stops, progress, derived, now, tripStart)
        )
    }
}

internal fun computeIllustrationPosition(
    stops: List<Stop>,
    progress: TripProgress,
    derived: TripDerived,
    nowMs: Long,
    userLocation: LatLng?,
    coordOf: (Stop) -> LatLng?,
    roadSegments: List<List<LatLng>>,
    transitTimes: List<Long>,
    /** 多日行程兩天之間：停在今天最後一站，不往明天第一站走 */
    overnight: OvernightInfo? = null
): IllustrationPosition? {
    if (!progress.isOngoing || stops.isEmpty()) return null
    if (overnight != null) return (derived.here?.stop ?: overnight.lodging)?.let { IllustrationPosition(atStop = it) }
    val here = derived.activeHere(progress)
    val nowMin = minutesOfDay(nowMs)

    // 下一個要去的站：還沒打卡的景點；景點都完成了就是回程車站
    val target = derived.currentStop
        ?: stops.lastOrNull()?.takeIf { it.isStation && derived.allDone }
    if (target == null) return here?.let { IllustrationPosition(atStop = it.stop) }
    val targetIdx = stops.indexOfFirst { it.stopId == target.stopId }

    if (here != null) {
        val hereCoord = coordOf(here.stop)
        val stillHere = if (userLocation != null && hereCoord != null)
            haversineMeters(userLocation, hereCoord) <= STILL_HERE_METERS
        else nowMin < here.leaveMin
        if (stillHere) return IllustrationPosition(atStop = here.stop)
    }

    // 出發站：剛打卡的那一站（在目標之前的話）；否則是目標前一個沒被跳過的站（含出發車站、住宿）
    val hereIdx = here?.let { h -> stops.indexOfFirst { it.stopId == h.stop.stopId } } ?: -1
    val fromIdx = if (hereIdx in 0 until targetIdx) hereIdx
        else (targetIdx - 1 downTo 0).firstOrNull { stops[it].stopId !in progress.skips } ?: -1
    val from = stops.getOrNull(fromIdx)
    val eta = derived.estimatedTimes[target.stopId] ?: parseClockToMinutes(target.time)

    val toCoord = coordOf(target)
    if (userLocation != null && toCoord != null && haversineMeters(userLocation, toCoord) <= NEAR_STOP_METERS) {
        return IllustrationPosition(fromStop = from, toStop = target, ratio = 1f, arrived = true, etaMin = eta, byGps = true)
    }
    if (from == null) return IllustrationPosition(toStop = target, etaMin = eta)

    val fromCoord = coordOf(from)
    val gpsRatio = if (userLocation != null && fromCoord != null && toCoord != null)
        ratioAlongPath(LocationSimulator.roadPath(roadSegments, fromCoord, toCoord), userLocation)
    else null

    val ratio = gpsRatio ?: run {
        // 這一段的預計車程：中間被跳過的站的路段也算進來
        val travel = (fromIdx until targetIdx).sumOf { transitTimes.getOrNull(it) ?: 0L }.toInt()
            .takeIf { it > 0 } ?: 15
        // 離開上一站的時間：剛打卡的站用它的預計離開；其他（出發車站、昨晚的住宿）用排定時間
        val leftMin = when {
            from.stopId == here?.stop?.stopId -> here?.leaveMin
            from.dayIndex == target.dayIndex -> parseClockToMinutes(from.time)?.plus(from.duration.toInt())
            else -> null
        } ?: eta?.minus(travel) ?: return IllustrationPosition(fromStop = from, toStop = target, etaMin = eta)
        (nowMin - leftMin).toFloat() / travel
    }
    return IllustrationPosition(
        fromStop = from,
        toStop = target,
        ratio = ratio.coerceIn(0f, MAX_RATIO_BEFORE_ARRIVAL),
        etaMin = eta,
        byGps = gpsRatio != null
    )
}

/**
 * 把位置投影到折線上，回傳「起點到投影點的沿路距離 ÷ 全長」。小範圍內把經緯度當平面
 * （經度乘 cos(緯度) 修正），一段車程的尺度誤差可以忽略。折線少於 2 點或全長 0 回 null。
 */
internal fun ratioAlongPath(path: List<LatLng>, p: LatLng): Float? {
    if (path.size < 2) return null
    val k = cos(Math.toRadians(p.latitude))
    fun x(l: LatLng) = l.longitude * k
    fun y(l: LatLng) = l.latitude
    var total = 0.0
    var bestDist = Double.MAX_VALUE
    var bestAlong = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]
        val dx = x(b) - x(a); val dy = y(b) - y(a)
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((x(p) - x(a)) * dx + (y(p) - y(a)) * dy) / len2).coerceIn(0.0, 1.0)
        val px = x(a) + dx * t; val py = y(a) + dy * t
        val d = (x(p) - px) * (x(p) - px) + (y(p) - py) * (y(p) - py)
        val segLen = Math.sqrt(len2)
        if (d < bestDist) { bestDist = d; bestAlong = total + segLen * t }
        total += segLen
    }
    return if (total <= 0.0) null else (bestAlong / total).toFloat()
}
