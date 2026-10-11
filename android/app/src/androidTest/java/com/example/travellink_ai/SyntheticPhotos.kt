package com.example.travellink_ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.example.travellink_ai.ui.poster.PhotoRef

/**
 * 合成佔位照片（DEBUG 專用）。
 *
 * ⚠️ 這**不是真實照片**，是用 Canvas 畫出來的佔位圖。
 *   用途：讓照片排版測試不依賴登入 / Storage / 網路，emulator 也能跑，
 *   且結果決定性、可重複。
 *
 *   能驗證：排版、切線、cover 裁切、旋轉、記憶體。
 *   驗不到：真實照片的 URL 載入、EXIF 轉正、真實長寬比（那要走測試 D）。
 *
 * 「對應行程」＝ 每張佔位圖印上該趟的景點名，方便對照哪張排在哪。
 */
object SyntheticPhotos {

    /** 幾組旅遊場景色調，讓每張看起來像不同地點而非純色塊。 */
    private val palettes = listOf(
        intArrayOf(0xFF2E6E8E.toInt(), 0xFF0E3A4F.toInt()),  // 海
        intArrayOf(0xFF3E7B4F.toInt(), 0xFF14361F.toInt()),  // 山林
        intArrayOf(0xFFE0873A.toInt(), 0xFF9C4A1A.toInt()),  // 日落
        intArrayOf(0xFF7A5AA0.toInt(), 0xFF362350.toInt()),  // 夜
        intArrayOf(0xFFD8A93A.toInt(), 0xFF8A6414.toInt()),  // 米食
        intArrayOf(0xFFB84A52.toInt(), 0xFF6E1E24.toInt())   // 街屋
    )

    /** 三種長寬比輪流，測 cover 裁切在直/橫/方都正確。 */
    private val sizes = listOf(
        1200 to 1600,   // 直
        1600 to 1200,   // 橫
        1400 to 1400    // 方
    )

    /**
     * 對應一趟行程，產出 [count] 張佔位照片。
     *
     * @param stopNames 景點名，循環套用；空的話用「照片 N」
     * @return (PhotoRef, Bitmap)：PhotoRef.url 是合成 key，直接放進 renderer 的 photos map，
     *         不經 PhotoBitmapCache（合成圖沒有 URL 可下載）
     */
    fun forTrip(stopNames: List<String>, count: Int): List<Pair<PhotoRef, Bitmap>> =
        (0 until count).map { i ->
            val label = stopNames.getOrNull(i % stopNames.size.coerceAtLeast(1))
                ?.takeIf { it.isNotBlank() } ?: "照片 ${i + 1}"
            val (w, h) = sizes[i % sizes.size]
            val bmp = make(label, i, w, h)
            PhotoRef(photoId = "syn_$i", url = "synthetic://$i") to bmp
        }

    private fun make(label: String, index: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pal = palettes[index % palettes.size]

        // 底：對角漸層
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        // 幾個半透明光斑，讓畫面不死板、也方便看出 cover 有沒有裁對位置
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawCircle(w * 0.75f, h * 0.25f, w * 0.28f, paint)
        canvas.drawCircle(w * 0.20f, h * 0.35f, w * 0.16f, paint)
        paint.color = Color.argb(30, 0, 0, 0)
        canvas.drawCircle(w * 0.30f, h * 0.80f, w * 0.30f, paint)

        // 中央序號（大字，一眼看出這是第幾張、有沒有排錯位）
        paint.color = Color.argb(60, 255, 255, 255)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = w * 0.42f
        canvas.drawText("${index + 1}", w / 2f, h * 0.55f, paint)

        // 底部深色帶 + 景點名（像地標標籤）
        paint.shader = LinearGradient(
            0f, h * 0.7f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(180, 0, 0, 0), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = w * 0.075f
        canvas.drawText(ellipsize(label, paint, w * 0.9f), w * 0.05f, h * 0.93f, paint)

        // 尺寸標記（右上），方便對照 cover 有沒有依長寬比裁對
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = w * 0.04f
        paint.color = Color.argb(160, 255, 255, 255)
        canvas.drawText("${w}×${h}", w * 0.95f, h * 0.08f, paint)

        return bmp
    }

    private fun ellipsize(text: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var end = text.length
        val budget = maxWidth - p.measureText("…")
        while (end > 0 && p.measureText(text, 0, end) > budget) end--
        return text.substring(0, end.coerceAtLeast(0)) + "…"
    }
}
