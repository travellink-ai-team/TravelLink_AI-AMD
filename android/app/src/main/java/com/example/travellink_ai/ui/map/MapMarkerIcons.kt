package com.example.travellink_ai.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * 地圖上的小圓點標記（行程進行中的「下一站模式」用）：比預設大頭針小、帶編號，下一站大而醒目、
 * 已完成的淡灰。圖示依（編號、樣式、螢幕密度）快取，地圖重畫時不必每次重建 Bitmap。
 * 只能在 Google 地圖初始化之後呼叫（BitmapDescriptorFactory 的限制），也就是 GoogleMap 內容裡。
 */
internal object MapMarkerIcons {
    enum class Style { NEXT, UPCOMING, DONE }

    private val cache = HashMap<String, BitmapDescriptor>()

    fun stop(label: String, style: Style, density: Float): BitmapDescriptor =
        cache.getOrPut("$label|$style|$density") {
            val sizeDp = when (style) { Style.NEXT -> 40f; Style.UPCOMING -> 28f; Style.DONE -> 24f }
            val size = (sizeDp * density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val (fill, ring, text) = when (style) {
                Style.NEXT -> Triple(0xFFF07B3F.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
                Style.UPCOMING -> Triple(0xFFFFFFFF.toInt(), 0xFF2A6B5E.toInt(), 0xFF2A6B5E.toInt())
                Style.DONE -> Triple(0xFFD6D1C8.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A857C.toInt())
            }
            p.color = 0x33000000; c.drawCircle(r, r + density, r - 2 * density, p)
            p.color = ring; c.drawCircle(r, r, r - 2 * density, p)
            p.color = fill; c.drawCircle(r, r, r - (if (style == Style.UPCOMING) 4.5f else 4f) * density, p)
            p.color = text
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = size * (if (label.length > 2) 0.32f else 0.42f)
            c.drawText(label, r, r - (p.ascent() + p.descent()) / 2, p)
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
