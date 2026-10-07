package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

/**
 * 超出預算時的「建議替換」（對齊網頁：把最貴的景點換成附近省錢的）。
 *
 * 只給建議、由使用者確認才換，不自動改行程——使用者特地選的景點不該被默默換掉。
 *
 * 規則（2026-09-28 使用者決定）：
 *  - 替換對象：只看有**真實門票資料**的景點，挑最貴的一個。類型估算的票價（博物館 150、
 *    景點 60）是猜的，拿猜的數字去換掉使用者的景點不合理。餐廳、車站、住宿、船班不列入。
 *  - 候選：附近所有「估算門票比它便宜」的景點，不限定免費（確認免費的只有十幾個，
 *    嚴格限定免費的話多數行程找不到）。票價估算與費用卡同一套：真實 fee 優先，否則類型估算。
 *
 * 候選篩選（範圍、已在行程、營業時間、非景點設施）與 [LocalAlternatives] 共用。
 */
object BudgetSwap {

    /** 「附近」的範圍（直線公里）。比途中替代景點寬：台東景點稀疏，太小會常常找不到 */
    const val MAX_KM = 15.0
    const val MAX_OPTIONS = 3

    data class Option(
        val place: PlaceCost,
        val distanceKm: Double,
        val fee: Int,           // 每人門票（真實或估算）
        val feeKnown: Boolean,  // true＝真實門票資料；false＝類型估算
        val saving: Int         // 換掉後每人門票約省多少
    )

    data class Proposal(val target: Stop, val targetFee: Int, val options: List<Option>)

    /**
     * @param lookupFee 真實門票查詢（名稱, lat, lng）→ 元/人；null＝沒有資料。
     *                  正式環境傳 CostReference.lookup(...)?.fee，含人工覆蓋表。
     * @param dateOfDay 第幾天（1-based）→ `yyyy/MM/dd`；空字串＝不知道日期，不檢查營業時間
     * @return 沒有可換的對象時回 null；有對象但附近找不到更便宜的，options 為空
     */
    fun propose(
        stops: List<Stop>,
        places: List<PlaceCost>,
        lookupFee: (String, Double?, Double?) -> Int?,
        dateOfDay: (Int) -> String
    ): Proposal? {
        val (target, targetFee) = stops
            .filter { LocalAlternatives.isReplaceable(it) && !isMealStop(it) }
            .mapNotNull { s -> lookupFee(s.name, s.lat, s.lng)?.takeIf { it > 0 }?.let { s to it } }
            .maxByOrNull { it.second }
            ?: return null

        val options = LocalAlternatives
            .nearby(target, stops, places, dateOfDay(target.dayIndex), maxKm = MAX_KM)
            .asSequence()
            .mapNotNull { c ->
                val real = lookupFee(c.place.name, c.place.lat, c.place.lng)
                val fee = real ?: CostConfig.entranceFee(c.place.typeName)
                if (fee >= targetFee) null
                else Option(c.place, c.distanceKm, fee, feeKnown = real != null, saving = targetFee - fee)
            }
            .take(MAX_OPTIONS)
            .toList()
        return Proposal(target, targetFee, options)
    }

    fun applyTo(target: Stop, option: Option, date: String): Stop =
        LocalAlternatives.applyTo(target, option.place, date, "為省預算替換原本的「${target.name}」")
}
