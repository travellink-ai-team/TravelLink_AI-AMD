package com.example.travellink_ai.ui.recap

import com.example.travellink_ai.data.local.LocalItinerary
import com.example.travellink_ai.ui.planning.PlaceCost
import java.util.Calendar

/**
 * AI 寫字幕用的「實用資訊」（C4）：營業時間（行程當天）、門票／餐費（只用真實資料）、停好車走幾分鐘（開車、機車才有）。
 * 字幕只能照抄這裡的數字，所以拿不準的一律不給：當天公休、日期不明又每天不同、只有參考表估價的都略過。
 */
object RecapFacts {
    private val DATE = Regex("(\\d{4})[/.-](\\d{1,2})[/.-](\\d{1,2})")
    private val DAY_LINE = Regex("^\\s*(星期[一二三四五六日天])\\s*[:：]\\s*(.+)$")
    private val CLOCK = Regex("\\d{1,2}:\\d{2}")
    private val WEEKDAYS = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /**
     * @param cost 名稱＋座標 → 真實費用（[com.example.travellink_ai.ui.planning.CostReference.lookup]）
     * @param parkingWalkMins 座標 → 最近停車場走過去幾分鐘；null＝這趟不是開車／機車
     * @return stopId → 「營業時間 09:00–17:00；門票 50 元；停好車走約 3 分鐘」
     */
    fun build(
        item: LocalItinerary, trip: RecapTrip,
        cost: (String, Double, Double) -> PlaceCost?,
        parkingWalkMins: ((Double, Double) -> Int?)?
    ): Map<String, String> {
        val start = tripStart(item.days)
        val raw = item.stops.associateBy { it.stopId }
        return trip.stops.mapNotNull { s ->
            val src = raw[s.stopId] ?: return@mapNotNull null
            if (src.isLodging || src.isFerry) return@mapNotNull null
            val parts = listOfNotNull(
                hoursOn(src.businessHours, start?.let { weekdayOf(it, s.dayIndex) })?.let { "營業時間 $it" },
                cost(s.name, s.lat, s.lng)?.let { feeText(it) },
                parkingWalkMins?.invoke(s.lat, s.lng)?.let { "停好車走約 $it 分鐘" }
            )
            if (parts.isEmpty()) null else s.stopId to parts.joinToString("；")
        }.toMap()
    }

    /** 行程第一天（item.days 例：「2026/08/13 - 2026/08/14」）；看不出日期回 null */
    fun tripStart(days: String): Calendar? {
        val m = DATE.find(days) ?: return null
        val (y, mo, d) = m.destructured
        return Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt()) }
    }

    /** 第 dayIndex 天是星期幾（「星期一」…） */
    fun weekdayOf(start: Calendar, dayIndex: Int): String {
        val c = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, (dayIndex - 1).coerceAtLeast(0)) }
        return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /**
     * 那一天的營業時間，整理成「11:30–14:00、17:00–20:30」或「24 小時營業」。
     * 單日格式（沒有星期）直接用；不知道是星期幾時，只有每天都一樣才用；公休、沒資料回 null。
     */
    fun hoursOn(businessHours: String, weekday: String?): String? {
        val text = businessHours.trim()
        if (text.isEmpty() || text == "未提供") return null
        val byDay = text.lines().mapNotNull { DAY_LINE.find(it)?.destructured?.let { (d, h) -> d.replace("星期天", "星期日") to h.trim() } }.toMap()
        val h = when {
            byDay.isEmpty() -> text
            weekday != null -> byDay[weekday] ?: return null
            else -> byDay.values.distinct().singleOrNull() ?: return null
        }
        if (h.contains("24 小時") || h.contains("24小時")) return "24 小時營業"
        if (!CLOCK.containsMatchIn(h)) return null   // 休息、不定期等
        return h.replace(Regex("\\s*[–—~-]\\s*"), "–").replace(Regex("\\s*[,，、]\\s*"), "、")
    }

    /** 門票優先（0＝免費入場），沒有門票資料才看餐費；都沒有回 null */
    fun feeText(p: PlaceCost): String? = when {
        p.fee == 0 -> "免費入場"
        p.fee != null && p.fee > 0 -> "門票 ${p.fee} 元"
        p.costPerPerson != null && p.costPerPerson > 0 -> "每人約 ${p.costPerPerson} 元"
        else -> null
    }
}
