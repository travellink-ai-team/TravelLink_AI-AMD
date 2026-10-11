package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

/**
 * 兩天一夜的住宿站。
 *
 * 過去使用者填的「已訂住宿」只變成 prompt 裡的一行字：行程裡沒有住宿站、沒有座標，
 * 排程器不知道有「過夜」。第 2 天的起點是第 1 天最後一站的座標，跟飯店在哪無關；
 * 每天又都假設「最後回到當天起點」，第 2 天的回程車程因此算到第 1 天最後一站，
 * 不是回程車站。
 *
 * 住宿站用 `isStation = true` + `isLodging = true`：既有約 40 個 isStation 的使用點
 * 幾乎全是「不當景點看待」（不計景點數、不算費用、不能編輯拖曳、不能打卡、不進回憶相簿），
 * 飯店正好需要這一整套待遇（原計畫 A5 也是這樣設計：「跟 isStation、isFerry 同一套機制」）。
 * 只有極少數地方把 isStation 當成出發／回程車站，另外處理。
 *
 * A 型：飯店是第 1 天的最後一站（收工後入住）。第 2 天從飯店出發。
 */
/** 精靈裡住宿名稱的查詢結果，用來在欄位下方即時告訴使用者「找到了沒」 */
sealed interface LodgingLookup {
    object Searching : LodgingLookup
    /** [officialName] 是 Google 上的正式名稱（使用者可能只填簡稱），[distanceKm] 距目的地中心 */
    data class Found(val officialName: String, val distanceKm: Double) : LodgingLookup
    /** Google 查得到結果但沒有一筆像這個名稱 */
    object NotFound : LodgingLookup
    /** 網路或服務問題：不代表飯店不存在，生成時會再試一次 */
    object Failed : LodgingLookup
}

object Lodging {

    /**
     * 欄位下方的提示文字。過去查詢只在按下「AI 生成行程」之後才發生，使用者填完名稱
     * 沒有任何回饋，要等結果出來才知道飯店有沒有被找到。
     *
     * 查不到時要講清楚後果（生成時不會排入住宿站），而不是只說「找不到」；
     * 網路失敗則明講「不代表不存在」，避免使用者以為自己填錯而去改對的名稱。
     */
    fun statusText(state: LodgingLookup, typedName: String): String = when (state) {
        LodgingLookup.Searching -> "🔎 查詢住宿位置中…"
        is LodgingLookup.Found -> {
            val name = state.officialName
            val km = "%.1f".format(state.distanceKm)
            if (name == typedName.trim()) "✅ 找到：$name（距市中心約 $km 公里）"
            else "✅ 找到：$name（你填的是「${typedName.trim()}」，距市中心約 $km 公里）"
        }
        LodgingLookup.NotFound ->
            "⚠️ 找不到「${typedName.trim()}」。請確認名稱（可貼上 Google 地圖上的全名）；" +
                "查不到的話，生成時不會排入住宿站"
        LodgingLookup.Failed -> "⚠️ 暫時查詢不到（網路或服務問題），不代表飯店不存在；生成時會再試一次"
    }

    /** 入住手續（放行李、辦理入住）停留分鐘數 */
    const val CHECK_IN_STAY_MINS = 30

    /** 飯店早餐加退房的停留分鐘數 */
    const val BREAKFAST_STAY_MINS = 45

    /** 只退房（不含早餐）的停留分鐘數：還鑰匙、拿行李 */
    const val CHECK_OUT_STAY_MINS = 20

    /** 台灣飯店多半 11:00 前退房；日出後回去的抵達時間超過這個就要提醒 */
    const val CHECK_OUT_DEADLINE_MINS = 11 * 60

    /** 日出後回飯店休息、退房，預計離開的時間（使用者要求：休息到 10 點左右再出發） */
    const val LEAVE_HOTEL_MINS = 10 * 60

    /** 離開飯店後至少要剩這麼久才值得休息到 [LEAVE_HOTEL_MINS]，否則退房後就直接出發 */
    const val MIN_DAY_AFTER_REST_MINS = 3 * 60

    /**
     * 日出後在飯店休息到幾點才離開；當天結束時間太早、休息完剩不到 [MIN_DAY_AFTER_REST_MINS]
     * 就回 null（不休息，辦完退房就出發），免得把整天的行程壓縮到只剩一兩站。
     */
    fun restUntilMins(dayEndMins: Int): Int? =
        LEAVE_HOTEL_MINS.takeIf { dayEndMins - it >= MIN_DAY_AFTER_REST_MINS }

    /**
     * 第 2 天起、看完日出後要不要回飯店休息退房。
     *
     * 早出發看日出的人離開飯店時還沒退房（退房手續、行李都在飯店），日出後回去休息一下，
     * 正式行程從離開飯店後才開始。沒有日出（一般 09:00 出發）就是從飯店直接出發，不需要多這一站；
     * 離島由船班決定起訖，不處理。
     */
    fun needsCheckoutStop(dayIndex: Int, hasHotel: Boolean, hasSunrise: Boolean, isIsland: Boolean): Boolean =
        dayIndex >= 2 && hasHotel && hasSunrise && !isIsland

    /**
     * 休息過的飯店站的說明文字。卡片上只有抵達時間，不寫離開時間的話，使用者要看下一站
     * 才知道要休息到幾點；[departMins] 用排程後的實際離開時間（抵達＋停留），不是目標值。
     */
    fun restDesc(withBreakfast: Boolean, departMins: Int): String =
        (if (withBreakfast) "早餐・休息・退房" else "休息・退房") + "（約 ${DayPlanner.hhmmOf(departMins)} 離開）"

    /** 只退房的站名：與入住站、早餐站都不同（排程與座標表都以站名為 key） */
    fun checkoutStopName(hotelName: String) = "$hotelName（退房）"

    /**
     * 日出後回飯店只辦退房（住宿不含早餐，早餐另外找店）。
     * 不寫營業時間：櫃檯本來就 24 小時或不受限，不編造。
     */
    fun checkoutStop(hotelName: String, lat: Double, lng: Double, dayIndex: Int): Stop = Stop(
        name = checkoutStopName(hotelName), time = "00:00", desc = "退房", emoji = "🧳",
        duration = CHECK_OUT_STAY_MINS.toLong(), order = 0L, isStation = true,
        stopType = "住宿", lat = lat, lng = lng, dayIndex = dayIndex, isLodging = true
    )

    /** 使用者填的入住時間 "HH:mm" → 分鐘；空白或格式不對回 null（表示沒有指定） */
    fun parseCheckIn(text: String): Int? = DayPlanner.parseHhMm(text.trim())

    /**
     * 第 1 天最後一站的入住站。
     *
     * 抵達時間與使用者指定的入住時間取晚的：飯店入住通常 15:00 起，太早到要等；
     * 沒指定時間就用抵達時間，不擅自假設一個入住時刻。
     */
    fun checkInStop(
        name: String, lat: Double, lng: Double,
        arrivalMins: Int, requestedCheckInMins: Int?, dayIndex: Int
    ): Stop {
        val at = maxOf(arrivalMins, requestedCheckInMins ?: arrivalMins)
        return Stop(
            name = name, time = DayPlanner.hhmmOf(at), desc = "入住", emoji = "🏨",
            duration = CHECK_IN_STAY_MINS.toLong(), order = 0L, isStation = true,
            stopType = "住宿", lat = lat, lng = lng, dayIndex = dayIndex, isLodging = true
        )
    }

    /** 飯店早餐的站名：與入住站同一家飯店，但站名要不同（排程與座標表都以站名為 key） */
    fun breakfastStopName(hotelName: String) = "$hotelName（早餐・退房）"

    /**
     * 第 2 天日出後回飯店吃早餐、退房。
     * 不寫營業時間：不知道飯店的供餐時段，寧可留空也不編一個。
     */
    fun breakfastStop(hotelName: String, lat: Double, lng: Double, dayIndex: Int): Stop = Stop(
        name = breakfastStopName(hotelName), time = "00:00", desc = "早餐・退房", emoji = "🍳",
        duration = BREAKFAST_STAY_MINS.toLong(), order = 0L, isStation = true,
        stopType = "住宿", lat = lat, lng = lng, dayIndex = dayIndex, isLodging = true
    )

    /**
     * 使用者填了住宿、但查不到位置：行程裡不會有住宿站。
     * 過去查不到時仍照常告訴 AI「有住宿」，AI 就在說明裡寫「最後回到市區鮪魚飯店、
     * 第二天從飯店出發」，但行程裡根本沒有飯店，說明與實際互相矛盾。
     */
    fun unresolvedPromptSection(hotelName: String): String =
        "\n- 【住宿】使用者已訂「$hotelName」，但位置無法確認，行程中不會排入住宿站。" +
            "規劃說明（planningReason）請勿寫成「回到飯店」「從飯店出發」這類行程實際不會有的安排。"

    /**
     * 給選點 prompt 的住宿說明（以換行開頭；沒有住宿時是空字串，prompt 與原本逐字相同）。
     * AI 無從得知飯店在哪，只能要求它讓第 1 天最後幾站與第 2 天最前面的站彼此靠近；
     * 真正的起訖銜接由排程器用飯店座標處理。
     */
    fun promptSection(hotelName: String?): String {
        if (hotelName.isNullOrBlank()) return ""
        return "\n- 【住宿】$hotelName。第 1 天收工後入住、第 2 天從住宿出發：" +
            "請讓第 1 天最後幾站與第 2 天最前面的幾站在地理上彼此相近，動線不要在住宿兩側來回折返。"
    }
}
