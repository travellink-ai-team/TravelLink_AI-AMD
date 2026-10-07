package com.example.travellink_ai.ui.trip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.DayPlanner
import com.example.travellink_ai.data.weather.CwaWeatherService
import com.example.travellink_ai.data.weather.WeatherInfo
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.planning.CostReference
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.example.travellink_ai.ui.planning.LocalAlternatives
import com.example.travellink_ai.ui.planning.NearbyAlternativesList
import com.example.travellink_ai.ui.planning.PlaceCost
import com.example.travellink_ai.ui.theme.DesignTokens
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 站點詳細頁（W3）：從行程頁時間軸點站點進入；行程進行中在行程頁打卡後也會自動跳來這頁
 * （目前站的預計離開時間在這裡調）。
 * 簡介 / 營業時間 / 門票 / 天氣 / 附近公廁 / 打卡紀錄 / 停留調整 / 跳過。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(
    viewModel: ItineraryViewModel,
    tripVm: TripProgressViewModel,
    onBack: () -> Unit
) {
    val collabVm: CollabViewModel = hiltViewModel()

    val itinerary     by viewModel.itinerary.collectAsState()
    val stopLocations by viewModel.stopLocations.collectAsState()
    val stopToilets   by viewModel.stopToilets.collectAsState()
    val progress      by tripVm.progress.collectAsState()
    val visitedKeys   by tripVm.visitedKeys.collectAsState()
    val myRole        by collabVm.myRole.collectAsState()
    val isViewer      = myRole == "viewer"

    val stops = itinerary?.stops ?: emptyList()
    val stop = stops.firstOrNull { it.stopId == tripVm.selectedStopId }

    // 站點被刪除或狀態遺失 → 回時間軸
    if (stop == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 📸 這站的照片：直接寫共同相簿（要有雲端行程文件）──
    val photosVm: StopPhotosViewModel = hiltViewModel()
    val cloudTripId = viewModel.currentFirestoreDocIdPublic?.takeIf { it.isNotBlank() && it != "current" }
    LaunchedEffect(cloudTripId) { cloudTripId?.let(photosVm::bind) }
    val photoActions = rememberStopPhotoActions(photosVm, stop)

    val coord: LatLng? =
        if (stop.lat != null && stop.lng != null) LatLng(stop.lat, stop.lng)
        else stopLocations[stop.name]

    val checkIn   = progress.checkIns[stop.stopId]
    val isSkipped = stop.stopId in progress.skips
    val transitTimes by viewModel.transitTimes.collectAsState()
    val derived   = remember(stops, progress, transitTimes) { deriveTripState(stops, progress, transitTimes) }
    // 目前站＝最後打卡的站（下一站打卡才往前移）；下一站＝還沒打卡的第一站
    val here      = derived.activeHere(progress)?.takeIf { it.stop.stopId == stop.stopId }
    val isNext    = progress.isOngoing && derived.currentStop?.stopId == stop.stopId
    val conflicts = rememberScheduleConflicts(viewModel, tripVm, stops, progress, derived)

    // 行程日期（yyyy/MM/dd），供營業時間過濾與天氣查詢
    // 多日行程：這一站是第幾天就查那一天的日期，否則第二天的站會拿到出發日的
    // 天氣與營業時間（週一休館這種判斷會整個錯開）
    val tripDateStr = remember(itinerary?.days, stop.dayIndex) {
        val start = itinerary?.days?.substringBefore(" ")?.take(10) ?: ""
        if (start.length == 10 && stop.dayIndex > 1)
            DayPlanner.addDays(start, stop.dayIndex - 1) else start
    }

    // ── 背景載入：門票 / 天氣 / 公廁 ─────────────────────────────
    val appContext = context.applicationContext
    val placeCost by produceState<PlaceCost?>(initialValue = null, stop.name) {
        value = withContext(Dispatchers.IO) {
            CostReference.get(appContext).lookup(stop.name, stop.lat, stop.lng)
        }
    }
    // 附近替代景點（本地資料即時算）：途中臨時想換，例如到了才發現沒開、人太多
    val alternatives by produceState(emptyList<LocalAlternatives.Candidate>(), stop, stops, tripDateStr) {
        value = withContext(Dispatchers.IO) {
            LocalAlternatives.nearby(stop, stops, CostReference.get(appContext).places, tripDateStr)
                .take(LocalAlternatives.MAX_OPTIONS)
        }
    }
    var replacing by remember { mutableStateOf(false) }
    val weather by produceState<WeatherInfo?>(initialValue = null, stop.name, tripDateStr) {
        value = if (coord != null && tripDateStr.length == 10) {
            withContext(Dispatchers.IO) {
                CwaWeatherService.fetchForecast(coord.latitude, coord.longitude, tripDateStr)
            }
        } else null
    }
    // 公廁改為使用者主動查詢（D1 降 Places 費用）：不自動搜尋，避免每開一站就打 API。
    // 若 stop 已有現成公廁資料（Room/共編帶來）則直接顯示、免點按。
    val hasPresetToilets = stop.nearbyToiletLocations.isNotEmpty()
    var toiletRequested by remember(stop.stopId) { mutableStateOf(hasPresetToilets) }
    LaunchedEffect(toiletRequested) {
        if (toiletRequested && !stop.isStation) viewModel.loadStopToilets(listOf(stop))
    }
    val toilets = stopToilets[stop.name] ?: emptyList()

    // ── 停留時間調整 ─────────────────────────────────────────
    var durationDraft by remember(stop.stopId) { mutableStateOf(stop.duration.coerceIn(10L, 180L).toFloat()) }
    var savingDuration by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 打卡距離檢查：用 demo 開著時的模擬座標，否則用行程進行中頁鏡射過來的最後 GPS 位置
    val demoLoc by tripVm.locationSimulator.location.collectAsState()
    fun checkInWithDistanceWarning() {
        tripVm.checkIn(stop.stopId)
        tripVm.justCheckedInStopId = stop.stopId   // 和行程頁打卡一樣提示拍照
        val loc = if (com.example.travellink_ai.BuildConfig.DEBUG && tripVm.demoModeOn) demoLoc else tripVm.lastKnownLocation
        if (coord != null && loc != null && haversineMeters(loc, coord) > NEAR_STOP_METERS) {
            scope.launch { snackbarHostState.showSnackbar("距離「${stop.name}」還有點遠，已經幫你打卡囉") }
        }
    }

    // 行程頁打卡後自動跳來這頁：提示拍照（拍照入口原本在行程頁的提示條）
    LaunchedEffect(tripVm.justCheckedInStopId) {
        if (tripVm.justCheckedInStopId != stop.stopId) return@LaunchedEffect
        tripVm.justCheckedInStopId = null
        val r = snackbarHostState.showSnackbar(
            message = "✅ ${stop.name} 打卡成功！拍張照留下回憶吧",
            actionLabel = "📸 拍照",
            duration = SnackbarDuration.Long
        )
        // 有雲端行程就直接開相機、照片歸到這一站；純本地行程維持到旅程回憶上傳
        if (r == SnackbarResult.ActionPerformed) {
            if (cloudTripId != null) photoActions.takePhoto() else viewModel.openMemoryForCurrentTrip()
        }
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = DesignTokens.Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("景點資訊", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = DesignTokens.Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 標題卡：名稱 / 狀態 / 簡介 / 時間 chips ─────────────
            DetailCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stop.emoji, fontSize = 30.sp)
                    Column(Modifier.weight(1f)) {
                        Text(stop.name, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = DesignTokens.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        when {
                            here != null && checkIn != null -> Text(
                                "📍 目前站 · ${fmtClock(minutesOfDay(checkIn.at))} 到達（${checkIn.displayName}打卡）",
                                fontSize = 13.sp, color = DesignTokens.Accent, fontWeight = FontWeight.SemiBold)
                            checkIn != null -> Text("✅ 已於 ${fmtClock(minutesOfDay(checkIn.at))} 打卡（${checkIn.displayName}）",
                                fontSize = 13.sp, color = DesignTokens.StatusOngoingText, fontWeight = FontWeight.SemiBold)
                            isSkipped -> Text("已跳過此站", fontSize = 13.sp, color = DesignTokens.Ink3)
                            isNext -> Text("➡ 下一站", fontSize = 13.sp,
                                color = DesignTokens.AccentDark, fontWeight = FontWeight.SemiBold)
                            else -> { }
                        }
                    }
                }
                if (stop.desc.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stop.desc, fontSize = 14.sp, color = DesignTokens.Ink2, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stop.time.isNotBlank()) InfoChip("🕒 ${stop.time}", DesignTokens.AccentLight, DesignTokens.AccentDark)
                    InfoChip("⏱ 停留 ${stop.duration} 分", DesignTokens.Surface2, DesignTokens.Ink2)
                }
                val hoursDisplay = if (tripDateStr.isNotBlank())
                    filterHoursForDate(stop.businessHours, tripDateStr) else stop.businessHours
                if (hoursDisplay.isNotBlank() && hoursDisplay != "未提供") {
                    Spacer(Modifier.height(8.dp))
                    Text("🕐 營業時間：$hoursDisplay", fontSize = 13.sp, color = DesignTokens.Ink2)
                }
            }

            // ── 🕒 預計離開時間（只有目前站）：調了只順延後續預計時間，原規劃不變 ──
            here?.let { h ->
                DetailCard {
                    LeaveTimeSection(
                        here = h,
                        downstreamShift = derived.delayMin,
                        onSetLeave = { tripVm.adjustLeave(h, it) },
                        onReset = { tripVm.clearLeaveAt(h.stop.stopId) }
                    )
                }
            }

            // ── ⚠️ 時間衝突與處理選項：目前站列出全部（調離開時間的當下就看得到後果），
            //    其他站只列跟這站有關的 ──
            val shownConflicts = if (here != null) conflicts else conflicts.filter { it.stopId == stop.stopId }
            ScheduleConflictCard(
                conflicts = shownConflicts,
                here = derived.activeHere(progress),
                stopDone = { progress.isDone(it) },
                onSetLeave = { h, m -> tripVm.adjustLeave(h, m) },
                onSkip = { tripVm.skipStop(it) },
                onDismiss = { tripVm.dismissConflict(it.key) }
            )

            // ── 📌 我去過了＋🎧 語音導覽（對齊網頁景點詳情）──────────────
            if (!stop.isStation) {
                val isVisited = com.example.travellink_ai.data.repository.VisitedSpotsRepository
                    .nameKey(stop.name) in visitedKeys
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            tripVm.toggleVisited(stop) { r ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(when (r) {
                                        true  -> "📌 已記錄，之後重新規劃會避開這裡"
                                        false -> "已取消「去過了」"
                                        null  -> "記錄失敗，請確認已登入後再試"
                                    })
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isVisited) DesignTokens.AccentLight else Color.Transparent,
                            contentColor = DesignTokens.Accent
                        ),
                        border = BorderStroke(1.dp, DesignTokens.Accent)
                    ) { Text(if (isVisited) "✓ 我已去過" else "📌 我去過了", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    VoiceGuideButton(
                        text = listOf(stop.name, stop.desc).filter { it.isNotBlank() }.joinToString("。"),
                        onUnsupported = { scope.launch { snackbarHostState.showSnackbar("此裝置不支援中文語音導覽") } },
                        modifier = Modifier.weight(1f).height(44.dp)
                    )
                }
            }

            // ── 打卡 / 導航 / 跳過（車站不顯示）────────────────────
            if (!stop.isStation && progress.isOngoing) {
                DetailCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (checkIn == null) {
                            Button(
                                onClick = { checkInWithDistanceWarning() },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                            ) {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("打卡完成", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { tripVm.undoCheckIn(stop.stopId) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Ink2),
                                border = BorderStroke(1.dp, DesignTokens.Border)
                            ) { Text("取消打卡", fontSize = 14.sp) }
                        }
                        OutlinedButton(
                            onClick = { launchStopNavigation(context, stop.name, coord) },
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                            border = BorderStroke(1.dp, DesignTokens.Accent)
                        ) {
                            Icon(Icons.Default.Navigation, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("導航", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        if (checkIn == null) {
                            TextButton(
                                onClick = {
                                    if (isSkipped) tripVm.unskipStop(stop.stopId)
                                    else tripVm.skipStop(stop.stopId)
                                },
                                modifier = Modifier.height(44.dp)
                            ) {
                                Text(if (isSkipped) "恢復" else "跳過", fontSize = 14.sp, color = DesignTokens.Ink3)
                            }
                        }
                    }
                }
            }

            // ── 📸 這站的照片（打卡後的拍照提示也走這裡）────────────
            if (!stop.isStation && progress.isOngoing) {
                DetailCard { StopPhotosSection(stop, photosVm, photoActions, cloudReady = cloudTripId != null) }
            }

            // ── 門票 / 花費 ──────────────────────────────────────
            placeCost?.let { pc ->
                if (pc.fee != null || pc.costPerPerson != null) {
                    DetailCard {
                        SectionTitle("💰 費用參考")
                        pc.fee?.let { fee ->
                            Text(
                                if (fee == 0) "門票：免費" else "門票：$$fee / 人",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink
                            )
                            if (pc.feeNote.isNotBlank()) {
                                Text(pc.feeNote, fontSize = 12.sp, color = DesignTokens.Ink3)
                            }
                        }
                        pc.costPerPerson?.let { cost ->
                            Spacer(Modifier.height(4.dp))
                            Text("餐費：約 $$cost / 人", fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                            if (pc.costNote.isNotBlank()) {
                                Text(pc.costNote, fontSize = 12.sp, color = DesignTokens.Ink3)
                            }
                        }
                    }
                }
            }

            // ── 附近替代景點（還沒打卡、沒跳過的站才能換；對齊網頁「途中隨時能換」）──
            if (alternatives.isNotEmpty() && checkIn == null && !isSkipped) {
                DetailCard {
                    SectionTitle("🔁 附近替代景點")
                    NearbyAlternativesList(
                        targetName = stop.name,
                        candidates = alternatives,
                        onReplace = if (isViewer || replacing) null else { place ->
                            replacing = true
                            scope.launch {
                                if (collabVm.acquireEditingLock(stop.stopId)) {
                                    viewModel.updateStop(
                                        stop.stopId,
                                        LocalAlternatives.applyTo(stop, place, tripDateStr, "替換原本的「${stop.name}」")
                                    )
                                    collabVm.releaseEditingLock(stop.stopId)
                                    snackbarHostState.showSnackbar("已換成「${place.name}」，後續時間將重新計算")
                                } else {
                                    snackbarHostState.showSnackbar("其他成員正在編輯此站，請稍後再試")
                                }
                                replacing = false
                            }
                        }
                    )
                }
            }

            // ── 當日天氣 ────────────────────────────────────────
            weather?.let { w ->
                DetailCard {
                    SectionTitle("${w.emoji} 當日天氣")
                    Text(
                        buildString {
                            if (w.description.isNotBlank()) append("${w.description}・")
                            append("${w.minTemp}–${w.maxTemp}°C")
                            if (w.rainProbability >= 0) append("・降雨 ${w.rainProbability}%")
                        },
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink
                    )
                    Text(w.townshipName, fontSize = 12.sp, color = DesignTokens.Ink3)
                }
            }

            // ── 附近公廁（D1：使用者主動查詢才打 API）────────────────
            if (!stop.isStation) {
                DetailCard {
                    SectionTitle("🚻 附近公廁")
                    when {
                        toilets.isNotEmpty() -> toilets.forEach { t ->
                            val dist = coord?.let { haversineMeters(it, t.latLng).toInt() }
                            Text(
                                "・${t.name}" + (dist?.let { "（約 ${it}m）" } ?: ""),
                                fontSize = 13.sp, color = DesignTokens.Ink2,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        toiletRequested -> Text(
                            "查無附近公廁資料",
                            fontSize = 13.sp, color = DesignTokens.Ink3
                        )
                        else -> OutlinedButton(
                            onClick = { toiletRequested = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
                            border = BorderStroke(1.dp, DesignTokens.Border)
                        ) { Text("查看附近公廁", fontSize = 14.sp) }
                    }
                }
            }

            // ── 停留時間調整（車站/已打卡/觀看者不可調）─────────────
            // 行程進行中不改原規劃（對齊網頁：進行中鎖住編輯）；
            // 想多待就在目前站上面的「預計離開」調，後續站只順延預計時間
            if (!stop.isStation && checkIn == null && !isViewer && !progress.isOngoing) {
                DetailCard {
                    SectionTitle("⏱ 調整停留時間")
                    Text("${durationDraft.toInt()} 分鐘", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = DesignTokens.Accent)
                    Slider(
                        value = durationDraft,
                        onValueChange = { durationDraft = it },
                        valueRange = 10f..180f,
                        steps = 16,
                        colors = SliderDefaults.colors(
                            thumbColor = DesignTokens.Accent,
                            activeTrackColor = DesignTokens.Accent,
                            inactiveTrackColor = DesignTokens.Surface2
                        )
                    )
                    if (durationDraft.toInt().toLong() != stop.duration) {
                        Button(
                            onClick = {
                                if (savingDuration) return@Button
                                savingDuration = true
                                scope.launch {
                                    val locked = collabVm.acquireEditingLock(stop.stopId)
                                    if (locked) {
                                        viewModel.updateStop(stop.stopId, stop.copy(duration = durationDraft.toInt().toLong()))
                                        collabVm.releaseEditingLock(stop.stopId)
                                        snackbarHostState.showSnackbar("已更新停留時間，後續時間將重新計算")
                                    } else {
                                        snackbarHostState.showSnackbar("其他成員正在編輯此站，請稍後再試")
                                    }
                                    savingDuration = false
                                }
                            },
                            enabled = !savingDuration,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                        ) { Text(if (savingDuration) "儲存中…" else "儲存變更", fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

// ── 小元件 ───────────────────────────────────────────────────

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun InfoChip(text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/**
 * 從完整一週營業時間字串中取出行程日期當天的時段
 * （同 ItineraryMapScreen 的 filterBusinessHoursForDate 邏輯）。
 */
private fun filterHoursForDate(businessHours: String, tripDateStr: String): String {
    if (!businessHours.contains("星期")) return businessHours
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
        val date = sdf.parse(tripDateStr) ?: return businessHours
        val cal = java.util.Calendar.getInstance().apply { time = date }
        val weekdayNames = listOf("日", "一", "二", "三", "四", "五", "六")
        val target = "星期" + weekdayNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val segment = businessHours.split("；", ";", "\n")
            .firstOrNull { it.contains(target) }
            ?.substringAfter("：")?.substringAfter(":")?.trim()
        if (segment.isNullOrBlank()) businessHours else "$target $segment"
    } catch (e: Exception) {
        businessHours
    }
}

/**
 * 🎧 語音導覽（對齊網頁 speechSynthesis）：用系統 TextToSpeech 念景點名稱與介紹，再按一次停止。
 * 離開畫面時釋放 TTS 引擎。
 */
@Composable
private fun VoiceGuideButton(text: String, onUnsupported: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var speaking by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val tts = remember {
        var engine: android.speech.tts.TextToSpeech? = null
        engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            val e = engine ?: return@TextToSpeech
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val r = e.setLanguage(java.util.Locale.TRADITIONAL_CHINESE)
                ready = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                    r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                })
            }
        }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    OutlinedButton(
        onClick = {
            when {
                speaking -> { tts.stop(); speaking = false }
                !ready -> onUnsupported()
                else -> {
                    tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "voice_guide")
                    speaking = true
                }
            }
        },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
        border = BorderStroke(1.dp, DesignTokens.Accent)
    ) { Text(if (speaking) "⏹ 停止導覽" else "🎧 語音導覽", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}
