package com.example.travellink_ai.data.assistant

/**
 * AI 隨行管家資料模型。
 * 聊天 → AI 回結構化 JSON → 卡片式推薦，或「修改提案卡」（使用者按套用才改行程）。
 */
data class AssistantMessage(
    val role: String,                                  // "user" | "assistant"
    val text: String,
    val recommendations: List<AssistantRec> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    /** AI 提議的行程修改；不為空時訊息下方顯示修改卡，使用者按「套用」才執行 */
    val actions: List<AssistantAction> = emptyList(),
    val actionState: ActionState = ActionState.NONE,
    /** 訊息身分（修改卡的套用／取消靠它找到是哪一則） */
    val id: Long = nextId()
) {
    enum class ActionState { NONE, PENDING, APPLIED, DISMISSED }

    companion object {
        private var seq = 0L
        @Synchronized private fun nextId(): Long = ++seq
    }
}

/** 卡片式推薦：點「加入行程」= addSpecificStop(name)。 */
data class AssistantRec(
    val name: String,
    val reason: String = ""
)

/** AI 明確要求的行程動作。 */
data class AssistantAction(
    val type: String,        // "add_stop" | "remove_stop" | "replace_stop"
    val name: String = "",   // 新景點名（add / replace 用）
    val target: String = ""  // 要刪或被換掉的現有景點名（remove / replace 用）
)

/**
 * 「已為您刪除」「已把 A 換成 B」→「建議刪除」「建議把 A 換成 B」。
 * 有修改卡時使用者還沒按套用、行程其實沒改；gpt-oss 常寫成已完成，照搬會和實際狀態矛盾。
 */
fun suggestionTone(reply: String): String = reply
    .replace(Regex("(已經|已)(幫|為|替)(您|你)?"), "建議")
    .replace(Regex("(已經|已)(將|把)"), "建議把")
    .replace(Regex("(已經|已)(新增|加入|加上|刪除|移除|刪掉|拿掉|替換|更換|換成|換掉|調整|改成|改為)"), "建議$2")
