package com.example.travellink_ai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 兩端共用設計 token（單一來源）。
 * 數值對應網頁端 ai-travel-explore-final.css 的 :root CSS 變數，
 * 網頁端調整色票時只需同步此檔。
 *
 * 各畫面目前仍有同名的 private val 複本（值已同步），新寫的畫面請直接引用本檔，
 * 舊畫面可於重構時逐步改為引用。
 */
object DesignTokens {
    // 基底
    val Bg           = Color(0xFFF7F5F0)   // --bg
    val Surface      = Color(0xFFFFFFFF)   // --surface
    val Surface2     = Color(0xFFF0EDE6)   // --surface2
    val Border       = Color(0xFFE2DDD6)   // --border

    // 墨色（文字階層）
    val Ink          = Color(0xFF1A1814)   // --ink
    val Ink2         = Color(0xFF5A5750)   // --ink2
    val Ink3         = Color(0xFF767068)   // --ink3

    // 主色（墨綠）
    val Accent       = Color(0xFF2A6B5E)   // --accent
    val AccentDark   = Color(0xFF1F5448)   // --accent-dark
    val AccentLight  = Color(0xFFE8F3F0)   // --accent-light

    // 次要強調（橘，網頁用於美食標籤/次要提示）
    val Accent2      = Color(0xFFE8733A)   // --accent2
    val Accent2Light = Color(0xFFFDF0E8)   // --accent2-light
    val Accent2Dark  = Color(0xFFC45C28)   // --accent2-dark

    // 金（收藏/熱門/即將出發狀態）
    val Gold         = Color(0xFFC9A227)   // --gold
    val GoldLight    = Color(0xFFF5E8C0)   // --gold-light

    // 紅（危險操作/喜歡）
    val Red          = Color(0xFFD94040)   // --red
    val RedLight     = Color(0xFFFDEAEA)   // --red-light

    // 行程狀態徽章配色（對應網頁 .mt-status）
    val StatusOngoingBg   = Color(0xFFE6FFFA)
    val StatusOngoingText = Color(0xFF0D9488)
    val StatusDoneBg      = Color(0xFFF3F4F6)
    val StatusDoneText    = Color(0xFF4B5563)

    /**
     * 圓角比例（F3，對齊網頁）。取代各畫面散落的 RoundedCornerShape 硬值。
     * 網頁：卡片 --radius 14、按鈕 8–10、藥丸/晶片 20 或 999、彈窗/大面板 20、輸入欄位 ~12。
     */
    object Radius {
        val Chip   = 8.dp     // 小標籤/晶片
        val Button = 10.dp    // 按鈕
        val Field  = 12.dp    // 輸入欄位
        val Card   = 14.dp    // 內容卡片（網頁 --radius）
        val Modal  = 20.dp    // 彈窗 / bottom sheet / 大面板
        val Pill   = 999.dp   // 全圓藥丸（tag/切換）
    }
}
