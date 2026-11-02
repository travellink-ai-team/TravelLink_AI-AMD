package com.example.travellink_ai.ui.explore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.weather.CwaWeatherService
import com.example.travellink_ai.data.weather.WeatherInfo
import com.example.travellink_ai.debug.DemoClock
import com.example.travellink_ai.ui.planning.CostReference
import com.example.travellink_ai.ui.planning.PlaceCost
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * 首頁「現在去哪？」面板的狀態（規則見 [QuickPick]）。
 *
 * 不寫 Firestore／Room／DataStore：看過哪些只放在記憶體，關掉面板（[close]）就清空，
 * 篩選每次都從「隨便」開始。定位只用來在手機上算距離，查天氣送出去的是鄉鎮名稱。
 */
@HiltViewModel
class QuickPickViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val category: QuickPick.Category = QuickPick.Category.ANY,
        /** 推薦的起點：「依你現在的位置」，或退回台東市區的原因 */
        val originNote: String = "",
        val weather: WeatherInfo? = null,
        val current: QuickPick.Candidate? = null,
        val hint: String? = null,
        /** 實際用的半徑；比 [QuickPick.RADII_KM] 第一圈大＝近的不夠、已放寬 */
        val radiusKm: Double = QuickPick.RADII_KM.first(),
        val empty: QuickPick.Empty? = null,
        /** 附近的都看過一輪了，從頭再抽 */
        val wrapped: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var places: List<PlaceCost> = emptyList()
    private var cond: QuickPick.Conditions? = null
    private var candidates: List<QuickPick.Candidate> = emptyList()
    /** 這次已經抽過的名稱 */
    private val shown = mutableSetOf<String>()
    private var job: Job? = null

    /** 面板打開時呼叫（定位權限的結果出來之後） */
    fun open(hasLocationPermission: Boolean) {
        job?.cancel()
        shown.clear()
        _state.value = UiState(loading = true)
        job = viewModelScope.launch {
            places = withContext(Dispatchers.IO) { CostReference.get(context).places }
            val loc = if (hasLocationPermission) currentLocation() else null
            var origin = loc ?: TAITUNG_CITY
            var note = if (loc != null) "依你現在的位置" else "沒有定位，先以台東市區推薦"
            if (loc != null && !QuickPick.anyWithin(places, loc.first, loc.second)) {
                origin = TAITUNG_CITY
                note = "你目前不在台東資料範圍內，先以台東市區推薦"
            }
            // 天氣只是加權用，最多等 4 秒，查不到就當不知道
            val weather = withTimeoutOrNull(4_000L) {
                runCatching { CwaWeatherService.fetchNextHours(origin.first, origin.second) }.getOrNull()
            }
            cond = conditionsAt(origin, weather?.rainProbability ?: -1)
            _state.update { it.copy(originNote = note, weather = weather) }
            recompute()
        }
    }

    fun setCategory(category: QuickPick.Category) {
        if (category == _state.value.category) return
        _state.update { it.copy(category = category) }
        recompute()
    }

    /** 「換一個」 */
    fun next() {
        val c = cond ?: return
        var wrapped = false
        var pick = QuickPick.draw(candidates, shown)
        if (pick == null && candidates.isNotEmpty()) {
            // 全部看過：從頭再抽，但不要馬上又是剛剛那一個
            shown.clear()
            wrapped = true
            val last = _state.value.current?.place?.name
            pick = QuickPick.draw(candidates, if (candidates.size > 1 && last != null) setOf(last) else emptySet())
        }
        pick?.let { shown += it.place.name }
        _state.update { it.copy(current = pick, hint = pick?.let { p -> QuickPick.hint(p, c) }, wrapped = wrapped) }
    }

    /** 關掉面板：看過的清單、位置一併丟掉 */
    fun close() {
        job?.cancel()
        shown.clear()
        candidates = emptyList()
        cond = null
        _state.value = UiState()
    }

    private fun recompute() {
        val c = cond ?: return
        val r = QuickPick.candidates(places, c, _state.value.category)
        candidates = r.candidates
        shown.clear()
        _state.update { it.copy(loading = false, radiusKm = r.radiusKm, empty = r.empty, current = null, wrapped = false) }
        next()
    }

    private fun conditionsAt(origin: Pair<Double, Double>, rain: Int): QuickPick.Conditions {
        val cal = Calendar.getInstance().apply { timeInMillis = DemoClock.now() }
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = fmt.format(cal.time)
        val yesterday = fmt.format(cal.apply { add(Calendar.DAY_OF_MONTH, -1) }.time)
        return QuickPick.Conditions(origin.first, origin.second, nowMins, today, yesterday, rain)
    }

    /** 十分鐘內的最後已知位置優先（快），沒有才主動定位一次，最多等 8 秒 */
    private suspend fun currentLocation(): Pair<Double, Double>? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            @Suppress("MissingPermission")
            val last = client.lastLocation.await()
                ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
            val loc = last ?: withTimeoutOrNull(8_000L) {
                @Suppress("MissingPermission")
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
            }
            loc?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** 沒有定位時的起點：臺東市（鐵花村一帶，資料最密） */
        private val TAITUNG_CITY = 22.7554 to 121.1465
    }
}
