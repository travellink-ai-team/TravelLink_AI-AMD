package com.example.travellink_ai.ui.planning

/**
 * 使用者（或 AI）打的名稱，與 Google 回傳的正式名稱像不像。
 *
 * 實測：使用者填「鮪魚飯店」，Google 上的全名是「鮪魚家族飯店臺東館」。兩者不是「包含」
 * 關係（中間多了「家族」、結尾多了「臺東館」），連「臺／台」寫法都不同，舊比對得 0 分，
 * 加上距離分才 6，低於門檻 12，於是「真實存在的飯店被判成不存在」。
 *
 * 簡稱是使用者填住宿名稱時的常態，所以要能容忍。但這個寬鬆只給「使用者自己填的名稱」用
 * （[lenient]）：AI 選點的驗證維持嚴格——AI 會編造不存在的景點，放寬只會讓幻覺更容易過關。
 */
object PlaceNameMatch {

    /** 小寫、臺→台、去掉空白與標點：讓「臺東」「台東」「Hotel 鮪魚」這類寫法差異不影響比對 */
    fun normalize(s: String): String = s.lowercase()
        .replace("臺", "台")
        .replace(Regex("""[\s()（）【】—・,，、.。．·|｜*\-_/]"""), "")

    /**
     * 簡稱：[query] 的前兩個字（品牌名）要在 [candidate] 裡相連出現，其餘字依序出現。
     * 「鮪魚飯店」→「鮪魚家族飯店台東館」：「鮪魚」相連、「飯」「店」依序在後 → 符合。
     *
     * 要求品牌名相連是為了擋誤判：「鮪家魚飯店」拆開了品牌、「海洋飯店」根本不同，都不算。
     * 至少 3 個字才適用，2 個字的名稱太短，依序出現幾乎什麼都能湊上。
     */
    private fun isAbbreviation(query: String, candidate: String): Boolean {
        if (query.length < 3) return false
        if (!candidate.contains(query.take(2))) return false
        var from = 0
        for (ch in query) {
            val idx = candidate.indexOf(ch, from)
            if (idx < 0) return false
            from = idx + 1
        }
        return true
    }

    /**
     * 名稱相似度分數（0、5、12、14、18）。
     *  - 18：候選名稱包含查詢名稱
     *  - 14：簡稱（僅 [lenient]）
     *  - 12：查詢名稱包含候選名稱
     *  - 5：候選名稱以空白切開的任一段落被查詢名稱包含（舊規則，保留）
     */
    fun score(query: String, candidate: String, lenient: Boolean = false): Int {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        return when {
            c.contains(q) -> 18
            lenient && isAbbreviation(q, c) -> 14
            q.contains(c) -> 12
            candidate.lowercase().split(" ").any { it.isNotBlank() && q.contains(normalize(it)) &&
                normalize(it).isNotEmpty() } -> 5
            else -> 0
        }
    }
}
