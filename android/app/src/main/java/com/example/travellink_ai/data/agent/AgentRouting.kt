package com.example.travellink_ai.data.agent

/**
 * 隨行管家的分流（AMD 模式）：要改行程的話交給旅程應變 Agent，其他照舊聊天。
 * 規則逐字照搬 AMD 版網站（ai-travel-planner-v8.js 的 isItineraryChangeIntent／isClearlyOffTopic），
 * 兩端對同一句話的判斷才會一樣。只用關鍵字、不多呼叫一次 AI；猜錯時使用者可以點「幫我調整行程」。
 */
object AgentRouting {

    private val INTENT = Regex(
        "好累|很累|太累|累了|走不動|想休息|休息一下|早點回|提早回|提前回|早點結束|提早結束|回飯店|回旅館|回民宿|" +
            "下雨|雨變大|大雨|延誤|遲到|晚到|來不及|趕不上|刪掉|刪除|拿掉|不想去|不去了|取消|跳過|縮短|少去|" +
            "調整行程|改行程|重排|順延|晚點出發|輕鬆一點|放慢"
    )
    /** 同時在問推薦／找地方 → 照舊聊天 */
    private val ASK = Regex("推薦|好吃|美食|吃什麼|餐廳|小吃|咖啡|哪裡|有什麼|有沒有|介紹|怎麼去|多遠|門票|營業")
    /** 但說得很明確要改行程的，就算也在問，仍交給 Agent */
    private val STRONG = Regex("刪掉|刪除|拿掉|不去了|跳過|取消|改行程|調整行程|早點回|提早回|提前回|回飯店|回旅館|回民宿")

    fun isItineraryChangeIntent(text: String): Boolean {
        if (!INTENT.containsMatchIn(text)) return false
        return !ASK.containsMatchIn(text) || STRONG.containsMatchIn(text)
    }

    private val OFF_TOPIC = listOf(
        Regex("""```|console\.log|print\(|#include|\bdef \w+\(|\bfunction\s*\w*\(|\bSELECT\b.+\bFROM\b""", RegexOption.IGNORE_CASE),
        Regex("python|javascript|typescript|c\\+\\+|leetcode|演算法|程式碼|寫程式|debug|除錯", RegexOption.IGNORE_CASE),
        // 不放「積分」「功課」：會員積分、出發前做功課都是旅遊話題
        Regex("微積分|解方程|導數|矩陣|證明題|數學題|寫作業"),
        Regex("(忽略|無視|忘記).{0,10}(指令|規則|設定|提示)|system prompt|系統提示|ignore (all|previous|the above)", RegexOption.IGNORE_CASE)
    )
    private val PURE_MATH = Regex("""^[\d\s.,+\-*/×÷^%()=?？]+$""")
    private val MATH_OP = Regex("""[+\-*/×÷^%]""")

    /** 一看就知道離題的（程式碼、純算式、要它忽略規則）：直接婉拒，不花 AI 呼叫 */
    fun isClearlyOffTopic(text: String): Boolean {
        val m = text.trim()
        if (PURE_MATH.matches(m) && MATH_OP.containsMatchIn(m)) return true
        return OFF_TOPIC.any { it.containsMatchIn(m) }
    }

    /** 後端訊息裡的「代理人」統一成「AI 隨行管家」：畫面上只有一個 AI 名字（同網頁 agentText） */
    fun displayText(text: String): String = text.replace(Regex("AI\\s*代理人|代理人"), "AI 隨行管家")
}
