package com.example.travellink_ai.ui.planning

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「這兩筆是不是同一個地方」。
 *
 * 種子清單同一個點常有多筆寫法，只比對「名稱完全相同或相距 80 公尺內」抓不到：
 * 台東天后宮／臺東天后宮（差一個字、座標差 830m）、卑南遺址／卑南遺址公園（430m）、
 * 榕樹下米苔目／榕樹下米苔目*小滿雨生。
 *
 * 原本是 ItineraryViewModel 的 private 函式，測試檔裡還複製了一份（註解自己警告
 * 「兩邊若分歧，這組測試會失效」）。抽成純函式後兩邊共用同一份，測的就是真的規則。
 */
object PlaceIdentity {

    fun distanceMeters(latA: Double, lngA: Double, latB: Double, lngB: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(latB - latA)
        val dLng = Math.toRadians(lngB - lngA)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latA)) * cos(Math.toRadians(latB)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 名稱正規化：臺→台、去掉括號後綴與標點 */
    fun normalize(s: String): String = s.lowercase()
        .replace("臺", "台")
        .substringBefore("（").substringBefore("(")
        .replace(Regex("""[\s()（）【】—・,，、.。．·|｜*]"""), "")

    private val ALIAS_STOP_WORDS = listOf(
        "請", "休", "營業", "時間", "最新", "消息", "參考", "查詢", "公休",
        "預約", "電話", "資訊", "月", "日", "點餐", "例如"
    )

    /**
     * 一筆資料實際指涉的所有名字：主名，加上括號裡「看起來像別名」的部分。
     *
     * 「富岡地質公園 (小野柳)」與「小野柳遊客中心」是同一個園區的兩筆，只取主名沒有
     * 共同前綴。但括號也常拿來寫營業說明（「(最新消息請看FB、IG)」），那種當別名會把
     * 不相干的店湊成一對，所以只收 2–8 個純中文字、且不含說明性字眼的括號內容。
     */
    fun aliases(name: String): List<String> {
        val main = normalize(name)
        val inner = Regex("[（(]([^）)]*)[）)]").findAll(name)
            .map { it.groupValues[1].trim() }
            .filter { alias ->
                alias.length in 2..8 &&
                    alias.none { it.code < 128 } &&
                    ALIAS_STOP_WORDS.none { alias.contains(it) }
            }
            .map { normalize(it) }
            .toList()
        return (listOf(main) + inner).filter { it.isNotBlank() }
    }

    /**
     * 通用的地名後綴：「加路蘭」與「加路蘭遊憩區」是同一處，差別只是後面接了一個
     * 說明「這是什麼類型的地方」的詞。後綴必須是這種通用詞才算——
     * 「綠島小」與「綠島小長城」差的「長城」是專名，不能被吃掉。
     */
    private val GENERIC_SUFFIXES = setOf(
        "遊憩區", "休憩區", "風景區", "風景特定區", "觀光區", "景區", "園區",
        "公園", "步道", "老街", "海水浴場", "海灘", "遊客中心"
    )

    fun isSame(
        nameA: String, latA: Double, lngA: Double,
        nameB: String, latB: Double, lngB: Double
    ): Boolean {
        val dist = distanceMeters(latA, lngA, latB, lngB)
        if (dist < 80) return true
        if (dist >= 5000) return false
        val aliasesA = aliases(nameA)
        val aliasesB = aliases(nameB)
        if (aliasesA.isEmpty() || aliasesB.isEmpty()) return false
        // 主名相同：容許到 5 公里（台東／臺東天后宮相距 830m、森林公園 1.4km）
        if (aliasesA.first() == aliasesB.first()) return true
        if (dist >= 2000) return false
        return aliasesA.any { a ->
            aliasesB.any { b ->
                if (a == b) return@any true
                val (short, long) = if (a.length <= b.length) a to b else b to a
                if (!long.startsWith(short)) return@any false
                // 主名前綴要 4 字（別讓「綠島小」吃掉「綠島小長城」）；別名是人寫下來的
                // 另一個叫法，3 字就夠（如「小野柳」）
                val minLen = if (a == aliasesA.first() && b == aliasesB.first()) 4 else 3
                if (short.length >= minLen) return@any true
                // 主名較短（如「加路蘭」3 字）但多出來的只是通用地名後綴 → 仍是同一處
                short.length >= 3 && long.removePrefix(short) in GENERIC_SUFFIXES
            }
        }
    }

    /**
     * 把同一個地方的重複項合併，只留一筆。順序以先出現者為準，除非 [prefer] 認為
     * 後來的比已留下的更該留（例如日出景點要留在日出那一天）。
     *
     * 沒有座標時只用名稱判斷（主名相同才算），不會單憑不確定的資訊合併。
     *
     * @param onDuplicate 有項目被合併掉時通知（被丟掉的、留下的）
     */
    fun <T> dedupe(
        items: List<T>,
        name: (T) -> String,
        position: (T) -> Pair<Double, Double>?,
        prefer: (candidate: T, existing: T) -> Boolean = { _, _ -> false },
        onDuplicate: (dropped: T, kept: T) -> Unit = { _, _ -> }
    ): List<T> {
        val kept = mutableListOf<T>()
        for (item in items) {
            val idx = kept.indexOfFirst { other -> same(item, other, name, position) }
            if (idx < 0) { kept += item; continue }
            val existing = kept[idx]
            if (prefer(item, existing)) {
                kept[idx] = item
                onDuplicate(existing, item)
            } else {
                onDuplicate(item, existing)
            }
        }
        return kept
    }

    private fun <T> same(a: T, b: T, name: (T) -> String, position: (T) -> Pair<Double, Double>?): Boolean {
        val pa = position(a)
        val pb = position(b)
        if (pa != null && pb != null) return isSame(name(a), pa.first, pa.second, name(b), pb.first, pb.second)
        val na = aliases(name(a)).firstOrNull()
        val nb = aliases(name(b)).firstOrNull()
        return na != null && na == nb
    }
}
