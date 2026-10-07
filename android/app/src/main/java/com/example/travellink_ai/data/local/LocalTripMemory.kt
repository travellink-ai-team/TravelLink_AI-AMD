package com.example.travellink_ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * W4 C6 旅遊回憶的離線快取（Room v11）。
 * spots 以 JSON 字串存（Map<stopId, SpotMemory>），由 [Converters] 轉換；
 * 照片本體不存 Room，只存 Storage download URL 字串於 spots JSON 內。
 */
@Entity(tableName = "trip_memories")
data class LocalTripMemory(
    @PrimaryKey val tripId: String,
    val userId: String = "",
    val tripTitle: String = "",
    val region: String = "",
    val coverUrl: String = "",
    val updatedAt: Long = 0L,
    val spotsJson: String = "{}"
)
