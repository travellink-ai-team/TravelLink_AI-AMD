package com.example.travellink_ai.ui.trip

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.travellink_ai.BuildConfig
import com.example.travellink_ai.data.model.Stop
import androidx.compose.ui.draw.clip
import com.example.travellink_ai.ui.planning.DayPlanner
import com.example.travellink_ai.data.weather.CwaWeatherService
import com.example.travellink_ai.data.weather.WeatherInfo
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.util.notifyLeftWithoutCheckIn
import com.example.travellink_ai.util.notifyNearbyStop
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource

/** 距景點多近（公尺）視為「已到達附近」，打卡按鈕高亮 */
internal const val NEAR_STOP_METERS = 300.0

/**
 * 打卡時超過這個距離才跳確認。比「到達附近」寬，是因為 GPS 會飄——
 * 網頁的門檻是 300m＋定位精度（最多 400m），這裡取中間值，避免真的到場卻被問。
 */
private const val CHECKIN_CONFIRM_METERS = 500.0

/**
 * 行程進行中的畫面資料與動作。由 [rememberOngoingTrip] 產生，交給行程頁的 LazyColumn
 * 用 [ongoingTripItems] 畫出來（對齊網頁：開始行程後不換頁，同一頁切成打卡模式）。
 */
class OngoingTripUi internal constructor(
    internal val stops: List<Stop>,
    internal val progress: TripProgress,
    internal val derived: TripDerived,
    internal val transitTimes: List<Long>,
    internal val conflicts: List<ScheduleConflict>,
    internal val here: HereStop?,
    internal val endTimeText: String?,
    internal val weather: WeatherInfo?,
    internal val returnTrains: com.example.travellink_ai.data.transit.ReturnTrainPlan?,
    internal val islandOfTrip: com.example.travellink_ai.data.island.IslandProfile?,
    internal val ferrySchedules: com.example.travellink_ai.data.island.FerrySchedules,
    internal val tripStartDate: String,
    internal val hasLocationPermission: Boolean,
    internal val isNearCurrent: Boolean,
    internal val showParking: Boolean,
    internal val completedExpanded: Boolean,
    internal val parkingDistanceText: String?,
    internal val onSetLeave: (HereStop, Int) -> Unit,
    internal val onDismissConflict: (ScheduleConflict) -> Unit,
    internal val onToggleCompleted: () -> Unit,
    internal val onOpenStop: (String) -> Unit,
    internal val onNavigate: (Stop) -> Unit,
    internal val onCheckIn: (Stop) -> Unit,
    internal val onSkip: (Stop) -> Unit,
    internal val onUnskip: (Stop) -> Unit,
    internal val onRecordParking: () -> Unit,
    internal val onToilets: () -> Unit,
    internal val onParkingNavigate: () -> Unit,
    internal val onParkingMap: () -> Unit,
    internal val onParkingRelease: () -> Unit,
    internal val onOpenPermissionSettings: () -> Unit,
    /** 🧪 demo 工具（開發版且開著 demo 模式才有） */
    internal val demo: DemoToolsUi? = null,
    /** 結束行程：跳出完成對話框（網頁不必全部打卡完也能結束） */
    val requestFinish: () -> Unit,
    /** 回程車站會晚到時，點紅字請 AI 處理延誤（AMD 模式、可以調整這份行程時才有） */
    internal val onAskAiForLate: (() -> Unit)? = null
)

/**
 * 行程進行中的狀態、GPS、到站通知與對話框。只在行程進行中時呼叫——
 * 一進來就會要定位權限、開始接收 GPS。
 */
@Composable
fun rememberOngoingTrip(
    viewModel: ItineraryViewModel,
    tripVm: TripProgressViewModel
): OngoingTripUi {
    val itinerary     by viewModel.itinerary.collectAsState()
    // AMD 模式：行程頁登記了行程（非唯讀成員）才有，回程車站晚到時可以直接請 AI 處理
    val agentVm: com.example.travellink_ai.ui.agent.AgentViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val agentInfo     by agentVm.hostInfo.collectAsState()
    val transitTimes  by viewModel.transitTimes.collectAsState()
    val stopLocations by viewModel.stopLocations.collectAsState()
    val transportMode by viewModel.transportMode.collectAsState()
    val progress      by tripVm.progress.collectAsState()

    val stops = itinerary?.stops ?: emptyList()
    val derived = remember(stops, progress, transitTimes) {
        deriveTripState(stops, progress, transitTimes)
    }

    // 預計回到出發車站的時間（deriveTripState 已含回程站 estimate）
    val endTimeText = remember(stops, derived) {
        stops.lastOrNull { it.isStation }?.let { rs ->
            (derived.estimatedTimes[rs.stopId] ?: parseClockToMinutes(rs.time))?.let { fmtClock(it) }
        }
    }
    // 時間衝突：用預計時間檢查營業時間、收工、末班船、回程火車（第 4 項）
    val scheduleConflicts = rememberScheduleConflicts(viewModel, tripVm, stops, progress, derived)

    // 🚆 回程班次（A7 ②）：抵達車站的推估時間會隨打卡/跳過/預計離開浮動，變動時重查班次
    val returnTrains by viewModel.returnTrains.collectAsState()
    LaunchedEffect(endTimeText) {
        if (endTimeText != null) viewModel.loadReturnTrains(endTimeText)
    }
    // 即時誤點：進行中每分鐘更新一次（TraService 另有 30 秒快取擋住重複請求）
    LaunchedEffect(returnTrains?.stationId) {
        while (returnTrains != null) {
            viewModel.refreshReturnTrainDelays()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    // 已完成站摺疊狀態
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    // 距離太遠時的打卡確認（站, 公尺）
    var farCheckIn by remember { mutableStateOf<Pair<Stop, Double>?>(null) }

    // A5：離島行程改看船班。船班表是 assets 靜態檔，讀一次即可
    val tripCtx = LocalContext.current
    val ferrySchedules = remember { com.example.travellink_ai.data.island.FerrySchedules.get(tripCtx.applicationContext) }
    val islandOfTrip = remember(itinerary?.region) {
        com.example.travellink_ai.data.island.IslandRegistry.byDestination(itinerary?.region)
    }

    // 行程起始日（多日行程用它推算第 N 天的日期）
    val tripStartDate = remember(itinerary?.days) {
        itinerary?.days?.substringBefore(" ")?.takeIf { it.length == 10 } ?: ""
    }

    // ☀️ 天氣（CWA）：多日行程要查「今天那一天」而不是出發日，
    //    否則第二天看到的是昨天的預報。景點也取當日的第一站。
    var weather by remember { mutableStateOf<WeatherInfo?>(null) }
    LaunchedEffect(stops.size, stopLocations, derived.currentDayIndex, tripStartDate) {
        val spot = stops.firstOrNull { !it.isStation && it.dayIndex == derived.currentDayIndex }
            ?: stops.firstOrNull { !it.isStation } ?: return@LaunchedEffect
        val ll = (spot.lat?.let { la -> spot.lng?.let { lo -> LatLng(la, lo) } })
            ?: stopLocations[spot.name] ?: return@LaunchedEffect
        if (tripStartDate.isEmpty()) return@LaunchedEffect
        val date = DayPlanner.addDays(tripStartDate, derived.currentDayIndex - 1)
        weather = try { CwaWeatherService.fetchForecast(ll.latitude, ll.longitude, date) } catch (e: Exception) { null }
    }

    // ── GPS：接近目前站 300m 內打卡按鈕高亮 ─────────────────────
    val context = LocalContext.current
    var realLocation by remember { mutableStateOf<LatLng?>(null) }
    // 🧪 Debug-only 假 GPS（demo 用）：狀態掛在 tripVm 上（見該檔案註解），畫面只負責讀寫
    val simulatedLocation by tripVm.locationSimulator.location.collectAsState()
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocationPermission = result.values.any { it } }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    val loc = LatLng(it.latitude, it.longitude)
                    realLocation = loc
                    // 鏡射到 tripVm：站點詳細頁沒有自己的 GPS 訂閱，打卡距離檢查要靠這份最後已知位置
                    tripVm.lastKnownLocation = loc
                }
            }
        }
        try {
            fusedClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        } catch (_: SecurityException) { }
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    fun coordOf(stop: Stop): LatLng? =
        if (stop.lat != null && stop.lng != null) LatLng(stop.lat, stop.lng)
        else stopLocations[stop.name]

    // 🧪 demo 開關切換／打卡推進到下一站時：從「上一站」模擬移動到「目前站」就停在那，
    //   不會整條路線一次跑完──這樣使用者才有時間按「到達打卡」，跟真的開車過去等紅燈再下車一樣。
    //   沿行程已查好的道路路線走（不是直線），整段 10 秒；路線還沒載入就先走直線，載入後重跑一次。
    //   已打卡的站（here）一律停在那站，預計離開時間到了也不自動開走，要按 Demo 工具的「離開本站」才走：
    //   過去時鐘一到就開往下一站，按「到預計離開」測離開提醒、延誤處理時人已經不在那站了。
    val roadSegments by viewModel.roadSegments.collectAsState()
    val demoOffset by com.example.travellink_ai.debug.DemoClock.offsetMs.collectAsState()
    var demoReplay by remember { mutableIntStateOf(0) }   // demo 工具「重跑到目前站」
    val demoHere = derived.activeHere(progress)
    val demoHereAt = demoHere?.let { progress.checkIns[it.stop.stopId]?.at }
    // 不以時鐘當 key：快轉時間不該把已經按「離開本站」開走的 GPS 拉回原站
    LaunchedEffect(tripVm.demoModeOn, derived.currentStop?.stopId, derived.currentDayIndex, stops,
        roadSegments.isNotEmpty(), demoReplay, demoHere?.stop?.stopId) {
        if (!(BuildConfig.DEBUG && tripVm.demoModeOn)) {
            tripVm.locationSimulator.stop()
            return@LaunchedEffect
        }
        if (demoHere != null && demoHereAt != null) {
            android.util.Log.d("DemoGps", "stay at ${demoHere.stop.name}（按「離開本站」才往下一站）")
            tripVm.locationSimulator.start(listOfNotNull(coordOf(demoHere.stop)))
            return@LaunchedEffect
        }
        val target = derived.currentStop
        if (target == null) {
            tripVm.locationSimulator.stop()
            return@LaunchedEffect
        }
        // 起點＝整趟行程中的前一站：當天第一站就從前一天最後一站（或住宿）出發，不會原地不動
        val ordered = stops.sortedWith(compareBy<Stop>({ it.dayIndex }, { it.order }))
        val anchor = ordered.getOrNull(ordered.indexOfFirst { it.stopId == target.stopId } - 1) ?: target
        val from = coordOf(anchor)
        val to = coordOf(target)
        val route = if (from != null && to != null)
            com.example.travellink_ai.debug.LocationSimulator.roadPath(roadSegments, from, to)
        else listOfNotNull(from, to)
        android.util.Log.d("DemoGps", "drive ${anchor.name} -> ${target.name} (${route.size} pts)")
        tripVm.locationSimulator.start(route)
    }
    /**
     * demo 工具「離開本站」：
     * - 還在已打卡的站：沿路開到下一站（還沒打卡的那站）就停，等使用者按「到達打卡」
     * - 已經在下一站附近但還沒打卡：沿往再下一站的路走約 700m 就停，用來觸發「忘記打卡了嗎？」
     */
    fun demoLeaveCurrentStop() {
        val cur = derived.currentStop ?: return
        val curCoord = coordOf(cur) ?: return
        val hereCoord = demoHere?.takeIf { demoHereAt != null }?.let { coordOf(it.stop) }
        val loc = simulatedLocation
        if (hereCoord != null && (loc == null || haversineMeters(loc, curCoord) > NEAR_STOP_METERS)) {
            android.util.Log.d("DemoGps", "leave ${demoHere?.stop?.name} -> ${cur.name}")
            tripVm.locationSimulator.start(
                com.example.travellink_ai.debug.LocationSimulator.roadPath(roadSegments, hereCoord, curCoord))
            return
        }
        val dayStops = stops.filter { it.dayIndex == derived.currentDayIndex }.sortedBy { it.order }
        val next = dayStops.getOrNull(dayStops.indexOfFirst { it.stopId == cur.stopId } + 1)?.let { coordOf(it) }
        val path = if (next != null)
            com.example.travellink_ai.debug.LocationSimulator.roadPath(roadSegments, curCoord, next)
        else listOf(curCoord, LatLng(curCoord.latitude + 0.0065, curCoord.longitude))   // 沒有下一站：往北約 700m
        tripVm.locationSimulator.start(
            com.example.travellink_ai.debug.LocationSimulator.truncate(path, 700.0), durationMs = 5_000L)
    }
    val userLocation = if (BuildConfig.DEBUG && tripVm.demoModeOn) simulatedLocation else realLocation

    // ── 🅿 記錄停車點：按下當下抓一次高精度 GPS，再導向地圖頁 ──────────
    val fusedOneShot = remember { LocationServices.getFusedLocationProviderClient(context) }
    fun openMap(mode: String) {
        tripVm.mapMode = mode
        viewModel.currentScreen = "trip_map"
    }
    fun openParkingMap(latLng: LatLng, accuracy: Float) {
        // 停車點以「目前站」為 key（供日後 per-spot 眾包）；無目前站時退回首個景點
        val stopId = derived.currentStop?.stopId
            ?: stops.firstOrNull { !it.isStation }?.stopId
            ?: "trip"
        tripVm.setParking(stopId, latLng.latitude, latLng.longitude, accuracy)
        openMap("parking")
    }
    fun recordParkingHere() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val cts = CancellationTokenSource()
        try {
            fusedOneShot.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    when {
                        loc != null -> openParkingMap(LatLng(loc.latitude, loc.longitude), loc.accuracy)
                        userLocation != null -> openParkingMap(userLocation, 0f)  // 退回串流定位
                        else -> derived.currentStop?.let { coordOf(it) }?.let { openParkingMap(it, 0f) } // 最後退回目前站座標
                    }
                }
                .addOnFailureListener {
                    userLocation?.let { openParkingMap(it, 0f) }
                }
        } catch (_: SecurityException) {
            userLocation?.let { openParkingMap(it, 0f) }
        }
    }

    val isNearCurrent = remember(userLocation, derived.currentStop) {
        val cur = derived.currentStop ?: return@remember false
        val target = coordOf(cur) ?: return@remember false
        val loc = userLocation ?: return@remember false
        haversineMeters(loc, target) <= NEAR_STOP_METERS
    }

    // 📍 到站本地通知 + 離開未打卡提醒：靠近時通知一次；之後沒打卡/跳過就離開範圍時再提醒一次
    var lastNearStopId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isNearCurrent, derived.currentStop?.stopId) {
        val cur = derived.currentStop
        if (isNearCurrent && cur != null) {
            notifyNearbyStop(context, cur.name, viewModel.currentFirestoreDocIdPublic)
            lastNearStopId = cur.stopId
        } else {
            val leftId = lastNearStopId
            if (leftId != null) {
                stops.firstOrNull { it.stopId == leftId }?.let { left ->
                    if (!progress.isDone(leftId)) notifyLeftWithoutCheckIn(context, left.name, viewModel.currentFirestoreDocIdPublic)
                }
            }
            lastNearStopId = null
        }
    }

    // （原本 demo 模式到站 30 秒沒打卡會自動跳過；2026-10-01 拿掉：它會在講解時把目前站跳掉、
    //   假 GPS 隨即開往下一站，沒辦法展示預計離開。要展示「忘記打卡」改用 Demo 工具的「離開本站」）

    // ── 打卡：跳到這站的景點資訊頁（目前站＝剛打卡的站），拍照提示與調整離開時間都在那頁。
    //    最後一站也一樣：過去打完最後一站就直接跳「行程完成」，人還在景點、還沒拍照就被問要不要結束；
    //    現在要按行程頁的「🎉 完成行程」才出現 ──
    fun doCheckIn(stop: Stop) {
        tripVm.checkIn(stop.stopId)
        tripVm.justCheckedInStopId = stop.stopId
        tripVm.selectedStopId = stop.stopId
        viewModel.currentScreen = "stop_detail"
    }
    fun requestCheckIn(stop: Stop) {
        val target = coordOf(stop)
        val loc = userLocation
        // 沒有定位（權限被拒、還沒定到位）就照舊手動打卡，不擋人——和網頁相同
        if (target != null && loc != null) {
            val dist = haversineMeters(loc, target)
            if (dist > CHECKIN_CONFIRM_METERS) {
                farCheckIn = stop to dist
                return
            }
        }
        doCheckIn(stop)
    }

    // ── 對話框 ────────────────────────────────────────────────
    farCheckIn?.let { (stop, dist) ->
        AlertDialog(
            onDismissRequest = { farCheckIn = null },
            title = { Text("確定要打卡嗎？", fontWeight = FontWeight.Bold) },
            text = { Text("📍 你距離 ${stop.name} 還有 ${formatDistance(dist)}。") },
            confirmButton = {
                TextButton(onClick = { farCheckIn = null; doCheckIn(stop) }) {
                    Text("仍要打卡", color = DesignTokens.Accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { farCheckIn = null }) { Text("取消") } }
        )
    }

    if (showFinishDialog) {
        val elapsedMin = ((com.example.travellink_ai.debug.DemoClock.now() - progress.startedAt) / 60_000L).toInt()
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text(if (derived.allDone) "🎉 行程完成！" else "🏁 要結束行程嗎？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("走訪 ${progress.checkIns.size} 個景點" +
                        (if (progress.skips.isNotEmpty()) "、跳過 ${progress.skips.size} 站" else ""))
                    if (!derived.allDone) {
                        Text("還有 ${derived.totalCount - derived.doneCount} 站沒去，結束後就不能再打卡。",
                            fontSize = 14.sp, color = DesignTokens.Ink2)
                    }
                    if (progress.startedAt > 0 && elapsedMin in 1..1440) {
                        Text("總時長約 ${elapsedMin / 60} 小時 ${elapsedMin % 60} 分",
                            fontSize = 14.sp, color = DesignTokens.Ink2)
                    }
                }
            },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 寫旅遊回憶（W4 C6）：完成並發通知提醒，直接進回憶頁
                    Button(
                        onClick = {
                            showFinishDialog = false
                            tripVm.finishTrip(notifyFeedback = true)
                            viewModel.openMemoryForCurrentTrip()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent2)
                    ) { Text("📸 製作旅程回憶") }
                    Button(
                        onClick = {
                            showFinishDialog = false
                            // 直接進回饋頁填寫，不用再發通知提醒
                            tripVm.finishTrip(notifyFeedback = false)
                            viewModel.currentScreen = "feedback"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                    ) { Text("填寫回饋") }
                    TextButton(onClick = {
                        showFinishDialog = false
                        // 之後再填：發通知提醒
                        tripVm.finishTrip(notifyFeedback = true)
                        viewModel.currentScreen = "home"
                    }) { Text("完成並回首頁") }
                    TextButton(onClick = { showFinishDialog = false }) {
                        Text(if (derived.allDone) "稍後再說" else "繼續行程", color = DesignTokens.Ink3)
                    }
                }
            }
        )
    }

    // 🧪 demo 工具
    // 今天這一天的行程開始時間（第 N 天的日期＋當天第一站的排定時間，第 1 天就是出發時間）
    val demoDayStartMs = remember(stops, tripStartDate, derived.currentDayIndex) {
        val first = stops.filter { it.dayIndex == derived.currentDayIndex }.sortedBy { it.order }
            .firstNotNullOfOrNull { parseClockToMinutes(it.time) }
        val day = tripStartDate.takeIf { it.isNotEmpty() }?.let { DayPlanner.addDays(it, derived.currentDayIndex - 1) }
        val dayMs = day?.let {
            runCatching { java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).parse(it)?.time }.getOrNull()
        }
        if (first == null || dayMs == null) null else dayMs + first * 60_000L
    }
    // 打開 demo 模式時，時鐘直接設成今天的行程開始時間（已快轉過就不動）
    LaunchedEffect(tripVm.demoModeOn, demoDayStartMs) {
        if (BuildConfig.DEBUG && tripVm.demoModeOn && demoDayStartMs != null &&
            !com.example.travellink_ai.debug.DemoClock.isShifted()) {
            com.example.travellink_ai.debug.DemoClock.setTo(demoDayStartMs)
        }
    }
    val demoUi = if (BuildConfig.DEBUG && tripVm.demoModeOn) DemoToolsUi(
        offsetMs = demoOffset,
        canJumpToTripStart = demoDayStartMs != null,
        onJumpToTripStart = { demoDayStartMs?.let { com.example.travellink_ai.debug.DemoClock.setTo(it) } },
        canJumpToLeave = demoHere != null && demoHereAt != null,
        onAdvance = { com.example.travellink_ai.debug.DemoClock.advance(it * 60_000L) },
        onJumpToLeave = {
            if (demoHere != null && demoHereAt != null)
                com.example.travellink_ai.debug.DemoClock.jumpTo(startOfDayMs(demoHereAt) + demoHere.leaveMin * 60_000L)
        },
        onResetClock = { com.example.travellink_ai.debug.DemoClock.reset() },
        onReplayGps = { demoReplay++ },
        onLeaveStop = { demoLeaveCurrentStop() },
        onNotifyUpcoming = {
            com.example.travellink_ai.UpcomingTripReceiver().notify(
                context, itinerary?.let { it.title.ifBlank { it.aiTitle } } ?: "你的行程", true,
                viewModel.currentFirestoreDocIdPublic, "demo_upcoming".hashCode())
        }
    ) else null

    val activeCar = progress.activeParking?.second
    return OngoingTripUi(
        stops = stops,
        progress = progress,
        derived = derived,
        transitTimes = transitTimes,
        conflicts = scheduleConflicts,
        here = derived.activeHere(progress),
        endTimeText = endTimeText,
        weather = weather,
        returnTrains = returnTrains,
        islandOfTrip = islandOfTrip,
        ferrySchedules = ferrySchedules,
        tripStartDate = tripStartDate,
        hasLocationPermission = hasLocationPermission,
        isNearCurrent = isNearCurrent,
        showParking = transportMode == "car",
        completedExpanded = completedExpanded,
        parkingDistanceText = activeCar?.let { car ->
            userLocation?.let { "${haversineMeters(it, car.latLng).toInt()} m 外" } ?: "已記錄"
        },
        onSetLeave = { h, m -> tripVm.adjustLeave(h, m) },
        onDismissConflict = { tripVm.dismissConflict(it.key) },
        onToggleCompleted = { completedExpanded = !completedExpanded },
        onOpenStop = { stopId ->
            tripVm.selectedStopId = stopId
            viewModel.currentScreen = "stop_detail"
        },
        onNavigate = { stop -> launchStopNavigation(context, stop.name, coordOf(stop)) },
        onCheckIn = { requestCheckIn(it) },
        onSkip = { tripVm.skipStop(it.stopId) },
        onUnskip = { tripVm.unskipStop(it.stopId) },
        onRecordParking = { recordParkingHere() },
        onToilets = { openMap("toilets") },
        onParkingNavigate = { activeCar?.let { launchStopNavigation(context, "我的停車點", it.latLng) } },
        onParkingMap = { openMap("parking") },
        onParkingRelease = { progress.activeParking?.first?.let { tripVm.releaseParking(it) } },
        onOpenPermissionSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        },
        demo = demoUi,
        requestFinish = { showFinishDialog = true },
        onAskAiForLate = if (agentInfo?.hasDelay == true) ({
            viewModel.setAssistantOpen(true)
            agentVm.startDelay()
        }) else null
    )
}

/** 行程進行中的清單內容：日程主卡、尋車條、工具列、時間軸（含打卡與預計離開）、回程班次 */
fun LazyListScope.ongoingTripItems(ui: OngoingTripUi) {
    val progress = ui.progress
    val derived = ui.derived

    // ── 定位權限被拒：沒有它，打卡高亮/到站提醒/地圖都收不到 GPS ──
    if (!ui.hasLocationPermission) {
        item(key = "ongoing_permission") {
            LocationPermissionCard(ui.onOpenPermissionSettings)
            Spacer(Modifier.height(14.dp))
        }
    }

    // ── 日程主卡：進度環 + 回站時間 + 天氣 ─────────────────
    item(key = "ongoing_header") {
        DayProgressHeader(derived = derived, isCompleted = progress.isCompleted,
            endTimeText = ui.endTimeText, weather = ui.weather, here = derived.activeHere(progress))
        Spacer(Modifier.height(14.dp))
    }

    // ── 🧪 demo 工具（開發版＋demo 模式）──
    ui.demo?.let { demo ->
        item(key = "ongoing_demo") {
            DemoToolsCard(demo)
            Spacer(Modifier.height(14.dp))
        }
    }

    // ── ⚠️ 時間衝突（第 4、5 項）：照預計時間會撞到的限制＋處理選項 ──
    if (ui.conflicts.isNotEmpty()) {
        item(key = "ongoing_conflicts") {
            ScheduleConflictCard(
                conflicts = ui.conflicts,
                here = ui.here,
                stopDone = { progress.isDone(it) },
                onSetLeave = ui.onSetLeave,
                onSkip = { sid -> ui.stops.firstOrNull { it.stopId == sid }?.let(ui.onSkip) },
                onDismiss = ui.onDismissConflict
            )
            Spacer(Modifier.height(14.dp))
        }
    }

    // ── 🚗 尋車條（有生效停車點才顯示）────────────────────
    progress.activeParking?.second?.let { car ->
        item(key = "ongoing_parking") {
            ParkingFinderBar(
                distanceText = ui.parkingDistanceText ?: "已記錄",
                note = car.note,
                parkedAt = fmtClock(minutesOfDay(car.at)),
                onNavigate = ui.onParkingNavigate,
                onOpenMap = ui.onParkingMap,
                onRelease = ui.onParkingRelease
            )
            Spacer(Modifier.height(14.dp))
        }
    }

    // ── 🅿🚻 工具列 ──────────────────────────────────────
    item(key = "ongoing_tools") {
        TripToolRow(
            showParking = ui.showParking,
            hasParked = progress.activeParking != null,
            onParking = ui.onRecordParking,
            onToilets = ui.onToilets
        )
        Spacer(Modifier.height(8.dp))
    }

    // ── 站點時間軸：你在這裡（可調預計離開）→ 目前站（到達打卡）→ 後續站 ──
    itemsIndexedStops(ui)

    // ── 全部完成 → 完成行程 ───────────────────────────
    if (derived.allDone) item(key = "ongoing_finish") {
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = ui.requestFinish,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
        ) {
            Text("🎉 完成行程", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }

    // ── 末班船（A5）：抵港時間隨打卡浮動，趕不上就紅字 ──────
    ui.islandOfTrip?.let { isl ->
        item(key = "ongoing_ferry") {
            Spacer(Modifier.height(14.dp))
            LastFerryCard(isl, ui.ferrySchedules, ui.endTimeText)
        }
    }

    // ── 回程班次（A7 ②）：抵達時間隨進度浮動，含即時誤點 ──
    ui.returnTrains?.let { plan ->
        item(key = "ongoing_trains") {
            Spacer(Modifier.height(14.dp))
            LiveReturnTrainCard(plan)
        }
    }
}

/** 公尺 → 「850 公尺」「1.2 公里」 */
private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} 公尺" else "%.1f 公里".format(meters / 1000)

/** 自訂預計離開時間（Material3 TimePicker，24 小時制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveTimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val m = ((initialMinutes % 1440) + 1440) % 1440
    val state = rememberTimePickerState(initialHour = m / 60, initialMinute = m % 60, is24Hour = true)
    val picked = state.hour * 60 + state.minute
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("預計幾點離開？", fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Text("後續景點的預計時間會跟著調整，原本的規劃不會被改掉。",
                    fontSize = 12.sp, color = DesignTokens.Ink3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text("確定", color = DesignTokens.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


// ── 日程主卡：綠底 + 進度環 + 回站時間 + 天氣 ─────────────────────
@Composable
private fun DayProgressHeader(
    derived: TripDerived,
    isCompleted: Boolean,
    endTimeText: String?,
    weather: WeatherInfo?,
    here: HereStop? = null
) {
    val frac = if (derived.totalCount == 0) 0f else derived.doneCount.toFloat() / derived.totalCount
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DesignTokens.Accent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // 進度環
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
                    CircularProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.size(60.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${derived.doneCount}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("/${derived.totalCount}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 目前站＝最後打卡的站：「第 N 站 · 名稱」；還沒打卡時顯示要前往哪一站
                        val hereNo = here?.let { h -> derived.playable.indexOfFirst { it.stopId == h.stop.stopId } + 1 }
                        Text(
                            when {
                                derived.allDone -> "全部景點已完成"
                                here != null && hereNo != null && hereNo > 0 -> "第 $hereNo 站 · ${here.stop.name}"
                                else -> derived.currentStop?.let { "前往 ${it.name}" } ?: "行程進行中"
                            },
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        val badge = when {
                            isCompleted -> "已完成"
                            derived.delayMin > 30 -> "嚴重落後 ${derived.delayMin} 分"
                            derived.delayMin > 5  -> "落後 ${derived.delayMin} 分"
                            derived.delayMin < -5 -> "超前 ${-derived.delayMin} 分"
                            else -> "準時"
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = Color.White.copy(alpha = 0.2f)) {
                            Text(badge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    endTimeText?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("🏁 預計 $it 回到出發車站", fontSize = 13.sp, color = Color.White.copy(alpha = 0.92f))
                    }
                    weather?.let { w ->
                        Spacer(Modifier.height(2.dp))
                        val rain = if (w.rainProbability >= 0) " · 降雨 ${w.rainProbability}%" else ""
                        Text("${w.emoji} ${w.townshipName} ${w.maxTemp}° ${w.description}$rain",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.92f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}

// ── 🚗 尋車條：顯示距離 + 導航回車，點卡片進地圖看車位置 ──────────────
@Composable
private fun ParkingFinderBar(
    distanceText: String,
    note: String,
    parkedAt: String,
    onNavigate: () -> Unit,
    onOpenMap: () -> Unit,
    onRelease: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMap),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, DesignTokens.Accent.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🚗", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("你的車在 $distanceText", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                val sub = listOfNotNull(note.ifBlank { null }, "$parkedAt 停放").joinToString(" · ")
                Text(sub, fontSize = 12.sp, color = DesignTokens.Ink3)
                // 取車後收起尋車條（對齊網頁「我已取車」）
                Text("我已取車", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Accent,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onRelease))
            }
            OutlinedButton(
                onClick = onNavigate,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                border = BorderStroke(1.dp, DesignTokens.Accent)
            ) {
                Icon(Icons.Default.Navigation, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("導航回車", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── 🅿🚻 工具列：記停車點（自駕才顯示）／附近廁所，皆導向獨立地圖頁 ──────
@Composable
private fun TripToolRow(
    showParking: Boolean,
    hasParked: Boolean,
    onParking: () -> Unit,
    onToilets: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showParking) {
            OutlinedButton(
                onClick = onParking,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                border = BorderStroke(1.dp, DesignTokens.Border)
            ) {
                Text("🅿", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(if (hasParked) "更新停車點" else "記停車點",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(
            onClick = onToilets,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
            border = BorderStroke(1.dp, DesignTokens.Border)
        ) {
            Text("🚻", fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text("附近廁所", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── 時間軸：目前站（最後打卡）→ 下一站（到達打卡）→ 後續站；已完成站可摺疊（目前站永遠顯示）──
private fun LazyListScope.itemsIndexedStops(ui: OngoingTripUi) {
    val stops = ui.stops
    val progress = ui.progress
    val derived = ui.derived
    val completedExpanded = ui.completedExpanded
    val currentId = if (progress.isOngoing) derived.currentStop?.stopId else null
    val here = derived.activeHere(progress)
    val hereId = here?.stop?.stopId
    fun isFolded(s: Stop) = !completedExpanded && !s.isStation && progress.isDone(s.stopId) && s.stopId != hereId
    val foldedCount = stops.count { !it.isStation && progress.isDone(it.stopId) && it.stopId != hereId }

    // 已完成摺疊入口
    if (foldedCount > 0) {
        item(key = "completed_summary") {
            CompletedSummaryRow(count = foldedCount, expanded = completedExpanded, onToggle = ui.onToggleCompleted)
        }
    }

    // 多日：哪幾天在清單裡還有看得到的列（全部完成又摺疊起來的那一天就不放分隔）
    val visibleDays = if (derived.dayCount <= 1) emptySet() else stops.filterNot { isFolded(it) }
        .map { it.dayIndex }.toSet()
    var lastDayEmitted = 0

    stops.forEachIndexed { index, stop ->
        // 分隔要在「該日第一列之前」插入，所以放在下面的 return 之前判斷
        if (stop.dayIndex != lastDayEmitted && stop.dayIndex in visibleDays) {
            lastDayEmitted = stop.dayIndex
            val day = stop.dayIndex
            item(key = "day_divider_$day") {
                DayDividerRow(
                    dayIndex = day,
                    date = if (ui.tripStartDate.length == 10) DayPlanner.addDays(ui.tripStartDate, day - 1) else "",
                    isCurrentDay = day == derived.currentDayIndex && progress.isOngoing
                )
            }
        }
        if (isFolded(stop)) return@forEachIndexed

        item(key = stop.stopId) {
            // 與上一站的車程 chip
            if (index > 0) {
                val legMins = ui.transitTimes.getOrNull(index - 1)
                Row(
                    modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "︙ " + (legMins?.let { "車程約 $it 分" } ?: ""),
                        fontSize = 12.sp, color = DesignTokens.Ink3
                    )
                }
            }
            when {
                stop.isStation ->
                    StationRow(stop, isReturn = index > 0, estimate = derived.estimatedTimes[stop.stopId], onAskAi = ui.onAskAiForLate)
                here != null && stop.stopId == hereId -> HereStopCard(
                    here = here,
                    checkIn = progress.checkIns[stop.stopId],
                    onOpenInfo = { ui.onOpenStop(stop.stopId) }
                )
                stop.stopId == currentId -> NextStopCard(
                    stop = stop,
                    label = when {
                        here != null -> "下一站"
                        derived.doneCount == 0 -> "第一站"
                        derived.dayCount > 1 -> "今天第一站"   // 多日行程過夜、今天還沒打卡
                        else -> "下一站"
                    },
                    estimate = derived.estimatedTimes[stop.stopId],
                    isNear = ui.isNearCurrent,
                    warning = ui.conflicts.firstOrNull { it.stopId == stop.stopId }?.message,
                    onOpenInfo = { ui.onOpenStop(stop.stopId) },
                    onCheckIn = { ui.onCheckIn(stop) },
                    onNavigate = { ui.onNavigate(stop) },
                    onSkip = { ui.onSkip(stop) }
                )
                else -> TimelineStopRow(
                    stop = stop,
                    checkIn = progress.checkIns[stop.stopId],
                    isSkipped = stop.stopId in progress.skips,
                    estimate = derived.estimatedTimes[stop.stopId],
                    warning = ui.conflicts.firstOrNull { it.stopId == stop.stopId }
                        ?.let { if (it.isInfo) "⏰ 會早到，要等開門" else "⚠️ 照目前時間可能趕不上" },
                    onClick = { ui.onOpenStop(stop.stopId) },
                    onUnskip = { ui.onUnskip(stop) }
                )
            }
        }
    }
}

/**
 * 末班船卡（A5 Stage 5）。與 A7 的即時班次卡不同：船班是人工抄的靜態表，
 * 沒有即時誤點可查，所以這張卡的價值在於「照目前進度趕不趕得上」。
 *
 * [arriveAtPortHhmm] 來自 deriveTripState 對回程港口的推估，會隨打卡浮動。
 */
@Composable
private fun LastFerryCard(
    island: com.example.travellink_ai.data.island.IslandProfile,
    schedules: com.example.travellink_ai.data.island.FerrySchedules,
    arriveAtPortHhmm: String?
) {
    val route = schedules.routeForIsland(island.code) ?: return
    val season = schedules.takeIf { !it.isStale() }?.let { route.activeSeason } ?: return
    val last = season.lastDeparture("island") ?: return
    val next = arriveAtPortHhmm?.let { season.nextDeparture("island", it) }
    val willMiss = arriveAtPortHhmm != null && next == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (willMiss) Color(0xFFFDECEA) else Color(0xFFF0F7F5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⛴️", fontSize = 18.sp)
                Text(if (willMiss) "趕不上末班船" else "回程船班",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (willMiss) Color(0xFFD32F2F) else DesignTokens.Ink)
            }
            Spacer(Modifier.height(6.dp))
            when {
                willMiss -> Text(
                    "照目前進度預計 $arriveAtPortHhmm 回到${route.islandPort}，" +
                        "已晚於末班 $last。請跳過幾站或改訂隔日船班。",
                    fontSize = 13.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
                next != null -> Text(
                    "預計 $arriveAtPortHhmm 回到${route.islandPort}，可搭 $next 這班（末班 $last）",
                    fontSize = 13.sp, color = DesignTokens.Ink2)
                else -> Text("當日末班 $last 由${route.islandPort}開航",
                    fontSize = 13.sp, color = DesignTokens.Ink2)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                (if (season.isOfficial) "" else "時刻為代訂平台彙整、非官方公告。") +
                    "實際以船公司公佈為準，天候不佳可能停航。",
                fontSize = 11.sp, color = DesignTokens.Ink3)
            route.contactable.firstOrNull()?.let {
                Text("☎ ${it.name} ${it.phone}", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink2)
            }
        }
    }
}

/**
 * 多日行程的日分隔列（A5 Stage 3）。單日行程不會出現。
 *
 * 標「今天」是因為時間軸只有 HH:mm，沒有日期——兩天一夜的清單裡兩個 09:00
 * 長得一模一樣，不指出哪一天是今天，使用者會分不清現在該走哪一段。
 */
@Composable
private fun DayDividerRow(dayIndex: Int, date: String, isCurrentDay: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Day $dayIndex",
            fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
            color = if (isCurrentDay) DesignTokens.Accent else DesignTokens.Ink2
        )
        if (date.isNotBlank()) {
            Text(date.replace('/', '.').removePrefix("20"), fontSize = 13.sp, color = DesignTokens.Ink3)
        }
        if (isCurrentDay) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DesignTokens.AccentLight)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text("今天", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesignTokens.AccentDark)
            }
        }
        HorizontalDivider(Modifier.weight(1f), color = DesignTokens.Border)
    }
}

// 已完成站摺疊入口列
@Composable
private fun CompletedSummaryRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            null, Modifier.size(20.dp), tint = DesignTokens.Ink3
        )
        Text("已完成 $count 站", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink2)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "收合" else "展開", fontSize = 12.sp, color = DesignTokens.Ink3)
    }
}

/**
 * 行程進行中的回程班次卡（A7 ②）。
 *
 * 與預覽頁的差別：抵達車站時間吃 deriveTripState 的即時推估（打卡落後、跳過站都會反映），
 * 並顯示 TDX StationLiveBoard 的即時誤點。查不到班次時整張卡不顯示。
 */
// internal 而非 private：同模組的 androidTest 需要直接渲染這張卡來驗版面
@Composable
internal fun LiveReturnTrainCard(
    plan: com.example.travellink_ai.data.transit.ReturnTrainPlan
) {
    // 抵站時間跟「還能搭的那班車」的發車時間差距很小（含已誤點）時，卡片轉紅提醒要加緊腳步
    // recommended 本身已經內扣 10 分鐘走進站緩衝（見 ReturnTrainPlan 註解），這裡的門檻是在那之上再抓緊
    val marginMin = remember(plan) {
        val arrive = parseClockToMinutes(plan.arriveTime)
        val depart = plan.recommended?.departureTime?.let { parseClockToMinutes(it) }
        val delay = plan.recommended?.delayMins ?: 0
        if (arrive != null && depart != null) (depart + delay) - arrive else null
    }
    val isUrgent = marginMin != null && marginMin <= 15
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isUrgent) DesignTokens.RedLight else Color.White),
        border = if (isUrgent) BorderStroke(1.5.dp, DesignTokens.Red) else null,
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚆", fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Text("回程班次", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                    color = DesignTokens.Ink)
                if (isUrgent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (marginMin!! < 0) "可能來不及" else "快來不及了",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Red
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("依目前進度，約 ${plan.arriveTime} 抵達${plan.stationName}")
                    if (plan.finalDestination.isNotBlank()) append("，往${plan.finalDestination}")
                },
                fontSize = 12.sp, color = DesignTokens.Ink2
            )

            Spacer(Modifier.height(10.dp))
            plan.departures.take(3).forEachIndexed { i, t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            t.departureTime,
                            fontSize = if (i == 0) 18.sp else 15.sp,
                            fontWeight = if (i == 0) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (i == 0) DesignTokens.Accent2 else DesignTokens.Ink
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${t.trainType} ${t.trainNo}", fontSize = 13.sp,
                                color = DesignTokens.Ink)
                            Text(
                                buildString {
                                    append("${t.directionLabel}・往${t.destinationName}")
                                    t.arrivalTime?.let { append("・$it 抵達") }
                                },
                                fontSize = 11.sp, color = DesignTokens.Ink2
                            )
                        }
                    }
                    t.delayMins?.let { d ->
                        if (d > 0) {
                            Text("誤點 $d 分", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = DesignTokens.Red)
                        } else {
                            Text("準點", fontSize = 12.sp, color = DesignTokens.Ink3)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text("誤點資訊每分鐘更新・來源 交通部 TDX", fontSize = 10.sp, color = DesignTokens.Ink3)
        }
    }
}

/** 回程車站晚於這個分鐘數才用紅字提醒（排程本來就有幾分鐘誤差，同收工提醒） */
private const val RETURN_LATE_GRACE_MIN = 5

/**
 * 出發／回程車站與住宿站。回程車站的時間是固定的（要搭的車），多待之後晚到的是人、不是車站：
 * 過去寫「預計 16:50（原 15:46）」，看起來像車站時間被往後拖，和「回程車站的時間不動」對不上。
 */
@Composable
private fun StationRow(stop: Stop, isReturn: Boolean, estimate: Int?, onAskAi: (() -> Unit)? = null) {
    val scheduled = parseClockToMinutes(stop.time)
    val returnStation = isReturn && !stop.isLodging
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 住宿站（入住、飯店早餐）也是 isStation，但它在行程中間，不是出發／回程車站
            Text(if (stop.isLodging) stop.emoji else "🚉", fontSize = 18.sp)
            Text(stop.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink2)
            Spacer(Modifier.weight(1f))
            val timeText = when {
                // 入住 16:40；休息站的說明帶括號（「休息・退房（約 10:00 離開）」），時間要插在括號前
                stop.isLodging && scheduled != null ->
                    "${stop.desc.substringBefore("（")} ${fmtClock(scheduled)}${stop.desc.substringAfter("（", "").let { if (it.isEmpty()) "" else "（$it" }}"
                returnStation && scheduled != null -> "${fmtClock(scheduled)} 固定"
                scheduled != null -> (if (isReturn) "預計 " else "出發 ") + fmtClock(scheduled)
                else -> stop.time
            }
            Text(timeText, fontSize = 13.sp, color = DesignTokens.Ink3)
        }
        if (returnStation && estimate != null && scheduled != null && estimate != scheduled) {
            val late = estimate - scheduled
            if (late > RETURN_LATE_GRACE_MIN) {
                Text(
                    "照目前進度約 ${fmtClock(estimate)} 才到，晚 $late 分" + if (onAskAi != null) "　🤖 請 AI 處理 ›" else "",
                    fontSize = 12.sp, color = DesignTokens.Red, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                        .then(if (onAskAi != null) Modifier.clickable(onClick = onAskAi) else Modifier)
                )
            } else if (late < 0) {
                Text(
                    "照目前進度約 ${fmtClock(estimate)} 到",
                    fontSize = 12.sp, color = DesignTokens.Ink3,
                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                )
            }
        }
    }
}

// 下一站（還沒打卡的第一站）：直接在卡片上到達打卡；打卡後它就變成目前站
@Composable
private fun NextStopCard(
    stop: Stop,
    label: String,
    estimate: Int?,
    isNear: Boolean,
    warning: String?,
    onOpenInfo: () -> Unit,
    onCheckIn: () -> Unit,
    onNavigate: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onOpenInfo),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (isNear) DesignTokens.Accent else DesignTokens.Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stop.emoji, fontSize = 26.sp)
                Column(Modifier.weight(1f)) {
                    Text("$label · ${stop.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = DesignTokens.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val scheduled = parseClockToMinutes(stop.time)
                    val timeText = when {
                        estimate != null && scheduled != null && estimate != scheduled ->
                            "預計 ${fmtClock(estimate)} 抵達（原 ${fmtClock(scheduled)}）"
                        scheduled != null -> "預計 ${fmtClock(scheduled)} 抵達"
                        else -> ""
                    }
                    if (timeText.isNotBlank()) {
                        Text(timeText, fontSize = 13.sp, color = DesignTokens.AccentDark, fontWeight = FontWeight.SemiBold)
                    }
                    Text("⏱ 停留 ${stop.duration} 分", fontSize = 12.sp, color = DesignTokens.Ink2)
                }
            }

            // ⏰ 這站照預計時間會撞到的營業時間（處理選項在上方的衝突卡）
            warning?.let {
                Spacer(Modifier.height(10.dp))
                WarnLine(it)
            }
            if (isNear) {
                Spacer(Modifier.height(8.dp))
                Text("📍 已到達附近，按「到達打卡」吧！", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, color = DesignTokens.StatusOngoingText)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onCheckIn,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isNear) DesignTokens.Accent else DesignTokens.AccentDark
                    )
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("到達打卡", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                HeroIconButton(Icons.Default.Navigation, "導航", onNavigate)
                TextButton(onClick = onSkip, modifier = Modifier.height(44.dp)) {
                    Text("跳過", fontSize = 14.sp, color = DesignTokens.Ink3)
                }
            }
        }
    }
}

/**
 * 目前站（最後打卡的站，要等下一站打卡才往前移）：綠框高亮，顯示到達與預計離開。
 * 預計離開的調整在景點資訊頁，這裡點整張卡或「預計離開 ›」進去。
 */
@Composable
private fun HereStopCard(
    here: HereStop,
    checkIn: CheckInRecord?,
    onOpenInfo: () -> Unit
) {
    val stop = here.stop
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onOpenInfo),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DesignTokens.AccentLight.copy(alpha = 0.45f)),
        border = BorderStroke(1.5.dp, DesignTokens.Accent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stop.emoji, fontSize = 28.sp)
                Column(Modifier.weight(1f)) {
                    Text("📍 目前站 · ${stop.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = DesignTokens.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${fmtClock(here.arrivedMin)} 到達" +
                            (checkIn?.displayName?.let { "（$it 打卡）" } ?: "") +
                            " · 原定停留 ${stop.duration} 分",
                        fontSize = 12.sp, color = DesignTokens.Ink2
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("預計離開", fontSize = 13.sp, color = DesignTokens.Ink2)
                Spacer(Modifier.width(8.dp))
                Text(fmtClock(here.leaveMin), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    color = DesignTokens.AccentDark)
                if (here.isAdjusted) {
                    Spacer(Modifier.width(6.dp))
                    Text("原 ${fmtClock(here.defaultLeaveMin)}", fontSize = 12.sp, color = DesignTokens.Ink3,
                        textDecoration = TextDecoration.LineThrough)
                }
                Spacer(Modifier.weight(1f))
                Text("調整 ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Accent)
            }
        }
    }
}

/**
 * 預計離開時間的調整區（景點資訊頁用）：−15／+15／+30／+60／自訂、恢復原定。
 * 只寫 tripProgress.leaveAt，原規劃的停留時間不變；後續站的預計時間跟著順延。
 */
@Composable
internal fun LeaveTimeSection(
    here: HereStop,
    downstreamShift: Int,
    onSetLeave: (Int) -> Unit,
    onReset: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        LeaveTimePickerDialog(
            initialMinutes = here.leaveMin,
            onConfirm = { onSetLeave(it); showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🕒 預計離開", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
            Spacer(Modifier.width(10.dp))
            Text(fmtClock(here.leaveMin), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = DesignTokens.AccentDark)
            if (here.isAdjusted) {
                Spacer(Modifier.width(6.dp))
                Text("原 ${fmtClock(here.defaultLeaveMin)}", fontSize = 12.sp, color = DesignTokens.Ink3,
                    textDecoration = TextDecoration.LineThrough)
            }
            Spacer(Modifier.weight(1f))
            if (here.isAdjusted) {
                TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("恢復原定", fontSize = 13.sp, color = DesignTokens.Accent)
                }
            }
        }
        Text("${fmtClock(here.arrivedMin)} 到達 · 原定停留 ${here.stop.duration} 分",
            fontSize = 12.sp, color = DesignTokens.Ink3)

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LeaveChip("−15", Modifier.weight(1f)) { onSetLeave(here.leaveMin - 15) }
            LeaveChip("+15", Modifier.weight(1f)) { onSetLeave(here.leaveMin + 15) }
            LeaveChip("+30", Modifier.weight(1f)) { onSetLeave(here.leaveMin + 30) }
            LeaveChip("+60", Modifier.weight(1f)) { onSetLeave(here.leaveMin + 60) }
            LeaveChip("自訂", Modifier.weight(1f)) { showPicker = true }
        }

        // 預測不是承諾：告訴使用者後面的時間是跟著這個值推出來的
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                downstreamShift > 0 -> "後續景點預計延後 $downstreamShift 分鐘"
                downstreamShift < 0 -> "後續景點預計提前 ${-downstreamShift} 分鐘"
                else -> "後續景點照原定時間"
            } + "，實際以抵達時間為準",
            fontSize = 12.sp, color = DesignTokens.Ink3
        )
    }
}

@Composable
private fun LeaveChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
        border = BorderStroke(1.dp, DesignTokens.Border)
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** 定位權限被拒時的提醒卡：打卡高亮／到站通知／地圖定位都需要它才會動 */
@Composable
private fun LocationPermissionCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DesignTokens.RedLight),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("⚠️", fontSize = 18.sp)
            Column(Modifier.weight(1f)) {
                Text("尚未開啟定位權限", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
                Text("無法提醒你已抵達景點，打卡按鈕也不會自動高亮", fontSize = 12.sp, color = DesignTokens.Ink2)
            }
            TextButton(onClick = onOpenSettings) {
                Text("去開啟", color = DesignTokens.Red, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WarnLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(DesignTokens.GoldLight, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("⏰", fontSize = 13.sp)
        Text(text, fontSize = 12.sp, color = DesignTokens.Accent2Dark)
    }
}

@Composable
private fun HeroIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
        border = BorderStroke(1.dp, DesignTokens.Accent)
    ) {
        Icon(icon, desc, Modifier.size(18.dp))
    }
}

// 已打卡 / 跳過 / 還沒到的站
@Composable
private fun TimelineStopRow(
    stop: Stop,
    checkIn: CheckInRecord?,
    isSkipped: Boolean,
    estimate: Int?,
    warning: String?,
    onClick: () -> Unit,
    onUnskip: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 狀態圖示
        when {
            checkIn != null -> Box(
                modifier = Modifier.size(24.dp).background(DesignTokens.StatusOngoingBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = DesignTokens.StatusOngoingText)
            }
            isSkipped -> Box(
                modifier = Modifier.size(24.dp).background(DesignTokens.Surface2, CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("—", fontSize = 12.sp, color = DesignTokens.Ink3) }
            else -> Box(
                modifier = Modifier.size(24.dp)
                    .background(Color.Transparent, CircleShape)
                    .border(1.5.dp, DesignTokens.Border, CircleShape)
            )
        }
        Text(stop.emoji, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(
                stop.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (checkIn != null || isSkipped) DesignTokens.Ink3 else DesignTokens.Ink,
                textDecoration = if (isSkipped) TextDecoration.LineThrough else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            when {
                checkIn != null -> Text("✓ 已打卡 · ${checkIn.displayName}",
                    fontSize = 12.sp, color = DesignTokens.Ink3)
                isSkipped -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已跳過 · ", fontSize = 12.sp, color = DesignTokens.Ink3)
                    Text("恢復", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Accent,
                        modifier = Modifier.clickable(onClick = onUnskip))
                }
                warning != null -> Text(warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (warning.startsWith("⚠️")) DesignTokens.Red else DesignTokens.Accent2Dark)
                else -> Text("⏳ 未到 · 停留 ${stop.duration} 分", fontSize = 12.sp, color = DesignTokens.Ink3)
            }
        }
        // 時間欄：還沒到的站一律標「預計」——是推估不是承諾，改過的站附上原定時間
        val scheduled = parseClockToMinutes(stop.time)
        when {
            checkIn != null -> Text("${fmtClock(minutesOfDay(checkIn.at))} ✓",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.StatusOngoingText)
            isSkipped -> { }
            estimate != null && scheduled != null && estimate != scheduled -> Column(horizontalAlignment = Alignment.End) {
                Text("預計 ${fmtClock(estimate)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Accent2Dark)
                Text("原 ${fmtClock(scheduled)}", fontSize = 11.sp, color = DesignTokens.Ink3,
                    textDecoration = TextDecoration.LineThrough)
            }
            scheduled != null -> Text("預計 ${fmtClock(scheduled)}", fontSize = 13.sp, color = DesignTokens.Ink2)
        }
    }
    HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.5f))
}

// ── 共用工具 ─────────────────────────────────────────────────

/** 開啟 Google Maps 導航（同 C4 邏輯；無 Google Maps 時退回 geo URI） */
internal fun launchStopNavigation(context: android.content.Context, stopName: String, latLng: LatLng?) {
    val navUri = if (latLng != null)
        Uri.parse("google.navigation:q=${latLng.latitude},${latLng.longitude}&mode=d")
    else
        Uri.parse("google.navigation:q=${Uri.encode(stopName)}&mode=d")
    val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        val geoUri = if (latLng != null)
            Uri.parse("geo:${latLng.latitude},${latLng.longitude}?q=${Uri.encode(stopName)}")
        else
            Uri.parse("geo:0,0?q=${Uri.encode(stopName)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        } catch (_: ActivityNotFoundException) { }
    }
}

/** 兩座標的直線距離（公尺） */
internal fun haversineMeters(a: LatLng, b: LatLng): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) *
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
    return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
}
