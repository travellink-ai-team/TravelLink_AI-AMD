package com.example.travellink_ai.data.ai

import com.google.firebase.functions.FirebaseFunctionsException

/**
 * 每日免費額度（後端 functions/main.py 的 usage_daily，依帳號、台灣日期計次，網頁與 App 共用）。
 * 種類：trip 行程生成、image 插圖（一張算一次）、chat 隨行管家、swap 換景點建議、text 文字呼叫總數。
 */
data class AiQuota(
    val exempt: Boolean,
    val limits: Map<String, Int>,
    val used: Map<String, Int>
) {
    /** 今天還剩幾次；豁免帳號、或後端沒有這個種類時回 null（不限） */
    fun left(kind: String): Int? {
        if (exempt) return null
        val limit = limits[kind] ?: return null
        return (limit - (used[kind] ?: 0)).coerceAtLeast(0)
    }

    companion object {
        const val TRIP = "trip"
        const val IMAGE = "image"
        const val CHAT = "chat"
        const val SWAP = "swap"

        /** get_ai_quota 的回應 → AiQuota；格式不對回 null */
        fun parse(data: Any?): AiQuota? {
            val m = data as? Map<*, *> ?: return null
            fun ints(v: Any?): Map<String, Int> = (v as? Map<*, *>).orEmpty()
                .mapNotNull { (k, n) -> (k as? String)?.let { key -> (n as? Number)?.let { key to it.toInt() } } }
                .toMap()
            val limits = ints(m["limits"]).takeIf { it.isNotEmpty() } ?: return null
            return AiQuota(exempt = m["exempt"] == true, limits = limits, used = ints(m["used"]))
        }

        /** 錯誤是「今天的免費額度用完」時回後端給的那句話（例「今天的免費插圖（3 張）用完了，明天再畫」），否則 null */
        fun exceededMessage(e: Throwable): String? =
            generateSequence(e) { it.cause }.take(10)
                .filterIsInstance<FirebaseFunctionsException>()
                .firstOrNull { it.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED }
                ?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: "今天的免費次數用完了，明天再試" }
    }
}
