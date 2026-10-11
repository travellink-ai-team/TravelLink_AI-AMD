package com.example.travellink_ai.ui.planning

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 單一地點的真實成本資料（來自 assets/local_places.json，由 scripts/build_local_places.py 產生）。
 * fee / costPerPerson 為 null 代表該地點沒有對應資料，估算時應回退 CostConfig 的參考表。
 */
data class PlaceCost(
    val name: String,
    val lat: Double,
    val lng: Double,
    val fee: Int?,            // 真實入場費（元/人）；0 = 免費；null = 無資料
    val feeNote: String,      // 收費說明（如停車清潔費級距）
    val costPerPerson: Int?,  // 真實餐費（元/人）；null = 無資料
    val costNote: String,     // 餐費區間說明（如「$200–400」）
    val priceLevel: Int,      // Google 價格等級 0–4；-1 = 未知
    // 以下供「超出預算時建議替換」挑候選用（BudgetSwap）
    val typeName: String = "",      // 餐廳／景點／咖啡廳
    val businessHours: String = "", // 全週原文；空＝無資料
    val duration: Long = 0L         // 建議停留分鐘；0＝無資料
)

/**
 * 本地成本參考庫：把 local_places.json 讀成「名稱／座標 → 真實費用」的查詢索引，
 * 供 [estimateTripCost] 以實際票價、餐費取代 [CostConfig] 的硬編碼猜測值。
 *
 * 📦 純檔案讀取＋記憶體查詢，不觸發任何外部 API。App 存活期間僅解析一次（單例）。
 */
class CostReference private constructor(
    private val byName: Map<String, PlaceCost>,
    /** 全部地點（含沒有費用資料的），供 [BudgetSwap] 找附近較省錢的景點 */
    val places: List<PlaceCost>
) {
    /**
     * 依名稱優先、座標最近鄰備援比對地點成本，最後套用門票人工覆蓋表。
     * 找不到（名稱不符、無鄰近點、也無覆蓋）時回傳 null，呼叫方應回退參考表。
     */
    fun lookup(name: String, lat: Double?, lng: Double?): PlaceCost? {
        val base = byName[normKey(name)] ?: run {
            if (lat == null || lng == null) null
            else {
                var best: PlaceCost? = null
                var bestDist = MATCH_RADIUS_M
                for (p in places) {
                    val d = haversine(lat, lng, p.lat, p.lng)
                    if (d <= bestDist) {
                        bestDist = d
                        best = p
                    }
                }
                best
            }
        }
        val patch = FEE_OVERRIDES[overrideKey(name)] ?: return base
        return when {
            base != null -> base.copy(fee = patch.fee ?: base.fee, feeNote = patch.note)
            else         -> PlaceCost(name, lat ?: 0.0, lng ?: 0.0, patch.fee, patch.note, null, "", -1)
        }
    }

    /** 門票覆蓋項：fee = null 代表只換說明文字、金額沿用主來源 */
    private data class FeePatch(val fee: Int?, val note: String)

    companion object {
        private const val TAG = "CostReference"
        private const val ASSET_FILE = "local_places.json"
        // 座標最近鄰比對半徑：60m 內視為同一地點（避免把隔壁店家誤配）
        private const val MATCH_RADIUS_M = 60.0

        /**
         * 門票「人工覆蓋表」，值與網頁端 attraction-fee-config.js 對齊（2026-07 查核）。
         * 主來源＝local_places.json（台東觀光旅遊網 opendata）；本表只放解析不理想、
         * 或想改用假日全票等保守值的少數景點。優先序：本表 → local_places.json。
         */
        private val FEE_OVERRIDES = mapOf(
            // opendata 解析到「平日全票」，改用假日全票讓預算偏保守、避免規劃到超支
            "初鹿牧場"          to FeePatch(200, "平日全票 \$100 / 假日全票 \$200"),
            "知本國家森林遊樂區" to FeePatch(100, "假日全票 \$100 / 平日 \$80"),
            // 史前館本館不在 opendata 台東清單中，補上官方全票
            "國立台灣史前文化博物館" to FeePatch(100, "本館全票 \$100 / 團體 \$80"),
            // opendata 的「導覽10人成團」被誤讀成 \$1000；園區免費入園、體驗才收費
            // overrideKey 是精確比對，種子資料的正式名是「台東糖廠文創園區」，
            // 兩個鍵都留著：舊行程與網頁端可能仍用短名
            "台東糖廠"          to FeePatch(0, "園區免費入園；體驗/導覽另計（約 \$200–1500，10 人成團預約）"),
            "台東糖廠文創園區"    to FeePatch(0, "園區免費入園；體驗/導覽另計（約 \$200–1500，10 人成團預約）")
        )

        /** 覆蓋表比對鍵：normKey 之上再做 臺→台（網頁 normalizeFeeName 同款） */
        private fun overrideKey(name: String): String = normKey(name).replace('臺', '台')

        @Volatile
        private var instance: CostReference? = null

        /** 取得單例，第一次呼叫時載入 asset（請在背景執行緒呼叫以免阻塞 UI）。 */
        fun get(context: Context): CostReference =
            instance ?: synchronized(this) {
                instance ?: load(context).also { instance = it }
            }

        /**
         * 比對用名稱正規化：取第一個括號前、去除所有空白、轉小寫。
         * 必須與 scripts/build_local_places.py 的 norm_key() 完全一致。
         */
        internal fun normKey(name: String): String {
            val cut = name.split('(', '（').first()
            return cut.filterNot { it.isWhitespace() }.lowercase()
        }

        private fun load(context: Context): CostReference {
            return try {
                val text = context.assets.open(ASSET_FILE)
                    .bufferedReader().use { it.readText() }
                val arr = JSONObject(text).getJSONArray("pois")
                val byName = HashMap<String, PlaceCost>(arr.length())
                val all = ArrayList<PlaceCost>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pc = PlaceCost(
                        name = o.getString("name"),
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        fee = if (o.has("fee")) o.getInt("fee") else null,
                        feeNote = o.optString("feeNote", ""),
                        costPerPerson = if (o.has("costPerPerson")) o.getInt("costPerPerson") else null,
                        costNote = o.optString("costNote", ""),
                        priceLevel = o.optInt("priceLevel", -1),
                        typeName = o.optString("typeName", ""),
                        businessHours = o.optString("businessHours", ""),
                        duration = o.optLong("duration", 0L)
                    )
                    // 同 normKey 只保留第一筆，避免覆蓋；座標清單全收供最近鄰比對
                    byName.putIfAbsent(normKey(pc.name), pc)
                    all.add(pc)
                }
                Log.d(TAG, "📦 [本地資料] 已載入成本參考庫 ${all.size} 筆（本次 0 次 API 呼叫）")
                CostReference(byName, all)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ [本地資料] 載入 $ASSET_FILE 失敗：${e.message}，費用估算將全數回退參考表")
                CostReference(emptyMap(), emptyList())
            }
        }

        private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
