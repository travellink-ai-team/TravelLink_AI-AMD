package com.example.travellink_ai.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.ui.theme.DesignTokens

/**
 * 「附近替代景點」清單：編輯景點頁與行程進行中的景點資訊頁共用。
 * 按「換成這個」先確認，確認後才呼叫 [onReplace]（不能還原，所以不做一點就換）。
 *
 * @param onReplace null＝只列出、不能換（檢視者、已打卡的站）
 */
@Composable
fun NearbyAlternativesList(
    targetName: String,
    candidates: List<LocalAlternatives.Candidate>,
    onReplace: ((PlaceCost) -> Unit)?
) {
    var pending by remember(candidates) { mutableStateOf<PlaceCost?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        candidates.forEach { c ->
            val p = c.place
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.Ink)
                    val price = when {
                        p.fee == 0 -> "免費"
                        p.fee != null -> "門票 \$${p.fee}"
                        p.costPerPerson != null -> "約 \$${p.costPerPerson}/人"
                        else -> null
                    }
                    Text(
                        listOfNotNull("${p.typeName} · ${"%.1f".format(c.distanceKm)} 公里", price)
                            .joinToString(" · "),
                        fontSize = 12.sp, color = DesignTokens.Ink2
                    )
                }
                if (onReplace != null) {
                    TextButton(onClick = { pending = p }) { Text("換成這個", fontSize = 13.sp) }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("替換景點") },
            text = { Text("把「$targetName」換成「${p.name}」？\n換完會重新計算路線與後續時間。") },
            confirmButton = {
                TextButton(onClick = { pending = null; onReplace?.invoke(p) }) { Text("替換") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}
