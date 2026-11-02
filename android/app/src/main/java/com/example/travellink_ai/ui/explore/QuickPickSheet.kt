package com.example.travellink_ai.ui.explore

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.travellink_ai.ui.map.launchNavigation
import com.example.travellink_ai.ui.theme.DesignTokens
import com.google.android.gms.maps.model.LatLng

private val Accent      = DesignTokens.Accent
private val AccentDark  = DesignTokens.AccentDark
private val AccentLight = DesignTokens.AccentLight
private val Ink         = DesignTokens.Ink
private val Ink2        = DesignTokens.Ink2
private val Surface2    = DesignTokens.Surface2

/** 首頁 Hero 標題右邊的入口（綠色漸層底上，用半透明白框） */
@Composable
fun QuickPickHeroButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🎲", fontSize = 26.sp)
        Text("現在去哪？", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** 「現在去哪？」底部面板：一張卡、換一個、導航 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPickSheet(onDismiss: () -> Unit, vm: QuickPickViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> vm.open(result.values.any { it }) }
    LaunchedEffect(Unit) {
        val granted = locationPermissions.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        // 拒絕過的話系統會直接回 false，退回台東市區
        if (granted) vm.open(true) else permissionLauncher.launch(locationPermissions)
    }

    ModalBottomSheet(
        onDismissRequest = { vm.close(); onDismiss() },
        sheetState = sheetState,
        containerColor = DesignTokens.Surface
    ) {
        // 面板固定拉滿：卡片長短不一（有沒有照片、簡介）時，上面的篩選鈕和下面的按鈕都不會跟著跑
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("🎲 現在去哪？", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            if (state.originNote.isNotBlank()) {
                Text(state.originNote, fontSize = 14.sp, color = Ink2)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickPick.Category.entries.forEach { cat ->
                    FilterChip(
                        selected = state.category == cat,
                        onClick = { vm.setCategory(cat) },
                        label = { Text(cat.label, fontSize = 15.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentLight,
                            selectedLabelColor = AccentDark
                        )
                    )
                }
            }
            state.weather?.let { w ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${w.emoji} ${w.townshipName} 接下來幾小時：${w.description}，${w.minTemp}–${w.maxTemp}°C" +
                        if (w.rainProbability >= 0) "，降雨機率 ${w.rainProbability}%" else "",
                    fontSize = 13.sp, color = Ink2
                )
            }
            Spacer(Modifier.height(14.dp))

            val current = state.current
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    state.loading -> Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(Modifier.height(10.dp))
                            Text("正在找附近現在有開的地方…", fontSize = 14.sp, color = Ink2)
                        }
                    }
                    current != null -> {
                        PickCard(current, state.hint)
                        val notes = listOfNotNull(
                            if (state.radiusKm > QuickPick.RADII_KM.first())
                                "${QuickPick.RADII_KM.first().toInt()} 公里內現在有開的不多，已放寬到 ${state.radiusKm.toInt()} 公里"
                            else null,
                            if (state.wrapped) "附近的都看過了，從頭再抽一輪" else null
                        )
                        notes.forEach {
                            Spacer(Modifier.height(6.dp))
                            Text("ⓘ $it", fontSize = 13.sp, color = Ink2)
                        }
                    }
                    else -> EmptyState(state)
                }
            }
            if (!state.loading && current != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.next() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("🎲 換一個", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Button(
                        onClick = {
                            launchNavigation(context, current.place.name, LatLng(current.place.lat, current.place.lng))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("導航", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickCard(c: QuickPick.Candidate, hint: String?) {
    val meal = c.place.typeName != "景點"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface2)
    ) {
        if (c.place.photoUrl.isNotBlank()) {
            AsyncImage(
                model = c.place.photoUrl,
                contentDescription = c.place.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(170.dp)
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(96.dp).background(AccentLight),
                contentAlignment = Alignment.Center
            ) { Text(if (meal) "🍽️" else "📍", fontSize = 40.sp) }
        }
        Column(Modifier.padding(16.dp)) {
            Text(
                c.place.name, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(c.place.typeName)
                if (c.setting != QuickPick.Setting.UNKNOWN && !meal) Tag(c.setting.label)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    "📍 ${QuickPick.distanceLabel(c.km)}",
                    "🕒 ${QuickPick.hoursLabel(c)}",
                    if (c.place.rating > 0) "⭐ ${"%.1f".format(c.place.rating)}" else null
                ).joinToString("　"),
                fontSize = 14.sp, color = Ink2
            )
            hint?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 14.sp, color = AccentDark, fontWeight = FontWeight.SemiBold)
            }
            if (c.place.desc.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    c.place.desc.trim(), fontSize = 14.sp, color = Ink2, lineHeight = 20.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AccentLight)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = 12.sp, color = AccentDark, fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun EmptyState(state: QuickPickViewModel.UiState) {
    val food = state.category == QuickPick.Category.FOOD
    val max = QuickPick.RADII_KM.last().toInt()
    val (title, sub) = when (state.empty) {
        QuickPick.Empty.NO_DATA ->
            (if (food) "這一帶還沒有餐廳資料" else "這一帶還沒有景點資料") to
                "$max 公里內查不到資料，可以換個篩選看看"
        else -> "附近現在都沒開" to "$max 公里內的地方現在都不在營業時間，晚點再試試"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface2).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🗺️", fontSize = 36.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(sub, fontSize = 14.sp, color = Ink2)
    }
}
