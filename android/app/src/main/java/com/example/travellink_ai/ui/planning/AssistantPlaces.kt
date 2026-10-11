package com.example.travellink_ai.ui.planning

/**
 * 隨行管家可以推薦的地點：只從本地資料（CostReference，已確認存在）挑，不讓 AI 憑記憶給名字。
 *
 * gpt-oss 對台東不熟，問「附近咖啡廳」會編出「咖啡弄 (Coffee Lab)」這種不存在的店，
 * 按「加入」時 Google 驗證比對不到就加不進去。做法與「換一個景點」相同：給它附近的真實清單，
 * 回覆後再檢查一次，清單外的名字直接拿掉。
 */
object AssistantPlaces {
    const val MAX_KM = 12.0
    private val QUOTA = linkedMapOf("景點" to 12, "餐廳" to 10, "咖啡廳" to 6)

    data class Candidate(val place: PlaceCost, val km: Double)

    /**
     * [center] 附近的候選（依距離），各類型各取配額；已在行程中的排除。
     * [date]（`yyyy/MM/dd`）有給時，那天公休的不列入。
     */
    fun pick(
        places: List<PlaceCost>, center: Pair<Double, Double>, excludeNames: Collection<String>, date: String = ""
    ): List<Candidate> {
        val used = excludeNames.map { CostReference.normKey(it) }.filter { it.isNotEmpty() }
        val near = places.asSequence()
            .filter { it.typeName in QUOTA.keys }
            .filter { p ->
                val k = CostReference.normKey(p.name)
                used.none { u -> k == u || k.contains(u) || u.contains(k) }
            }
            .filter { p -> date.isBlank() || p.businessHours.isBlank() ||
                !BusinessHours.isClosed(BusinessHours.resolve(p.businessHours, date)) }
            .map { Candidate(it, km(center.first, center.second, it.lat, it.lng)) }
            .filter { it.km <= MAX_KM }
            .sortedBy { it.km }
            .toList()
        return QUOTA.flatMap { (type, n) -> near.filter { it.place.typeName == type }.take(n) }
            .sortedBy { it.km }
    }

    /**
     * 給 prompt 的清單。附上 [date] 那天的營業時間：過去沒給，AI 換景點時不知道幾點開，
     * 會把 11 點才開的店排在 9 點的那一站
     */
    fun promptSection(candidates: List<Candidate>, date: String = ""): String =
        candidates.joinToString("\n") {
            val hours = it.place.businessHours.takeIf { h -> h.isNotBlank() && date.isNotBlank() }
                ?.let { h -> BusinessHours.resolve(h, date) }
                ?.takeIf { h -> h.length <= 40 }
            "- ${it.place.name}（${it.place.typeName}，約 ${"%.1f".format(it.km)} 公里" +
                (hours?.let { h -> "，營業 $h" } ?: "") + "）"
        }

    /**
     * AI 給的名稱對回清單上的正式名稱；對不到（多半是編的）回 null。
     * 允許互相包含（AI 常省略分店名或括號），但太短的名字不做包含比對，避免「咖啡」這種字對到一堆店。
     */
    fun canonical(name: String, candidates: List<Candidate>): String? {
        val k = CostReference.normKey(name)
        if (k.isEmpty()) return null
        candidates.firstOrNull { CostReference.normKey(it.place.name) == k }?.let { return it.place.name }
        if (k.length < 3) return null
        return candidates.filter {
            val c = CostReference.normKey(it.place.name)
            c.length >= 3 && (c.contains(k) || k.contains(c))
        }.singleOrNull()?.place?.name
    }

    private fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
    }
}
