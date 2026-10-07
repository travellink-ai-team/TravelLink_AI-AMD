package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

// 台東旅遊費率參考表（對應網頁端 cost-config.js）
object CostConfig {
    /**
     * 每公里「每車」費率（2026-07-17 對齊網頁 cost-config.js modeRates）：
     * perKm 每公里、base 每段起跳、capacity 每車乘載。
     * 計價方式與網頁一致：車數 = ceil(人數/容量)，段費 =（起跳 + perKm×公里數）×車數，
     * 最後除以人數得人均。walking 為 0；網頁已移除大眾運輸（public→car）。
     */
    data class ModeRate(val perKm: Double, val base: Int, val capacity: Int)

    private val MODE_RATES = mapOf(
        "walking" to ModeRate(0.0,  0,  99),
        "walk"    to ModeRate(0.0,  0,  99),
        "scooter" to ModeRate(1.5,  0,  2),
        "car"     to ModeRate(4.0,  0,  4),
        "taxi"    to ModeRate(20.0, 85, 4),
    )

    fun modeRate(mode: String): ModeRate = MODE_RATES[mode] ?: MODE_RATES.getValue("taxi")

    /** 無座標可算距離時的退路：以台東平均時速 35 km/h 由車程分鐘換算公里 */
    const val FALLBACK_KMH = 35.0

    /** 直線距離 → 道路距離的繞行係數 */
    const val ROAD_DETOUR_FACTOR = 1.3

    // 景點入場費（元/人），依 stopType 模糊比對
    private val ENTRANCE_FEE_TABLE = listOf(
        "博物館" to 150,
        "文化館" to 100,
        "藝廊"   to 80,
        "展覽"   to 80,
        "遊樂"   to 200,
        "景點"   to 60,
    )

    // 餐費（元/人/餐）依預算等級。新值與網頁 BUDGET_TIERS 對齊（節省/適中/舒適/豪華），
    // 舊值保留相容歷史行程；新 token 格式「適中（每人 $500–$1,500）」以 contains 比對可命中
    private val MEAL_COST_TABLE = mapOf(
        "節省" to 120,
        "省錢" to 120,
        "適中" to 280,
        "一般" to 280,
        "舒適" to 450,
        "豪華" to 580,
        "享受" to 580,
        "不設限" to 580,
    )
    const val DEFAULT_MEAL_COST = 250

    fun entranceFee(stopType: String): Int =
        ENTRANCE_FEE_TABLE.firstOrNull { stopType.contains(it.first) }?.second ?: 0

    fun mealCostPerPerson(budget: String): Int =
        MEAL_COST_TABLE.entries.firstOrNull { budget.contains(it.key) }?.value ?: DEFAULT_MEAL_COST

    /** 人均預算級距（與網頁 buildBudgetBreakdown 的 tiers 一致）；perMax = null 代表無上限 */
    data class BudgetTier(val key: String, val perMin: Int, val perMax: Int?)

    private val BUDGET_TIERS = listOf(
        BudgetTier("節省", 0,    500),
        BudgetTier("適中", 500,  1500),
        BudgetTier("舒適", 1500, 3000),
        BudgetTier("豪華", 3000, null),
    )

    /**
     * 從 budget 字串解析人均預算級距（供費用卡的預算追蹤區塊使用）。
     * 先比對等級名稱（含舊值別名），再退回抓字串中最大的數字歸級；都失敗回 null。
     */
    fun budgetTier(budget: String): BudgetTier? {
        val str = budget.trim()
        if (str.isEmpty()) return null
        // 舊值相容：省錢→節省、一般→適中、享受/不設限→豪華
        val aliased = str
            .replace("省錢", "節省").replace("一般", "適中")
            .replace("享受", "豪華").replace("不設限", "豪華")
        BUDGET_TIERS.firstOrNull { aliased.contains(it.key) }?.let { return it }
        val amount = Regex("""\d[\d,]*""").findAll(str)
            .mapNotNull { it.value.replace(",", "").toIntOrNull() }
            .maxOrNull() ?: return null
        return BUDGET_TIERS.firstOrNull { t ->
            if (t.perMax == null) amount >= t.perMin else amount <= t.perMax
        } ?: BUDGET_TIERS.last()
    }
}

data class CostBreakdown(
    val transportPerPerson: Int,
    val entrancePerPerson: Int,
    val mealPerPerson: Int,
    val totalPerPerson: Int,
    val grandTotal: Int,
    val peopleCount: Int,
    // 🌟 資料來源統計：用於在費用卡標示「其中 N 項採實際收費資料」
    val entranceMatched: Int = 0, // 命中真實門票資料的景點數
    val sightCount: Int = 0,      // 需估算門票的景點總數
    val mealMatched: Int = 0,     // 命中真實餐費資料的餐廳數
    val mealCount: Int = 0        // 餐廳總數
)

/** 費用估算把這站算餐費而不是門票（[BudgetSwap] 挑替換對象時也用同一個判斷） */
internal fun isMealStop(s: Stop): Boolean =
    s.stopType.contains("餐廳") || s.name.contains("午餐") ||
    s.name.contains("晚餐") || s.desc.contains("用餐")

/**
 * 估算整趟行程費用。
 *
 * @param reference 本地成本參考庫（[CostReference]）。命中時以真實票價／餐費計算，
 *                  未命中則回退 [CostConfig] 的參考表；傳 null 則完全使用參考表（行為同舊版）。
 */
fun estimateTripCost(
    stops: List<Stop>,
    transitTimes: List<Long>,
    segmentModes: List<String>,
    budget: String,
    people: String,
    reference: CostReference? = null
): CostBreakdown {
    val peopleCount = people.filter { it.isDigit() }.toIntOrNull()?.coerceAtLeast(1) ?: 2
    val effectiveModes = segmentModes.ifEmpty { List(transitTimes.size) { "taxi" } }

    // 交通費（對齊網頁 modeRates：每公里×每車，人數攤分）。
    // 距離：兩站座標 haversine × 繞行係數；缺座標的段退回「車程分鐘×平均時速」。
    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    val transportTotal = transitTimes.indices.sumOf { i ->
        val mode = effectiveModes.getOrNull(i) ?: "taxi"
        val rate = CostConfig.modeRate(mode)
        if (rate.perKm == 0.0 && rate.base == 0) return@sumOf 0.0
        val from = stops.getOrNull(i)
        val to   = stops.getOrNull(i + 1)
        // A5：跨海段不能套每公里費率——這段是船不是車。過去離島行程會把 33 公里
        // 海路乘上計程車每公里 20 元，費用卡直接失真。船票走另一套（Stage 5 由
        // ferry_schedules.json 帶入實際票價），這裡先確保不會算成車資。
        if (from?.isFerry == true || to?.isFerry == true) return@sumOf 0.0
        val km = if (from != null && from.lat != null && from.lng != null &&
            to != null && to.lat != null && to.lng != null
        ) {
            haversineKm(from.lat, from.lng, to.lat, to.lng) * CostConfig.ROAD_DETOUR_FACTOR
        } else {
            (transitTimes.getOrNull(i) ?: 0L).toDouble() / 60.0 * CostConfig.FALLBACK_KMH
        }
        val vehicles = ((peopleCount + rate.capacity - 1) / rate.capacity).coerceAtLeast(1)
        (rate.base + rate.perKm * km) * vehicles
    }
    val transportPerPerson = kotlin.math.ceil(transportTotal / peopleCount).toInt()

    val nonStationStops = stops.filter { !it.isStation }
    val mealStops  = nonStationStops.filter(::isMealStop)
    val sightStops = nonStationStops.filterNot(::isMealStop)

    // 門票：優先用真實 fee（含 0 元免費），未命中才回退類型參考表
    var entranceMatched = 0
    val entrancePerPerson = sightStops.sumOf { stop ->
        val realFee = reference?.lookup(stop.name, stop.lat, stop.lng)?.fee
        if (realFee != null) {
            entranceMatched++
            realFee
        } else {
            CostConfig.entranceFee(stop.stopType)
        }
    }

    // 餐費：優先用該店真實 costPerPerson，未命中才回退預算等級表
    var mealMatched = 0
    val fallbackMeal = CostConfig.mealCostPerPerson(budget)
    val mealPerPerson = mealStops.sumOf { stop ->
        val realMeal = reference?.lookup(stop.name, stop.lat, stop.lng)?.costPerPerson
        if (realMeal != null) {
            mealMatched++
            realMeal
        } else {
            fallbackMeal
        }
    }

    val totalPerPerson = transportPerPerson + entrancePerPerson + mealPerPerson
    return CostBreakdown(
        transportPerPerson = transportPerPerson,
        entrancePerPerson  = entrancePerPerson,
        mealPerPerson      = mealPerPerson,
        totalPerPerson     = totalPerPerson,
        grandTotal         = totalPerPerson * peopleCount,
        peopleCount        = peopleCount,
        entranceMatched    = entranceMatched,
        sightCount         = sightStops.size,
        mealMatched        = mealMatched,
        mealCount          = mealStops.size
    )
}
