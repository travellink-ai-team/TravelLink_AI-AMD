package com.example.travellink_ai.ui.planning

import kotlin.math.ln

/**
 * 早出發那天的早餐。
 *
 * 實測 9/21 行程第 2 天 05:14 出發、看完日出後一路排景點，11:49 才有第一餐——
 * 中間六個多小時沒吃東西。用餐機制只認午餐與晚餐窗口，完全沒有早餐這回事。
 *
 * 本地景點庫也救不了：台東市 12 公里內 46 家餐飲，週二 09:00 前就開門的只有 3 家
 * （麥當勞、一家 11 公里外的植物園餐廳、一家 09:00 才開的剉冰），沒有任何早餐店。
 * 所以候選要另外向 Google Places 查（由呼叫端負責），這裡只負責「哪一家適合」。
 */
data class BreakfastCandidate(
    val name: String,
    /** Google 評論數：當作「真的有人在營業、有人去」的指標，太少的不敢排進行程 */
    val ratings: Int,
    val distanceKm: Double,
    /** 已依日期取單日的營業時間 */
    val hours: String
)

object BreakfastSpots {

    /**
     * 出發時間不晚於此（07:30）就需要另外排早餐。再晚出發的話，一般是在住宿吃過才出門；
     * 09:00 出發的行程完全不受影響。
     */
    const val NEEDED_IF_START_AT_OR_BEFORE = 7 * 60 + 30

    /** 早餐停留時間 */
    const val STAY_MINS = 40

    /** 出發後抵達第一站的預估分鐘數（與日出點的估算一致） */
    private const val ARRIVE_AFTER_START_MINS = 10

    /** 看完日出後到早餐店：日出點停留 45 分＋車程約 15 分 */
    private const val AFTER_SUNRISE_MINS = 60

    /** 評論數低於此的不排：早上開門的小店常常資料不全，沒人評論的可能早已歇業 */
    const val MIN_RATINGS = 15

    const val SEARCH_RADIUS_M = 3000

    fun needsBreakfast(dayStartMins: Int): Boolean = dayStartMins <= NEEDED_IF_START_AT_OR_BEFORE

    /** 預計幾點吃早餐（用來判斷店家那個時刻有沒有開） */
    fun targetMins(dayStartMins: Int, afterSunrise: Boolean): Int =
        dayStartMins + ARRIVE_AFTER_START_MINS + if (afterSunrise) AFTER_SUNRISE_MINS else 0

    /**
     * 0 = 不適合。抵達時必須確定開著（營業時間不明的不敢排：早上本來就少有店開，
     * 排一家不知道開不開的，使用者站在門口才發現沒開，比沒有早餐更糟）。
     * 通過後看人氣（評論數，取對數避免大店獨佔）與距離。
     */
    fun score(c: BreakfastCandidate, targetMins: Int): Int {
        if (c.ratings < MIN_RATINGS) return 0
        if (BusinessHours.isOpenAt(c.hours, targetMins, STAY_MINS) != true) return 0
        val popularity = minOf(30.0, ln(c.ratings + 1.0) * 6).toInt()
        val distancePenalty = minOf(c.distanceKm * 5, 25.0).toInt()
        return (50 + popularity - distancePenalty).coerceAtLeast(1)
    }

    /** 沒有適合的回 null（呼叫端會在結果說明如實寫「附近沒查到這個時段有開的早餐店」） */
    fun pick(candidates: List<BreakfastCandidate>, targetMins: Int): BreakfastCandidate? =
        candidates
            .map { it to score(it, targetMins) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
}
