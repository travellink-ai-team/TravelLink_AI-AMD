package com.example.travellink_ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.R

/**
 * 字體地基（對齊網頁端 ai-travel-explore-final.css）。
 *
 * 網頁端字體策略（實測 DOM/CSS）：
 *   ・內文／卡片標題／中文大標 → `Noto Sans TC` 黑體
 *     （Android 系統 CJK 本來就是 Noto Sans CJK ≈ Noto Sans TC，故中文沿用系統預設，不另塞字）
 *   ・Logo 字標「TravelLinkAI」＋大型統計數字 → `DM Serif Display`（襯線，僅含拉丁字符）
 *
 * 中文大標決策（2026-08-15）：採黑體。網頁中文大標雖在真機上會 fallback 成系統宋體，
 * 但屬 fallback 而非明確指定，且網頁卡片標題本身就選黑體；黑體亦符合現代 App 慣例。
 * 故本檔只為「拉丁字顯示文字」提供 DM Serif，其餘一律黑體（系統預設 sans）。
 */

/** DM Serif Display：只用於拉丁字顯示文字（Logo 字標、大型數字）。中文字符不含，會 fallback 到系統字。 */
val DmSerif = FontFamily(Font(R.font.dm_serif_display_regular, FontWeight.Normal))

/**
 * Logo 字標樣式（對齊網頁 .topbar-logo：DM Serif 26px，weight 400，字距 -0.5）。
 * 用於頂欄「TravelLinkAI」等拉丁字標。
 */
val WordmarkStyle = TextStyle(
    fontFamily = DmSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 24.sp,
    letterSpacing = (-0.5).sp
)

/** 大型統計數字樣式（對齊網頁 .hstat-num / .pmstat-val：DM Serif 襯線數字）。 */
val SerifNumberStyle = TextStyle(
    fontFamily = DmSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 34.sp,
    lineHeight = 36.sp
)

/**
 * 共用字階（sp 值對齊網頁 px；網頁 body 18px＝本檔 bodyLarge 18sp）。
 * 各畫面逐步改為引用這裡的 style，取代散落的寫死 fontSize（見 F1 rollout）。
 */
val Typography = Typography(
    // Hero／頁面大標（中文，黑體）— 網頁 hero clamp(26–41)
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp
    ),
    // 彈窗大標 — 網頁 .lm-title/.pm-title 29–31
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 29.sp, lineHeight = 36.sp
    ),
    // 區塊標題 — 網頁 .news-title 24 bold
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp
    ),
    // 卡片標題 — 網頁 .tc-title 20
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    // 基準內文 — 網頁 body 18px / line-height 1.7
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 28.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    // 按鈕／導覽 — 網頁 17
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 22.sp
    ),
    // meta — 網頁 14
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 18.sp
    ),
    // caption — 網頁 13
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    )
)
