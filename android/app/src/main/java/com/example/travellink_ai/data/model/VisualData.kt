package com.example.travellink_ai.data.model

import android.graphics.Bitmap

data class VisualData(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val isFailed: Boolean = false,
    /** 生成行程時的分階段進度文字，isLoading=true 時顯示 */
    val loadingPhase: String = "AI 正在規劃行程…"
)
