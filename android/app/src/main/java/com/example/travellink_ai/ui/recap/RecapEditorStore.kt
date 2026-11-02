package com.example.travellink_ai.ui.recap

import android.content.Context
import com.example.travellink_ai.ui.recap.model.RecapSavedTimeline
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import com.google.gson.Gson

/**
 * 回顧短片編輯器的本機存檔（與網頁相同：存在這台裝置，不上雲）。
 *  - 選的影片節奏：全部行程共用一個（網頁 localStorage `wai_recap_template`）
 *  - 編輯過的時間軸：每趟行程一份，連同簽章（[RecapTimelines.signature]）；站、照片或節奏變了就不適用
 */
class RecapEditorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recap_editor", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 沒選過的人用 Reels（網頁同樣改了預設）；選過別的節奏照舊 */
    var templateId: String
        get() = prefs.getString("template", null)?.takeIf { RecapTimelines.hasTemplate(it) } ?: RecapTimelines.RECOMMENDED_TEMPLATE_ID
        set(value) { prefs.edit().putString("template", value).apply() }

    /** Reels 的長度（all／15），全部行程共用 */
    var lengthMode: String
        get() = prefs.getString("length", null)?.takeIf { it in RecapTimelines.LENGTH_MODES } ?: "all"
        set(value) { prefs.edit().putString("length", value).apply() }

    /** 這趟選的主角景點（stopId）；null＝依規則挑 */
    fun heroStopId(tripKey: String): String? = prefs.getString("hero_$tripKey", null)
    fun setHeroStopId(tripKey: String, stopId: String?) {
        prefs.edit().apply { if (stopId == null) remove("hero_$tripKey") else putString("hero_$tripKey", stopId) }.apply()
    }

    /** 這趟選的封面（stopId）；null＝行程插圖 */
    fun coverStopId(tripKey: String): String? = prefs.getString("cover_$tripKey", null)
    fun setCoverStopId(tripKey: String, stopId: String?) {
        prefs.edit().apply { if (stopId == null) remove("cover_$tripKey") else putString("cover_$tripKey", stopId) }.apply()
    }

    /** 封面站裡選的那張（photoId）；null＝那站第一張 */
    fun coverPhotoId(tripKey: String): String? = prefs.getString("coverPhoto_$tripKey", null)
    fun setCoverPhotoId(tripKey: String, photoId: String?) {
        prefs.edit().apply { if (photoId == null) remove("coverPhoto_$tripKey") else putString("coverPhoto_$tripKey", photoId) }.apply()
    }

    /** 這趟存的編輯版：簽章相符才回傳；不符回 (null, 有沒有過時的編輯) */
    fun load(tripKey: String, sig: String): Pair<RecapTimeline?, Boolean> {
        val raw = prefs.getString("tl_$tripKey", null) ?: return null to false
        val saved = runCatching { gson.fromJson(raw, RecapSavedTimeline::class.java) }.getOrNull() ?: return null to false
        return if (saved.sig == sig && saved.timeline != null) saved.timeline to false
        else null to (saved.timeline?.edited == true)
    }

    /** 編輯過才存；推薦版（沒改過）就清掉 */
    fun save(tripKey: String, sig: String, timeline: RecapTimeline?) {
        val e = prefs.edit()
        if (timeline?.edited == true) e.putString("tl_$tripKey", gson.toJson(RecapSavedTimeline(sig, timeline)))
        else e.remove("tl_$tripKey")
        e.apply()
    }

    fun clear(tripKey: String) { prefs.edit().remove("tl_$tripKey").apply() }

    private data class SavedMusic(val sig: String = "", val suggestion: RecapMusic.Suggestion? = null)

    /** 這趟的配樂建議：簽章（節奏、站、照片）相符才用，免得每次進頁都再問一次 AI */
    fun loadMusic(tripKey: String, sig: String): RecapMusic.Suggestion? {
        val raw = prefs.getString("music_$tripKey", null) ?: return null
        val saved = runCatching { gson.fromJson(raw, SavedMusic::class.java) }.getOrNull() ?: return null
        // Gson 不管 Kotlin 的非空型別：舊格式缺欄位會是 null，檢查時出錯就當沒存
        return runCatching { saved.suggestion?.takeIf { saved.sig == sig && it.mood.isNotEmpty() && it.keywords.isNotEmpty() } }.getOrNull()
    }

    fun saveMusic(tripKey: String, sig: String, suggestion: RecapMusic.Suggestion) {
        prefs.edit().putString("music_$tripKey", gson.toJson(SavedMusic(sig, suggestion))).apply()
    }

    fun settings(tripKey: String) =
        RecapSettings(templateId, lengthMode, heroStopId(tripKey), coverStopId(tripKey), coverPhotoId(tripKey))
}

/**
 * 一支短片的設定：節奏、Reels 的長度、主角景點、封面（回顧短片頁與旅程結束自動產生共用，送出的內容才會一樣）。
 * 長度、主角與封面只有 Reels 用得到。
 */
data class RecapSettings(
    val templateId: String,
    val lengthMode: String = "all",
    val heroStopId: String? = null,
    val coverStopId: String? = null,
    val coverPhotoId: String? = null
) {
    val isReels: Boolean get() = RecapTimelines.isAnchor(templateId)

    /** 自選封面在 stops 的位置；那站沒有照片（或不是 Reels）就是 null＝行程插圖 */
    fun coverIndex(trip: RecapTrip, photos: List<RecapPhoto>): Int? {
        if (!isReels) return null
        val i = coverStopId?.let { id -> trip.stops.indexOfFirst { it.stopId == id } } ?: return null
        return i.takeIf { idx -> idx >= 0 && photos.any { it.stopIndex == idx } }
    }

    /** 封面用的那張：選的那張還在要送的照片裡就用它，否則那站第一張；沒有自選封面是 null */
    fun coverPhoto(trip: RecapTrip, photos: List<RecapPhoto>): RecapPhoto? {
        val idx = coverIndex(trip, photos) ?: return null
        val list = photos.filter { it.stopIndex == idx }
        return list.firstOrNull { coverPhotoId != null && it.photoId == coverPhotoId } ?: list.first()
    }

    /**
     * 實際送出的照片：後端封面用 coverStopIndex 那站的第一張，所以把選的那張移到那站最前面。
     * 照片片段是用 photoId 對照片，順序不受影響；那站有照片沒有 photoId（依位置對）就不動。
     */
    fun requestPhotos(trip: RecapTrip, photos: List<RecapPhoto>): List<RecapPhoto> {
        val chosen = coverPhoto(trip, photos) ?: return photos
        val first = photos.indexOfFirst { it.stopIndex == chosen.stopIndex }
        if (photos[first] == chosen || photos.any { it.stopIndex == chosen.stopIndex && it.photoId == null }) return photos
        return photos.toMutableList().apply { remove(chosen); add(first, chosen) }
    }

    /** 選的主角還在這趟行程裡才算數 */
    fun hero(trip: RecapTrip): String? = heroStopId?.takeIf { id -> isReels && trip.stops.any { it.stopId == id } }

    fun signature(trip: RecapTrip, photos: List<RecapPhoto>) =
        RecapTimelines.signature(templateId, trip, photos, lengthMode, hero(trip))

    fun recommended(trip: RecapTrip, photos: List<RecapPhoto>) =
        RecapTimelines.buildRecommended(trip, templateId, photos, lengthMode, hero(trip), coverIndex(trip, photos))

    /** 存的編輯版：封面另外存，取出來時套上目前選的 */
    fun withCover(timeline: RecapTimeline, trip: RecapTrip, photos: List<RecapPhoto>) =
        if (timeline.schemaVersion == 2) RecapTimelines.setCover(timeline, coverIndex(trip, photos)) else timeline
}
