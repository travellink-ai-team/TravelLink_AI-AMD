package com.example.travellink_ai.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun AiItineraryScreen(viewModel: ItineraryViewModel, docId: String) {
    val backgroundUrl by viewModel.backgroundUrl.collectAsState()
    val visualState by viewModel.visualState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val hasImage = visualState.bitmap != null || !backgroundUrl.isNullOrEmpty()

        when {
            hasImage -> {
                AsyncImage(
                    model = visualState.bitmap ?: backgroundUrl,
                    contentDescription = "AI 旅遊插圖",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            visualState.isFailed -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎨", fontSize = 42.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("插圖生成失敗", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("模型暫時無法使用", color = Color.Gray, fontSize = 15.sp)
                }
            }
            else -> {
                // 生成中由 switch.kt 的 CircularProgressIndicator 顯示
            }
        }
    }
}
