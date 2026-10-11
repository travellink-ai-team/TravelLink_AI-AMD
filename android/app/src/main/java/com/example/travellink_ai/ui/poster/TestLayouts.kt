package com.example.travellink_ai.ui.poster

/**
 * 版面範本。
 *
 * ★ 數值直接對齊網頁端 `ai-travel-planner-v8.js` 的 MEMORY_TEMPLATES
 *   （2026-08-08 版），讓兩端九宮格長一樣。四個範本：
 *     classic  原本    4 卡，有傾斜
 *     grid6    整齊六宮 6 卡直立
 *     collage5 錯落拼貼 5 卡
 *     feature5 雜誌主打 5 卡
 *   （檔名沿用 TestLayouts 以免動到現有引用；內容已是正式範本。）
 *
 * angle 由範本內建（原本那個有傾斜、新三個直立），使用者可移動/縮放但不自己轉。
 * 使用者也能自行加小卡超出範本原本張數，故卡數不固定。
 */
object TestLayouts {

    fun byKey(key: String): PhotoLayout =
        all.firstOrNull { it.templateKey == key } ?: all.first()

    val all: List<PhotoLayout> get() = listOf(classic(), grid6(), collage5(), feature5())

    /** classic「原本」：4 張傾斜小卡 + 中列標題帶。 */
    fun classic() = PhotoLayout(
        templateKey = "classic",
        background = fill(),
        cards = listOf(
            card(0.30f, 0.26f, 0.34f, -5f),
            card(0.72f, 0.30f, 0.30f, 4f),
            card(0.27f, 0.72f, 0.30f, -3f),
            card(0.70f, 0.76f, 0.28f, 6f)
        ),
        titleCard = TitleCard(cx = 0.5f, cy = 0.50f, w = 0.56f, h = 0.11f)
    )

    /** grid6「整齊六宮」：上下各三張，正方形固定比例才會整齊 + 正中標題。 */
    fun grid6() = PhotoLayout(
        templateKey = "grid6",
        background = fill(),
        cards = listOf(
            square(0.25f, 0.19f, 0.28f), square(0.5f, 0.19f, 0.28f), square(0.75f, 0.19f, 0.28f),
            square(0.25f, 0.81f, 0.28f), square(0.5f, 0.81f, 0.28f), square(0.75f, 0.81f, 0.28f)
        ),
        titleCard = TitleCard(cx = 0.5f, cy = 0.5f, w = 0.64f, h = 0.13f)
    )

    /** collage5「錯落拼貼」：四角 + 底中。 */
    fun collage5() = PhotoLayout(
        templateKey = "collage5",
        background = fill(),
        cards = listOf(
            card(0.26f, 0.22f, 0.34f), card(0.73f, 0.24f, 0.28f),
            card(0.24f, 0.73f, 0.28f), card(0.77f, 0.74f, 0.30f),
            card(0.5f, 0.86f, 0.26f)
        ),
        titleCard = TitleCard(cx = 0.5f, cy = 0.5f, w = 0.56f, h = 0.12f)
    )

    /** feature5「雜誌主打」：左上主圖較大。 */
    fun feature5() = PhotoLayout(
        templateKey = "feature5",
        background = fill(),
        cards = listOf(
            card(0.30f, 0.22f, 0.38f), card(0.74f, 0.20f, 0.28f),
            card(0.22f, 0.78f, 0.26f), card(0.5f, 0.80f, 0.24f),
            card(0.80f, 0.78f, 0.28f)
        ),
        titleCard = TitleCard(cx = 0.5f, cy = 0.5f, w = 0.6f, h = 0.12f)
    )

    /** Spike 與現有呼叫用的預設範本（錯落拼貼）。 */
    fun collage() = collage5()

    /** 預設範本會用到幾張照片（1 底圖 + N 小卡）。 */
    val collageCapacity: Int get() = 1 + collage().cards.size

    private fun fill() = PhotoSlot(cx = 0.5f, cy = 0.5f, w = 1f)

    private fun card(cx: Float, cy: Float, w: Float, angle: Float = 0f) = PhotoSlot(
        cx = cx, cy = cy, w = w, angle = angle,
        cornerRadius = 0.012f,
        borderWidth = 0.010f
    )

    /** 正方形固定比例的卡（整齊格用），照片 cover 裁成方形。 */
    private fun square(cx: Float, cy: Float, w: Float) = PhotoSlot(
        cx = cx, cy = cy, w = w, angle = 0f,
        cornerRadius = 0.012f,
        borderWidth = 0.010f,
        aspect = 1f
    )
}

/**
 * 把照片依序填進版面：第 1 張當底圖鋪滿，其餘依序填小卡（對齊網頁：hero=ordered[0]）。
 * 少於範本張數時，缺的小卡留空（不畫）。
 */
fun PhotoLayout.withPhotos(photos: List<PhotoRef>): PhotoLayout {
    if (photos.isEmpty()) return this
    val bg = background?.copy(photoRef = photos.first())
    val cardPhotos = photos.drop(1)
    val filledCards = cards.mapIndexed { i, slot ->
        slot.copy(photoRef = cardPhotos.getOrNull(i))
    }.filter { it.photoRef != null }
    return copy(background = bg, cards = filledCards)
}
