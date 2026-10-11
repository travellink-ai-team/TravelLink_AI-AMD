package com.example.travellink_ai.data.local

import androidx.room.TypeConverter
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.webCollabStopId
import com.google.common.reflect.TypeToken
import com.google.gson.*
import java.lang.reflect.Type

class Converters {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Stop::class.java, StopDeserializer())
        .create()

    @TypeConverter
    fun fromStopList(value: List<Stop>): String = gson.toJson(value)

    @TypeConverter
    fun toStopList(value: String): List<Stop> {
        val listType = object : TypeToken<List<Stop>>() {}.type
        return gson.fromJson(value, listType)
    }
}

/**
 * 自訂 Gson 反序列化器，確保舊版 Stop JSON（缺少新欄位）不會產生 null。
 * Kotlin data class 的預設值在 Gson 反射模式下無效，必須手動補 fallback。
 */
private class StopDeserializer : JsonDeserializer<Stop> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Stop {
        val obj = json.asJsonObject
        @Suppress("UNCHECKED_CAST")
        val toilets: List<Map<String, String>> = try {
            val arr = obj.get("nearbyToiletLocations")?.asJsonArray
            arr?.mapNotNull { elem ->
                elem.asJsonObject?.entrySet()?.associate { it.key to (it.value?.asString ?: "") }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val name  = obj.get("name")?.asString ?: ""
        val order = obj.get("order")?.asLong  ?: 1L

        return Stop(
            name                  = name,
            time                  = obj.get("time")?.asString          ?: "",
            desc                  = obj.get("desc")?.asString          ?: "",
            emoji                 = obj.get("emoji")?.asString         ?: "📍",
            duration              = obj.get("duration")?.asLong        ?: 90L,
            order                 = order,
            businessHours         = obj.get("businessHours")?.asString ?: "未提供",
            nearbyToiletLocations = toilets,
            searchKeyword         = obj.get("searchKeyword")?.asString ?: "",
            isStation             = obj.get("isStation")?.asBoolean    ?: false,
            placeId               = obj.get("placeId")?.asString       ?: "",
            // 舊資料無此欄位時用確定性推導而非隨機 UUID：隨機值在每台裝置都不同，
            // 而 memories.spots 以 stopId 為 key → 會直接造成照片對不上（旅程故事計畫 §3.1）。
            // 推導與 Firestore 讀取同一套（與網頁 getStableCollabStopId 一致，見 webCollabStopId）
            stopId                = obj.get("stopId")?.asString?.takeIf { it.isNotBlank() }
                                    ?: webCollabStopId("", name, (order - 1).toInt().coerceAtLeast(0)),
            bestTime              = obj.get("bestTime")?.asString ?: "",
            stopType              = obj.get("stopType")?.asString ?: "",
            // 🌟 v8：座標快取（舊資料無此欄位時為 null，loadFromHistory 會 fallback 重新查詢）
            lat                   = obj.get("lat")?.takeIf { !it.isJsonNull }?.asDouble,
            lng                   = obj.get("lng")?.takeIf { !it.isJsonNull }?.asDouble,
            // 🌟 A5 多日：dayIndex 是 1-based，缺欄位（所有既有行程）一律視為第 1 天。
            //   fallback 必須是 1 不是 0，否則舊行程會與網頁端的第一天分成兩個不同的日。
            //   小於 1 的髒值（例如網頁端某版本寫 0）一併正規化成 1。
            dayIndex              = obj.get("dayIndex")?.takeIf { !it.isJsonNull }
                                        ?.asInt?.takeIf { it >= 1 } ?: 1,
            isFerry               = obj.get("isFerry")?.asBoolean   ?: false,
            isLodging             = obj.get("isLodging")?.asBoolean ?: false,
            walkNext              = obj.get("walkNext")?.asBoolean  ?: false,
            manualStartMin        = obj.get("manualStartMin")?.takeIf { !it.isJsonNull }?.asInt,
            manualEndMin          = obj.get("manualEndMin")?.takeIf { !it.isJsonNull }?.asInt
        )
    }
}
