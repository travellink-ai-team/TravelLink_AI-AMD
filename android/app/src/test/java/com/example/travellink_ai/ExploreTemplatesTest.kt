package com.example.travellink_ai

import com.example.travellink_ai.data.model.ExploreTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App 內建的官方精選範本（scripts/export_explore_templates.mjs 用網頁程式產生）能正確解析，
 * 且「用這份開始規劃」帶進精靈的值與網頁 copyTrip 相同。
 */
class ExploreTemplatesTest {

    private val data by lazy {
        ExploreTemplates.parse(File("src/main/assets/explore_templates.json").readText(Charsets.UTF_8))
    }

    @Test
    fun `bundled templates parse and cover every district group`() {
        assertTrue(data.templates.isNotEmpty())
        assertEquals("台東車站", data.gate.name)
        val groupLabels = data.groups.map { it.label }
        assertTrue(data.templates.all { it.group in groupLabels })
        assertTrue(data.templates.all { it.stops.isNotEmpty() })
    }

    @Test
    fun `home picks follow the web order`() {
        val keys = data.homeTemplates.map { it.key }
        assertEquals(data.homePicks.filter { k -> data.templates.any { it.key == k } }, keys)
    }

    @Test
    fun `mainland seed uses 台東 and only main stops`() {
        val tpl = data.templates.first { !it.island && it.viaCount > 0 }
        val seed = tpl.toSeed()
        assertEquals("台東", seed.destination)
        assertEquals(tpl.title, seed.tripName)
        val wanted = seed.desiredSpots.split("、")
        assertEquals(tpl.stops.filter { !it.via }.map { it.name }, wanted)
        assertFalse(tpl.stops.filter { it.via }.any { it.name in wanted })
    }

    @Test
    fun `island seed uses the island itself and far trips ask for two days`() {
        data.templates.firstOrNull { it.island }?.let { assertEquals(it.key, it.toSeed().destination) }
        data.templates.filter { it.farForOneDay }.forEach { assertTrue(it.toSeed().twoDays) }
    }
}
