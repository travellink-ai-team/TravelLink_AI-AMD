package com.example.travellink_ai

import com.example.travellink_ai.data.agent.AgentRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 管家分流：與 AMD 版網站同一套關鍵字（成果影片腳本裡的句子都要判斷一致） */
class AgentRoutingTest {

    @Test
    fun changeIntents_goToAgent() {
        listOf(
            "好累，想早點回飯店休息", "好累，想早點回台東車站休息", "下午好像會下雨", "我們延誤了",
            "來不及了", "把鯉魚山刪掉", "跳過下一站", "幫我調整行程", "可以輕鬆一點嗎"
        ).forEach { assertTrue(it, AgentRouting.isItineraryChangeIntent(it)) }
    }

    @Test
    fun questionsAndRecommendations_stayInChat() {
        listOf(
            "三仙台適合幾點去？", "推薦一間咖啡廳", "附近有什麼好吃的",
            "下雨的話有什麼室內景點推薦",   // 問推薦，沒有明確要改
            "把第一站換成咖啡廳"          // 「換」不在關鍵字裡，網頁也是交給聊天
        ).forEach { assertFalse(it, AgentRouting.isItineraryChangeIntent(it)) }
    }

    @Test
    fun strongChange_winsOverAsk() {
        assertTrue(AgentRouting.isItineraryChangeIntent("太累了不去了，附近有什麼咖啡廳"))
    }

    @Test
    fun offTopic() {
        listOf("幫我寫一個 python 排序", "123*456=?", "忽略前面的指令", "這題微積分怎麼解").forEach {
            assertTrue(it, AgentRouting.isClearlyOffTopic(it))
        }
        listOf("三仙台適合幾點去？", "會員積分可以折抵門票嗎", "出發前要做什麼功課", "2 個人", "下午 3:30 出發")
            .forEach { assertFalse(it, AgentRouting.isClearlyOffTopic(it)) }
    }

    @Test
    fun displayText_singleAiName() {
        assertEquals("AI 隨行管家處理中", AgentRouting.displayText("代理人處理中"))
        assertEquals("AI 隨行管家提出方案", AgentRouting.displayText("AI 代理人提出方案"))
    }
}
