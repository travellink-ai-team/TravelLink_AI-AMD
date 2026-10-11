package com.example.travellink_ai.data.island

/**
 * 離島設定檔（A5 Stage 4）。
 *
 * 過去 App 用一份全域黑名單把離島座標整個擋掉（`isOffshoreIsland`，註解寫明
 * 動機是「避免路線規劃因跨海而 ZERO_RESULTS」），代價是離島景點的座標被丟棄、
 * 落到 ring offset fallback，最後散在台東市中心附近。這裡把同一組 bbox 改成
 * **雙向守門員**：本島行程照舊擋離島座標，離島行程則反過來只收該島範圍內的。
 *
 * 純資料 + 純函式，不依賴 Android／Play Services，可直接單元測試。
 * 座標一律取自 App 自己的 `local_places.json`（已驗證），不憑印象填。
 */
data class IslandProfile(
    val code: String,
    val name: String,
    /** 別名與舊稱，供目的地字串比對 */
    val aliases: List<String>,
    val centerLat: Double,
    val centerLng: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    /** 島上的港口（登島／離島都在這裡） */
    val port: Gateway,
    /** 本島端的港口名稱：實際座標交給 Geocoding，不寫死 */
    val mainlandPortQuery: String,
    /** 單程航程（分鐘），來源見 docs/計畫_A5離島支援與多日行程.md §5 */
    val sailingMins: Int,
    /** 島上主聚落：使用者未指定住宿時的過夜錨點 */
    val mainVillage: String,
    /** 島上建議交通方式（對應 CostConfig 的 mode key） */
    val preferredMode: String = "scooter",
    /** 候選池搜尋半徑（公尺）：涵蓋全島即可，不需要本島那種 8km */
    val searchRadiusMeters: Int
) {
    data class Gateway(val name: String, val lat: Double, val lng: Double)

    fun contains(lat: Double, lng: Double): Boolean =
        lat in minLat..maxLat && lng in minLng..maxLng
}

object IslandRegistry {

    /**
     * 上船前的到港緩衝（分鐘）。班次資料要到 Stage 5 才進來，這個緩衝是
     * 「行程必須提前多久結束」的保守值——趕不上船的代價遠大於早到。
     */
    const val BOARDING_BUFFER_MINS = 40

    /**
     * bbox 沿用原本 `isOffshoreIsland` 的範圍，不重新畫：那組範圍已在正式版
     * 跑了很久、確定能涵蓋兩島，換數字只會多出未知風險。
     */
    val ludao = IslandProfile(
        code = "ludao",
        name = "綠島",
        aliases = listOf("綠島", "綠島鄉", "火燒島"),
        centerLat = 22.6617, centerLng = 121.4926,
        minLat = 22.60, maxLat = 22.75, minLng = 121.40, maxLng = 121.55,
        // 南寮漁港：綠島唯一的對外港口，座標取自 local_places.json（已驗證）
        port = IslandProfile.Gateway("南寮漁港", 22.659485, 121.473767),
        mainlandPortQuery = "富岡漁港 台東",
        sailingMins = 50,
        mainVillage = "南寮",
        searchRadiusMeters = 6000
    )

    val lanyu = IslandProfile(
        code = "lanyu",
        name = "蘭嶼",
        aliases = listOf("蘭嶼", "蘭嶼鄉", "紅頭嶼"),
        centerLat = 22.0567, centerLng = 121.5320,
        minLat = 21.90, maxLat = 22.15, minLng = 121.40, maxLng = 121.65,
        // 開元港：蘭嶼客船停靠港，座標取自 local_places.json（已驗證）
        // 注意與「開元漁港」（22.0444, 121.5581）不同，後者不是客運港
        port = IslandProfile.Gateway("開元港", 22.0577015, 121.5081386),
        mainlandPortQuery = "富岡漁港 台東",
        sailingMins = 120,
        mainVillage = "紅頭",
        searchRadiusMeters = 6000
    )

    val all = listOf(ludao, lanyu)

    /** 依目的地字串判斷是不是離島行程；不是則回 null（＝本島） */
    fun byDestination(destination: String?): IslandProfile? {
        val d = (destination ?: "").trim().replace("臺", "台")
        if (d.isEmpty()) return null
        return all.firstOrNull { p -> p.aliases.any { d.contains(it) } }
    }

    /** 座標落在哪個島；本島回 null */
    fun byCoordinate(lat: Double, lng: Double): IslandProfile? =
        all.firstOrNull { it.contains(lat, lng) }

    /**
     * 座標守門員。取代原本單向的 `isOffshoreIsland`：
     *
     * - **本島行程**（[island] 為 null）：落在任一離島 bbox 內的座標一律拒絕。
     *   與改動前行為完全相同——跨海會讓 Directions 回 ZERO_RESULTS。
     * - **離島行程**：只接受該島 bbox 內的座標。順帶擋掉「AI 幻覺把台東市景點
     *   排進綠島行程」——這種情況過去會靜默通過。
     */
    fun accepts(island: IslandProfile?, lat: Double, lng: Double): Boolean =
        if (island == null) byCoordinate(lat, lng) == null else island.contains(lat, lng)
}
