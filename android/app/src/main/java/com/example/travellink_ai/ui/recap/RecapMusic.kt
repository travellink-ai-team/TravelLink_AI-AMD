package com.example.travellink_ai.ui.recap

import com.example.travellink_ai.data.local.LocalItinerary
import org.json.JSONObject

/**
 * 回顧短片的配樂建議（C6）：影片做好後給一張文字卡——氛圍、30 字內的理由、3 個 IG 音樂庫搜尋關鍵字。
 * 只建議方向，不給歌名（IG 音樂庫因地區、帳號類型而異，指定歌名常常搜不到）。
 * AI 看每站一張代表照（最多 [MAX_PHOTOS] 張、長邊 [PHOTO_EDGE_PX]px）；照片那次失敗就只用景點類型＋節奏再問一次。
 * 指令與輸出格式與網頁同一份（網頁尚未實作，以這裡為準）。
 */
object RecapMusic {
    const val MAX_PHOTOS = 6
    const val PHOTO_EDGE_PX = 512
    const val REASON_MAX = 30
    const val KEYWORD_COUNT = 3

    data class Suggestion(val mood: String, val reason: String, val keywords: List<String>)

    /** 每站的提示（stopId 對 [RecapTrip].stops）：景點類型、是不是晚上的行程 */
    data class StopHint(val stopType: String, val night: Boolean)

    /** 節奏 → 氣氛基調（提案 C6 的對照表） */
    fun moodBase(templateId: String): String = when (templateId) {
        "reels" -> "明亮、輕快"   // 提案的對照表沒有 Reels（後來才加）：照片當主角、節奏明快
        "brisk" -> "輕快、高能量"
        "leisurely" -> "舒緩、悠閒"
        "cinematic" -> "電影感、壯闊"
        else -> "明亮、溫暖"
    }

    /** 從行程原始站點取類型與夜間（抵達 18:00 以後，或最佳時段寫晚上）；住宿站的入住時間不算夜間行程 */
    fun hints(item: LocalItinerary): Map<String, StopHint> =
        item.stops.filter { !it.isStation }.associate { s ->
            val hour = s.time.trim().substringBefore(":").toIntOrNull()
            val night = !s.isLodging && ((hour != null && hour >= 18) || s.bestTime.contains("晚上"))
            s.stopId to StopHint(s.stopType.trim(), night)
        }

    /** @param photoCount 實際附上的照片張數（0＝只用文字） */
    fun prompt(trip: RecapTrip, templateId: String, hints: Map<String, StopHint>, photoCount: Int): String {
        val t = RecapTimelines.template(templateId)
        val days = trip.stops.maxOfOrNull { it.dayIndex } ?: 1
        val night = trip.stops.any { hints[it.stopId]?.night == true }
        val stops = trip.stops.mapIndexed { i, s ->
            val type = hints[s.stopId]?.stopType?.takeIf { it.isNotEmpty() }
            "${i + 1}. ${s.name}" + (type?.let { "（$it）" } ?: "") + if (days > 1) "・第 ${s.dayIndex} 天" else ""
        }
        return (listOf(
            "你是旅遊短片的配樂顧問，要替一支旅遊回顧短片建議 Instagram 配樂的方向（使用者發布時會自己到 IG 音樂庫搜尋加入）。",
            "旅程：${trip.title.take(40)}；地區：${trip.region}；共 $days 天、${trip.stops.size} 個景點；有晚上的行程：${if (night) "有" else "沒有"}",
            "影片節奏：${t.label}（${t.desc}）→ 氣氛基調：${moodBase(t.id)}",
            "景點（依順序）："
        ) + stops + listOfNotNull(
            if (photoCount > 0) "另附 $photoCount 張照片，是依序幾個景點的代表照；請從照片看整體的色調、場景與氛圍（海邊、山林、城市、夜景等）。" else null,
            "",
            "規則：",
            "1. 不可建議具體的歌名、歌手或專輯，只給曲風與氛圍。",
            "2. mood：4～6 個中文字的氛圍詞，以上面的氣氛基調為主，再依${if (photoCount > 0) "照片與" else ""}景點類型微調。",
            "3. reason：$REASON_MAX 個中文字以內，說明為什麼適合；只能根據上面的資料${if (photoCount > 0) "與照片" else ""}，不可編造天氣、同行者或發生的事。",
            "4. keywords：剛好 $KEYWORD_COUNT 個，用來在 IG 音樂庫搜尋；中英混合，中文 2～4 個字、英文 2～4 個單字，不要 hashtag 與 emoji。",
            "只回 JSON（不要多餘文字）：{\"mood\":\"輕快明亮\",\"reason\":\"理由\",\"keywords\":[\"summer chill\",\"acoustic beach\",\"城市漫遊\"]}"
        )).joinToString("\n")
    }

    /** 解析 AI 回應；mood 空或一個關鍵字都沒有就回 null。理由超過字數截斷，關鍵字去 # 去重、最多 3 個 */
    fun parse(text: String): Suggestion? {
        val body = text.substringAfter("{", "").takeIf { it.isNotEmpty() }?.let { "{" + it.substringBeforeLast("}") + "}" } ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val mood = o.optString("mood").trim().takeIf { it.isNotEmpty() } ?: return null
        val reason = o.optString("reason").trim().let { r ->
            if (r.codePointCount(0, r.length) <= REASON_MAX) r else r.substring(0, r.offsetByCodePoints(0, REASON_MAX))
        }
        val arr = o.optJSONArray("keywords")
        val keywords = (0 until (arr?.length() ?: 0))
            .map { arr!!.optString(it).trim().trimStart('#').trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(KEYWORD_COUNT)
        if (keywords.isEmpty()) return null
        return Suggestion(mood, reason, keywords)
    }

    /** 每站一張、依景點順序、最多 [MAX_PHOTOS] 張；超過就平均挑，頭尾都留 */
    fun pickPhotos(photos: List<RecapPhoto>): List<RecapPhoto> {
        val sorted = photos.distinctBy { it.stopIndex }.sortedBy { it.stopIndex }
        if (sorted.size <= MAX_PHOTOS) return sorted
        return (0 until MAX_PHOTOS).map { i -> sorted[i * (sorted.size - 1) / (MAX_PHOTOS - 1)] }
    }
}
