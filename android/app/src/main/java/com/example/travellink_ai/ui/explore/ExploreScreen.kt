package com.example.travellink_ai.ui.explore

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.travellink_ai.ui.theme.DesignTokens
import com.example.travellink_ai.ui.planning.ItineraryViewModel

private val PAccent      = DesignTokens.Accent
private val PAccentLight = DesignTokens.AccentLight
private val PAccentDark  = DesignTokens.AccentDark
private val PIink        = DesignTokens.Ink
private val PIink2       = DesignTokens.Ink2
private val PSurface     = DesignTokens.Surface2
private val PBorder      = DesignTokens.Border

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    viewModel: ItineraryViewModel,
    initialQuery: String? = null,          // 首頁搜尋框帶過來的關鍵字
    onInitialQueryConsumed: () -> Unit = {},
    onUseTemplate: (com.example.travellink_ai.data.model.TemplateSeed) -> Unit = {},
    onMenuClick: (() -> Unit)? = null      // 底部導覽分頁：搜尋列左側開側欄
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }   // 0=官方精選範本 1=景點
    var query by remember { mutableStateOf(initialQuery.orEmpty()) }
    LaunchedEffect(initialQuery) {
        if (initialQuery != null) { query = initialQuery; onInitialQueryConsumed() }
    }


    Column(modifier = Modifier.fillMaxSize().background(PSurface)) {
        // ── 頂部：搜尋 + 分頁 ─────────────────────────────────────
        Surface(color = Color.White, shadowElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                // 全域搜尋（同時搜行程與景點）
                Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMenuClick != null) {
                    IconButton(onClick = onMenuClick, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Default.Menu, contentDescription = "選單", tint = PIink, modifier = Modifier.size(24.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(PSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = PIink2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜尋目的地、行程名稱…",
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, null, tint = PIink2,
                            modifier = Modifier.size(16.dp).clickable { query = "" }
                        )
                    }
                }
                }
                Spacer(Modifier.height(6.dp))
                // 分頁
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    contentColor = PAccent,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("精選範本", fontSize = 15.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("景點", fontSize = 15.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }

        // ── 內容 ───────────────────────────────────────────────────
        if (selectedTab == 0) {
            OfficialTemplatesContent(query = query, onUseTemplate = onUseTemplate)
        } else {
            PoiContent(viewModel = viewModel, query = query)
        }
    }
}

// ══════════════════════════════════════════════════════════════
// ✦ 官方精選範本（對齊網頁探索首頁；資料見 ExploreTemplatesRepository）
// ══════════════════════════════════════════════════════════════
@Composable
private fun OfficialTemplatesContent(
    query: String,
    onUseTemplate: (com.example.travellink_ai.data.model.TemplateSeed) -> Unit,
    vm: ExploreTemplatesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val data by vm.templates.collectAsState()
    val loading by vm.loading.collectAsState()
    var activeKey by remember { mutableStateOf<String?>(null) }   // null＝全部
    var preview by remember { mutableStateOf<com.example.travellink_ai.data.model.ExploreTemplate?>(null) }

    val d = data
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(color = PAccent)
            else Text("景點資料尚未載入", color = PIink2, fontSize = 15.sp)
        }
        return
    }
    // 篩選規則同網頁 getFiltered()：選了地區只看那區；有關鍵字搜全部；否則首頁精選 6 份
    val list = remember(d, activeKey, query) {
        val q = query.trim().lowercase()
        when {
            activeKey != null -> d.templates.filter { it.key == activeKey }
            q.isNotEmpty() -> d.templates.filter { t ->
                t.key.contains(q) || t.title.lowercase().contains(q) || t.group.contains(q) ||
                    t.stops.any { it.name.lowercase().contains(q) }
            }
            else -> d.homeTemplates
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 地區分頁：全部｜海線 長濱 20 成功 29…（對齊網頁 districtTabs）
        item { Column {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RegionChip("全部", null, activeKey == null, PIink) { activeKey = null }
                // 每個大分類一個底色區塊＋分類名，一眼分得出海線／市區／縱谷…
                d.groups.forEach { g ->
                    val keys = g.keys.filter { k -> d.templates.any { it.key == k } }
                    if (keys.isEmpty()) return@forEach
                    val c = groupColor(g.label)
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(c.copy(alpha = 0.12f))
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(g.label, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = c,
                            modifier = Modifier.padding(end = 4.dp))
                        keys.forEach { k ->
                            RegionChip(k, d.districtCounts[k], activeKey == k, c) {
                                activeKey = if (activeKey == k) null else k
                            }
                        }
                    }
                }
            }
            Text("地區後的數字＝該地區可排進行程的景點數", fontSize = 11.sp, color = PIink2,
                modifier = Modifier.padding(top = 6.dp))
        } }
        item {
            Column {
                Text("✦ 官方精選範本", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = PIink)
                Text(
                    if (activeKey == null) "挑一份直接開始，或切上面的地區看別的" else "目前只看「$activeKey」",
                    fontSize = 13.sp, color = PIink2
                )
            }
        }
        if (list.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    Text("這個地區還沒有足夠的景點資料", color = PIink2, fontSize = 15.sp)
                }
            }
        } else {
            items(list, key = { it.key }) { tpl ->
                TemplateCard(tpl = tpl, gate = d.gate, onPreview = { preview = tpl }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    preview?.let { tpl ->
        TemplatePreviewSheet(
            tpl = tpl, gate = d.gate,
            onUse = { preview = null; onUseTemplate(tpl.toSeed()) },
            onDismiss = { preview = null }
        )
    }
}

@Composable
private fun RegionChip(label: String, count: Int?, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) color else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp,
            color = if (selected) Color.White else PIink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (count != null) {
            Text(" $count", fontSize = 11.sp,
                color = if (selected) Color.White.copy(alpha = 0.8f) else PIink2)
        }
    }
}

@Composable
private fun BasicSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = PIink2)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PIink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PAccent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 景點知識庫（原探索頁內容，完整保留）
// ══════════════════════════════════════════════════════════════
@Composable
private fun PoiContent(viewModel: ItineraryViewModel, query: String) {
    val pois by viewModel.explorePOIs.collectAsState()
    val isLoading by viewModel.exploreLoading.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadExplorePOIs() }

    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectedStyle by remember { mutableStateOf<String?>(null) }

    // 顯示用網頁六種興趣（＋親子，網頁熱門標籤也有），篩選時對應回資料裡的 travelStyles 值
    val travelStyles = listOf(
        "🍜 美食" to "美食", "🏛️ 文化" to "文化探索", "🌿 自然" to "自然生態",
        "📸 打卡" to "攝影", "🏃 運動" to "冒險", "😌 放鬆" to "慢旅行", "👨‍👩‍👧 親子" to "親子"
    )

    val filtered = remember(pois, selectedTag, selectedStyle, query) {
        val q = query.trim()
        pois.filter { poi ->
            val tagOk = selectedTag == null || poi.tags.contains(selectedTag)
            val styleOk = selectedStyle == null || poi.travelStyles.contains(selectedStyle)
            val qOk = q.isEmpty() || poi.name.contains(q, true) || poi.region.contains(q, true) ||
                poi.tags.any { it.contains(q, true) } || poi.shortDesc.contains(q, true)
            tagOk && styleOk && qOk
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = Color.White, shadowElevation = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                travelStyles.forEach { (label, style) ->
                    val selected = selectedStyle == style
                    FilterChip(
                        selected = selected,
                        onClick = { selectedStyle = if (selected) null else style },
                        label = { Text(label, fontSize = 14.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PAccent, selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PAccent)
            }
            filtered.isEmpty() && pois.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未載入景點資料", color = PIink2, fontSize = 16.sp)
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("沒有符合的景點", color = PIink2, fontSize = 16.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("${filtered.size} 個景點", fontSize = 14.sp, color = PIink2,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                items(filtered, key = { it.placeId.ifBlank { it.name } }) { poi ->
                    POICard(poi = poi, onTagClick = { tag -> selectedTag = if (selectedTag == tag) null else tag })
                }
            }
        }
    }
}

@Composable
private fun POICard(
    poi: ItineraryViewModel.CustomPOI,
    onTagClick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
        POICover(poi)
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(poi.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = PIink)
                    if (poi.shortDesc.isNotBlank()) {
                        Text(poi.shortDesc, fontSize = 14.sp, color = PIink2, lineHeight = 18.sp)
                    }
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, tint = PIink2, modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (poi.region.isNotBlank()) MetaChip(Icons.Default.Place, poi.region)
                if (poi.bestTime.isNotBlank()) MetaChip(Icons.Default.AccessTime, poi.bestTime)
                if (poi.visitDurationMins > 0) {
                    val hrs = poi.visitDurationMins / 60
                    val mins = poi.visitDurationMins % 60
                    val label = when {
                        hrs > 0 && mins > 0 -> "${hrs}h${mins}m"
                        hrs > 0 -> "${hrs}h"
                        else -> "${mins}m"
                    }
                    MetaChip(null, label)
                }
            }

            if (poi.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    poi.tags.take(5).forEach { tag ->
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(PAccentLight)
                                .clickable { onTagClick(tag) }.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) { Text(tag, fontSize = 13.sp, color = PAccent) }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = PBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    if (poi.story.isNotBlank()) {
                        Text("關於這個地方", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PAccent)
                        Spacer(Modifier.height(4.dp))
                        Text(poi.story, fontSize = 14.sp, color = PIink2, lineHeight = 19.sp)
                    }
                    if (poi.culturalNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("文化連結", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PAccent)
                        Spacer(Modifier.height(4.dp))
                        Text(poi.culturalNote, fontSize = 14.sp, color = PIink2, lineHeight = 19.sp)
                    }
                    if (poi.highlights.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("亮點特色", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PAccent)
                        Spacer(Modifier.height(4.dp))
                        poi.highlights.forEach { h ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 1.dp)
                            ) {
                                Box(modifier = Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(PAccent))
                                Spacer(Modifier.width(6.dp))
                                Text(h, fontSize = 14.sp, color = PIink2)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

// 探索景點卡封面：有官方照片用 Coil，否則分類色塊 + emoji；右上角評分
@Composable
private fun POICover(poi: ItineraryViewModel.CustomPOI) {
    val (bgColor, emoji) = when {
        poi.tags.any { it.contains("餐廳") } -> 0xFFD8843C to "🍜"
        poi.tags.any { it.contains("咖啡") } -> 0xFF8B5E3C to "☕"
        else -> 0xFF2A6B5E to "🏞️"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth().height(120.dp)
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(Color(bgColor))
    ) {
        // 佔位色塊 emoji（同時作為載入中/失敗的底）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 40.sp)
        }
        if (poi.coverImageUrl.isNotBlank()) {
            AsyncImage(
                model = poi.coverImageUrl,
                contentDescription = poi.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (poi.rating > 0.0) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xCC1A1A2E)).padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("⭐ ${"%.1f".format(poi.rating)}", fontSize = 12.sp, color = Color.White,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = PIink2)
            Spacer(Modifier.width(2.dp))
        }
        Text(label, fontSize = 13.sp, color = PIink2)
    }
}
