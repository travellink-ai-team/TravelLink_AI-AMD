package com.example.travellink_ai.ui.recap.model

/**
 * 回顧短片的「時間軸」（送後端 POST /recap/render 的 `timeline`），欄位名與網頁 app/recap-timeline.js 相同：
 *   { schemaVersion, locale, revision, templateId, clips: [{ clipId, order, type, ... durationMs }] }
 * type：cover（開場）、route（站間路線）、photo（到站照片）、timeline（換日轉場）、summary（旅程收束）、outro（旅程數據／攻略卡）。
 * 第 1 版：標準／快節奏／慢遊／電影感開場；第 2 版（schemaVersion 2）：Reels，見 RECAP_IG_V2_ALIGNMENT.md。
 *
 * Gson 序列化：null 欄位不輸出（與網頁「沒有就不寫」一致）。放在 model 套件，release 混淆時欄位名保留
 *（proguard-rules：`ui.**.model.**`）。
 */
data class RecapAssetRef(
    val kind: String = "memory_photo",
    val stopId: String = "",
    val photoId: String? = null
)

data class RecapOutroStats(
    val stops: Int = 0,
    val distanceKm: Double = 0.0,
    val days: Int = 1
)

data class RecapClip(
    val clipId: String = "",
    val order: Int = 0,
    val type: String = "",
    /** cover：title（台灣全景俯衝）或 photoCut（實景照片硬切） */
    val style: String? = null,
    /** 第 2 版 cover：illustration（行程插圖）或 photo（自選封面照片，見 [coverStopIndex]） */
    val source: String? = null,
    val coverStopIndex: Int? = null,
    val stopIndex: Int? = null,
    val label: String? = null,
    /** 第 1 版 photo：單張 */
    val assetRef: RecapAssetRef? = null,
    /** 第 2 版 photo：一站多張 */
    val assetRefs: List<RecapAssetRef>? = null,
    /** 第 2 版 photo：主角景點 */
    val hero: Boolean? = null,
    /** 第 2 版 photo：多日行程當天第一張照片標「第 N 天」 */
    val dayLabel: Int? = null,
    val segmentIndex: Int? = null,
    /** 第 2 版 route：路線揭曉（第一站到最後一站） */
    val reveal: Boolean? = null,
    val fromStopId: String? = null,
    val toStopId: String? = null,
    /** 第 2 版 route：經過的站（必須正好是起訖之間的站、依序） */
    val viaStopIds: List<String>? = null,
    val distanceKm: Double? = null,
    /** timeline（換日）：第幾天 */
    val day: Int? = null,
    val stats: RecapOutroStats? = null,
    val durationMs: Long = 0,
    /** 字幕（14 字內）；沒有是 null */
    val caption: String? = null,
    /** 移除照片時的暫時標記（重接路線用，不會留在送出的時間軸裡） */
    val marker: Boolean? = null
)

data class RecapTimeline(
    val schemaVersion: Int = 1,
    val locale: String = "zh-TW",
    val revision: Int = 0,
    val templateId: String = "standard",
    /** 第 2 版：all（放進全部照片）／15（固定 15 秒） */
    val lengthMode: String? = null,
    /** 第 2 版：photoStory／mapFeatured／routeStory（顯示用，後端自己重算） */
    val structure: String? = null,
    val adaptiveTier: String = "",
    val totalMs: Long = 0,
    val clips: List<RecapClip> = emptyList(),
    /** 使用者改過（秒數、字幕、移除照片、AI 字幕）；推薦版是 null */
    val edited: Boolean? = null,
    /** AI 寫過字幕時是 "ai" */
    val scriptSource: String? = null
)

/** 本機存的編輯版時間軸（RecapEditorStore）：簽章相符才適用 */
data class RecapSavedTimeline(
    val sig: String = "",
    val timeline: RecapTimeline? = null
)
