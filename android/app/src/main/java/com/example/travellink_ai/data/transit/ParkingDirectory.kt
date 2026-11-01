package com.example.travellink_ai.data.transit

import com.example.travellink_ai.data.model.ParkingLotInfo
import com.example.travellink_ai.ui.planning.PlaceIdentity
import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject

/**
 * 臺東縣路外停車場清單（TDX CarPark ＋ ParkingSpace），整縣一次抓下來、在本機找最近的停車場。
 *
 * 過去每開一次行程就每站打兩支 TDX（附近停車場＋車位數），8 站就是 16 次，TDX 免費帳號
 * 撐不住：實測 2026-09-24 就算每次間隔 600ms，第 5、6 次之後全部回「API rate limit exceeded」，
 * 8 站只查到 1～2 站。這份資料其實是靜態的（UpdateTime 停在 2023-10-13），
 * 整縣抓一次、存 30 天就夠了——之後查停車場不再打 API。
 *
 * TDX 在臺東只登錄 4 座，另外合併網頁的停車場清單（[parseLocal]）：縣府約 30 座＋
 * OpenStreetMap 約 170 座。候選挑選規則對齊網頁 resolveParkingCoord（1 公里、最多 4 個候選、
 * 步行 12 分鐘內）。
 */
object ParkingDirectory {

    // 判斷規則對齊網頁 resolveParkingCoord：1 公里內最近的 4 個候選，依序驗證步行時間，
    // 12 分鐘內走得到的第一個就採用。兩端規則一致，才不會一邊說有停車場、一邊說沒有。
    /** 找停車場的半徑（網頁 PARKING_SEARCH_RADIUS_METERS） */
    const val RADIUS_M = 1000.0
    /** 每站最多驗證幾個候選（網頁 PARKING_CANDIDATE_LIMIT） */
    const val CANDIDATE_LIMIT = 4
    /** 停好車走到景點最多幾分鐘（網頁 PARKING_MAX_WALK_MINUTES） */
    const val MAX_WALK_MINS = 12
    /** 兩筆資料距離在這之內視為同一座停車場（縣府清單與 TDX 會有重疊） */
    private const val SAME_LOT_M = 60.0
    // 網頁 estimateWalkSeconds：直線距離 × 繞路係數 ÷ 步速
    private const val WALK_DETOUR_FACTOR = 1.35
    private const val WALK_SPEED_MPS = 1.25

    /** 清單快取多久（資料本身幾乎不更新） */
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 把 CarPark 與 ParkingSpace 兩份回應組成清單。spacesBody 可為 null（車位數查不到時車位記 0）。
     * CarPark 回應不是清單（例如被限流）時回 null——呼叫端不可寫入快取。
     */
    fun parse(carParksBody: String, spacesBody: String?): List<ParkingLotInfo>? {
        val parks = try { JSONObject(carParksBody).optJSONArray("CarParks") } catch (e: Exception) { null }
            ?: return null
        val spaces = mutableMapOf<String, Int>()
        try {
            val arr = spacesBody?.let { JSONObject(it) }
                ?.let { it.optJSONArray("ParkingSpaces") ?: it.optJSONArray("Items") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                spaces[o.optString("CarParkID")] = o.optInt("TotalSpaces", 0)
            }
        } catch (e: Exception) { /* 車位數只是附加資訊 */ }
        return (0 until parks.length()).mapNotNull { i ->
            val o = parks.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("CarParkID").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pos = o.optJSONObject("CarParkPosition") ?: return@mapNotNull null
            if (!pos.has("PositionLat") || !pos.has("PositionLon")) return@mapNotNull null
            ParkingLotInfo(
                id = id,
                name = o.optJSONObject("CarParkName")?.optString("Zh_tw")?.takeIf { it.isNotBlank() } ?: id,
                position = LatLng(pos.getDouble("PositionLat"), pos.getDouble("PositionLon")),
                totalSpaces = spaces[id] ?: 0
            )
        }
    }

    /**
     * 網頁 parking-data.js 匯出的停車場（assets/parking_taitung.json，scripts/export_parking_data.mjs 產生）：
     * 縣府約 30 筆（含汽機車位）＋ OSM 約 170 筆（多半只有座標、名稱就叫「停車場」）。
     *
     * id 用「來源＋名稱＋座標」：OSM 有 150 多筆同名「停車場」，只用名稱會撞在一起，
     * 地圖依 id 去重時不同景點的停車場就只剩一個標點；縣府的火車站 A～D 區則是同一組座標，
     * 只用座標也會撞。
     */
    fun parseLocal(json: String): List<ParkingLotInfo> {
        val arr = try { JSONObject(json).optJSONArray("lots") } catch (e: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!o.has("lat") || !o.has("lng")) return@mapNotNull null
            val lat = o.getDouble("lat")
            val lng = o.getDouble("lng")
            val source = o.optString("source", "taitung_gov_parking")
            ParkingLotInfo(
                id = "$source:$name@%.6f,%.6f".format(java.util.Locale.ROOT, lat, lng),
                name = name,
                position = LatLng(lat, lng),
                totalSpaces = o.optInt("smallSpots", 0),
                motoSpots = o.optInt("motoSpots", 0),
                source = source,
                feeNote = o.optString("notes", "")
            )
        }
    }

    /**
     * 合併兩份清單：[primary] 全留，[secondary] 裡與 primary 同一座（60m 內或名稱相同）的略過，
     * 但用它補上 primary 缺的資訊（車位數、收費、具名名稱）。
     * 優先順序：縣府 → OSM → TDX（網頁也是先查本地清單、再查 TDX）。
     */
    fun merge(primary: List<ParkingLotInfo>, secondary: List<ParkingLotInfo>): List<ParkingLotInfo> {
        val merged = primary.toMutableList()
        secondary.forEach { s ->
            val i = merged.indexOfFirst {
                dist(it, s.position.latitude, s.position.longitude) <= SAME_LOT_M || sameName(it.name, s.name)
            }
            if (i < 0) { merged += s; return@forEach }
            val p = merged[i]
            merged[i] = p.copy(
                name = if (isGenericName(p.name) && !isGenericName(s.name)) s.name else p.name,
                totalSpaces = p.totalSpaces.takeIf { it > 0 } ?: s.totalSpaces,
                motoSpots = p.motoSpots.takeIf { it > 0 } ?: s.motoSpots,
                feeNote = p.feeNote.ifBlank { s.feeNote }
            )
        }
        return merged
    }

    // 只是類別、不是專有名稱的（同網頁 PARKING_GENERIC_NAME_RE，再加 OSM 常見的車種寫法）。
    // 這種名稱不能拿來比對同一座——OSM 有 150 多筆都叫「停車場」，會被當成同一座全部吃掉。
    private val GENERIC_NAME = Regex("""^\s*(停車場|停車位|公有停車場|小型車停車場|小客車停車場|parking)\s*$""", RegexOption.IGNORE_CASE)

    fun isGenericName(name: String) = GENERIC_NAME.matches(name)

    /**
     * 名稱比對：兩份資料的座標可能差很遠（實測長濱鄉一號／壹號差 600m、火車站 A 區差 147m 以上），
     * 只看距離會把同一座當成兩座。正規化：臺→台、壹→一、去「台東市／台東」前綴、去使用限制的括號；
     * 「(A區)」這種分區括號要留著，否則 A～D 區會被當成同一座。
     */
    private fun normName(name: String) = name.lowercase()
        .replace("臺", "台").replace("壹", "一")
        .replace(Regex("[（(](月租型?|供宿客|供遊客)[）)]"), "")
        .replace(Regex("^台東(縣|市)?"), "")
        .replace(Regex("""[\s（）()]"""), "")

    private fun sameName(a: String, b: String) =
        !isGenericName(a) && !isGenericName(b) && normName(a).let { it.isNotEmpty() && it == normName(b) }

    // 觀光客停不了的：月租（含「月租型」）、只給住客的（「供宿客」）。「供遊客」的照留。
    // OSM 另外有員工停車場、垃圾車車庫、停車棚（機車／腳踏車）、大型車（遊覽車）停車場。
    private val RESTRICTED = Regex("月租|宿客|員工|車庫|停車棚|大型車")

    fun isRestricted(name: String) = RESTRICTED.containsMatchIn(name)

    /**
     * 合併後再排除觀光客停不了的停車場。一定要「先合併、再排除」：縣府的「長濱鄉一號停車場(月租)」
     * 在 TDX 叫「長濱鄉壹號停車場」、名稱沒標月租——先排除的話 TDX 那筆會補進來。
     * 本地清單裡縣府優先於 OSM：縣府那筆有車位數與正式名稱，OSM 同一座只用來補收費資訊。
     */
    fun usable(local: List<ParkingLotInfo>, tdx: List<ParkingLotInfo>): List<ParkingLotInfo> {
        val (osm, gov) = local.partition { it.source == "osm" }
        return merge(merge(gov, osm), tdx).filterNot { isRestricted(it.name) }
    }

    /** 半徑內由近到遠的候選（最多 [CANDIDATE_LIMIT] 個），交給呼叫端逐一驗證步行時間 */
    fun candidates(
        lots: List<ParkingLotInfo>, lat: Double, lng: Double,
        radiusM: Double = RADIUS_M, limit: Int = CANDIDATE_LIMIT
    ): List<ParkingLotInfo> =
        lots.map { it to dist(it, lat, lng) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }

    /** Directions 沒回答時的步行分鐘推估（網頁 estimateWalkSeconds） */
    fun estimateWalkMins(lot: ParkingLotInfo, lat: Double, lng: Double): Int =
        Math.round(dist(lot, lat, lng) * WALK_DETOUR_FACTOR / WALK_SPEED_MPS / 60.0).toInt()

    private fun dist(lot: ParkingLotInfo, lat: Double, lng: Double) =
        PlaceIdentity.distanceMeters(lat, lng, lot.position.latitude, lot.position.longitude)
}
