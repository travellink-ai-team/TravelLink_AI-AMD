package com.example.travellink_ai

import com.example.travellink_ai.ui.recap.RecapPhoto
import com.example.travellink_ai.ui.recap.RecapStop
import com.example.travellink_ai.ui.recap.RecapTimelines
import com.example.travellink_ai.ui.recap.RecapTrip
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 回顧短片時間軸與網頁逐段比對：resources/recap_fixtures.json 是 scripts/gen_recap_fixtures.js
 * 用網頁實際部署的 recap-*.js 跑出來的（Reels 第 2 版 17 組＋第 1 版四種節奏，每組含推薦版與各種編輯）。
 * 網頁的算法改了就重產對照資料再跑這支測試。
 */
class RecapReelsParityTest {
    private val gson = Gson()
    private val root: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("recap_fixtures.json")!!.bufferedReader().use { it.readText() }
        JsonParser.parseString(text).asJsonObject
    }

    private fun trip(o: JsonObject) = RecapTrip(
        o["title"].asString, o["region"].asString, o["dateLabel"].asString, o["people"].asString,
        o["distanceKm"].asDouble, o["transportMode"].asString,
        o["stops"].asJsonArray.map { it.asJsonObject }.map { s ->
            RecapStop(s["stopId"].asString, s["name"].asString, s["lat"].asDouble, s["lng"].asDouble,
                s["mode"].asString, s["stayMin"].asInt, s["dayIndex"].asInt)
        }
    )

    private fun photos(a: JsonElement) = a.asJsonArray.map { it.asJsonObject }.map { p ->
        RecapPhoto(p["stopIndex"].asInt, p["url"].asString, p["photoId"]?.asString)
    }

    /** 兩份 JSON 完全一樣（數字比數值；Kotlin 沒值的欄位不輸出，所以兩邊的欄位也要一樣） */
    private fun same(path: String, js: JsonElement?, kt: JsonElement?, diffs: MutableList<String>) {
        when {
            js == null || js.isJsonNull -> if (!(kt == null || kt.isJsonNull)) diffs += "$path：網頁沒有，App 有 $kt"
            kt == null || kt.isJsonNull -> diffs += "$path：App 沒有，網頁是 $js"
            js.isJsonObject && kt.isJsonObject -> {
                val a = js.asJsonObject
                val b = kt.asJsonObject
                (a.keySet() + b.keySet()).forEach { k -> same("$path.$k", a[k], b[k], diffs) }
            }
            js.isJsonArray && kt.isJsonArray -> {
                val a = js.asJsonArray
                val b = kt.asJsonArray
                if (a.size() != b.size()) diffs += "$path：長度 ${a.size()} vs ${b.size()}"
                else for (i in 0 until a.size()) same("$path[$i]", a[i], b[i], diffs)
            }
            js.isJsonPrimitive && js.asJsonPrimitive.isNumber && kt.isJsonPrimitive && kt.asJsonPrimitive.isNumber ->
                if (abs(js.asDouble - kt.asDouble) > 1e-6) diffs += "$path：${js.asDouble} vs ${kt.asDouble}"
            else -> if (js != kt) diffs += "$path：$js vs $kt"
        }
    }

    private fun check(label: String, js: JsonElement, kt: RecapTimeline) {
        val diffs = mutableListOf<String>()
        same("timeline", js, gson.toJsonTree(kt), diffs)
        assertTrue("$label 與網頁不同：\n" + diffs.take(12).joinToString("\n"), diffs.isEmpty())
    }

    @Test fun matchesWebForEveryCase() {
        val cases = root["cases"].asJsonArray.map { it.asJsonObject }
        assertTrue(cases.size >= 20)
        var edits = 0
        cases.forEach { c ->
            val name = c["name"].asString
            val t = trip(c["trip"].asJsonObject)
            val ph = photos(c["photos"])
            val o = c["options"].asJsonObject
            val tl = RecapTimelines.buildRecommended(t, o["templateId"].asString, ph,
                lengthMode = o["lengthMode"]?.asString ?: "all",
                highlightStopId = o["highlightStopId"]?.asString,
                coverStopIndex = o["coverStopIndex"]?.asInt)
            check("$name 推薦版", c["timeline"], tl)
            assertEquals("$name 總長", c["totalMs"].asLong, RecapTimelines.totalMs(t, tl, ph))

            c["edits"].asJsonArray.map { it.asJsonObject }.forEach { e ->
                val next = when (e["op"].asString) {
                    "remove" -> RecapTimelines.removePhoto(tl, e["clipId"].asString)
                    "remove2" -> e["clipIds"].asJsonArray.fold(tl) { acc, id -> RecapTimelines.removePhoto(acc, id.asString) }
                    "removePhoto" -> RecapTimelines.removeClipPhoto(tl, e["clipId"].asString, e["index"].asInt)
                    "duration" -> RecapTimelines.setDuration(tl, e["clipId"].asString, e["durationMs"].asLong)
                    else -> error("unknown op")
                }
                val label = "$name ${e["op"].asString} ${e["clipId"] ?: e["clipIds"]}"
                check(label, e["timeline"], next)
                assertEquals("$label 總長", e["totalMs"].asLong, RecapTimelines.totalMs(t, next, ph))
                edits++
            }
        }
        assertTrue(edits > 300)
    }

    /** 提案「參考時間表」：逐段開始時間（泓廷文件第 4 節） */
    @Test fun referenceSchedule() {
        val c = root["cases"].asJsonArray.map { it.asJsonObject }.first { it["name"].asString == "reference" }
        val tl = RecapTimelines.buildRecommended(trip(c["trip"].asJsonObject), "reels", photos(c["photos"]))
        var t = 0L
        val starts = tl.clips.map { cl -> "${cl.type}@$t".also { t += cl.durationMs } }
        assertEquals(listOf("cover@0", "route@1200", "photo@2200", "photo@3600", "route@5000", "photo@5700", "photo@7100",
            "route@8500", "photo@9200", "route@12200", "photo@12900", "photo@14300", "summary@15700", "outro@17200"), starts)
        assertEquals(20500L, tl.totalMs)
        assertEquals(true, tl.clips.first { it.clipId == "photo-5" }.hero)
    }
}
