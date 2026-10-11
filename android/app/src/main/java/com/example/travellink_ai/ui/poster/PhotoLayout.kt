package com.example.travellink_ai.ui.poster

/**
 * 九宮格「照片排版」的版面描述。
 *
 * ★ 核心設計：**版面是資料，不是程式碼。**
 *   組員的網頁編輯器輸出的就是一份 [PhotoLayout]，App 端渲染引擎吃這份資料。
 *   替換版面 = 替換資料，[PhotoGridRenderer] 不動。
 *
 * ★ 欄位命名已對齊網頁端（`ai-travel-planner-v8.js` 的 MEMORY_TEMPLATES）：
 *   - 小卡用 `{ cx, cy, w, angle }`，**高度不在版面裡**，由照片長寬比決定
 *     （網頁模板同樣只給 w）。
 *   - 標題用 `{ cx, cy, w, h, visible, font }`。
 *   目標是兩端共用同一份 schema：網頁輸出的 layout JSON，App 直接吃。
 *
 * ★ 座標一律相對主視覺（3240×4320）的 0f~1f，與輸出解析度、逐格渲染無關。
 */
data class PhotoLayout(
    /** 版面範本 key：classic / grid6 / collage5 / feature5（對齊網頁）。 */
    val templateKey: String = "classic",
    /** 底圖：鋪滿整張主視覺。null 表示用純色/漸層底。 */
    val background: PhotoSlot?,
    /** 疊在底圖上的照片小卡，依序由下往上疊。 */
    val cards: List<PhotoSlot>,
    /** 標題卡。null 或 visible=false 表示不放標題。 */
    val titleCard: TitleCard?
)

/**
 * 一張照片的擺放。
 *
 * ★ 沒有高度欄位：小卡高度由照片長寬比決定（白框拍立得，照片不裁切變形），
 *   與網頁一致。底圖走 fill 模式鋪滿，也不看 w/h。
 *
 * @param cx,cy 卡片中心的相對位置（0~1）
 * @param w     卡片的相對寬（0~1）；夾在 [MIN_W]~[MAX_W]
 * @param angle 旋轉角（度）；對齊網頁命名。angle 由範本內建，使用者可移動/縮放但不轉
 * @param focusX,focusY 裁切焦點：底圖 cover 時，照片的這個相對點對齊中心
 * @param zoom  額外放大倍率
 * @param cornerRadius 圓角，相對主視覺寬（0~1）
 * @param borderWidth 白邊寬度（相對主視覺寬），拍立得效果。0 = 無邊
 * @param photoRef 對應哪張照片；由選片流程填入，範本本身留空
 */
data class PhotoSlot(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val angle: Float = 0f,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val cornerRadius: Float = 0.012f,
    val borderWidth: Float = 0f,
    /**
     * 卡片高寬比（高/寬）。
     * - null：高度依**照片**長寬比，照片完整不裁（拍立得錯落，用於 classic/collage）。
     * - 有值：固定比例，照片 cover 裁切填滿（整齊格子，用於 grid6；1f＝正方形）。
     */
    val aspect: Float? = null,
    val photoRef: PhotoRef? = null
) {
    companion object {
        /** 卡片寬上下限（對齊網頁 MEMORY_CARD_MIN/MAX_W），避免縮到看不見或蓋滿整張。 */
        const val MIN_W = 0.14f
        const val MAX_W = 0.60f
    }
}

/**
 * 標題卡。文字內容不寫死在版面裡（那是行程資料），版面只描述位置與樣式。
 *
 * ★ 網頁的標題帶「落在中列內，只跨垂直切線」—— 這是刻意設計：
 *   標題橫跨左中右三格（跨垂直切線），但不跨水平切線，
 *   單張發布時上下兩列的照片格不會被標題破壞。
 */
data class TitleCard(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val visible: Boolean = true,
    /** 字體家族：sans / serif（對齊網頁 MEMORY_TITLE_FONTS）。 */
    val font: String = "sans",
    /** 卡片底色透明度（0~1），半透明深色卡。 */
    val scrimAlpha: Float = 0.72f,
    val cornerRadius: Float = 0.02f
)

/**
 * 指向一張實際照片。用 photoId 而非 URL 當長期引用（計畫 §3.2），URL 只在載入當下用。
 */
data class PhotoRef(
    val photoId: String,
    val url: String
)
