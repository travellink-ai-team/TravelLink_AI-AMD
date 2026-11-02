package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.PlaceCost
import com.example.travellink_ai.ui.planning.TaitungAreas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 建立行程的目的地只接受台東縣 */
class TaitungAreasTest {

    private fun p(name: String, address: String) =
        PlaceCost(name, 0.0, 0.0, null, "", null, "", -1, address = address)

    private val places = listOf(
        p("三仙台", "961臺東縣成功鎮三仙里基翬路74號"),
        p("知本國家森林遊樂區", "954臺東縣卑南鄉溫泉村龍泉路290號"),
        p("都蘭紅糖文化藝術館", "959臺東縣東河鄉都蘭村61號")
    )

    @Test
    fun townshipsAndCounty() {
        listOf("台東", "臺東市", "池上鄉", "鹿野", "綠島", "蘭嶼", "台東縣太麻里").forEach {
            assertTrue(it, TaitungAreas.isSupported(it, emptyList()))
        }
    }

    @Test
    fun placeNamesAndVillages() {
        // 不是鄉鎮名，但本機景點的名稱或地址找得到
        listOf("知本", "三仙台", "都蘭").forEach { assertTrue(it, TaitungAreas.isSupported(it, places)) }
    }

    @Test
    fun outsideTaitung_rejected() {
        listOf("台北", "京都", "花蓮", "墾丁", " ", "東").forEach {
            assertFalse(it, TaitungAreas.isSupported(it, places))
        }
    }
}
