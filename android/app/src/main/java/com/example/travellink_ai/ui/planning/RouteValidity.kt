package com.example.travellink_ai.ui.planning

import com.google.android.gms.maps.model.LatLng

/**
 * 判斷 Directions 結果／快取車程能不能拿去重算景點時間。
 *
 * 查不到的路段（最常見是沒網路）回的是空折線＋0 分鐘。過去只檢查「清單不是空的」，
 * 全是 0 的車程照樣拿去重算，整天的時間被壓縮後寫回 Room 與 Firestore；還把「0,0,0…」
 * 存進地圖快取，恢復網路後再開地圖又用快取重算一次（實測 2026-10-01：台東山海兩日遊
 * 卑南遺址 09:02→09:00、結束 15:08→14:05，共編的網頁端也被改到）。
 */
object RouteValidity {

    /**
     * 每一段都真的查到路線。用「折線是否為空」判斷，不用「是否為 0 分」：
     * 不到 1 分鐘的步行段本來就是 0 分，但它有折線。
     */
    fun complete(segments: List<List<LatLng>>, mins: List<Long>): Boolean =
        mins.isNotEmpty() && segments.size == mins.size && segments.all { it.isNotEmpty() }

    /** 快取的車程能不能用：0 分的段超過兩成就當作沒有快取（重查只多花一次 API，比用錯時間好） */
    fun cachedTransitUsable(mins: List<Long>): Boolean =
        mins.isNotEmpty() && mins.count { it <= 0L } * 5 <= mins.size
}
