package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 每一站的「附近替代景點」（對齊網頁：每站都備好附近的替代景點，途中隨時能換）。
 *
 * 網頁是生成時就為每站存好；App 改成從本地資料庫（local_places.json）即時算——
 * 不用多打一次 AI、不用改 Room／Firestore 的資料結構，離線也能用（2026-09-28 使用者決定）。
 * [BudgetSwap] 找「附近較省錢的景點」也共用這裡的候選篩選與替換。
 *
 * 純函式，不碰 ViewModel／網路，方便單元測試。
 */
object LocalAlternatives {

    /** 替代景點的範圍（直線公里）：途中臨時換，太遠就不算「附近」 */
    const val MAX_KM = 10.0
    const val MAX_OPTIONS = 3

    /**
     * 本地資料裡歸成「景點」、但不是可以取代景點的設施。實測富岡地質公園的第一個候選是
     * 0.2 公里外的「小野柳遊客中心」、三仙台是「三仙台遊客中心」——等於沒換。
     */
    private val NOT_A_SIGHT = listOf("遊客中心", "資訊站", "服務站", "公車站", "轉運站", "航空站", "休息區")

    private val MEAL_TYPES = setOf("餐廳", "咖啡廳")

    data class Candidate(val place: PlaceCost, val distanceKm: Double)

    /** 這站能不能有替代景點：車站、住宿、船班是錨點，不換 */
    fun isReplaceable(stop: Stop): Boolean = !stop.isStation && !stop.isLodging && !stop.isFerry

    /**
     * [target] 附近可以替換它的地點，依距離由近到遠（全部，呼叫端自行篩選、取前幾個）。
     * 餐廳換餐廳／咖啡廳，景點換景點；已在行程中的、當天公休或原抵達時間沒開的排除。
     *
     * @param date 這站那一天 `yyyy/MM/dd`；空字串＝不知道日期，不檢查營業時間
     */
    fun nearby(
        target: Stop,
        stops: List<Stop>,
        places: List<PlaceCost>,
        date: String,
        maxKm: Double = MAX_KM
    ): List<Candidate> {
        if (!isReplaceable(target)) return emptyList()
        // 目標沒存座標時，用名稱比對到的本地資料座標
        val origin = target.lat?.takeIf { target.lng != null }?.let { it to target.lng!! }
            ?: places.firstOrNull { CostReference.normKey(it.name) == CostReference.normKey(target.name) }
                ?.let { it.lat to it.lng }
            ?: return emptyList()

        val wantMeal = isMealStop(target)
        val usedKeys = stops.map { CostReference.normKey(it.name) }
        val arriveMins = hhmmToMins(target.time)

        return places.asSequence()
            .filter { p ->
                if (wantMeal) p.typeName in MEAL_TYPES
                else p.typeName == "景點" && NOT_A_SIGHT.none { k -> p.name.contains(k) }
            }
            .filter { p ->
                val k = CostReference.normKey(p.name)
                usedKeys.none { u -> u.isNotEmpty() && (k == u || k.contains(u) || u.contains(k)) }
            }
            .map { p -> Candidate(p, haversineKm(origin.first, origin.second, p.lat, p.lng)) }
            .filter { it.distanceKm <= maxKm }
            .filter { openForVisit(it.place, date, arriveMins, target.duration) }
            .sortedBy { it.distanceKm }
            .toList()
    }

    /**
     * 用 [place] 取代 [target]：沿用 stopId／dayIndex／order／time（時間由路線重算補正），
     * 其餘換成新地點的資料。營業時間依那一天取單日時段，與生成路徑一致。
     */
    fun applyTo(target: Stop, place: PlaceCost, date: String, desc: String): Stop {
        val hours = place.businessHours.takeIf { it.isNotBlank() }
            ?.let { if (date.isBlank()) it else BusinessHours.resolve(it, date) }
            ?: "未提供"
        return target.copy(
            name = place.name,
            emoji = if (place.typeName in MEAL_TYPES) "🍽️" else "📍",
            desc = desc,
            duration = place.duration.takeIf { it > 0 } ?: target.duration,
            businessHours = hours,
            nearbyToiletLocations = emptyList(),
            searchKeyword = place.name,
            placeId = "",
            bestTime = "",
            stopType = place.typeName,
            lat = place.lat,
            lng = place.lng
        )
    }

    /** 行程的 days（`yyyy/MM/dd HH:mm - …`）→ 第 [day] 天（1-based）的日期；解析不出回空字串 */
    fun dateOfDay(itineraryDays: String?, day: Int): String {
        val start = itineraryDays?.substringBefore(" ")?.take(10).orEmpty()
        if (start.length != 10) return ""
        return if (day > 1) DayPlanner.addDays(start, day - 1) else start
    }

    /** 那天有開、而且在原本的抵達時間待得下；沒資料或解析不出就不擋（隨行管家換景點也用這個檢查） */
    internal fun openForVisit(p: PlaceCost, date: String, arriveMins: Int?, visitMins: Long): Boolean {
        if (p.businessHours.isBlank() || date.isBlank()) return true
        val resolved = BusinessHours.resolve(p.businessHours, date)
        if (BusinessHours.isClosed(resolved)) return false
        if (arriveMins == null) return true
        val stay = (p.duration.takeIf { it > 0 } ?: visitMins).toInt()
        return BusinessHours.isOpenAt(resolved, arriveMins, stay) != false
    }

    private fun hhmmToMins(t: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(t) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
