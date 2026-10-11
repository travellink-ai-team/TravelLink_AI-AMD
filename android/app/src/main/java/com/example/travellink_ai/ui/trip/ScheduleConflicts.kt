package com.example.travellink_ai.ui.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.BusinessHours
import com.example.travellink_ai.ui.planning.DayPlanner
import com.example.travellink_ai.ui.planning.ItineraryViewModel
import com.example.travellink_ai.ui.theme.DesignTokens

/**
 * 行程進行中的時間衝突（第 4 項）：用「預計時間」而不是原規劃時間，檢查順延之後會撞到的固定限制。
 * 營業時間、當天收工時間、末班船、回程火車。只提醒、不自動改行程——怎麼處理由使用者決定（第 5 項）。
 *
 * 已訂餐廳沒有檢查：精靈只把它寫進 AI 的 prompt，行程裡沒有標出哪一站是訂位、幾點，查不到。
 */
internal enum class ConflictKind {
    CLOSED_TODAY,         // 當天公休
    CLOSED_ON_ARRIVAL,    // 到的時候已經打烊
    CLOSES_DURING_VISIT,  // 停留期間會打烊
    EARLY_ARRIVAL,        // 提早到、還沒開門（只是提醒要等）
    DAY_OVERRUN,          // 今天收工時間比原定晚
    FERRY_MISS,           // 趕不上末班船
    TRAIN_TIGHT           // 回程火車很趕
}

internal data class ScheduleConflict(
    val kind: ConflictKind,
    /** 相關的站；整天或班次類為 null */
    val stopId: String?,
    val message: String,
    /** 目前站要提早幾分鐘離開才解得開；0＝提早離開沒用或不適用 */
    val shortenMin: Int = 0,
    /** 可以多待幾分鐘（提早到要等開門時） */
    val extendMin: Int = 0
) {
    val key: String get() = "${kind}_${stopId.orEmpty()}"
    /** 只是提醒、不算衝突（提早到要等開門） */
    val isInfo: Boolean get() = kind == ConflictKind.EARLY_ARRIVAL
}

/** 收工時間超過原定多少分鐘才提醒（排程本來就有幾分鐘誤差） */
private const val DAY_OVERRUN_GRACE_MIN = 10
/** 回程火車緩衝少於幾分鐘算很趕（ReturnTrainPlan 已內扣 10 分鐘走進站） */
private const val TRAIN_TIGHT_MIN = 15

/**
 * 營業時間與當天收工時間。只看「今天」（目前進行到的那一天）還沒完成的站，
 * 和目前站本身（待太久會撞到打烊）。
 *
 * @param dayDate 今天的日期 yyyy/MM/dd（全週營業時間要靠它挑出當天那一行）；空字串時跳過全週格式
 * @param dayEndMins 今天的原定收工時間（當日分鐘數）；null＝不檢查
 */
internal fun detectScheduleConflicts(
    stops: List<Stop>,
    progress: TripProgress,
    derived: TripDerived,
    dayDate: String,
    dayEndMins: Int?
): List<ScheduleConflict> {
    if (!progress.isOngoing) return emptyList()
    val day = derived.currentDayIndex
    val here = derived.activeHere(progress)
    val out = mutableListOf<ScheduleConflict>()

    fun rangesOf(stop: Stop): Pair<String, List<Pair<Int, Int>>?>? {
        val raw = stop.businessHours
        if (raw.isBlank() || raw == "未提供") return null
        if (raw.contains("星期") && dayDate.isBlank()) return null
        val resolved = BusinessHours.resolve(raw, dayDate)
        return resolved to BusinessHours.openRanges(resolved)
    }

    // ── 目前站：預計離開晚於打烊 ──
    here?.let { h ->
        val (resolved, ranges) = rangesOf(h.stop) ?: return@let
        if (BusinessHours.isClosed(resolved) || ranges == null) return@let
        val range = ranges.firstOrNull { (open, close) -> h.arrivedMin in open until close } ?: return@let
        if (h.leaveMin > range.second) {
            out += ScheduleConflict(
                ConflictKind.CLOSES_DURING_VISIT, h.stop.stopId,
                "${h.stop.name} ${fmtClock(range.second)} 打烊，預計 ${fmtClock(h.leaveMin)} 才離開",
                shortenMin = h.leaveMin - range.second
            )
        }
    }

    // ── 後續站：用預計抵達時間對營業時間 ──
    stops.filter { it.dayIndex == day && !it.isStation && !progress.isDone(it.stopId) }.forEach { stop ->
        val est = derived.estimatedTimes[stop.stopId] ?: return@forEach
        val (resolved, ranges) = rangesOf(stop) ?: return@forEach
        val dur = stop.duration.toInt()
        if (BusinessHours.isClosed(resolved)) {
            out += ScheduleConflict(ConflictKind.CLOSED_TODAY, stop.stopId, "${stop.name} 今天公休")
            return@forEach
        }
        ranges ?: return@forEach   // 全天營業或沒資料
        val inside = ranges.firstOrNull { (open, close) -> est in open until close }
        when {
            inside != null -> {
                val over = est + dur - inside.second
                if (over > 0) out += ScheduleConflict(
                    ConflictKind.CLOSES_DURING_VISIT, stop.stopId,
                    "${stop.name} ${fmtClock(inside.second)} 打烊，預計 ${fmtClock(est)} 到只能待 " +
                        "${(inside.second - est).coerceAtLeast(0)} 分鐘（原定 $dur 分）",
                    shortenMin = over
                )
            }
            else -> {
                val later = ranges.filter { it.first > est }.minByOrNull { it.first }
                val earlier = ranges.filter { it.second <= est }.maxByOrNull { it.second }
                if (later != null && later.first + dur <= later.second) {
                    out += ScheduleConflict(
                        ConflictKind.EARLY_ARRIVAL, stop.stopId,
                        "${stop.name} ${fmtClock(later.first)} 才開門，預計 ${fmtClock(est)} 到要等 ${later.first - est} 分鐘",
                        extendMin = later.first - est
                    )
                } else if (earlier != null) {
                    out += ScheduleConflict(
                        ConflictKind.CLOSED_ON_ARRIVAL, stop.stopId,
                        "${stop.name} ${fmtClock(earlier.second)} 就打烊了，預計 ${fmtClock(est)} 才到",
                        shortenMin = est + dur - earlier.second
                    )
                }
            }
        }
    }

    // ── 今天收工時間 ──
    if (dayEndMins != null) {
        val dayStops = stops.filter { it.dayIndex == day }
        val last = dayStops.lastOrNull()
        val lastPlayable = dayStops.lastOrNull { !it.isStation && !progress.isDone(it.stopId) }
        val estEnd = last?.let { derived.estimatedTimes[it.stopId] }
            ?: lastPlayable?.let { s -> derived.estimatedTimes[s.stopId]?.plus(s.duration.toInt()) }
        val plannedEnd = last?.takeIf { it.isStation }?.let { parseClockToMinutes(it.time) }
            ?: lastPlayable?.let { s -> parseClockToMinutes(s.time)?.plus(s.duration.toInt()) }
        val limit = maxOf(dayEndMins, plannedEnd ?: dayEndMins)
        if (estEnd != null && estEnd > limit + DAY_OVERRUN_GRACE_MIN) {
            out += ScheduleConflict(
                ConflictKind.DAY_OVERRUN, null,
                "今天預計 ${fmtClock(estEnd)} 才結束，比原定 ${fmtClock(limit)} 晚 ${estEnd - limit} 分鐘",
                shortenMin = estEnd - limit
            )
        }
    }
    return out
}

/** 末班船：預計回到港口的時間已晚於末班 */
internal fun ferryConflict(arriveMin: Int, lastDepartMin: Int, portName: String): ScheduleConflict? =
    if (arriveMin <= lastDepartMin) null else ScheduleConflict(
        ConflictKind.FERRY_MISS, null,
        "預計 ${fmtClock(arriveMin)} 回到$portName，已晚於末班船 ${fmtClock(lastDepartMin)}",
        shortenMin = arriveMin - lastDepartMin
    )

/** 回程火車：抵站到（含誤點的）發車只剩不到 [TRAIN_TIGHT_MIN] 分鐘 */
internal fun trainConflict(arriveMin: Int, departMin: Int, delayMin: Int, trainLabel: String): ScheduleConflict? {
    val margin = departMin + delayMin - arriveMin
    if (margin > TRAIN_TIGHT_MIN) return null
    return ScheduleConflict(
        ConflictKind.TRAIN_TIGHT, null,
        "回程 ${fmtClock(departMin)} $trainLabel 只剩 ${margin.coerceAtLeast(0)} 分鐘緩衝",
        shortenMin = TRAIN_TIGHT_MIN - margin
    )
}

/**
 * 收集目前行程的所有衝突（行程頁與景點資訊頁共用）。已按「先不用」的會濾掉。
 */
@Composable
internal fun rememberScheduleConflicts(
    viewModel: ItineraryViewModel,
    tripVm: TripProgressViewModel,
    stops: List<Stop>,
    progress: TripProgress,
    derived: TripDerived
): List<ScheduleConflict> {
    val itinerary by viewModel.itinerary.collectAsState()
    val returnTrains by viewModel.returnTrains.collectAsState()
    val ctx = LocalContext.current
    val ferrySchedules = remember { com.example.travellink_ai.data.island.FerrySchedules.get(ctx.applicationContext) }
    val days = itinerary?.days.orEmpty()
    val region = itinerary?.region

    return remember(stops, progress, derived, days, region, returnTrains, tripVm.dismissedConflicts) {
        if (!progress.isOngoing) return@remember emptyList()
        val startDate = days.substringBefore(" ").takeIf { it.length == 10 }.orEmpty()
        val dayDate = if (startDate.isNotEmpty()) DayPlanner.addDays(startDate, derived.currentDayIndex - 1) else ""
        val dayEnd = DayPlanner.effectiveWindows(days, stops)
            .firstOrNull { it.dayIndex == derived.currentDayIndex }?.endMins
        val list = detectScheduleConflicts(stops, progress, derived, dayDate, dayEnd).toMutableList()

        // 回程站的預計抵達（deriveTripState 有給最後一個車站預估）
        val returnArrive = stops.lastOrNull { it.isStation }?.let { rs ->
            derived.estimatedTimes[rs.stopId] ?: parseClockToMinutes(rs.time)
        }
        // 末班船（A5 離島）
        val island = com.example.travellink_ai.data.island.IslandRegistry.byDestination(region)
        val route = island?.let { ferrySchedules.routeForIsland(it.code) }
        if (route != null && returnArrive != null && !ferrySchedules.isStale()) {
            route.activeSeason?.lastDeparture("island")?.let { parseClockToMinutes(it) }?.let { last ->
                ferryConflict(returnArrive, last, route.islandPort)?.let { list += it }
            }
        }
        // 回程火車（A7）
        returnTrains?.let { plan ->
            val arrive = parseClockToMinutes(plan.arriveTime)
            val rec = plan.recommended
            val depart = rec?.departureTime?.let { parseClockToMinutes(it) }
            if (arrive != null && depart != null) {
                trainConflict(arrive, depart, rec.delayMins ?: 0, "${rec.trainType} ${rec.trainNo}")?.let { list += it }
            }
        }
        list.filter { it.key !in tripVm.dismissedConflicts }
    }
}

/**
 * 衝突提醒與處理選項（第 5 項）：提早離開目前站、跳過該站、先不用。
 * 不自動刪站或重排，每個動作都由使用者按。
 */
@Composable
internal fun ScheduleConflictCard(
    conflicts: List<ScheduleConflict>,
    here: HereStop?,
    stopDone: (String) -> Boolean,
    onSetLeave: (HereStop, Int) -> Unit,
    onSkip: (String) -> Unit,
    onDismiss: (ScheduleConflict) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conflicts.isEmpty()) return
    val hasReal = conflicts.any { !it.isInfo }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (hasReal) DesignTokens.RedLight else DesignTokens.GoldLight),
        border = if (hasReal) BorderStroke(1.dp, DesignTokens.Red.copy(alpha = 0.4f)) else null,
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (hasReal) "⚠️ 照目前的時間可能趕不上" else "⏰ 時間提醒",
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (hasReal) DesignTokens.Red else DesignTokens.Accent2Dark
            )
            conflicts.forEach { c ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.message, fontSize = 13.sp, color = DesignTokens.Ink)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 提早離開目前站：最多只能提早到剛到達的時間
                        if (here != null && c.shortenMin > 0) {
                            val room = here.leaveMin - here.arrivedMin
                            val cut = minOf(c.shortenMin, room)
                            if (cut > 0) ConflictAction(
                                if (cut < c.shortenMin) "最多提早 $cut 分離開" else "提早 $cut 分離開"
                            ) { onSetLeave(here, here.leaveMin - cut) }
                        }
                        // 提早到要等開門：乾脆在目前站多待
                        if (here != null && c.extendMin > 0) {
                            ConflictAction("多待 ${c.extendMin} 分") { onSetLeave(here, here.leaveMin + c.extendMin) }
                        }
                        c.stopId?.takeIf { !stopDone(it) && !c.isInfo }?.let { sid ->
                            ConflictAction("跳過這站") { onSkip(sid) }
                        }
                        TextButton(onClick = { onDismiss(c) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("先不用", fontSize = 12.sp, color = DesignTokens.Ink3)
                        }
                    }
                }
            }
            if (hasReal) {
                Text("時間是依目前進度推估，實際以抵達為準。", fontSize = 11.sp, color = DesignTokens.Ink3)
            }
        }
    }
}

@Composable
private fun ConflictAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DesignTokens.Accent),
        border = BorderStroke(1.dp, DesignTokens.Accent.copy(alpha = 0.6f))
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
