package com.example.travellink_ai.data.model

/**
 * 行程插圖上每一站的位置（互動插圖）。
 *
 * 插圖是 AI 畫的，不對應真實地理，所以站在圖上的哪裡由後端 `detect_stop_boxes`
 * 請 Gemini 看圖找出來，存在 `micro_trips/{tripId}.imageStopBoxes`（App 與網頁共用）：
 * ```
 * imageStopBoxes: {
 *   imageUrl: "這組框是看哪張插圖找的",
 *   model: "gemini-3-flash-preview",
 *   at: Timestamp,
 *   boxes: [{ stopId, name, box: [ymin, xmin, ymax, xmax], point: [y, x] }]   // 0～1000，相對整張圖
 * }
 * ```
 * 插圖重畫後 imageUrl 會變，[imageUrl] 對不上目前插圖就表示這組框已經作廢。
 */
data class IllustrationStopBoxes(
    val imageUrl: String,
    val boxes: List<StopBox>
) {
    fun boxOf(stopId: String): StopBox? = boxes.firstOrNull { it.stopId == stopId }

    companion object {
        /** 解析 Firestore 或 callable 回傳的 map；格式不對回 null */
        fun fromMap(raw: Any?): IllustrationStopBoxes? {
            val m = raw as? Map<*, *> ?: return null
            val url = m["imageUrl"] as? String ?: return null
            val boxes = (m["boxes"] as? List<*>).orEmpty().mapNotNull { StopBox.fromMap(it) }
            return IllustrationStopBoxes(url, boxes)
        }
    }
}

/** 一站在插圖上的框，座標都是 0～1 的比例（Firestore 存 0～1000） */
data class StopBox(
    val stopId: String,
    val name: String,
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
    /** 該站在路線上的標記（編號圓點）中心；舊資料沒有就用框中心。進行中的小點沿這些點走 */
    val pointX: Float? = null,
    val pointY: Float? = null
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val anchorX: Float get() = pointX ?: centerX
    val anchorY: Float get() = pointY ?: centerY
    val area: Float get() = (right - left) * (bottom - top)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        fun fromMap(raw: Any?): StopBox? {
            val m = raw as? Map<*, *> ?: return null
            val id = m["stopId"] as? String ?: return null
            val b = (m["box"] as? List<*>)?.map { (it as? Number)?.toFloat() ?: return null } ?: return null
            if (b.size != 4) return null
            val (y0, x0, y1, x1) = b.map { (it / 1000f).coerceIn(0f, 1f) }
            if (y1 <= y0 || x1 <= x0) return null
            val p = (m["point"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat()?.div(1000f)?.coerceIn(0f, 1f) }
                ?.takeIf { it.size == 2 }
            return StopBox(id, m["name"] as? String ?: "", y0, x0, y1, x1, pointX = p?.get(1), pointY = p?.get(0))
        }
    }
}

/**
 * 點在插圖上的 (x, y)（0～1 比例）對應到哪一站：落在框內取面積最小的那個（框會重疊時，
 * 小框通常是被大框包住的那站）；都沒點中就取中心最近、且距離在 [slop] 內的站。
 */
fun List<StopBox>.hitTest(x: Float, y: Float, slop: Float = 0.06f): StopBox? =
    filter { it.contains(x, y) }.minByOrNull { it.area }
        ?: minByOrNull { (it.centerX - x) * (it.centerX - x) + (it.centerY - y) * (it.centerY - y) }
            ?.takeIf { kotlin.math.hypot(it.centerX - x, it.centerY - y) <= slop + kotlin.math.max(it.right - it.left, it.bottom - it.top) / 2f }

/** 這一站的框；舊資料沒找到時用同名站的框（回程回到出發車站，圖上只畫一次） */
fun List<StopBox>.boxFor(stop: Stop): StopBox? =
    firstOrNull { it.stopId == stop.stopId } ?: firstOrNull { it.name.isNotBlank() && it.name.trim() == stop.name.trim() }

/** 和 [box] 位置完全相同的框（含它自己）：同一地點的多站共用一個標記 */
fun List<StopBox>.sharingBox(box: StopBox): List<StopBox> =
    filter { it.top == box.top && it.left == box.left && it.bottom == box.bottom && it.right == box.right }

/**
 * 多日行程的每日插圖（`micro_trips/{id}.imageDays`，與網頁同格式）：
 * `[{ day, imageUrl, imageStopBoxes: { imageUrl, model, at（毫秒）, boxes } }]`。第 1 天同時寫在 imageUrl／imageStopBoxes。
 */
data class IllustrationDay(
    val day: Int,
    val imageUrl: String,
    val boxes: IllustrationStopBoxes?,
    /** Firestore 原樣資料：只重畫某一天時，其他天原封不動寫回 */
    val raw: Map<String, Any?> = emptyMap()
)

/**
 * 解析 imageDays。與網頁 loadPosterDaysFromDoc 同規則：最早那天的圖和 [imageUrl] 不同，代表之後有人只畫了
 * 單張插圖（以最後畫的為準）→ 回空清單、改顯示單張。站框只收「就是這張圖」的。
 */
fun parseImageDays(raw: Any?, imageUrl: String?, firstDay: Int = 1): List<IllustrationDay> {
    val days = (raw as? List<*>).orEmpty().mapNotNull { item ->
        val m = item as? Map<*, *> ?: return@mapNotNull null
        val day = (m["day"] as? Number)?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
        val url = (m["imageUrl"] as? String)?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        IllustrationDay(day, url, IllustrationStopBoxes.fromMap(m["imageStopBoxes"])?.takeIf { it.imageUrl == url },
            m.entries.associate { (k, v) -> k.toString() to v })
    }.distinctBy { it.day }.sortedBy { it.day }
    // 第 1 天那張和 imageUrl 不同＝之後有人只畫了單張；第 1 天這次沒畫成（只有其他天）時照樣算數，與網頁相同
    val first = days.firstOrNull { it.day == firstDay }
    if (days.isEmpty() || (first != null && first.imageUrl != imageUrl)) return emptyList()
    return days
}
