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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

// ── 路段分色色板（每段不同顏色）────────────────────────────
private val SegmentColors = listOf(
    Color(0xFF2A6B5E),  // 綠（accent）
    Color(0xFFF07B3F),  // 橘
    Color(0xFF3B82F6),  // 藍
    Color(0xFF8B5CF6),  // 紫
    Color(0xFFEC4899),  // 粉
    Color(0xFFEF4444),  // 紅
    Color(0xFFEAB308),  // 黃
    Color(0xFF06B6D4),  // 青
    Color(0xFF84CC16),  // 萊姆綠
    Color(0xFFF472B6),  // 玫瑰
)

// ── 已走過的路段（終點站已打卡／跳過）統一淡灰，跟未走的分色區隔開 ──
private val DoneSegmentColor = Color(0xFFAFA89E)

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
private fun filterBusinessHoursForDate(businessHours: String, tripDateStr: String): String {
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
    onConfirmParking: ((LatLng, String) -> Unit)? = null, // 非 null＝停車編輯模式（可拖曳+備註+確認）
    onBack: (() -> Unit)? = null,              // 獨立畫面時的返回；null＝內嵌於預覽頁
    progress: com.example.travellink_ai.ui.trip.TripProgress = com.example.travellink_ai.ui.trip.TripProgress(), // 行程進行中的打卡狀態：已完成路段/景點淡化顯示
    // 🧪 Debug-only 假 GPS（demo 用）：開著時地圖上的「目前位置」改吃模擬座標，不吃真實 GPS
    demoModeOn: Boolean = false,
    simulatedLocation: LatLng? = null
) {
    val stopLocations  by viewModel.stopLocations.collectAsState()
    val roadSegments   by viewModel.roadSegments.collectAsState()
    val cameraTarget   by viewModel.cameraUpdate.collectAsState()
    val itinerary      by viewModel.itinerary.collectAsState()
    val transportMode  by viewModel.transportMode.collectAsState()
    val parkingLots    by viewModel.parkingLots.collectAsState()
    val walkingLegs    by viewModel.walkingLegs.collectAsState()
    val stopToilets    by viewModel.stopToilets.collectAsState()

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
    var topSegmentIdx   by remember { mutableStateOf<Int?>(null) }

    val cameraPositionState = rememberCameraPositionState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── C3：GPS 即時定位 ──────────────────────────────────
    val context = LocalContext.current
    var realLocation      by remember { mutableStateOf<LatLng?>(null) }
    var locationAccuracy by remember { mutableStateOf(0f) }  // 公尺，畫準確度光環用
    // demo 開著時整顆藍點改吃模擬器座標；真實 GPS 那份 DisposableEffect 照常跑、只是畫面不採用
    val userLocation = if (com.example.travellink_ai.BuildConfig.DEBUG && demoModeOn) simulatedLocation else realLocation
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

    // 權限就緒後開始接收定位更新（5 秒一次），離開地圖時停止
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    realLocation = LatLng(it.latitude, it.longitude)
                    locationAccuracy = it.accuracy
                }
            }
        }
        try {
            // 先取最後已知位置讓藍點立即出現，再開始持續更新
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && realLocation == null) {
                    realLocation = LatLng(loc.latitude, loc.longitude)
                    locationAccuracy = loc.accuracy
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

    LaunchedEffect(cameraTarget) {
        cameraTarget?.let {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 13f))
        }
    }

    // 停車按鈕導入時，進場相機聚焦停車點（在 cameraTarget 之後，確保聚焦到車）
    LaunchedEffect(parkingMarker, focusParking) {
        if (focusParking && parkingMarker != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(parkingMarker, 17f))
        }
    }

    // 加入者端：地圖開啟時若路線還未計算（roadSegments 為空但 stops 有內容），主動補算
    LaunchedEffect(stops.size) {
        if (roadSegments.isEmpty() && stops.size >= 2) {
            viewModel.fetchDirections(stops)
        }
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 100.dp,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContainerColor = Surface,
        sheetShadowElevation = 16.dp,
        containerColor = Color.Transparent,
        sheetDragHandle = {
            // 對應網頁 .driver-panel-drag-handle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(AccentLight.copy(alpha = 0.6f), Surface))
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Border)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = itinerary?.let { it.title.ifBlank { it.aiTitle } } ?: "行程地圖",
                            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // 景點數不含出發/回程車站
                        Text(
                            text = "${stops.count { !it.isStation }} 個景點・${itinerary?.days ?: ""}",
                            fontSize = 13.sp, color = Ink2
                        )
                    }
                    Surface(shape = RoundedCornerShape(999.dp), color = AccentLight) {
                        Text(
                            text = "${stops.count { !it.isStation }} 站",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = AccentDark
                        )
                    }
                }
            }
        },
        sheetContent = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(stops) { index, stop ->
                    TimelineRow(
                        stop = stop,
                        index = index,
                        isLast = index == stops.lastIndex,
                        tripDate = tripDate,
                        transportMode = transportMode,
                        onClick = {
                            selectedStop = stop
                            coroutineScope.launch {
                                // 收合資訊欄回底部，讓地圖與景點卡露出
                                sheetState.bottomSheetState.partialExpand()
                                stopLocations[stop.name]?.let { latLng ->
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(latLng, 15f)
                                    )
                                }
                            }
                        }
                    )
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
                    try {
                        val offsetPx = sheetState.bottomSheetState.requireOffset()
                        with(density) { (screenHeightPx - offsetPx).toDp() }
                    } catch (e: Exception) {
                        paddingValues.calculateBottomPadding()
                    }
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
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = false),
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
                // 使用者即時定位（C3）：藍色標點 + 準確度光環，取得 GPS fix 後才顯示
                userLocation?.let { loc ->
                    Circle(
                        center = loc,
                        radius = locationAccuracy.coerceAtLeast(30f).toDouble(),
                        fillColor = Color(0x333B82F6),
                        strokeColor = Color(0xFF3B82F6),
                        strokeWidth = 3f
                    )
                    Marker(
                        state = MarkerState(position = loc),
                        title = "目前位置",
                        icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                            .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE)
                    )
                }

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

                // 景點標點：已打卡／已跳過的站淡化，視覺上跟時間軸頁的「已完成」呼應
                stops.forEachIndexed { index, stop ->
                    val latLng = stopLocations[stop.name] ?: return@forEachIndexed
                    val isStopDone = !stop.isStation && progress.isDone(stop.stopId)
                    Marker(
                        state = MarkerState(position = latLng),
                        title = "${index + 1}. ${stop.name}",
                        snippet = stop.desc,
                        alpha = if (isStopDone) 0.45f else 1f,
                        onClick = { selectedStop = stop; true }
                    )
                }

                // 停車場標點（僅 car 模式），snippet 附「步行約 X 公尺至景點」
                if (transportMode == "car") {
                    parkingLots.entries.distinctBy { it.value.id }.forEach { (stopName, lot) ->
                        // 有步行分鐘（Directions 實測或推估）就用分鐘，對齊網頁「步行約 N 分鐘」
                        val walkText = lot.walkMins?.let { m ->
                            "・步行約 $m 分鐘${if (lot.walkEstimated) "（估計）" else ""}至 $stopName"
                        } ?: stopLocations[stopName]?.let { stopPos ->
                            "・步行約 ${walkDistanceMeters(lot.position, stopPos)}m 至 $stopName"
                        } ?: ""
                        val spacesText = listOfNotNull(
                            lot.totalSpaces.takeIf { it > 0 }?.let { "汽車 $it 格" },
                            lot.motoSpots.takeIf { it > 0 }?.let { "機車 $it 格" }
                        ).joinToString("、")
                        Marker(
                            state = MarkerState(position = lot.position),
                            title = "🅿 ${lot.name}",
                            snippet = (spacesText + walkText).ifBlank { null },
                            icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                                .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)
                        )
                    }
                }

                // 🚻 附近公廁標點（開關開啟時顯示）
                if (showToilets) {
                    stopToilets.forEach { (stopName, toilets) ->
                        toilets.forEach { t ->
                            Marker(
                                state = MarkerState(position = t.latLng),
                                title = "🚻 ${t.name}",
                                snippet = "鄰近 $stopName",
                                icon = com.google.android.gms.maps.model.BitmapDescriptorFactory
                                    .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_VIOLET)
                            )
                        }
                    }
                }

                // 停車場→景點步行虛線（僅 car 模式）
                if (transportMode == "car") {
                    walkingLegs.forEach { (parkingPos, stopPos) ->
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
                    // 這段路的終點站已打卡／跳過 → 這段路已經走過，淡灰處理
                    val isSegmentDone = stops.getOrNull(idx + 1)?.let { progress.isDone(it.stopId) } == true
                    Polyline(
                        points = points,
                        color = if (isSegmentDone) DoneSegmentColor else SegmentColors[idx % SegmentColors.size],
                        width = if (idx == topSegmentIdx) 18f else if (isSegmentDone) 10f else 14f,
                        clickable = true,
                        onClick = {
                            topSegmentIdx = idx
                            val midPoint = points[points.size / 2]
                            // 計算路段兩端的 LatLngBounds 讓整段放入視野
                            val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
                            points.forEach { builder.include(it) }
                            coroutineScope.launch {
                                try {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngBounds(builder.build(), 120)
                                    )
                                } catch (e: Exception) {
                                    // 邊界計算失敗時退回中點 zoom
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(midPoint, 14f)
                                    )
                                }
                            }
                        }
                    )
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
                        userLocation = userLocation,
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
                    userLocation = userLocation,
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
                            CameraUpdateFactory.newLatLngZoom(userLocation ?: FallbackLocation, 15f)
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

// ── 時間軸列 ──────────────────────────────────────────────
@Composable
private fun TimelineRow(
    stop: Stop,
    index: Int,
    isLast: Boolean,
    tripDate: String = "",
    transportMode: String = "taxi",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Box(modifier = Modifier.width(52.dp).padding(top = 10.dp), contentAlignment = Alignment.CenterEnd) {
            Text(text = stop.time, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink2, textAlign = TextAlign.End)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Accent))
            if (!isLast) {
                Spacer(modifier = Modifier.width(2.dp).height(72.dp).background(Border))
            }
        }
        Spacer(Modifier.width(10.dp))
        Card(
            modifier  = Modifier.weight(1f).padding(top = 4.dp, bottom = if (isLast) 0.dp else 8.dp),
            shape     = RoundedCornerShape(20.dp),
            colors    = CardDefaults.cardColors(containerColor = Surface),
            border    = BorderStroke(1.dp, Border),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Surface2),
                    contentAlignment = Alignment.Center
                ) { Text(stop.emoji, fontSize = 24.sp) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stop.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SpotTag("${stop.duration} 分")
                        val hoursDisplay = if (tripDate.isNotBlank())
                            filterBusinessHoursForDate(stop.businessHours, tripDate)
                        else stop.businessHours
                        if (hoursDisplay != "未提供") SpotTag("🕐 $hoursDisplay")
                    }
                    if (stop.desc.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = stop.desc, fontSize = 14.sp, color = Ink2, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotTag(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = Surface2) {
        Text(text = text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink2)
    }
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
