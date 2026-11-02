package com.example.travellink_ai

import com.example.travellink_ai.data.ai.AiQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiQuotaTest {
    private fun response(used: Map<String, Any>, exempt: Boolean = false) = mapOf(
        "date" to "2026-10-19",
        "exempt" to exempt,
        "limits" to mapOf("trip" to 10, "image" to 3, "chat" to 30, "swap" to 20, "text" to 150),
        "used" to used
    )

    @Test fun leftCountsDown() {
        val q = AiQuota.parse(response(mapOf("image" to 1, "trip" to 10)))!!
        assertEquals(2, q.left(AiQuota.IMAGE))
        assertEquals(0, q.left(AiQuota.TRIP))
        assertEquals(30, q.left(AiQuota.CHAT))   // 今天還沒用過的種類
    }

    @Test fun neverNegative() {
        // 上限在 Console 調低後，已用的可能超過新上限
        val q = AiQuota.parse(response(mapOf("image" to 5)))!!
        assertEquals(0, q.left(AiQuota.IMAGE))
    }

    @Test fun exemptHasNoLimit() {
        val q = AiQuota.parse(response(mapOf("image" to 9), exempt = true))!!
        assertNull(q.left(AiQuota.IMAGE))
    }

    @Test fun unknownKindHasNoLimit() {
        val q = AiQuota.parse(response(emptyMap()))!!
        assertNull(q.left("video"))
    }

    @Test fun firestoreLongsAreAccepted() {
        // callable 回來的數字可能是 Long／Integer／Double
        val q = AiQuota.parse(mapOf("limits" to mapOf("image" to 3L), "used" to mapOf("image" to 1.0)))!!
        assertEquals(2, q.left(AiQuota.IMAGE))
    }

    @Test fun badResponseIsNull() {
        assertNull(AiQuota.parse(null))
        assertNull(AiQuota.parse("oops"))
        assertNull(AiQuota.parse(mapOf("used" to mapOf("image" to 1))))
    }
}
