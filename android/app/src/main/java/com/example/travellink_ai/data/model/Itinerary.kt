package com.example.travellink_ai.data.model

import com.google.firebase.firestore.IgnoreExtraProperties
import androidx.annotation.Keep
import java.util.UUID


@Keep
@IgnoreExtraProperties
data class Stop(
    val name: String,
    val time: String,
    val desc: String,
    val emoji: String,
    val duration: Long,
    val order: Long,
    val businessHours: String = "未提供",
    val nearbyToiletLocations: List<Map<String, String>> = emptyList(),
    val searchKeyword: String = "", // Google Maps 搜尋關鍵字，比 name 更通用，提升座標準確率
    val isStation: Boolean = false, // 出發/回程車站站點，不可編輯或拖曳
    val placeId: String = "",       // Google Place ID，用於 scenic_points 評分對應
    val stopId: String = UUID.randomUUID().toString(), // 🌟 共編用唯一識別碼，與 index 解耦
    val bestTime: String = "",      // 最佳遊覽時段（清晨/上午/下午/黃昏/晚上/全天），來自 poi_knowledge
    val stopType: String = "",      // 景點類型（餐廳/景點/公園步道/博物館文化館/自然景觀 等）
    // 🌟 v8：生成當下解析出的座標，存起來供歷史行程重用，避免重複呼叫 Geocoding/Places
    val lat: Double? = null,
    val lng: Double? = null,
    // ── 多日行程（A5）─────────────────────────────────────────────
    /**
     * 第幾天，**1-based**（第一天 = 1）。單日行程恆為 1。
     *
     * 用 1-based 是為了與網頁端一致（實例 `my_1784460114502` 寫的是 1、2）。
     * 若改用 0-based，舊資料補的 0 會與網頁端的第一天分成兩個不同的日。
     */
    val dayIndex: Int = 1,
    /** 跨海船班錨點站：釘在當日頭尾、不參與排序，交通時間用固定航程而非 Directions */
    val isFerry: Boolean = false,
    /** 住宿錨點站：多日行程中「前一天的終點、隔天的起點」，同樣不參與排序 */
    val isLodging: Boolean = false,
    /**
     * 離開這一站的那一段改用走路（使用者手動指定）。對應網頁 stop.transitMode='walk'
     * ＋transitModeManual=true；false＝跟著全程主要交通工具。
     */
    val walkNext: Boolean = false,
    /**
     * 網頁的手動鎖定時間（Firestore `manualStartMin`／`manualEndMin`，從 0 點起算的分鐘，null＝沒鎖）。
     * App 不編輯它，只負責讀進來、存檔時原樣寫回，送旅程應變 Agent 時換算成 timeLocked（T4 規格第 4 節）。
     */
    val manualStartMin: Int? = null,
    val manualEndMin: Int? = null
)

/**
 * 這趟行程共幾天：取 stops 的最大 dayIndex。
 *
 * 刻意用推導而不另存欄位——天數與 stops 是同一件事的兩種說法，存兩份就會有
 * 「刪掉最後一天的站，天數卻還是 2」這種不一致，而且 LocalItinerary 是 Room
 * entity，多一個欄位就要多一次 migration。
 */
val List<Stop>.dayCount: Int
    get() = maxOfOrNull { it.dayIndex }?.coerceAtLeast(1) ?: 1

/** 取出指定日的站點（1-based），順序維持原本的 order */
fun List<Stop>.stopsOfDay(day: Int): List<Stop> = filter { it.dayIndex == day }

@Keep
@IgnoreExtraProperties
data class Itinerary(
    val title: String,
    val aiTitle: String,
    val aiReply: String,
    val region: String,
    val stops: List<Stop>,
    val days: String,
    val people: String,
    val budget: String = "$1,500",
    val planningReason: String = "" // 🌟 AI 規劃思路說明（不存 Room，僅本次行程生效）
)

