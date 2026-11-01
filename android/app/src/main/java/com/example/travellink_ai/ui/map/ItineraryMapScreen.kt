package com.example.travellink_ai.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.util.OfflineMapCache
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

// ── 對應網頁 CSS 變數 ──────────────────────────────────────
private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface     = DesignTokens.Surface
private val Surface2    = DesignTokens.Surface2
private val Border      = DesignTokens.Border

// ── 定位 fallback：台東大學知本校區 ──────────────────────
// 僅在尚未取得 GPS fix（權限被拒 / 模擬器未設座標）時，作為「回到定位」按鈕的相機目標
private val FallbackLocation = LatLng(22.7059, 121.0557)

// ── 路線顏色依交通方式（對齊網頁 ROUTE_MODE_COLORS）：過去每段一種顏色輪替，段數一多很花 ──
private val ModeCarColor     = Color(0xFF1D4ED8)  // 開車、計程車
private val ModeScooterColor = Color(0xFFEA580C)  // 機車
private val ModeWalkColor    = Color(0xFFF97316)  // 步行
/** 走過的路段、正在走那段已走過的部分（同網頁 ROUTE_COMPLETED_COLOR） */
private val RouteDoneColor   = Color(0xFF94A3B8)
/** 下一站模式下其他路段：比走過的更淡，只當背景 */
private val RouteDimColor    = Color(0xFFCBD5E1)

private fun modeColor(mode: String) = when (mode) {
    "walking" -> ModeWalkColor
    "scooter", "motorcycle" -> ModeScooterColor
    else -> ModeCarColor
}

private fun modeMeta(mode: String) = when (mode) {
    "walking" -> "🚶" to "步行"
    "taxi" -> "🚕" to "計程車"
    "scooter", "motorcycle" -> "🛵" to "機車"
    else -> "🚗" to "開車"
}

/** 路段（stops[idx] → stops[idx+1]）在下方面板與地圖標籤用的資料 */
private class RouteSegmentInfo(
    val idx: Int,
    val from: Stop,
    val to: Stop,
    val mode: String,
    val mins: Long?,
    val km: Double?,
    /** 出發時間（上一站排定時間＋停留） */
    val departText: String?
)

private fun pathKm(points: List<LatLng>): Double? =
    if (points.size < 2) null
    else points.zipWithNext().sumOf { (a, b) -> com.example.travellink_ai.ui.trip.haversineMeters(a, b) } / 1000.0

/** 停車場→景點步行距離（公尺，haversine 直線估算，不發 API） */
private fun walkDistanceMeters(a: LatLng, b: LatLng): Int {
    val r = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) *
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
    return (r * 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))).toInt()
}

/**
 * 從完整一週營業時間字串中，只取出行程日期當天的時段。
 * 若 businessHours 不含星期前綴（即已是單日格式），直接回傳原字串。
 */
internal fun filterBusinessHoursForDate(businessHours: String, tripDateStr: String): String {
    if (!businessHours.contains("星期")) return businessHours
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
        val date = sdf.parse(tripDateStr) ?: return businessHours
        val cal = java.util.Calendar.getInstance().apply { time = date }
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val dayName = when (dayOfWeek) {
            java.util.Calendar.MONDAY    -> "星期一"
            java.util.Calendar.TUESDAY   -> "星期二"
            java.util.Calendar.WEDNESDAY -> "星期三"
            java.util.Calendar.THURSDAY  -> "星期四"
            java.util.Calendar.FRIDAY    -> "星期五"
            java.util.Calendar.SATURDAY  -> "星期六"
            else                         -> "星期日"
        }
        // 逐行找含當天名稱的那行，取冒號後的時段
        businessHours.lines()
            .firstOrNull { it.trimStart().startsWith(dayName) }
            ?.substringAfter("：")?.substringAfter(":")?.trim()
            ?: businessHours
    } catch (e: Exception) {
        businessHours
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope", "MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryMapScreen(
    viewModel: ItineraryViewModel,
    stops: List<Stop>,
    initialShowToilets: Boolean = false,       // 進場即開啟廁所圖層（廁所按鈕導入）
    parkingMarker: LatLng? = null,             // 我的停車點（行程進行中記錄）
    parkingNote: String = "",                  // 停車點備註（顯示在 marker snippet）
    focusParking: Boolean = false,             // 進場相機聚焦停車點（停車按鈕導入）
    focusStopId: String? = null,               // 進場聚焦這一站並打開景點卡（互動插圖「在地圖上看」）
    onConfirmParking: ((LatLng, String) -> Unit)? = null, // 非 null＝停車編輯模式（可拖曳+備註+確認）
    onBack: (() -> Unit)? = null,              // 獨立畫面時的返回；null＝內嵌於預覽頁
    progress: com.example.travellink_ai.ui.trip.TripProgress = com.example.travellink_ai.ui.trip.TripProgress(), // 行程進行中的打卡狀態：已完成路段/景點淡化顯示
    // 🧪 Debug-only 假 GPS（demo 用）：開著時地圖上的「目前位置」改吃模擬座標，不吃真實 GPS
    demoModeOn: Boolean = false,
    simulatedLocation: State<LatLng?>? = null
) {
    val stopLocations  by viewModel.stopLocations.collectAsState()
    val roadSegments   by viewModel.roadSegments.collectAsState()
    val cameraTarget   by viewModel.cameraUpdate.collectAsState()
    val itinerary      by viewModel.itinerary.collectAsState()
    val transportMode  by viewModel.transportMode.collectAsState()
    val parkingLots    by viewModel.parkingLots.collectAsState()
    val walkingLegs    by viewModel.walkingLegs.collectAsState()
    val stopToilets    by viewModel.stopToilets.collectAsState()
    val transitTimes   by viewModel.transitTimes.collectAsState()
    val segmentModes   by viewModel.segmentModes.collectAsState()

    // 🚻 附近公廁顯示開關（開啟時才向 ViewModel 要資料，未載入的站才會發查詢）
    var showToilets by remember { mutableStateOf(initialShowToilets) }
    // 廁所按鈕導入時進場即載入公廁資料
    LaunchedEffect(Unit) { if (initialShowToilets) viewModel.loadStopToilets(stops) }

    // 🅿 停車點微調狀態：可拖曳 marker 位置 + 備註輸入（parking 編輯模式用）
    val parkingMarkerState = rememberMarkerState(
        key = parkingMarker?.toString(),
        position = parkingMarker ?: LatLng(0.0, 0.0)
    )
    var parkingNoteInput by remember(parkingMarker?.toString()) { mutableStateOf(parkingNote) }

    // 行程起始日期（格式 yyyy/MM/dd），用於過濾當天營業時間
    val tripDate = remember(itinerary?.days) {
        itinerary?.days?.substringBefore(" ")?.takeIf { it.length == 10 } ?: ""
    }

    var selectedStop    by remember { mutableStateOf<Stop?>(null) }
    // 聚焦的那一段路（roadSegments 的索引；null＝全部路線）。與網頁 activeRouteStage 相同：
    // 點某一段只顯示那一段與它頭尾兩站，再點一次恢復全部；點景點聚焦「從這站出發」的那一段
    var topSegmentIdx   by remember { mutableStateOf<Int?>(null) }

    val cameraPositionState = rememberCameraPositionState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    /** 第 i 站要聚焦哪一段：從這站出發的那段，沒有（最後一站）就是抵達這站的那段 */
    fun segmentForStop(i: Int): Int? = when {
        i in roadSegments.indices && roadSegments[i].isNotEmpty() -> i
        i - 1 in roadSegments.indices && roadSegments[i - 1].isNotEmpty() -> i - 1
        else -> null
    }
    /** 鏡頭框住整段路 */
    fun fitSegment(idx: Int) {
        val points = roadSegments.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: return
        val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
        points.forEach { builder.include(it) }
        coroutineScope.launch {
            try {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), 160))
            } catch (e: Exception) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(points[points.size / 2], 14f))
            }
        }
    }
    /** 聚焦第 i 站所在的那一段（點景點標記、點下方行程清單時） */
    fun focusStop(i: Int) {
        val seg = segmentForStop(i) ?: return
        topSegmentIdx = seg
        fitSegment(seg)
    }

    // ── C3：GPS 即時定位 ──────────────────────────────────
    val context = LocalContext.current
    // 定位存成 State 物件、只在用到的那一層讀：過去在這裡直接讀，GPS 每 2～5 秒一更新，
    // 整張地圖（所有標記、路線）連下方行程清單都跟著重建，是地圖頁卡頓的主因
    val realLocation = remember { mutableStateOf<LatLng?>(null) }
    val locationAccuracy = remember { mutableFloatStateOf(0f) }  // 公尺，畫準確度光環用
    // demo 開著時整顆藍點改吃模擬器座標；真實 GPS 那份 DisposableEffect 照常跑、只是畫面不採用
    val userLocation: State<LatLng?> = remember(demoModeOn, simulatedLocation) {
        derivedStateOf {
            if (com.example.travellink_ai.BuildConfig.DEBUG && demoModeOn) simulatedLocation?.value else realLocation.value
        }
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> hasLocationPermission = grants.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 權限就緒後開始接收定位更新（5 秒一次；過去最快 2 秒一次，看地圖用不到那麼密），離開地圖時停止
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(4000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    realLocation.value = LatLng(it.latitude, it.longitude)
                    locationAccuracy.floatValue = it.accuracy
                }
            }
        }
        try {
            // 先取最後已知位置讓藍點立即出現，再開始持續更新
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && realLocation.value == null) {
                    realLocation.value = LatLng(loc.latitude, loc.longitude)
                    locationAccuracy.floatValue = loc.accuracy
                }
            }
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // 權限在檢查後被撤銷，忽略
        }
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    // ── 行程進行中：預設「下一站模式」（只強調目前這一段），可切回「今天全部」──
    var nextOnly by rememberSaveable { mutableStateOf(true) }
    val tripFocus = remember(progress, stops, transitTimes, stopLocations, nextOnly, tripDate) {
        if (!progress.isOngoing) null else {
            val derived = com.example.travellink_ai.ui.trip.deriveTripState(stops, progress, transitTimes)
            fun coordOf(st: Stop?) = st?.let { it.lat?.let { la -> it.lng?.let { lo -> LatLng(la, lo) } } ?: stopLocations[it.name] }
            // 多日行程兩天之間：沒有下一站，看今天的路線、鏡頭停在今天最後一站（住宿）
            val overnight = com.example.travellink_ai.ui.trip.overnightState(
                stops, progress, derived, com.example.travellink_ai.debug.DemoClock.now(), tripDate)
            if (overnight != null) return@remember TripFocus(
                day = overnight.finishedDay, nextId = null, nextName = null, currentLeg = IntRange.EMPTY,
                nextOnly = nextOnly,
                nextLatLng = coordOf(derived.here?.stop ?: overnight.lodging ?: stops.lastOrNull { it.dayIndex == overnight.finishedDay }),
                fromLatLng = null
            )
            // 下一站：還沒打卡的景點；景點都完成了就是回程車站
            val next = derived.currentStop ?: stops.lastOrNull()?.takeIf { it.isStation && derived.allDone }
            val nextIdx = next?.let { n -> stops.indexOfFirst { it.stopId == n.stopId } } ?: -1
            // 這一段的起點：下一站之前最後一個沒被跳過的站（剛打卡的站、出發車站、昨晚的住宿）
            val fromIdx = if (nextIdx > 0) (nextIdx - 1 downTo 0).firstOrNull { stops[it].stopId !in progress.skips } ?: -1 else -1
            TripFocus(
                day = next?.dayIndex ?: derived.currentDayIndex,
                nextId = next?.stopId,
                nextName = next?.name,
                currentLeg = if (fromIdx >= 0) fromIdx until nextIdx else IntRange.EMPTY,
                nextOnly = nextOnly,
                nextLatLng = coordOf(next),
                fromLatLng = coordOf(stops.getOrNull(fromIdx))
            )
        }
    }

    LaunchedEffect(cameraTarget) {
        // 行程進行中由下一站模式自己決定鏡頭（框住「我」和下一站），不要縮回第一站
        if (tripFocus != null) return@LaunchedEffect
        cameraTarget?.let {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 13f))
        }
    }

    // 下一站模式：鏡頭框住「我」（沒有定位就用上一站）和下一站；今天全部：框住今天所有站。
    // 只在下一站換了或切換模式時移動，不跟著每次 GPS 更新跳
    LaunchedEffect(tripFocus?.nextId, tripFocus?.nextOnly, tripFocus?.nextLatLng) {
        val f = tripFocus ?: return@LaunchedEffect
        if (focusStopId != null || focusParking) return@LaunchedEffect
        val pts = if (f.nextOnly) listOfNotNull(f.nextLatLng, userLocation.value ?: f.fromLatLng)
            else stops.filter { it.dayIndex == f.day }.mapNotNull { stopLocations[it.name] }
        if (pts.isEmpty()) return@LaunchedEffect
        try {
            if (pts.size == 1) cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(pts[0], 15f))
            else {
                val b = com.google.android.gms.maps.model.LatLngBounds.builder()
                pts.forEach { b.include(it) }
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 180))
            }
        } catch (e: Exception) {
            // 地圖還沒排版好時 newLatLngBounds 會丟例外：退回以下一站為中心
            (f.nextLatLng ?: pts[0]).let { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 14f)) }
        }
    }

    // 停車按鈕導入時，進場相機聚焦停車點（在 cameraTarget 之後，確保聚焦到車）
    LaunchedEffect(parkingMarker, focusParking) {
        if (focusParking && parkingMarker != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(parkingMarker, 17f))
        }
    }

    // 互動插圖「在地圖上看」：在 cameraTarget 之後聚焦該站並打開景點卡
    LaunchedEffect(focusStopId, stopLocations[stops.firstOrNull { it.stopId == focusStopId }?.name]) {
        val stop = stops.firstOrNull { it.stopId == focusStopId } ?: return@LaunchedEffect
        selectedStop = stop
        val latLng = stop.lat?.let { la -> stop.lng?.let { lo -> LatLng(la, lo) } } ?: stopLocations[stop.name]
            ?: return@LaunchedEffect
        cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    // 加入者端：地圖開啟時若路線還未計算（roadSegments 為空但 stops 有內容），主動補算
    LaunchedEffect(stops.size) {
        if (roadSegments.isEmpty() && stops.size >= 2) {
            viewModel.fetchDirections(stops)
        }
    }

    // ── 下方面板：路段清單（對齊網頁手機版路線面板）。收合只顯示一段摘要，往上拉看全部 ──
    val segmentInfos = remember(stops, roadSegments, transitTimes, segmentModes, transportMode) {
        (0 until (stops.size - 1).coerceAtLeast(0)).map { i ->
            val from = stops[i]; val to = stops[i + 1]
            val depart = com.example.travellink_ai.ui.trip.parseClockToMinutes(from.time)
                ?.let { com.example.travellink_ai.ui.trip.fmtClock(it + if (from.isStation && i == 0) 0 else from.duration.toInt()) }
            RouteSegmentInfo(
                idx = i, from = from, to = to,
                mode = segmentModes.getOrNull(i) ?: if (from.walkNext) "walking" else transportMode,
                mins = transitTimes.getOrNull(i),
                km = roadSegments.getOrNull(i)?.let { pathKm(it) },
                departText = depart
            )
        }
    }
    val dayCount = remember(stops) { stops.map { it.dayIndex }.distinct().size }
    // 面板看哪一天（0＝全部）：行程進行中預設今天，規劃中預設全部
    var sheetDay by rememberSaveable { mutableIntStateOf(-1) }
    val shownDay = if (sheetDay >= 0) sheetDay else (tripFocus?.day ?: 0)
    val shownSegments = segmentInfos.filter { shownDay == 0 || it.to.dayIndex == shownDay }
    fun segmentStatus(seg: RouteSegmentInfo): String? = when {
        tripFocus == null -> null
        seg.idx in tripFocus.currentLeg -> "進行中"
        !seg.to.isStation && progress.isDone(seg.to.stopId) -> "已完成"
        else -> null
    }
    // 收合時的摘要：聚焦的那段 → 行程進行中目前這段 → 這天第一段
    val summarySeg = topSegmentIdx?.let { segmentInfos.getOrNull(it) }
        ?: tripFocus?.currentLeg?.lastOrNull()?.let { segmentInfos.getOrNull(it) }
        ?: shownSegments.firstOrNull()

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 92.dp,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = Surface,
        sheetShadowElevation = 16.dp,
        containerColor = Color.Transparent,
        sheetDragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch {
                            if (sheetState.bottomSheetState.currentValue == SheetValue.Expanded) sheetState.bottomSheetState.partialExpand()
                            else sheetState.bottomSheetState.expand()
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Border)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(10.dp))
                if (summarySeg == null) {
                    Text(itinerary?.let { it.title.ifBlank { it.aiTitle } } ?: "行程地圖",
                        fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    val (icon, _) = modeMeta(summarySeg.mode)
                    Text(
                        "第 ${summarySeg.idx + 1} 段 · ${summarySeg.from.name} → ${summarySeg.to.name}",
                        fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            summarySeg.mins?.let { "$icon $it 分" } ?: icon,
                            summarySeg.km?.let { "%.1f km".format(it) },
                            segmentStatus(summarySeg),
                            "${if (shownDay > 0 && dayCount > 1) "第 $shownDay 天 · " else ""}共 ${shownSegments.size} 段 ⌃"
                        ).joinToString(" · "),
                        fontSize = 13.sp, color = Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        sheetContent = {
            // 往上拉最多約三段的高度（不拉到頂，地圖還看得到）；超過的在清單裡捲動
            Column(Modifier.fillMaxWidth().heightIn(max = if (dayCount > 1) 360.dp else 316.dp).padding(horizontal = 16.dp)) {
                // 多日行程：面板裡直接切天（對齊網頁「看哪一天」）
                if (dayCount > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ((1..dayCount).toList() + 0).forEach { d ->
                            val selected = d == shownDay
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) Accent else Surface2,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { sheetDay = d; topSegmentIdx = null }
                            ) {
                                Text(if (d == 0) "全部" else "第 $d 天",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Ink2)
                            }
                        }
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shownSegments.isEmpty()) item {
                        Text("這一天沒有移動路段", fontSize = 14.sp, color = Ink2, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    items(shownSegments.size) { k ->
                        val seg = shownSegments[k]
                        RouteSegmentRow(
                            seg = seg,
                            status = segmentStatus(seg),
                            selected = seg.idx == topSegmentIdx,
                            onClick = {
                                if (topSegmentIdx == seg.idx) topSegmentIdx = null
                                else { topSegmentIdx = seg.idx; fitSegment(seg.idx) }
                                coroutineScope.launch { sheetState.bottomSheetState.partialExpand() }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // 用 BoxWithConstraints 取得螢幕高度（px），用來計算 panel 當前高度
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeightPx = with(density) { maxHeight.toPx() }

            // 動態追蹤 panel 當前高度（px → dp），讓按鈕跟著 panel 一起移動
            val panelHeightDp by remember {
                derivedStateOf {
                    // 面板剛排版或拖曳超出時 offset 可能大於螢幕高度，算出負數會讓下面的 padding 直接閃退
                    // （2026-10-19 Crash：Padding must be non-negative）
                    try {
                        val offsetPx = sheetState.bottomSheetState.requireOffset()
                        with(density) { (screenHeightPx - offsetPx).toDp() }
                    } catch (e: Exception) {
                        paddingValues.calculateBottomPadding()
                    }.coerceAtLeast(0.dp)
                }
            }

            // ── E1-B 離線偵測 ─────────────────────────────────
            // Google 互動地圖離線必為空白；偵測到離線時改疊上已快取的路線圖 fallback。
            var isOnline by remember { mutableStateOf(OfflineMapCache.isOnline(context)) }
            DisposableEffect(Unit) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { isOnline = OfflineMapCache.isOnline(context) }
                    override fun onLost(n: Network) { isOnline = OfflineMapCache.isOnline(context) }
                }
                runCatching { cm?.registerDefaultNetworkCallback(cb) }
                onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
            }
            val offlineKey = remember(stops) {
                OfflineMapCache.keyFor(viewModel.currentFirestoreDocIdPublic, stops)
            }
            // 上線且座標齊全 → 預抓 Static Maps 底圖存本地（best-effort，失敗離線時退回本地草圖）
            LaunchedEffect(isOnline, offlineKey, roadSegments.size) {
                if (isOnline && stops.any { it.lat != null && it.lng != null }) {
                    OfflineMapCache.prefetchStaticMap(context, offlineKey, stops, roadSegments)
                }
            }

            // ── 全螢幕地圖 ────────────────────────────────────
            // 地圖底圖先出來，載入完成後才加路線與標記：過去兩件事擠在同一個畫面裡，第一次進地圖掉幀 0.8 秒
            var mapLoaded by remember { mutableStateOf(false) }
            // 網路慢或離線時底圖可能很久都載不完：最多等 1.5 秒就照樣畫路線
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_500L); mapLoaded = true }
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = false),
                uiSettings = MapUiSettings(zoomControlsEnabled = false),
                onMapLoaded = { mapLoaded = true }
            ) {
                if (!mapLoaded) return@GoogleMap
                // 使用者即時定位（C3）：獨立一層，定位更新只重畫這一層（藍點＋準確度光環）
                UserLocationLayer(userLocation, locationAccuracy, density.density)

                // 🚗 我的停車點（行程進行中記錄；橘色與 TDX 停車場藍色區隔）
                // 編輯模式（onConfirmParking != null）可拖曳微調位置
                if (parkingMarker != null) {
                    Marker(
                        state = parkingMarkerState,
                        draggable = onConfirmParking != null,
                        title = "🚗 我的停車點",
                        snippet = parkingNote.ifBlank { null },
                        icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                            .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_ORANGE)
                    )
                }

                // 景點標點：已打卡／已跳過的站淡化。行程進行中只顯示今天的站，改用帶編號的小圓點（下一站大而醒目）。
                // MarkerState 用 remember 記住：過去每次重畫都 new 一個，等於所有標記重新設定一次
                stops.forEachIndexed { index, stop ->
                    val latLng = stopLocations[stop.name] ?: return@forEachIndexed
                    val focusSeg = topSegmentIdx
                    if (focusSeg != null) {
                        if (index != focusSeg && index != focusSeg + 1) return@forEachIndexed
                    } else if (tripFocus != null && stop.dayIndex != tripFocus.day) return@forEachIndexed
                    val isStopDone = !stop.isStation && progress.isDone(stop.stopId)
                    val isNext = stop.stopId == tripFocus?.nextId
                    key(stop.stopId) {
                        val state = remember(latLng) { MarkerState(position = latLng) }
                        Marker(
                            state = state,
                            title = "${index + 1}. ${stop.name}",
                            snippet = stop.desc,
                            icon = tripFocus?.let {
                                MapMarkerIcons.stop("${index + 1}", when {
                                    isNext -> MapMarkerIcons.Style.NEXT
                                    isStopDone -> MapMarkerIcons.Style.DONE
                                    else -> MapMarkerIcons.Style.UPCOMING
                                }, density.density)
                            },
                            anchor = if (tripFocus != null) androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                                else androidx.compose.ui.geometry.Offset(0.5f, 1f),
                            alpha = if (isStopDone) 0.45f else 1f,
                            zIndex = if (isNext) 2f else 1f,
                            onClick = { selectedStop = stop; focusStop(index); true }
                        )
                    }
                }

                // 聚焦某一段時，停車場、廁所、步行虛線只留那段頭尾兩站的
                val focusedNames: Set<String>? = topSegmentIdx?.let { seg ->
                    setOfNotNull(stops.getOrNull(seg)?.name, stops.getOrNull(seg + 1)?.name)
                }

                // 停車場標點（僅 car 模式），snippet 附「步行約 X 公尺至景點」。下一站模式只顯示下一站的
                if (transportMode == "car") {
                    parkingLots.entries
                        .filter { e -> focusedNames?.let { e.key in it } ?: (tripFocus == null || !tripFocus.nextOnly || e.key == tripFocus.nextName) }
                        .distinctBy { it.value.id }.forEach { (stopName, lot) ->
                        // 有步行分鐘（Directions 實測或推估）就用分鐘，對齊網頁「步行約 N 分鐘」
                        val walkText = lot.walkMins?.let { m ->
                            "・步行約 $m 分鐘${if (lot.walkEstimated) "（估計）" else ""}至 $stopName"
                        } ?: stopLocations[stopName]?.let { stopPos ->
                            "・步行約 ${walkDistanceMeters(lot.position, stopPos)}m 至 $stopName"
                        } ?: ""
                        val spacesText = listOfNotNull(
                            lot.totalSpaces.takeIf { it > 0 }?.let { "汽車 $it 格" },
                            lot.motoSpots.takeIf { it > 0 }?.let { "機車 $it 格" },
                            lot.feeNote.takeIf { it.isNotBlank() }
                        ).joinToString("、")
                        key("lot", lot.id) {
                            val state = remember(lot.position) { MarkerState(position = lot.position) }
                            Marker(
                                state = state,
                                title = "🅿 ${lot.name}",
                                snippet = (spacesText + walkText).ifBlank { null },
                                icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                                    .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)
                            )
                        }
                    }
                }

                // 🚻 附近公廁標點（開關開啟時顯示；下一站模式只顯示下一站附近的）
                if (showToilets) {
                    stopToilets
                        .filter { e -> focusedNames?.let { e.key in it } ?: (tripFocus == null || !tripFocus.nextOnly || e.key == tripFocus.nextName) }
                        .forEach { (stopName, toilets) ->
                        toilets.forEach { t ->
                            key("wc", stopName, t.latLng) {
                                val state = remember(t.latLng) { MarkerState(position = t.latLng) }
                                Marker(
                                    state = state,
                                    title = "🚻 ${t.name}",
                                    snippet = "鄰近 $stopName",
                                    icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                                        .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_VIOLET)
                                )
                            }
                        }
                    }
                }

                // 停車場→景點步行虛線（僅 car 模式；下一站模式只畫下一站那段）
                if (transportMode == "car") {
                    val nextPos = tripFocus?.nextName?.let { stopLocations[it] }
                    val focusedPos = focusedNames?.mapNotNull { stopLocations[it] }
                    walkingLegs
                        .filter { (_, stopPos) ->
                            focusedPos?.let { stopPos in it } ?: (tripFocus == null || !tripFocus.nextOnly || stopPos == nextPos)
                        }
                        .forEach { (parkingPos, stopPos) ->
                        Polyline(
                            points = listOf(parkingPos, stopPos),
                            color = Color(0xFF4CAF50),
                            width = 8f,
                            pattern = listOf(
                                com.google.android.gms.maps.model.Dot(),
                                com.google.android.gms.maps.model.Gap(12f),
                                com.google.android.gms.maps.model.Dash(20f),
                                com.google.android.gms.maps.model.Gap(12f)
                            )
                        )
                    }
                }

                // 路段分色，每段不同顏色，點擊後視角移到該路段並浮到最上層
                // 被點擊的路段移到渲染順序最末，確保它覆蓋其他重疊路段
                val renderOrder = remember(roadSegments.size, topSegmentIdx) {
                    val indices = (roadSegments.indices).toMutableList()
                    topSegmentIdx?.let { top ->
                        if (top in indices) { indices.remove(top); indices.add(top) }
                    }
                    indices
                }
                renderOrder.forEach { idx ->
                    val points = roadSegments.getOrNull(idx) ?: return@forEach
                    if (points.isEmpty()) return@forEach
                    val focusSeg = topSegmentIdx
                    if (focusSeg != null && idx != focusSeg) return@forEach
                    // 行程進行中只畫今天的路段（以終點站的天為準：第一段從昨晚住宿出發也算今天）
                    if (focusSeg == null && tripFocus != null && stops.getOrNull(idx + 1)?.dayIndex != tripFocus.day) return@forEach
                    // 這段路的終點站已打卡／跳過 → 這段路已經走過，淡灰處理
                    val isSegmentDone = stops.getOrNull(idx + 1)?.let { progress.isDone(it.stopId) } == true
                    val isCurrentLeg = tripFocus != null && idx in tripFocus.currentLeg
                    val mode = segmentInfos.getOrNull(idx)?.mode ?: transportMode
                    val color = when {
                        // 聚焦的那一段一定上色（下一站模式下聚焦一段走過的路也看得清楚）
                        idx == focusSeg -> modeColor(mode)
                        // 下一站模式：只有目前這一段上色，其他段（走過的、還沒到的）都是淡灰細線
                        tripFocus?.nextOnly == true -> if (isCurrentLeg) modeColor(mode) else RouteDimColor
                        isSegmentDone -> RouteDoneColor
                        else -> modeColor(mode)
                    }
                    val width = when {
                        idx == topSegmentIdx || isCurrentLeg -> 16f
                        tripFocus?.nextOnly == true -> 7f
                        isSegmentDone -> 9f
                        else -> 11f
                    }
                    Polyline(
                        points = points,
                        color = color,
                        width = width,
                        zIndex = if (isCurrentLeg) 1f else 0f,
                        clickable = true,
                        onClick = {
                            // 再點一次同一段 → 恢復全部路線（與網頁相同）
                            if (topSegmentIdx == idx) topSegmentIdx = null
                            else { topSegmentIdx = idx; fitSegment(idx) }
                        }
                    )
                }

                // 正在走的那段：已經走過的部分畫灰色（依 GPS 位置投影到路線上切開，同網頁 completedRouteRenderers）
                if (tripFocus != null && !tripFocus.currentLeg.isEmpty() &&
                    (topSegmentIdx == null || topSegmentIdx in tripFocus.currentLeg)) {
                    CurrentLegProgressLayer(tripFocus.currentLeg.mapNotNull { roadSegments.getOrNull(it) }, userLocation)
                }

                // 路段中間的時間標籤（例「🚗 7 分」）：只標聚焦的那段、下一站模式的目前這段，避免滿地圖都是字
                val labelSegs = topSegmentIdx?.let { listOf(it) }
                    ?: tripFocus?.takeIf { it.nextOnly }?.currentLeg?.toList().orEmpty()
                labelSegs.forEach { i ->
                    val info = segmentInfos.getOrNull(i) ?: return@forEach
                    val pts = roadSegments.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@forEach
                    val mins = info.mins ?: return@forEach
                    key("label", i) {
                        val pos = pts[pts.size / 2]
                        val state = remember(pos) { MarkerState(position = pos) }
                        Marker(
                            state = state,
                            icon = MapMarkerIcons.label("${modeMeta(info.mode).first} $mins 分",
                                android.graphics.Color.argb(255,
                                    (modeColor(info.mode).red * 255).toInt(), (modeColor(info.mode).green * 255).toInt(),
                                    (modeColor(info.mode).blue * 255).toInt()),
                                density.density),
                            anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            zIndex = 4f,
                            onClick = { true }
                        )
                    }
                }
            }

            // ── 聚焦某一段時：顯示是哪一段，點「顯示全部」恢復 ──
            topSegmentIdx?.let { seg ->
                val from = stops.getOrNull(seg)?.name.orEmpty()
                val to = stops.getOrNull(seg + 1)?.name.orEmpty()
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = if (tripFocus != null) 60.dp else 12.dp, start = 72.dp, end = 72.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { topSegmentIdx = null },
                    shape = RoundedCornerShape(999.dp),
                    color = Surface.copy(alpha = 0.95f),
                    shadowElevation = 4.dp
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${seg + 1}→${seg + 2}  $from → $to", fontSize = 13.sp, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text("顯示全部", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                }
            }

            // ── 行程進行中：「下一站／今天全部」切換 ──
            if (tripFocus != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Surface.copy(alpha = 0.95f))
                        .padding(3.dp)
                ) {
                    listOf(true to "下一站", false to "今天全部").forEach { (value, label) ->
                        val selected = nextOnly == value
                        Text(
                            label,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) Accent else Color.Transparent)
                                .clickable { nextOnly = value; topSegmentIdx = null }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else Ink2
                        )
                    }
                }
            }

            // ── E1-B 離線 fallback：蓋住空白的互動地圖，顯示已快取路線圖 ──
            // 已下載台東離線底圖、且行程站點都在圖資範圍內 → MapLibre 互動地圖；否則退回靜態圖
            val basemapState by com.example.travellink_ai.util.OfflineBasemap.state.collectAsState()
            LaunchedEffect(Unit) { com.example.travellink_ai.util.OfflineBasemap.refresh(context) }
            val tripInBasemap = remember(stops) {
                val (w, s, e, n) = com.example.travellink_ai.util.OfflineBasemap.BOUNDS.toList()
                stops.any { it.lat != null && it.lng != null } && stops.all { st ->
                    val la = st.lat; val ln = st.lng
                    la == null || ln == null || (la in s..n && ln in w..e)
                }
            }
            if (!isOnline && basemapState is com.example.travellink_ai.util.OfflineBasemap.State.Ready && tripInBasemap) {
                Box(Modifier.fillMaxSize()) {
                    OfflineVectorMap(
                        stops = stops,
                        roadSegments = roadSegments,
                        userLocation = userLocation.value,
                        modifier = Modifier.fillMaxSize()
                    )
                    OfflineBanner("📴 離線模式・離線地圖", Modifier.align(Alignment.TopCenter))
                }
            } else if (!isOnline) {
                OfflineMapFallback(
                    context = context,
                    key = offlineKey,
                    stops = stops,
                    roadSegments = roadSegments,
                    region = itinerary?.region ?: "",
                    userLocation = userLocation.value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── 景點詳情 Popup ────────────────────────────────
            AnimatedVisibility(
                visible = selectedStop != null,
                enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 3 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = panelHeightDp + 72.dp)
            ) {
                selectedStop?.let { stop ->
                    SpotInfoCard(
                        stop = stop,
                        latLng = stopLocations[stop.name],
                        tripDate = tripDate,
                        onClose = { selectedStop = null }
                    )
                }
            }

            // ── 🚻 附近公廁開關（在回到定位按鈕上方）──────────────
            SmallFloatingActionButton(
                onClick = {
                    showToilets = !showToilets
                    if (showToilets) viewModel.loadStopToilets(stops)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = panelHeightDp + 64.dp),
                containerColor = if (showToilets) Accent else Surface,
                contentColor = if (showToilets) Color.White else Accent,
                shape = RoundedCornerShape(12.dp),
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Text("🚻", fontSize = 16.sp)
            }

            // ── 回到定位按鈕（緊貼在 panel 上方，跟著 panel 一起移動）──
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(userLocation.value ?: FallbackLocation, 15f)
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = panelHeightDp + 12.dp),
                containerColor = Surface,
                contentColor = Accent,
                shape = RoundedCornerShape(12.dp),
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "回到定位",
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── 返回行程（獨立畫面時才顯示；內嵌預覽頁時 onBack 為 null）──
            onBack?.let { back ->
                SmallFloatingActionButton(
                    onClick = back,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 12.dp),
                    containerColor = Surface,
                    contentColor = Accent,
                    shape = RoundedCornerShape(12.dp),
                    elevation = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回行程",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── 🅿 停車點微調面板（parking 編輯模式）──────────────
            if (onConfirmParking != null && parkingMarker != null) {
                ParkingEditPanel(
                    note = parkingNoteInput,
                    onNoteChange = { parkingNoteInput = it },
                    onConfirm = { onConfirmParking(parkingMarkerState.position, parkingNoteInput.trim()) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                )
            }
        }
    }
}

/** 行程進行中的下一站模式：要看的那天、下一站、目前這一段路（roadSegments 的索引範圍） */
private class TripFocus(
    val day: Int,
    val nextId: String?,
    val nextName: String?,
    val currentLeg: IntRange,
    val nextOnly: Boolean,
    val nextLatLng: LatLng?,
    val fromLatLng: LatLng?
)

/** 「我在這裡」：獨立的一層，定位更新時只有這裡重畫 */
@Composable
@GoogleMapComposable
private fun UserLocationLayer(location: State<LatLng?>, accuracy: FloatState, density: Float) {
    val loc = location.value ?: return
    Circle(
        center = loc,
        radius = accuracy.floatValue.coerceAtLeast(30f).toDouble(),
        fillColor = Color(0x333B82F6),
        strokeColor = Color(0xFF3B82F6),
        strokeWidth = 3f
    )
    val state = remember { MarkerState(position = loc) }
    LaunchedEffect(loc) { state.position = loc }
    Marker(
        state = state,
        title = "目前位置",
        icon = MapMarkerIcons.userDot(density),
        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        zIndex = 3f
    )
}

// ── 🅿 停車點微調面板：拖曳提示 + 備註輸入 + 確認 ────────────────
@Composable
private fun ParkingEditPanel(
    note: String,
    onNoteChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "拖曳地圖上的 🚗 可微調停車位置",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                singleLine = true,
                label = { Text("停車備註（選填）", fontSize = 13.sp) },
                placeholder = { Text("例：B2 紅區 12 號柱", fontSize = 13.sp, color = Ink2) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                    cursorColor = Accent
                )
            )
            // 快選（對齊網頁 B1／B2／1F／紅區／靠近出口）：點了接到備註後面
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("B1", "B2", "1F", "紅區", "靠近出口").forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Surface2,
                        modifier = Modifier.clickable {
                            onNoteChange(listOf(note.trim(), tag).filter { it.isNotEmpty() }.joinToString(" "))
                        }
                    ) {
                        Text(tag, fontSize = 13.sp, color = Ink,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("確認停車位置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── 路段列（下方面板）：第幾段、出發時間、起點 → 終點、交通方式・時間・距離、狀態 ──
@Composable
private fun RouteSegmentRow(seg: RouteSegmentInfo, status: String?, selected: Boolean, onClick: () -> Unit) {
    val (icon, label) = modeMeta(seg.mode)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) AccentLight else Surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "第 ${seg.idx + 1} 段" + (seg.departText?.let { " · $it 出發" } ?: ""),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink2
                )
                Spacer(Modifier.height(3.dp))
                Text("${seg.from.name} → ${seg.to.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull("$icon $label", seg.mins?.let { "$it 分" }, seg.km?.let { "%.1f km".format(it) }).joinToString(" · "),
                    fontSize = 13.sp, color = modeColor(seg.mode)
                )
            }
            status?.let {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (it == "進行中") modeColor(seg.mode) else Surface2
                ) {
                    Text(it, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, color = if (it == "進行中") Color.White else Ink2)
                }
            }
        }
    }
}

/**
 * 正在走的那段路，已經走過的部分畫成灰色：把目前位置投影到這段路線上切開。
 * 獨立一層，定位更新只重畫這裡。離路線超過 400m（還沒出發、走錯路）就不畫。
 */
@Composable
@GoogleMapComposable
private fun CurrentLegProgressLayer(segments: List<List<LatLng>>, location: State<LatLng?>) {
    val loc = location.value ?: return
    val path = segments.flatten()
    val done = donePrefix(path, loc, maxOffsetMeters = 400.0) ?: return
    if (done.size < 2) return
    Polyline(points = done, color = RouteDoneColor, width = 16f, zIndex = 2f)
}

/** 位置投影到折線上，回傳「起點到投影點」那一段；離折線太遠回 null */
private fun donePrefix(path: List<LatLng>, p: LatLng, maxOffsetMeters: Double): List<LatLng>? {
    if (path.size < 2) return null
    val k = kotlin.math.cos(Math.toRadians(p.latitude))
    var best = -1; var bestT = 0.0; var bestD = Double.MAX_VALUE
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]
        val ax = a.longitude * k; val ay = a.latitude; val bx = b.longitude * k; val by = b.latitude
        val px = p.longitude * k; val py = p.latitude
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + dx * t; val qy = ay + dy * t
        val d = (px - qx) * (px - qx) + (py - qy) * (py - qy)
        if (d < bestD) { bestD = d; best = i; bestT = t }
    }
    if (best < 0) return null
    val a = path[best]; val b = path[best + 1]
    val proj = LatLng(a.latitude + (b.latitude - a.latitude) * bestT, a.longitude + (b.longitude - a.longitude) * bestT)
    if (com.example.travellink_ai.ui.trip.haversineMeters(proj, p) > maxOffsetMeters) return null
    return path.subList(0, best + 1) + proj
}

/**
 * 開啟 Google Maps 導航到指定景點（C4）。
 * 有座標用 turn-by-turn 導航 URI；無座標 fallback 用景點名稱搜尋。
 * 未安裝 Google Maps 時退回一般 geo URI 交給其他地圖 App。
 */
private fun launchNavigation(context: android.content.Context, stopName: String, latLng: LatLng?) {
    val navUri = if (latLng != null)
        Uri.parse("google.navigation:q=${latLng.latitude},${latLng.longitude}&mode=d")
    else
        Uri.parse("google.navigation:q=${Uri.encode(stopName)}&mode=d")
    val mapsIntent = Intent(Intent.ACTION_VIEW, navUri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(mapsIntent)
    } catch (e: ActivityNotFoundException) {
        val geoUri = if (latLng != null)
            Uri.parse("geo:${latLng.latitude},${latLng.longitude}?q=${Uri.encode(stopName)}")
        else
            Uri.parse("geo:0,0?q=${Uri.encode(stopName)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        } catch (e: ActivityNotFoundException) {
            // 裝置上沒有任何地圖 App，靜默忽略
        }
    }
}

// ── 景點詳情卡片 ──────────────────────────────────────────
@Composable
private fun SpotInfoCard(stop: Stop, latLng: LatLng? = null, tripDate: String = "", onClose: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier  = Modifier.width(260.dp),
        shape     = RoundedCornerShape(24.dp),
        colors    = CardDefaults.cardColors(containerColor = Surface.copy(alpha = 0.97f)),
        border    = BorderStroke(1.dp, Border.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 20.dp)
    ) {
        Box {
            Box(modifier = Modifier.fillMaxWidth().height(72.dp).background(Brush.verticalGradient(listOf(AccentLight.copy(alpha = 0.7f), Color.Transparent))))
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stop.emoji, fontSize = 26.sp)
                        Text(text = stop.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp).clip(CircleShape).background(Surface2)) {
                        Icon(Icons.Default.Close, "關閉", tint = Ink2, modifier = Modifier.size(14.dp))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Border.copy(alpha = 0.72f))
                if (stop.desc.isNotBlank()) {
                    Text(text = stop.desc, fontSize = 15.sp, color = Ink2, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(999.dp), color = AccentLight) {
                        Text(text = stop.time, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = AccentDark)
                    }
                    val hoursDisplay = if (tripDate.isNotBlank())
                        filterBusinessHoursForDate(stop.businessHours, tripDate)
                    else stop.businessHours
                    if (hoursDisplay != "未提供") {
                        Surface(shape = RoundedCornerShape(999.dp), color = Surface2) {
                            Text(text = "🕐 $hoursDisplay", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // ── 開始導航按鈕（C4）────────────────────────
                Button(
                    onClick = { launchNavigation(context, stop.name, latLng) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("開始導航", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── E1-B 離線路線圖 fallback ────────────────────────────────────────
// 離線時 Google 互動地圖是空白，改顯示已快取的 Static Maps 圖，
// 沒有預抓圖時退回用折線本地畫的路線草圖（0 網路）。都拿不到才顯示純文字提示。
@Composable
private fun OfflineMapFallback(
    context: android.content.Context,
    key: String,
    stops: List<Stop>,
    roadSegments: List<List<com.google.android.gms.maps.model.LatLng>>,
    region: String,
    userLocation: com.google.android.gms.maps.model.LatLng?,
    modifier: Modifier = Modifier
) {
    val offlineMap by produceState<com.example.travellink_ai.util.OfflineMap?>(initialValue = null, key, stops, roadSegments.size) {
        value = OfflineMapCache.loadOfflineBitmap(context, key, stops, roadSegments, region)
    }
    // 雙指縮放／拖曳（1x–5x）；圖與位置點放在同一層一起變換，點才會對齊
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 位置換成 bitmap 像素；null＝沒定位或舊快取沒投影資訊
    val userPx = remember(offlineMap, userLocation) {
        val m = offlineMap; val loc = userLocation
        if (m?.project == null || loc == null) null else m.project.invoke(loc)
    }
    val userOutside = offlineMap?.let { m ->
        userPx != null && (userPx.first !in 0f..m.bitmap.width.toFloat() ||
                           userPx.second !in 0f..m.bitmap.height.toFloat())
    } == true
    Box(modifier.background(Surface2)) {
        val m = offlineMap
        if (m != null) {
            val imageBitmap = remember(m) { m.bitmap.asImageBitmap() }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            // 平移上限＝放大後多出來的一半，避免把圖拖出畫面
                            val maxX = size.width * (newZoom - 1f) / 2f
                            val maxY = size.height * (newZoom - 1f) / 2f
                            pan = androidx.compose.ui.geometry.Offset(
                                (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                (pan.y + panChange.y).coerceIn(-maxY, maxY)
                            )
                            zoom = newZoom
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = pan.x; translationY = pan.y
                    }
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "離線路線圖",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                // 目前位置：藍點＋白框（大小不隨縮放變，縮放時反除 zoom）
                if (userPx != null && !userOutside) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val fit = minOf(size.width / m.bitmap.width, size.height / m.bitmap.height)
                        val ox = (size.width - m.bitmap.width * fit) / 2f
                        val oy = (size.height - m.bitmap.height * fit) / 2f
                        val c = androidx.compose.ui.geometry.Offset(ox + userPx.first * fit, oy + userPx.second * fit)
                        val r = 8.dp.toPx() / zoom
                        drawCircle(Color(0x333B82F6), radius = r * 2.6f, center = c)
                        drawCircle(Color.White, radius = r * 1.35f, center = c)
                        drawCircle(Color(0xFF3B82F6), radius = r, center = c)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "離線模式\n此行程沒有可離線顯示的路線資料",
                    color = Ink2,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        OfflineBanner(
            if (userOutside) "📴 離線模式・你目前不在這張圖的範圍內" else "📴 離線模式・顯示已儲存的路線",
            Modifier.align(Alignment.TopCenter)
        )
    }
}

// 離線橫幅（頂部置中），靜態圖與離線互動地圖共用
@Composable
private fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .statusBarsPadding()
            // 放在右上角「邀請／編輯行程」按鈕那一列的下面：同一列會被按鈕蓋住文字
            .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Ink.copy(alpha = 0.82f),
        shadowElevation = 4.dp
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
