package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop

/**
 * 編輯景點 sheet 按「儲存」：在原本那一站上改，不重建。
 *
 * 過去用 `Stop(...)` 重建整站，dayIndex 回到預設 1、stopId 換新、座標與類型清空——
 * 兩天行程改第 2 天的站（含套用 AI 建議）就被搬到第 1 天，時間接著第 1 天往後排
 * （2026-11-01 測試者回報，my_1793537561757）。
 *
 * 名稱改了才清掉跟「哪個地點」綁在一起的欄位（座標、placeId、類型、廁所…），
 * 讓路線重算時依新名稱重新定位；第幾天、識別碼、順序、船班／住宿旗標、手動鎖定時間都沿用。
 */
object StopEdit {
    fun apply(
        existing: Stop?,
        name: String,
        time: String,
        desc: String,
        emoji: String,
        duration: Long,
        businessHours: String
    ): Stop {
        val newName = name.trim()
        // 網頁格式的共編景點沒有 time，留空時沿用原值，由時間重算補齊
        val newTime = time.trim().ifBlank { existing?.time ?: "" }
        val newEmoji = emoji.ifBlank { "📍" }
        if (existing == null) {
            return Stop(
                name = newName, time = newTime, desc = desc.trim(), emoji = newEmoji,
                duration = duration, order = 99L, businessHours = businessHours
            )
        }
        val edited = existing.copy(
            name = newName, time = newTime, desc = desc.trim(), emoji = newEmoji,
            duration = duration, businessHours = businessHours
        )
        if (newName == existing.name.trim()) return edited
        return edited.copy(
            lat = null,
            lng = null,
            placeId = "",
            searchKeyword = newName,
            stopType = "",
            bestTime = "",
            nearbyToiletLocations = emptyList(),
            mergedSubSpots = emptyList(),
            mergedRadiusMeters = 0,
            mergedMemberCoords = emptyList()
        )
    }
}
