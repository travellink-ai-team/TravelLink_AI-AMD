package com.example.travellink_ai.ui.explore

import com.example.travellink_ai.ui.planning.BusinessHours
import com.example.travellink_ai.ui.planning.LocalAlternatives
import com.example.travellink_ai.ui.planning.PlaceCost
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 「現在去哪？」：依使用者當下的位置，從本地資料（local_places.json）挑一個現在有開的地方。
 *
 * 和建立行程的差別在「快」：不呼叫 AI（不吃每日額度、網頁後端掛了也能用），也不存任何資料。
 * 天氣只影響加權；室內／戶外用名稱＋簡介的關鍵字粗分，分不出來的不受天氣影響
 * （2026-11-01 使用者決定，不另外跑 AI 標註）。
 *
 * 純函式，不碰 ViewModel／網路，方便單元測試。
 */
object QuickPick {

    enum class Category(val label: String) { ANY("隨便"), SIGHT("景點"), FOOD("吃的") }

    enum class Setting(val label: String) { INDOOR("室內"), OUTDOOR("戶外"), UNKNOWN("") }

    /** 沒有候選的原因：這一帶本來就沒有資料，或有資料但現在都沒開 */
    enum class Empty { NO_DATA, ALL_CLOSED }

    /**
     * 由近到遠逐步放寬。10 公里對「附近」太遠，所以從 3 公里起跳；
     * 現在有開的候選少於 [MIN_CANDIDATES] 個才放寬到下一圈（郊區 3 公里內常常只有兩三個點）
     */
    val RADII_KM = listOf(3.0, 5.0, 10.0)
    const val MIN_CANDIDATES = 3

    /** 只在分數前幾名裡抽，避免抽到又遠又沒評分的 */
    const val POOL = 15

    /** 「現在有開」至少要能待這麼久；建議停留比這短就用建議停留 */
    const val MIN_STAY_MINS = 60

    private val MEAL_TYPES = setOf("餐廳", "咖啡廳")

    data class Conditions(
        val lat: Double,
        val lng: Double,
        /** 現在幾點（當天 0–1439 分） */
        val nowMins: Int,
        /** 今天 `yyyy/MM/dd` */
        val date: String,
        /** 昨天 `yyyy/MM/dd`：凌晨還開著的店，用的是昨天的跨夜時段（如 18:00–02:00） */
        val yesterday: String,
        /** 接下來幾小時的降雨機率；-1＝不知道 */
        val rainProbability: Int = -1
    )

    data class Candidate(
        val place: PlaceCost,
        val km: Double,
        val setting: Setting,
        /** true＝現在有開；null＝沒有營業時間資料（照樣列入，但降權、卡片標「未確認」） */
        val open: Boolean?,
        /** 幾點關門（當天分鐘數，跨夜會超過 1440）；全天營業或不知道是 null */
        val closesAt: Int?,
        val allDay: Boolean,
        val score: Double
    )

    data class Result(val candidates: List<Candidate>, val radiusKm: Double, val empty: Empty?)

    /** 依 [cond] 的位置與時間找候選（分數由高到低），找不到時 [Result.empty] 說明原因 */
    fun candidates(places: List<PlaceCost>, cond: Conditions, category: Category): Result {
        val maxKm = RADII_KM.last()
        val nearby = places.filter { matches(it, category) }
            .map { it to km(cond.lat, cond.lng, it.lat, it.lng) }
            .filter { it.second <= maxKm }
        if (nearby.isEmpty()) return Result(emptyList(), maxKm, Empty.NO_DATA)
        val usable = nearby.mapNotNull { (p, d) -> candidate(p, d, cond) }
        for (r in RADII_KM) {
            val within = usable.filter { it.km <= r }
            if (within.size >= MIN_CANDIDATES || r == maxKm) {
                return if (within.isEmpty()) Result(emptyList(), r, Empty.ALL_CLOSED)
                else Result(within.sortedByDescending { it.score }, r, null)
            }
        }
        return Result(emptyList(), maxKm, Empty.ALL_CLOSED)
    }

    /** [places] 裡有沒有任何一個點在 [maxKm] 內（判斷使用者是不是在資料範圍外） */
    fun anyWithin(places: List<PlaceCost>, lat: Double, lng: Double, maxKm: Double = RADII_KM.last()): Boolean =
        places.any { km(lat, lng, it.lat, it.lng) <= maxKm }

    /**
     * 依分數加權抽一個，[exclude] 是這次已經看過的名稱。
     * 只在剩下的前 [POOL] 名裡抽：分數高的機率大，但不是每次都同一個。
     */
    fun draw(candidates: List<Candidate>, exclude: Set<String>, random: Random = Random.Default): Candidate? {
        val pool = candidates.filter { it.place.name !in exclude }
            .sortedByDescending { it.score }
            .take(POOL)
        if (pool.isEmpty()) return null
        var r = random.nextDouble() * pool.sumOf { it.score }
        for (c in pool) {
            r -= c.score
            if (r <= 0) return c
        }
        return pool.last()
    }

    /** 卡片上那一句「為什麼／要注意什麼」；沒有特別要說的回 null */
    fun hint(c: Candidate, cond: Conditions): String? {
        val rain = cond.rainProbability
        val meal = c.place.typeName in MEAL_TYPES
        return when {
            rain >= 60 && c.setting == Setting.INDOOR && !meal -> "接下來降雨機率 $rain%，挑了室內的"
            rain >= 60 && c.setting == Setting.OUTDOOR -> "接下來降雨機率 $rain%，記得帶傘"
            isNight(cond.nowMins) && c.setting == Setting.OUTDOOR && !nightFriendly(c.place) ->
                "天黑了，戶外光線不足，注意安全"
            c.closesAt != null && c.closesAt - cond.nowMins <= 90 -> "${hhmm(c.closesAt)} 就關門，要去得快一點"
            else -> null
        }
    }

    fun hoursLabel(c: Candidate): String = when {
        c.open == null -> "營業時間未確認"
        c.allDay -> "24 小時開放"
        c.closesAt != null -> "營業到 ${hhmm(c.closesAt)}"
        else -> "現在有開"
    }

    fun distanceLabel(km: Double): String =
        if (km < 1.0) "${((km * 1000) / 50).roundToInt().coerceAtLeast(1) * 50} 公尺"
        else "%.1f 公里".format(km)

    internal fun matches(p: PlaceCost, category: Category): Boolean {
        val sight = p.typeName == "景點" && LocalAlternatives.NOT_A_SIGHT.none { p.name.contains(it) }
        val meal = p.typeName in MEAL_TYPES
        return when (category) {
            Category.ANY -> sight || meal
            Category.SIGHT -> sight
            Category.FOOD -> meal
        }
    }

    private fun candidate(p: PlaceCost, km: Double, cond: Conditions): Candidate? {
        val stay = (p.duration.takeIf { it > 0 }?.toInt() ?: MIN_STAY_MINS).coerceAtMost(MIN_STAY_MINS)
        val status = openStatus(p.businessHours, cond, stay)
        if (status.open == false) return null
        val setting = classify(p)
        return Candidate(p, km, setting, status.open, status.closesAt, status.allDay,
            score(p, km, setting, status.open, cond))
    }

    internal data class OpenStatus(val open: Boolean?, val closesAt: Int?, val allDay: Boolean)

    /**
     * 現在開著、而且待 [stayMins] 分鐘內不會關門。沒有資料或解析不出時段回 open = null。
     * 凌晨先看昨天的跨夜時段：BusinessHours.isOpenAt 只認當天，01:00 會被當成 18:00–02:00 之前。
     */
    internal fun openStatus(raw: String, cond: Conditions, stayMins: Int): OpenStatus {
        if (raw.isBlank()) return OpenStatus(null, null, false)
        val late = cond.nowMins + 24 * 60
        BusinessHours.openRanges(BusinessHours.resolve(raw, cond.yesterday))
            ?.firstOrNull { (open, close) -> close > 24 * 60 && late >= open && late + stayMins <= close }
            ?.let { return OpenStatus(true, it.second - 24 * 60, false) }
        val today = BusinessHours.resolve(raw, cond.date)
        val open = BusinessHours.isOpenAt(today, cond.nowMins, stayMins)
        if (open != true) return OpenStatus(open, null, false)
        val ranges = BusinessHours.openRanges(today) ?: return OpenStatus(true, null, true)   // 全天營業
        val close = ranges.firstOrNull { (o, c) -> cond.nowMins >= o && cond.nowMins + stayMins <= c }?.second
        return OpenStatus(true, close, false)
    }

    /**
     * 分數＝基本分＋評分＋距離，再乘上天氣、時段的係數。
     * 沒評分的給中間值（不因為資料缺漏就沉底）；沒營業時間的打六折。
     */
    internal fun score(p: PlaceCost, km: Double, setting: Setting, open: Boolean?, cond: Conditions): Double {
        val rating = if (p.rating > 0) ((p.rating - 3.5) / 1.5).coerceIn(0.0, 1.0) else 0.4
        var s = 1.0 + rating * 1.5 + 1.0 / (1.0 + km / 2.0)
        if (open == null) s *= 0.6
        val rain = cond.rainProbability
        if (setting == Setting.OUTDOOR) when {
            rain >= 60 -> s *= 0.3
            rain >= 30 -> s *= 0.7
        }
        if (setting == Setting.INDOOR && rain >= 60) s *= 1.3
        // 24 小時開放的多半是海邊、步道：晚上照樣「有開」，但不該推
        if (isNight(cond.nowMins) && setting == Setting.OUTDOOR && !nightFriendly(p)) s *= 0.15
        return s
    }

    // ── 室內／戶外：名稱命中算 3 分、簡介命中算 1 分，哪邊高就是哪邊，平手＝分不出來 ──

    private val INDOOR_NAME = listOf(
        "博物館", "美術館", "紀念館", "故事館", "展示館", "館", "圖書", "書店", "書", "藝文", "文創",
        "教堂", "聖堂", "教會", "廟", "宮", "寺", "堂", "工坊", "劇場", "商場", "夢工場", "倉庫",
        "冰城", "屋", "監獄", "車站", "教室"
    )
    private val OUTDOOR_NAME = listOf(
        "步道", "海灘", "沙灘", "海岸", "觀景", "瞭望", "公園", "森林", "濕地", "溫泉", "瀑布", "湖",
        "大坡池", "山", "岬", "鼻", "港", "農場", "牧場", "草原", "溪", "灣", "橋", "自行車", "綠道",
        "礁", "岩", "惡地", "林道", "古道", "大道", "遺址", "浮潛", "休憩區", "遊憩區", "埡口", "隧道",
        "部落", "洞", "峽谷", "砲台", "高台", "神社", "碑", "平交道", "崗哨", "樹", "廣場", "街",
        "夜市", "鐵道", "路廊", "植物園", "生態園區", "蝴蝶谷", "秘境", "聚落", "特區", "比西里岸"
    )
    private val INDOOR_DESC = listOf("室內", "展示廳", "展覽", "館內", "館藏", "冷氣", "展示")
    private val OUTDOOR_DESC = listOf(
        "步道", "海景", "沙灘", "草原", "露天", "戶外", "海岸", "觀景", "登山", "健行", "日出", "夕陽"
    )

    /** 關鍵字判斷錯、又常被推到的少數點，直接指定 */
    private val SETTING_OVERRIDES = mapOf(
        "白色恐怖綠島紀念園區" to Setting.INDOOR,
        "卡地布文化園區" to Setting.OUTDOOR,
        "綠島豆丁海馬海底郵筒(綠島海底世界)" to Setting.OUTDOOR
    )

    internal fun classify(p: PlaceCost): Setting {
        if (p.typeName in MEAL_TYPES) return Setting.INDOOR
        SETTING_OVERRIDES[p.name]?.let { return it }
        fun hits(text: String, keys: List<String>) = keys.count { text.contains(it) }
        val indoor = hits(p.name, INDOOR_NAME) * 3 + hits(p.desc, INDOOR_DESC)
        val outdoor = hits(p.name, OUTDOOR_NAME) * 3 + hits(p.desc, OUTDOOR_DESC)
        return when {
            indoor > outdoor -> Setting.INDOOR
            outdoor > indoor -> Setting.OUTDOOR
            else -> Setting.UNKNOWN
        }
    }

    private val NIGHT_OK = listOf("夜景", "星空", "觀星", "夜市", "夜間", "燈")

    private fun nightFriendly(p: PlaceCost) = NIGHT_OK.any { p.name.contains(it) || p.desc.contains(it) }

    private fun isNight(nowMins: Int) = nowMins >= 19 * 60 || nowMins < 5 * 60

    private fun hhmm(mins: Int): String = (mins % (24 * 60)).let { "%02d:%02d".format(it / 60, it % 60) }

    internal fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
