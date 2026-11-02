package com.example.travellink_ai.ui.planning

/**
 * 建立行程的目的地只支援台東縣（景點資料、天氣、船班都只有台東）。
 *
 * 自由輸入的目的地：含台東 16 鄉鎮市的名字，或本機景點資料的名稱／地址裡找得到
 * （例：都蘭、三仙台、金樽）才接受；台北、京都這類找不到的就擋下來。
 */
object TaitungAreas {
    private val TOWNSHIPS = listOf(
        "臺東", "卑南", "太麻里", "大武", "綠島", "蘭嶼", "池上", "關山",
        "鹿野", "延平", "海端", "東河", "成功", "長濱", "金峰", "達仁"
    )

    /** 台／臺混用（台東、臺東），比對前統一成「臺」 */
    private fun norm(s: String) = s.replace("台", "臺").replace(Regex("\\s+"), "")

    fun isSupported(input: String, places: List<PlaceCost>): Boolean {
        val q = norm(input)
        if (q.isEmpty()) return false
        if (TOWNSHIPS.any { q.contains(it) }) return true
        if (q.length < 2) return false
        return places.any { norm(it.name).contains(q) || norm(it.address).contains(q) }
    }
}
