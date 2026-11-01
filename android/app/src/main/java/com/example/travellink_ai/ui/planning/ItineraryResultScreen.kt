package com.example.travellink_ai.ui.planning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.travellink_ai.ui.collab.CollabViewModel
import com.example.travellink_ai.ui.map.ItineraryMapScreen
import com.example.travellink_ai.ui.theme.DesignTokens

// ── 設計語言 ────────────────────────────────────────────────────
private val SAccent      = DesignTokens.Accent
private val SIndigo      = Color(0xFF6366F1)

/**
 * 行程路線地圖（行程頁「查看路線」）。
 *
 * 2026-10-19 起只放地圖：行程插圖改到行程頁上的插圖卡（點開全螢幕，下載、分享、重新繪製都在那裡），
 * 這裡不再切換插圖，免得兩個入口、兩套畫面。
 */
@Composable
fun ItineraryResultScreen(
    viewModel: ItineraryViewModel,
    docId: String
) {
    val collabVm: CollabViewModel = hiltViewModel()

    LaunchedEffect(docId) {
        // "current" 表示剛生成的行程，需用真實 docId 啟動 Firestore listener
        // 讓 owner 也能即時收到 joiner 的編輯更新
        val realDocId = if (docId == "current")
            viewModel.currentFirestoreDocIdPublic ?: docId
        else docId
        collabVm.loadItinerary(realDocId)
    }

    var showShareSheet by remember { mutableStateOf(false) }

    val itinerary      by viewModel.itinerary.collectAsState()
    val visualState    by viewModel.visualState.collectAsState()
    val onlineMembers  by collabVm.onlineMembers.collectAsState()
    val joinPin        by collabVm.joinPin.collectAsState()
    val stops = itinerary?.stops ?: emptyList()

    val snackbarHostState = remember { SnackbarHostState() }

    // 系統返回鍵：在此頁攔截，否則會直接退出 App（而非回上一頁）。
    BackHandler {
        if (showShareSheet) showShareSheet = false else viewModel.goBackToPreview()
    }

    // ── 歷史行程資料新鮮度提醒（超過半年才出現一次）─────────────────
    val staleDataNotice by viewModel.staleDataNotice.collectAsState()
    LaunchedEffect(staleDataNotice) {
        val notice = staleDataNotice
        if (notice != null) {
            snackbarHostState.showSnackbar(
                message  = "⏳ $notice",
                duration = SnackbarDuration.Long
            )
            viewModel.dismissStaleDataNotice()
        }
    }

    // ── 邀請共編 Dialog（共用元件）──────────────────────────────
    if (showShareSheet) {
        CollabInviteDialog(
            shareLink    = collabVm.getShareLink(),
            joinPin      = joinPin,
            onlineMembers = onlineMembers,
            onDismiss    = { showShareSheet = false }
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ItineraryMapScreen(viewModel = viewModel, stops = stops)

            // ── 載入中 spinner ───────────────────────────────────
            if (visualState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = SIndigo
                )
            }

            // ── 右上：按鈕群組（邀請共編 + 編輯行程）──────────────
            Row(
                modifier = Modifier
                    .padding(top = 12.dp, end = 16.dp)
                    .statusBarsPadding()
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 邀請共編圖示按鈕
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { showShareSheet = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color.White.copy(alpha = 0.88f),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GroupAdd,
                            contentDescription = "邀請共編",
                            modifier = Modifier.size(18.dp),
                            tint = SIndigo
                        )
                    }
                }

                // 編輯行程按鈕
                Surface(
                    modifier = Modifier.clickable { viewModel.goBackToPreview() },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.88f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "編輯行程",
                            modifier = Modifier.size(13.dp),
                            tint = SAccent
                        )
                        Text(
                            "編輯行程",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SAccent
                        )
                    }
                }
            }
        }
    }
}
