package com.example.travellink_ai.ui.poster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * 「照片排版」九宮格的渲染器。
 *
 * 吃一份 [PhotoLayout]（版面即資料）＋ 已載入的照片，畫出主視覺。
 * 版面數值全部相對主視覺（0~1），因此與逐格渲染、輸出解析度無關 ——
 * 由 [TilePool] 決定畫哪一格、多大。
 *
 * ★ 版面現在是測試版（[TestLayouts]）。組員的網頁編輯器輸出的 [PhotoLayout]
 *   直接替換即可，這個渲染器不動。
 *
 * @param photos url → 已降採樣的 Bitmap（來自 [PhotoBitmapCache]）
 * @param title  標題文字（來自行程，不寫死在版面裡）
 * @param subtitle 副標，例如「綠島・2天」
 */
class PhotoGridRenderer(
    private val layout: PhotoLayout,
    private val photos: Map<String, Bitmap>,
    private val title: String,
    private val subtitle: String
) : TripPosterRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas, masterW: Float, masterH: Float) {
        drawBase(canvas, masterW, masterH)
        layout.background?.let { drawPhotoSlot(canvas, it, masterW, masterH, fill = true) }
        layout.cards.forEach { drawPhotoSlot(canvas, it, masterW, masterH, fill = false) }
        layout.titleCard?.takeIf { it.visible }?.let { drawTitleCard(canvas, it, masterW, masterH) }
    }

    /** 底：漸層，作為沒有底圖照片、或底圖為透明區域時的墊底。 */
    private fun drawBase(canvas: Canvas, w: Float, h: Float) {
        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            Color.parseColor("#2A2622"), Color.parseColor("#14110E"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null
    }

    /**
     * 把一張照片畫進 slot。
     *
     * - 底圖（fill）：cover 填滿整張主視覺 + 焦點對齊。
     * - 小卡：卡片**高度由照片長寬比決定**（對齊網頁，範本只給 w），
     *   照片完整不裁切變形（白框拍立得），加陰影 + 旋轉。
     */
    private fun drawPhotoSlot(canvas: Canvas, slot: PhotoSlot, w: Float, h: Float, fill: Boolean) {
        val bmp = slot.photoRef?.url?.let { photos[it] } ?: return
        if (bmp.isRecycled || bmp.width == 0) return

        val rect: RectF
        if (fill) {
            rect = RectF(0f, 0f, w, h)
        } else {
            val cardW = slot.w.coerceIn(PhotoSlot.MIN_W, PhotoSlot.MAX_W) * w
            // aspect 有值＝固定比例（整齊格，照片 cover 裁切）；null＝依照片比例（拍立得完整）
            val cardH = slot.aspect?.let { cardW * it } ?: (cardW * bmp.height / bmp.width)
            rect = RectF(
                slot.cx * w - cardW / 2, slot.cy * h - cardH / 2,
                slot.cx * w + cardW / 2, slot.cy * h + cardH / 2
            )
        }

        val save = canvas.save()
        if (!fill && slot.angle != 0f) {
            canvas.rotate(slot.angle, rect.centerX(), rect.centerY())
        }

        val border = slot.borderWidth * w
        val photoRect = RectF(rect)
        if (border > 0f) {
            fillPaint.shader = null
            fillPaint.color = Color.WHITE
            val r = slot.cornerRadius * w
            // 陰影讓小卡浮起來（對齊網頁 drawMemoryCard 的 shadow）
            if (!fill) fillPaint.setShadowLayer(w * 0.013f, 0f, w * 0.005f, Color.argb(115, 0, 0, 0))
            canvas.drawRoundRect(rect, r, r, fillPaint)
            fillPaint.clearShadowLayer()
            photoRect.inset(border, border)
        }

        val clip = Path().apply {
            val r = slot.cornerRadius * w
            addRoundRect(photoRect, r, r, Path.Direction.CW)
        }
        val clipSave = canvas.save()
        canvas.clipPath(clip)
        // 卡片矩形已等於照片比例，cover 即完整填滿無裁切
        canvas.drawBitmap(bmp, coverMatrix(bmp, photoRect, slot.focusX, slot.focusY, slot.zoom), paint)
        canvas.restoreToCount(clipSave)

        canvas.restoreToCount(save)
    }

    /**
     * 算出把 [bmp] 以 cover 方式填滿 [dst] 的矩陣：
     * 等比縮放到剛好蓋滿，再讓照片的 (focusX,focusY) 這個相對點對齊 dst 中心。
     */
    private fun coverMatrix(
        bmp: Bitmap, dst: RectF, focusX: Float, focusY: Float, zoom: Float
    ): Matrix {
        val scale = maxOf(dst.width() / bmp.width, dst.height() / bmp.height) * zoom
        val focusPxX = focusX * bmp.width * scale
        val focusPxY = focusY * bmp.height * scale
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dst.centerX() - focusPxX, dst.centerY() - focusPxY)
        }
    }

    /** 標題卡：半透明深色底 + 標題 + 副標，置中。 */
    private fun drawTitleCard(canvas: Canvas, card: TitleCard, w: Float, h: Float) {
        val rect = RectF(
            (card.cx - card.w / 2) * w, (card.cy - card.h / 2) * h,
            (card.cx + card.w / 2) * w, (card.cy + card.h / 2) * h
        )
        fillPaint.shader = null
        fillPaint.color = Color.argb((card.scrimAlpha * 255).toInt(), 20, 18, 15)
        val r = card.cornerRadius * w
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // 字體家族對齊網頁 MEMORY_TITLE_FONTS（sans/serif）
        val titleTypeface = if (card.font == "serif")
            Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD

        textPaint.color = Color.WHITE
        textPaint.typeface = titleTypeface
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = w * 0.048f
        canvas.drawText(
            ellipsize(title, textPaint, rect.width() * 0.9f),
            rect.centerX(), rect.centerY() + w * 0.005f, textPaint
        )

        if (subtitle.isNotBlank()) {
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = w * 0.024f
            textPaint.color = Color.parseColor("#C9C4BC")
            canvas.drawText(
                ellipsize(subtitle, textPaint, rect.width() * 0.9f),
                rect.centerX(), rect.centerY() + w * 0.048f, textPaint
            )
        }
    }

    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var end = text.length
        val budget = maxWidth - p.measureText("…")
        while (end > 0 && p.measureText(text, 0, end) > budget) end--
        return text.substring(0, end.coerceAtLeast(0)) + "…"
    }
}
