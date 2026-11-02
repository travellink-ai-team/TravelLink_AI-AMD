package com.example.travellink_ai

import com.example.travellink_ai.data.ai.IllustrationByok
import com.example.travellink_ai.data.ai.IllustrationByok.Provider
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.ui.planning.PosterLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插圖自備金鑰：遮罩、錯誤訊息（與網頁 byokErrorMessage 同一套）、OpenAI 的 2:3 版面 */
class IllustrationByokTest {

    @Test fun maskKeepsHeadAndTail() {
        assertEquals("sk-…a3kQ", IllustrationByok.mask("sk-proj-1234567890a3kQ"))
        assertEquals("AIza…WXYZ", IllustrationByok.mask("AIzaSyABCDEFGHWXYZ"))
        assertEquals("••••", IllustrationByok.mask("short"))
    }

    private fun err(message: String = "", code: String = "") =
        JSONObject().put("error", JSONObject().put("message", message).put("code", code))

    @Test fun openAiErrorsTellWhatToDo() {
        assertTrue(IllustrationByok.errorMessage(Provider.OPENAI, 401, err()).contains("無效"))
        assertTrue(IllustrationByok.errorMessage(Provider.OPENAI, 403, err("Your organization must be verified")).contains("組織還沒通過驗證"))
        assertTrue(IllustrationByok.errorMessage(Provider.OPENAI, 429, err(code = "insufficient_quota")).contains("儲值"))
        assertTrue(IllustrationByok.errorMessage(Provider.OPENAI, 429, err("Rate limit")).contains("太頻繁"))
        assertTrue(IllustrationByok.errorMessage(Provider.OPENAI, 400, err(code = "moderation_blocked")).contains("換個角色"))
    }

    @Test fun geminiErrorsTellWhatToDo() {
        assertTrue(IllustrationByok.errorMessage(Provider.GEMINI, 400, err("API key not valid")).contains("金鑰無效"))
        assertTrue(IllustrationByok.errorMessage(Provider.GEMINI, 429, err()).contains("開通帳單"))
        assertTrue(IllustrationByok.errorMessage(Provider.GEMINI, 402, err("Your prepayment credits are depleted")).contains("預付額度用完"))
        assertEquals("Gemini 回應錯誤（500）：boom", IllustrationByok.errorMessage(Provider.GEMINI, 500, err("boom")))
    }

    @Test fun stateKnowsWhenKeyIsMissing() {
        val entry = IllustrationByok.Entry("sk-x", "gpt-image-1")
        assertNull(IllustrationByok.State().active)
        assertFalse(IllustrationByok.State().missingKey)
        assertTrue(IllustrationByok.State(Provider.OPENAI).missingKey)
        assertEquals(entry, IllustrationByok.State(Provider.OPENAI, mapOf(Provider.OPENAI to entry)).active)
        // 存了 Gemini 的金鑰但選的是 OpenAI：不能拿 Gemini 的金鑰去畫
        assertTrue(IllustrationByok.State(Provider.OPENAI, mapOf(Provider.GEMINI to entry)).missingKey)
    }

    @Test fun openAiLayoutIsTwoByThree() {
        val stops = listOf("台東車站", "糖廠", "森林公園").mapIndexed { i, n ->
            Stop(name = n, time = "", desc = "", emoji = "📍", duration = 60, order = i.toLong(), stopId = "s$i")
        }
        val gemini = PosterLayout.build(stops)
        val openai = PosterLayout.build(stops, PosterLayout.OPENAI_W, PosterLayout.OPENAI_H)
        assertEquals("9:16 直式", gemini.ratioText)
        assertEquals("2:3 直式", openai.ratioText)
        // 相對位置一樣（同一套排法），只是座標系不同
        gemini.places.zip(openai.places).forEach { (g, o) ->
            assertEquals(g.x / PosterLayout.W, o.x / PosterLayout.OPENAI_W, 1e-4f)
            assertEquals(g.y / PosterLayout.H, o.y / PosterLayout.OPENAI_H, 1e-4f)
        }
        assertTrue(PosterLayout.basePrompt(openai, "台東", "").contains("比例 2:3 直式"))
    }
}
