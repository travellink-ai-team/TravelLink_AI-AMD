package com.example.travellink_ai.ui.recap

/**
 * 回顧短片的資料模型，欄位對齊組員交接契約
 * （TRAVEL_STORY_RECAP_APP_HANDOFF.md §2，POST /api/recap/render）。
 *
 * 用一般 data class（Gson 序列化，沿用專案既有 Ktor+Gson），欄位名即 JSON key。
 */

/** 一個景點。對齊契約 stops[]。 */
data class RecapStop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** 該站抵達所用交通工具。枚舉：taxi | car | scooter | walk | ferry。 */
    val mode: String,
    /** 停留分鐘。 */
    val stayMin: Int,
    /** 第幾天，由 1 起算。 */
    val dayIndex: Int,
    /** 預計抵達時間 HH:MM（沒有是空字串）；後端收進來給地圖飛行風格用 */
    val arriveTime: String = ""
)

/** 整趟行程。對齊契約 trip。 */
data class RecapTrip(
    val title: String,
    val region: String,
    /** 顯示用日期字串，例如 "2026.08.13–14"。 */
    val dateLabel: String,
    val people: String,
    /** 總路程公里（數字）。 */
    val distanceKm: Double,
    /** 枚舉同 RecapStop.mode。 */
    val transportMode: String,
    val stops: List<RecapStop>,
    /** 攻略卡的每人花費（元，與 App 費用卡同一套算法）；null 不顯示 */
    val costPerPerson: Int? = null,
    /** 使用者指定的主角景點（stopId）；null＝依規則挑 */
    val highlightStopId: String? = null
)

/**
 * 到站要插入的打卡照片。對齊契約 photos[]：用 stopIndex（stops 陣列索引）。
 * 第 1 版每站送一張；Reels（第 2 版）同一站可以多張（順序＝共同相簿的排序），[photoId] 給時間軸 assetRefs 對照。
 */
data class RecapPhoto(
    val stopIndex: Int,
    val url: String,
    val photoId: String? = null
)

/**
 * POST /api/recap/render 的 body：{trip, photos, routePoints, templateId, timeline, lengthMode, tripId, coverStopIndex}。
 *
 * ★ routePoints 是巢狀數字陣列 `[[[lat,lng], ...], ...]`（每段一條折線，長度 = stops-1），
 *   不是物件陣列。選填；優先帶 routeGeometry 快取（兩端一致，見 RecapRepository）。
 */
data class RecapRenderRequest(
    val trip: RecapTrip,
    val photos: List<RecapPhoto>,
    val routePoints: List<List<List<Double>>>?,
    /** 影片節奏（reels／standard／brisk／leisurely／cinematic）；後端不認得就用標準 */
    val templateId: String? = null,
    /** 片段時間軸（推薦版或編輯過的），含字幕；後端會重新驗證、夾住每段時長 */
    val timeline: com.example.travellink_ai.ui.recap.model.RecapTimeline? = null,
    /** Reels：all（放進全部照片，最長 30 秒）／15（固定 15 秒） */
    val lengthMode: String? = null,
    /** 雲端行程 id：後端讀 micro_trips/{tripId}.imageUrl 當封面（會檢查呼叫者是成員） */
    val tripId: String? = null,
    /** 自選封面：那站要有照片，否則後端改用行程插圖 */
    val coverStopIndex: Int? = null
)

/** POST /recap/render 回傳 {ok, jobId, totalMs}（其餘欄位 Gson 忽略）。 */
data class RecapJobCreated(val jobId: String)

/** GET /recap/jobs/{jobId} 回傳。status: queued | rendering | done | error。 */
data class RecapJobStatus(
    val status: String,
    val progress: Int = 0,
    val error: String? = null
) {
    val isDone: Boolean get() = status == "done"
    val isError: Boolean get() = status == "error"
    val isTerminal: Boolean get() = isDone || isError
}

/**
 * 雲端已存的回顧短片（micro_trips/{tripId}/recaps/{uid}）。
 * 兩端共用；App 讀到就直接播 videoUrl，sig 比對當前行程判斷要不要重生。
 */
data class CloudRecap(
    val videoUrl: String,
    val sig: String,
    val title: String,
    val ownerName: String,
    val updatedAt: Long
)
