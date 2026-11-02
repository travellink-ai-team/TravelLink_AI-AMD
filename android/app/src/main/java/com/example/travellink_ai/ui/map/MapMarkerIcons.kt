package com.example.travellink_ai.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * 地圖標記圖示：網頁同款水滴大頭針（景點、廁所、我的停車點）、停車場膠囊、路線方向箭頭、路段時間標籤、「我」藍點。
 * 圖示依參數與螢幕密度快取，地圖重畫時不必每次重建 Bitmap。
 * 只能在 Google 地圖初始化之後呼叫（BitmapDescriptorFactory 的限制），也就是 GoogleMap 內容裡。
 */
internal object MapMarkerIcons {
    enum class Style { NEXT, UPCOMING, DONE }

    private val cache = HashMap<String, BitmapDescriptor>()

    /** pin() 的錨點：水滴尖端（網頁 createEmojiPinIconNumbered 的 anchor 28,68 / 尺寸 64×72） */
    val PIN_ANCHOR = androidx.compose.ui.geometry.Offset(28f / 64f, 68f / 72f)

    /**
     * 網頁同款水滴大頭針（createEmojiPinIcon／createEmojiPinIconNumbered）：彩色水滴、白圓裡放 emoji，
     * 有編號時右上角加藍色圓牌。座標照網頁的 64×72 viewBox 畫，再依樣式縮放。
     * 2026-11-02 測試者回報 App 的預設紅色大頭針看不出是第幾站、是什麼地方。
     */
    fun pin(emoji: String, number: String?, style: Style, density: Float, color: Int = PIN_GREEN): BitmapDescriptor =
        cache.getOrPut("pin|$emoji|$number|$style|$color|$density") {
            val s = density * when (style) { Style.NEXT -> 0.78f; Style.UPCOMING -> 0.62f; Style.DONE -> 0.52f }
            val fill = when (style) { Style.NEXT -> 0xFFF07B3F.toInt(); Style.DONE -> 0xFFB8B2A7.toInt(); Style.UPCOMING -> color }
            val bmp = Bitmap.createBitmap((64 * s).toInt(), (72 * s).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(s, s)
            val drop = android.graphics.Path().apply {
                moveTo(28f, 68f)
                cubicTo(28f, 68f, 8f, 46.8f, 8f, 28f)
                cubicTo(8f, 17.2f, 16.9f, 8f, 28f, 8f)
                cubicTo(39.1f, 8f, 48f, 17.2f, 48f, 28f)
                cubicTo(48f, 46.8f, 28f, 68f, 28f, 68f)
                close()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0x2E000000
            c.save(); c.translate(0f, 2f); c.drawPath(drop, p); c.restore()
            p.color = fill; c.drawPath(drop, p)
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(28f, 28f, 15f, p)
            p.textAlign = Paint.Align.CENTER
            p.textSize = 18f
            c.drawText(emoji, 28f, 28f - (p.ascent() + p.descent()) / 2, p)
            if (number != null) {
                p.color = 0xFF1A56DB.toInt(); c.drawCircle(50f, 13f, 10f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(50f, 13f, 10f, p)
                p.style = Paint.Style.FILL
                p.typeface = Typeface.DEFAULT_BOLD
                p.textSize = if (number.length > 1) 10f else 12f
                c.drawText(number, 50f, 13f - (p.ascent() + p.descent()) / 2, p)
            }
            BitmapDescriptorFactory.fromBitmap(bmp)
        }

    const val PIN_GREEN = 0xFF2E7D6D.toInt()
    const val PIN_VIOLET = 0xFF7C3AED.toInt()
    const val PIN_ORANGE = 0xFFEA580C.toInt()

    /** 網頁 createRouteMidLabelIcon 同款實心膠囊（停車場「🅿️ 名稱」）：底色＝color、白字 */
    fun pill(text: String, color: Int, density: Float): BitmapDescriptor = cache.getOrPut("pill|$text|$color|$density") {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val padH = 10 * density; val h = 24 * density
        val w = p.measureText(text) + padH * 2
        val bmp = Bitmap.createBitmap(w.toInt() + 4, (h + 3 * density).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = android.graphics.RectF(2f, 1f, w + 2f, h + 1f)
        p.color = 0x33000000; c.drawRoundRect(android.graphics.RectF(r.left, r.top + 2 * density, r.right, r.bottom + 2 * density), h / 2, h / 2, p)
        p.color = color; c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawText(text, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 路線方向箭頭（網頁 FORWARD_CLOSED_ARROW：白色外框＋路段顏色）：尖端朝上＝北，用 flat marker 的 rotation 轉向 */
    fun arrow(color: Int, density: Float): BitmapDescriptor = cache.getOrPut("arrow|$color|$density") {
        val size = (20 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = size / 16f
        fun tri(inset: Float) = android.graphics.Path().apply {
            moveTo(8f * u, (1.5f + inset) * u)
            lineTo((14.5f - inset) * u, (14f - inset * 0.6f) * u)
            lineTo(8f * u, (11f - inset * 0.4f) * u)
            lineTo((1.5f + inset) * u, (14f - inset * 0.6f) * u)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt(); c.drawPath(tri(0f), p)
        p.color = color; c.drawPath(tri(2.2f), p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 路段中間的時間標籤（例「🚗 7 分」）：白底、交通方式顏色的框與字 */
    fun label(text: String, color: Int, density: Float): BitmapDescriptor = cache.getOrPut("label|$text|$color|$density") {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        val padH = 9 * density; val h = 26 * density
        val w = p.measureText(text) + padH * 2
        val bmp = Bitmap.createBitmap(w.toInt() + 4, (h + 3 * density).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = android.graphics.RectF(2f, 1f, w + 2f, h + 1f)
        p.color = 0x33000000; c.drawRoundRect(android.graphics.RectF(r.left, r.top + 2 * density, r.right, r.bottom + 2 * density), h / 2, h / 2, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * density; p.color = color
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.FILL
        c.drawText(text, r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 「我在這裡」藍點（白框＋藍心），取代預設大頭針 */
    fun userDot(density: Float): BitmapDescriptor = cache.getOrPut("user|$density") {
        val size = (22 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000; c.drawCircle(r, r + density, r - density, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r, r, r - density, p)
        p.color = 0xFF3B82F6.toInt(); c.drawCircle(r, r, r - 4 * density, p)
        BitmapDescriptorFactory.fromBitmap(bmp)
    }
}
