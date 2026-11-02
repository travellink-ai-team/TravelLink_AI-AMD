package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop
import kotlin.math.roundToInt

/**
 * 同一個大景區裡的幾個小景點併成一站（測試回饋第 10 條：森林公園裡的活水湖、琵琶湖、鷺鷥湖
 * 被排成三站，中間還各算一段車程）。
 *
 * 規則對齊網頁 `mergeNearbySubAttractions`，存同一組欄位（mergedSubSpots／mergedRadiusMeters／
 * mergedMemberCoords），兩端互看都會顯示「（含 …）」與地圖上的範圍：
 *  - 不同名的景點相距 [SAME_SPOT_RADIUS_M] 內就算同一處（網頁同值）；
 *  - 網頁的「同名家族 2 公里」由 [PlaceIdentity] 負責——那類是同一個地方的兩筆資料，
 *    生成時直接去重只留一筆，不需要列成子景點；
 *  - 網頁規則抓不到的（名字不同、又相距超過 300 公尺）用 [AREAS] 對照表補。
 *
 * 和網頁刻意不同的兩處：
 *  - 不用 Google Places 補子景點：實測網頁把民宿、浮潛店塞進「野銀舊部落」的子景點；
 *  - 300 公尺規則要「和群組裡每一站都近」才併（網頁是和任一站近就併）：台東市區
 *    故事館、圖書館、鐵花村一個接一個都在 300 公尺內，串起來會把整個市區併成一站。
 */
object SubSpotMerge {

    /** 不同名的景點相距多近算同一處（公尺，與網頁 SUB_SPOT_SAME_SPOT_RADIUS_M 相同） */
    const val SAME_SPOT_RADIUS_M = 300.0

    /**
     * 大景區 → 裡面的小景點。名稱比對經 [PlaceIdentity.normalize]（臺／台、括號後綴不影響）。
     * 只收距離規則抓不到、又確定是同一個園區的。
     */
    val AREAS: Map<String, List<String>> = mapOf(
        "台東森林公園" to listOf("活水湖", "琵琶湖", "鷺鷥湖"),
        "哈巴狗與睡美人岩" to listOf("哈巴狗岩", "睡美人岩")
    )

    /** 合併後停留時間上限（分鐘，與網頁相同） */
    private const val MAX_MERGED_STAY = 120L

    /** [name] 屬於哪個大景區（本身就是大景區名也算）；不在對照表回 null */
    fun areaOf(name: String): String? {
        val n = PlaceIdentity.normalize(name)
        if (n.isEmpty()) return null
        return AREAS.entries.firstOrNull { (area, subs) ->
            PlaceIdentity.normalize(area) == n || subs.any { PlaceIdentity.normalize(it) == n }
        }?.key
    }

    /** 兩個景點算不算同一個大景區（對照表同一組，或相距 300 公尺內） */
    fun sameArea(nameA: String, latA: Double, lngA: Double, nameB: String, latB: Double, lngB: Double): Boolean {
        val area = areaOf(nameA)
        if (area != null && area == areaOf(nameB)) return true
        return PlaceIdentity.distanceMeters(latA, lngA, latB, lngB) <= SAME_SPOT_RADIUS_M
    }

    /**
     * 候選地點是不是已經被行程裡的 [stop] 涵蓋（補位時用，免得剛併掉的小景點又被補回來）：
     * 名字在它的子景點裡，或和它同一個大景區。
     */
    fun covers(stop: Stop, name: String, lat: Double, lng: Double): Boolean {
        val n = PlaceIdentity.normalize(name)
        if (stop.mergedSubSpots.any { PlaceIdentity.normalize(it) == n }) return true
        val sLat = stop.lat ?: return areaOf(stop.name)?.let { it == areaOf(name) } ?: false
        val sLng = stop.lng ?: return false
        return sameArea(stop.name, sLat, sLng, name, lat, lng)
    }

    /**
     * 把同一個大景區的站併成一站，順序以群組第一站的位置為準。
     *
     * @param mergeable 能不能參與合併（車站、住宿、船班、餐廳都不行：用餐要獨立停留）
     * @param position 站的座標；沒有座標的只能靠對照表併
     * @param onMerged 有站被併進來時通知（併好的站、被併掉的站名）
     */
    fun merge(
        stops: List<Stop>,
        mergeable: (Stop) -> Boolean,
        position: (Stop) -> Pair<Double, Double>?,
        onMerged: (merged: Stop, absorbed: List<String>) -> Unit = { _, _ -> }
    ): List<Stop> {
        val assigned = BooleanArray(stops.size)
        val result = mutableListOf<Stop>()
        for (i in stops.indices) {
            if (assigned[i]) continue
            assigned[i] = true
            val base = stops[i]
            if (!mergeable(base)) { result += base; continue }
            val members = mutableListOf(base)
            for (j in i + 1 until stops.size) {
                if (assigned[j]) continue
                val cand = stops[j]
                if (!mergeable(cand)) continue
                if (members.all { m -> belongTogether(m, cand, position) }) {
                    members += cand; assigned[j] = true
                }
            }
            if (members.size == 1) { result += base; continue }
            val merged = build(members, position)
            onMerged(merged, members.map { it.name }.filter { it != merged.name })
            result += merged
        }
        return result
    }

    private fun belongTogether(a: Stop, b: Stop, position: (Stop) -> Pair<Double, Double>?): Boolean {
        val area = areaOf(a.name)
        if (area != null && area == areaOf(b.name)) return true
        val pa = position(a) ?: return false
        val pb = position(b) ?: return false
        return PlaceIdentity.distanceMeters(pa.first, pa.second, pb.first, pb.second) <= SAME_SPOT_RADIUS_M
    }

    private fun build(members: List<Stop>, position: (Stop) -> Pair<Double, Double>?): Stop {
        // 站名：對照表的大景區名優先，否則取最短的（網頁 pickClusterName 的簡化）
        val area = members.firstNotNullOfOrNull { areaOf(it.name) }
            ?.takeIf { a -> members.all { areaOf(it.name) == a } }
        val parent = members.firstOrNull { area != null && PlaceIdentity.normalize(it.name) == PlaceIdentity.normalize(area) }
            ?: members.minBy { it.name.length }
        val name = if (area != null && PlaceIdentity.normalize(parent.name) != PlaceIdentity.normalize(area)) area
            else parent.name
        val coords = members.mapNotNull(position)
        val center = position(parent)?.takeIf { PlaceIdentity.normalize(parent.name) == PlaceIdentity.normalize(name) }
            ?: coords.takeIf { it.isNotEmpty() }?.let { cs -> cs.sumOf { it.first } / cs.size to cs.sumOf { it.second } / cs.size }
        val radius = center?.let { c ->
            val maxDist = coords.maxOfOrNull { PlaceIdentity.distanceMeters(c.first, c.second, it.first, it.second) } ?: 0.0
            (maxDist + 80).roundToInt().coerceIn(150, 2500)
        } ?: 0
        val maxStay = members.maxOf { it.duration }.takeIf { it > 0 } ?: 30L
        return parent.copy(
            name = name,
            searchKeyword = if (name != parent.name) name else parent.searchKeyword,
            placeId = if (name != parent.name) "" else parent.placeId,
            duration = minOf(MAX_MERGED_STAY, maxStay + 30L * (members.size - 1)),
            lat = center?.first ?: parent.lat,
            lng = center?.second ?: parent.lng,
            mergedSubSpots = members.map { it.name }.filter { PlaceIdentity.normalize(it) != PlaceIdentity.normalize(name) },
            mergedRadiusMeters = radius,
            mergedMemberCoords = coords.map { mapOf("lat" to it.first, "lng" to it.second) }
        )
    }
}
