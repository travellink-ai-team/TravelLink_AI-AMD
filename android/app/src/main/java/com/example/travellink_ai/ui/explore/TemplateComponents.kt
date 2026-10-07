package com.example.travellink_ai.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.data.model.ExploreTemplate
import com.example.travellink_ai.data.model.TemplateStop
import com.example.travellink_ai.ui.theme.DesignTokens
import kotlin.math.cos
import kotlin.math.roundToInt

// 封面配色：與網頁 explore-templates.js THEME 相同
private data class CoverTheme(val from: Color, val to: Color, val line: Color)
private val THEMES = mapOf(
    "海線" to CoverTheme(Color(0xFF2A6B5E), Color(0xFF4F9C86), Color(0xFFFFD98A)),
    "市區" to CoverTheme(Color(0xFFB8531F), Color(0xFFE8733A), Color(0xFFFFE9C9)),
    "縱谷" to CoverTheme(Color(0xFF4A6B1F), Color(0xFF7A9C3A), Color(0xFFF2FFC9)),
    "南迴" to CoverTheme(Color(0xFF8A6D10), Color(0xFFC9A227), Color(0xFFFFF6D6)),
    "離島" to CoverTheme(Color(0xFF1F5448), Color(0xFF3A8F9C), Color(0xFFFFE27A))
)
private fun themeOf(group: String) = THEMES[group] ?: THEMES.getValue("海線")

/** 網頁 fmtDuration：「3小時20分」 */
internal fun fmtTemplateDuration(min: Int): String {
    val h = min / 60; val m = min % 60
    return when { h > 0 && m > 0 -> "${h}小時${m}分"; h > 0 -> "${h}小時"; else -> "${m}分" }
}

/** 封面路線縮圖（對齊網頁 routeSvg：經度乘 cos(緯度) 投影，本島頭尾接台東車站） */
@Composable
private fun RouteThumb(tpl: ExploreTemplate, gate: TemplateStop, line: Color, modifier: Modifier) {
    val nodes = if (tpl.island) tpl.stops else listOf(gate) + tpl.stops + listOf(gate)
    Canvas(modifier) {
        if (nodes.size < 2) return@Canvas
        val rad = Math.toRadians(nodes.map { it.lat }.average())
        val pts = nodes.map { Offset((it.lng * cos(rad)).toFloat(), (-it.lat).toFloat()) }
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val pad = 18.dp.toPx()
        val scale = minOf(
            (size.width - pad * 2) / (maxX - minX).coerceAtLeast(1e-4f),
            (size.height - pad * 2) / (maxY - minY).coerceAtLeast(1e-4f)
        )
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val screen = pts.map { Offset(size.width / 2 + (it.x - cx) * scale, size.height / 2 + (it.y - cy) * scale) }
        val path = Path().apply { moveTo(screen[0].x, screen[0].y); screen.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        screen.forEachIndexed { i, p ->
            val isGate = !tpl.island && (i == 0 || i == screen.lastIndex)
            drawCircle(Color.White, radius = if (isGate) 4.dp.toPx() else 5.dp.toPx(), center = p)
            if (!isGate) drawCircle(line, radius = 2.5.dp.toPx(), center = p)
        }
    }
}

/** 範本卡（對齊網頁 .tpl-card：封面＋官方精選＋站數公里、標題、可展開站點、停留／評分／預覽） */
@Composable
internal fun TemplateCard(
    tpl: ExploreTemplate,
    gate: TemplateStop,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false   // 首頁橫向列：不顯示站點清單與說明
) {
    val theme = themeOf(tpl.group)
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.clickable(onClick = onPreview),
        shape = RoundedCornerShape(DesignTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(if (compact) 110.dp else 150.dp)
                    .background(Brush.linearGradient(listOf(theme.from, theme.to)))
            ) {
                RouteThumb(tpl, gate, theme.line, Modifier.fillMaxSize())
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CoverChip("✦ 官方精選", Color(0xFF1A1814), Color.White)
                    CoverChip("${tpl.stops.size} 站 · ${tpl.km.roundToInt()} km", Color.White, DesignTokens.Ink)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    Text("${tpl.emoji} ${tpl.key}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (tpl.island) "島上環線" else "${gate.name} → ${tpl.stops.last().name} → 返回",
                        color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tpl.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) {
                        Text(if (expanded) "▴" else "▾", fontSize = 16.sp, color = DesignTokens.Ink2,
                            modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 6.dp))
                    }
                }
                if (!compact && expanded) TemplateStopList(tpl)
                if (!compact) {
                    Text(mixText(tpl, gate), fontSize = 13.sp, color = DesignTokens.Ink2)
                    if (tpl.farForOneDay) {
                        Text("⚠️ 來回 ${tpl.km.roundToInt()} km，當天往返太趕，建議排兩日。",
                            fontSize = 13.sp, color = DesignTokens.Accent2Dark)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⏱ 停留 ${fmtTemplateDuration(tpl.stayMin)}", fontSize = 13.sp, color = DesignTokens.Ink2)
                    tpl.rating?.let {
                        Text("★ ${"%.1f".format(it)} ${tpl.ratedCount}/${tpl.stops.size}", fontSize = 13.sp, color = DesignTokens.Gold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("預覽", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Accent,
                        modifier = Modifier.clickable(onClick = onPreview))
                }
            }
        }
    }
}

@Composable
private fun CoverChip(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

private fun mixText(tpl: ExploreTemplate, gate: TemplateStop) =
    if (tpl.island) "島上 ${tpl.mainCount} 站環線，沒有陸路往返，不需要沿途補點。"
    else "${tpl.mainCount} 站在${tpl.key}，${tpl.viaCount} 站是${gate.name}往返途中順路加入的。"

/** 站點清單：主軸／〇〇・沿途（對齊網頁 .tpl-stop） */
@Composable
private fun TemplateStopList(tpl: ExploreTemplate) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tpl.stops.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (s.via) DesignTokens.Border else DesignTokens.Accent))
                Spacer(Modifier.width(10.dp))
                Text(s.name, fontSize = 14.sp, color = DesignTokens.Ink, modifier = Modifier.weight(1f))
                Text(
                    if (s.via) "${s.fromDistrict}・沿途" else "主軸",
                    fontSize = 12.sp,
                    color = if (s.via) DesignTokens.Ink3 else DesignTokens.AccentDark,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (s.via) DesignTokens.Surface2 else DesignTokens.AccentLight)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** 預覽 sheet（對齊網頁 previewModal：官方精選範本＋站點＋「✨ 用這份開始規劃」） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplatePreviewSheet(
    tpl: ExploreTemplate,
    gate: TemplateStop,
    onUse: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(tpl.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink)
            Text("✦ 官方精選範本・${tpl.emoji} ${tpl.key}・${tpl.stops.size} 站・${tpl.km.roundToInt()} km・停留 ${fmtTemplateDuration(tpl.stayMin)}",
                fontSize = 13.sp, color = DesignTokens.Ink2)
            TemplateStopList(tpl)
            tpl.stops.firstOrNull { it.desc.isNotBlank() && !it.via }?.let {
                Text(it.desc, fontSize = 13.sp, color = DesignTokens.Ink2, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(mixText(tpl, gate), fontSize = 13.sp, color = DesignTokens.Ink2)
            Button(
                onClick = onUse,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
            ) { Text("✨ 用這份開始規劃", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Text("會帶入目的地、行程名稱與主軸景點，選好製作方式後由 AI 依你的節奏重新規劃。",
                fontSize = 12.sp, color = DesignTokens.Ink2)
        }
    }
}

/** 地區大分類（海線／市區…）的代表色，探索頁地區分頁用來區分分組 */
internal fun groupColor(group: String): Color = themeOf(group).from
