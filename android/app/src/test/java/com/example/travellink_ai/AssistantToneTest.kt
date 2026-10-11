package com.example.travellink_ai

import com.example.travellink_ai.data.assistant.suggestionTone
import org.junit.Assert.assertEquals
import org.junit.Test

/** 隨行管家修改卡：AI 說「已改好」時改寫成「建議…」（使用者按套用才會改） */
class AssistantToneTest {
    @Test
    fun rewritesCompletedPhrasingToSuggestion() {
        assertEquals("建議刪除加路蘭，這樣比較輕鬆。", suggestionTone("已為您刪除加路蘭，這樣比較輕鬆。"))
        assertEquals("建議把森林公園換成臺東美術館。", suggestionTone("已經把森林公園換成臺東美術館。"))
        assertEquals("建議加入鐵花村！", suggestionTone("已加入鐵花村！"))
        assertEquals("建議新增一站咖啡廳。", suggestionTone("已幫你新增一站咖啡廳。"))
        assertEquals("這幾間咖啡廳很不錯：", suggestionTone("這幾間咖啡廳很不錯："))
    }
}
