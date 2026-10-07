package com.example.travellink_ai.ui.trip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 🧪 Debug-only demo 工具（開著 demo 模式才出現，正式版沒有）：
 *  - demo 時鐘：快轉行程時間（見 DemoClock），落後／超前、預計離開、離開提醒都跟著走
 *  - 假 GPS：重跑到目前站、離開本站（觸發「忘記打卡了嗎？」）
 *  - 立即發通知：每天 08:00 才檢查的「行程即將開始」
 */
class DemoToolsUi(
    val offsetMs: Long,             // demo 時鐘快轉量（顯示用；時間由卡片自己每秒更新）
    val canJumpToLeave: Boolean,    // 目前站已打卡、有預計離開時間
    val canJumpToTripStart: Boolean,// 有今天的行程開始時間
    val onJumpToTripStart: () -> Unit,
    val onAdvance: (Int) -> Unit,
    val onJumpToLeave: () -> Unit,
    val onResetClock: () -> Unit,
    val onReplayGps: () -> Unit,
    val onLeaveStop: () -> Unit,
    val onNotifyUpcoming: () -> Unit
)

@Composable
internal fun DemoToolsCard(demo: DemoToolsUi) {
    // 卡片自己每秒刷新時鐘，不讓整個行程頁跟著重組
    var nowMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L) }
    }
    val demoNow = nowMs + demo.offsetMs
    val clockText = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(demoNow))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF4E5),
        border = BorderStroke(1.dp, Color(0xFFFFB74D))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧪 Demo 工具", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF8A4B00))
                Spacer(Modifier.weight(1f))
                Text(
                    "時鐘 $clockText" + if (demo.offsetMs != 0L) "（demo）" else "（手機時間）",
                    fontSize = 13.sp, color = Color(0xFF8A4B00), fontWeight = FontWeight.SemiBold
                )
            }
            DemoRow("時間") {
                DemoButton("行程開始", Modifier.weight(1.2f), enabled = demo.canJumpToTripStart) { demo.onJumpToTripStart() }
                DemoButton("+15 分", Modifier.weight(1f)) { demo.onAdvance(15) }
                DemoButton("+60 分", Modifier.weight(1f)) { demo.onAdvance(60) }
            }
            DemoRow("") {
                DemoButton("到預計離開", Modifier.weight(1f), enabled = demo.canJumpToLeave) { demo.onJumpToLeave() }
                DemoButton("回到手機時間", Modifier.weight(1f), enabled = demo.offsetMs != 0L) { demo.onResetClock() }
            }
            DemoRow("假 GPS") {
                DemoButton("重跑到目前站", Modifier.weight(1f)) { demo.onReplayGps() }
                DemoButton("離開本站", Modifier.weight(1f)) { demo.onLeaveStop() }
            }
            DemoRow("通知") {
                DemoButton("發「行程即將開始」", Modifier.weight(1f)) { demo.onNotifyUpcoming() }
            }
        }
    }
}

@Composable
private fun DemoRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF8A4B00), modifier = Modifier.width(48.dp))
        content()
    }
}

@Composable
private fun DemoButton(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        border = BorderStroke(1.dp, Color(0xFFFFB74D)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8A4B00))
    ) { Text(text, fontSize = 12.sp, maxLines = 1) }
}

/** epochMs 那天 00:00 的毫秒（預計離開＝打卡那天 00:00＋leaveMin，與 TripProgressViewModel 同算法） */
internal fun startOfDayMs(epochMs: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = epochMs
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
