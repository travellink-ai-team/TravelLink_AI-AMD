package com.example.travellink_ai.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 探索頁「✦ 官方精選範本」。內容由網頁 explore-templates.js 用 poi-data.js 組出來
 * （scripts/export_explore_templates.mjs），App 不自己組——兩端看到的範本才會一模一樣。
 */
data class ExploreTemplates(
    val generatedAt: String,
    val gate: TemplateStop,
    val groups: List<TemplateGroup>,
    val homePicks: List<String>,
    val districtCounts: Map<String, Int>,
    val templates: List<ExploreTemplate>
) {
    /** 首頁精選（網頁 HOME_PICKS），缺的鄉鎮略過 */
    val homeTemplates: List<ExploreTemplate>
        get() = homePicks.mapNotNull { k -> templates.firstOrNull { it.key == k } }
            .ifEmpty { templates.take(6) }

    companion object {
        fun parse(json: String): ExploreTemplates {
            val o = JSONObject(json)
            val counts = o.optJSONObject("districtCounts")
            return ExploreTemplates(
                generatedAt = o.optString("generatedAt"),
                gate = TemplateStop.parse(o.getJSONObject("gate")),
                groups = o.getJSONArray("groups").objects().map { g ->
                    TemplateGroup(g.getString("label"), g.getJSONArray("keys").strings())
                },
                homePicks = o.optJSONArray("homePicks")?.strings().orEmpty(),
                districtCounts = counts?.keys()?.asSequence()?.associateWith { counts.optInt(it) }.orEmpty(),
                templates = o.getJSONArray("templates").objects().map { ExploreTemplate.parse(it) }
            )
        }
    }
}

data class TemplateGroup(val label: String, val keys: List<String>)

data class ExploreTemplate(
    val key: String,          // 鄉鎮名，如「成功」「綠島」
    val group: String,        // 海線／市區／縱谷／南迴／離島
    val emoji: String,
    val title: String,
    val stops: List<TemplateStop>,
    val mainCount: Int,
    val viaCount: Int,
    val island: Boolean,
    val km: Double,
    val farForOneDay: Boolean, // 來回直線 >120km，建議兩日
    val stayMin: Int,
    val rating: Double?,       // 只平均有 Google 評分的站
    val ratedCount: Int
) {
    /** 「用這份開始規劃」帶進精靈的內容（對齊網頁 copyTrip 的 pendingTemplateSeed） */
    fun toSeed() = TemplateSeed(
        // 離島目的地就是島名；本島各鄉鎮共用「台東」這個目的地
        destination = if (island) key else "台東",
        tripName = title,
        desiredSpots = stops.filter { !it.via }.joinToString("、") { it.name },
        twoDays = farForOneDay,
        stops = stops.filter { !it.via }
    )

    companion object {
        fun parse(o: JSONObject) = ExploreTemplate(
            key = o.getString("key"),
            group = o.optString("group"),
            emoji = o.optString("emoji", "📍"),
            title = o.optString("title"),
            stops = o.getJSONArray("stops").objects().map { TemplateStop.parse(it) },
            mainCount = o.optInt("mainCount"),
            viaCount = o.optInt("viaCount"),
            island = o.optBoolean("island"),
            km = o.optDouble("km", 0.0),
            farForOneDay = o.optBoolean("farForOneDay"),
            stayMin = o.optInt("stayMin"),
            rating = if (o.isNull("rating")) null else o.optDouble("rating"),
            ratedCount = o.optInt("ratedCount")
        )
    }
}

data class TemplateStop(
    val name: String,
    val lat: Double,
    val lng: Double,
    val duration: Int,
    val rating: Double,
    val desc: String,
    val district: String,
    val via: Boolean,          // true＝台東車站往返途中順路加入
    val fromDistrict: String,
    val businessHours: String = ""   // Google weekday_text 整週原文（「星期一: 休息」每天一行），沒有就空白
) {
    companion object {
        fun parse(o: JSONObject) = TemplateStop(
            name = o.optString("name"),
            lat = o.optDouble("lat"),
            lng = o.optDouble("lng"),
            duration = o.optInt("duration", 45),
            rating = o.optDouble("rating", 0.0),
            desc = o.optString("desc"),
            district = o.optString("district"),
            via = o.optBoolean("via"),
            fromDistrict = o.optString("fromDistrict"),
            businessHours = o.optString("businessHours")
        )
    }
}

/** 從範本開精靈時要預填的值；精靈開啟時取用一次即清掉 */
data class TemplateSeed(
    val destination: String,
    val tripName: String,
    val desiredSpots: String,
    val twoDays: Boolean,
    /** 主軸站點（座標、營業時間、停留）：生成時直接放進候選池並列為必排，不必再查 Google */
    val stops: List<TemplateStop> = emptyList()
)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
