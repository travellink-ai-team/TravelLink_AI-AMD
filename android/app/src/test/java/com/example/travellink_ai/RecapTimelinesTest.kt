package com.example.travellink_ai

import com.example.travellink_ai.ui.recap.RecapPhoto
import com.example.travellink_ai.ui.recap.RecapStop
import com.example.travellink_ai.ui.recap.RecapTimelines
import com.example.travellink_ai.ui.recap.RecapTimelines.ManifestOptions
import com.example.travellink_ai.ui.recap.RecapTrip
import com.example.travellink_ai.ui.recap.model.RecapAssetRef
import com.example.travellink_ai.ui.recap.model.RecapClip
import com.example.travellink_ai.ui.recap.model.RecapTimeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 回顧短片時間軸的 Kotlin 版：斷言照搬網頁 server/test/recap-timeline.test.js（2026-10-19，1610c0b），
 * 確認與網頁、後端同一套算法（後端會用 JS 版重新驗證，兩邊算的秒數要一致）。
 */
class RecapTimelinesTest {
    private fun near(a: Double, b: Double, tol: Double = 2.0) = abs(a - b) <= tol
    private fun stop(id: String, name: String, lat: Double, lng: Double, day: Int = 1) =
        RecapStop(id, name, lat, lng, "car", 0, day)

    // 台東兩天：第 1 段只有 20 公尺（短程），第 3 段跨天
    private val trip = RecapTrip("台東兩天", "", "", "", 40.0, "car", listOf(
        stop("a", "台東車站", 22.7500, 121.1400, 1),
        stop("b", "鐵花村", 22.7502, 121.1401, 1),
        stop("c", "森林公園", 22.7700, 121.1600, 1),
        stop("d", "三仙台", 23.1200, 121.4000, 2),
        stop("e", "都蘭", 22.8800, 121.2300, 2)
    ))
    private val photos = listOf(RecapPhoto(1, "https://x/1.jpg"), RecapPhoto(3, "https://x/3.jpg"))
    private val media = photos.map { it.stopIndex }
    private val ids = listOf("standard", "brisk", "leisurely", "cinematic")

    @Test fun templates() {
        assertTrue(ids.all { RecapTimelines.hasTemplate(it) })
        assertEquals("standard", RecapTimelines.template("nope").id)
    }

    @Test fun manifestFollowsTemplate() {
        val lengths = ids.associateWith { id ->
            val m = RecapTimelines.buildManifest(trip, ManifestOptions(template = id, mediaCount = 2, mediaStopIndices = media))
            val tpl = RecapTimelines.template(id)
            val route = m.segments.sumOf { it.travelMs }
            assertTrue("$id：路線時間等於樣板目標（開車＋換日） $route vs ${m.routeMs}", near(route, m.routeMs))
            assertTrue("$id：每段開車至少 ${tpl.segMinMs}", m.segments.filter { !it.dayBreak }.all { it.travelMs >= tpl.segMinMs - 1 })
            assertEquals(id, m.templateId)
            m.totalMs
        }
        assertTrue("快節奏 < 標準 < 慢遊 $lengths",
            lengths.getValue("brisk") < lengths.getValue("standard") && lengths.getValue("standard") < lengths.getValue("leisurely"))
    }

    @Test fun cinematicCover() {
        val cine = RecapTimelines.buildManifest(trip, ManifestOptions(template = "cinematic", mediaCount = 2, mediaStopIndices = media))
        assertEquals("photoCut", cine.coverStyle)
        assertEquals(1, cine.coverPhotoStopIndex)
        assertEquals(1430.0 + 320.0, cine.coverMs, 0.0)
        val noPhoto = RecapTimelines.buildManifest(trip, ManifestOptions(template = "cinematic"))
        assertEquals("title", noPhoto.coverStyle)
    }

    @Test fun manyStopsStillByDistance() {
        // 站很多、路線時間不夠每段都給下限時，仍要依距離分配（不能每段一樣長）
        val km = listOf(2.6, 0.8, 1.0, 0.6, 6.1, 6.0, 6.9, 0.9, 7.7, 2.9)
        var lat = 22.04
        val stops = mutableListOf(stop("m0", "m0", lat, 121.55))
        km.forEachIndexed { i, k -> lat += k / 111; stops += stop("m${i + 1}", "m${i + 1}", lat, 121.55) }
        val m = RecapTimelines.buildManifest(RecapTrip("many", "", "", "", 0.0, "car", stops), ManifestOptions(template = "standard"))
        assertTrue(m.segments[8].travelMs > m.segments[3].travelMs * 2)
        assertTrue(near(m.segments.sumOf { it.travelMs }, m.routeMs))
    }

    @Test fun explicitValueWinsOverTemplate() {
        assertEquals(1234.0, RecapTimelines.buildManifest(trip, ManifestOptions(coverMs = 1234.0)).coverMs, 0.0)
    }

    private val tl get() = RecapTimelines.buildRecommended(trip, "standard", photos)

    @Test fun recommendedTimeline() {
        val t = tl
        assertEquals("cover,route,photo,route,timeline,photo,route,outro", t.clips.joinToString(",") { it.type })
        assertTrue(t.clips.withIndex().all { (i, c) -> c.order == i })
        assertEquals("b,d", t.clips.filter { it.type == "photo" }.joinToString(",") { it.assetRef!!.stopId })
        assertTrue(t.clips.filter { it.type == "route" }.all { c ->
            val i = trip.stops.indexOfFirst { it.stopId == c.fromStopId }
            trip.stops.getOrNull(i + 1)?.stopId == c.toStopId
        })
        val compiled = RecapTimelines.buildManifest(trip, RecapTimelines.compile(trip, t, photos))
        assertTrue("推薦版 compile 回 manifest，總長一致", near(compiled.totalMs, t.totalMs.toDouble()))
        // 總長看 totalMs，不是各片段加總：沒照片的中途站還有停留（dwell 0.2 秒），不屬於任何片段
        assertEquals(t.clips.size, RecapTimelines.summarize(t).size)
        assertTrue(RecapTimelines.summarize(t).sumOf { it.seconds } * 1000 <= t.totalMs + 60)
        assertTrue(abs(t.totalMs - RecapTimelines.totalMs(trip, t, photos)) <= 2)
    }

    @Test fun editedTimelineCompiles() {
        val t = tl
        val edited = t.copy(clips = t.clips
            .map { if (it.type == "route" && it.toStopId == "c") it.copy(durationMs = 4000) else it }
            .filterNot { it.type == "photo" && it.assetRef?.stopId == "d" })
        val o = RecapTimelines.compile(trip, edited, photos)
        assertEquals(4000.0, o.segmentMs[1]!!, 0.0)
        assertEquals(listOf(1), o.mediaStopIndices)
    }

    @Test fun untrustedInput() {
        val bad = RecapTimelines.compile(trip, RecapTimeline(templateId = "nope", clips = listOf(
            RecapClip(type = "route", fromStopId = "a", toStopId = "c", durationMs = 3000),      // 跳站
            RecapClip(type = "route", fromStopId = "a", toStopId = "b", durationMs = 999999),    // 超長
            RecapClip(type = "photo", assetRef = RecapAssetRef(stopId = "c"), durationMs = 3000), // 這站沒照片
            RecapClip(type = "photo", assetRef = RecapAssetRef(stopId = "b"), durationMs = 1),    // 太短
            RecapClip(type = "cover", durationMs = -5)
        )), photos)
        assertEquals(mapOf(0 to 15000.0), bad.segmentMs)
        assertEquals(listOf(1), bad.mediaStopIndices)
        assertEquals(800.0, bad.holdMsByStop[1]!!, 0.0)
        assertEquals(1000.0, bad.coverMs!!, 0.0)
    }

    @Test fun captions() {
        assertEquals("抵達 三仙台 看海超級超級超",
            RecapTimelines.sanitizeCaption(" 抵達\n三仙台" + 7.toChar() + "看海超級超級超級長的字幕 "))
        // 全形空白、不換行空白也算空白（同 JS 的 \s）；英文與數字照留
        assertEquals("台東 Day 2 出發", RecapTimelines.sanitizeCaption("台東　Day 2  出發"))
        var c = RecapTimelines.setCaption(tl, "cover", "台東慢慢走")
        c = RecapTimelines.setCaption(c, "photo-1", "鐵花村的晚上")
        assertEquals(true, c.edited)
        assertEquals("台東慢慢走", c.clips[0].caption)
        assertNull(tl.clips[0].caption)
        // 換日片段不能加字幕
        assertNull(RecapTimelines.setCaption(tl, "day-2", "不能加").clips.first { it.clipId == "day-2" }.caption)
        // 清成空白＝沒有字幕
        assertNull(RecapTimelines.setCaption(c, "cover", "   ").clips[0].caption)
    }

    @Test fun editorOps() {
        assertEquals(15000L, RecapTimelines.setDuration(tl, "route-0", 999999).clips.first { it.clipId == "route-0" }.durationMs)
        val removed = RecapTimelines.removePhoto(tl, "photo-1")
        assertTrue(removed.clips.withIndex().all { (i, c) -> c.order == i } && removed.clips.none { it.clipId == "photo-1" })
        assertEquals(tl.clips.size, RecapTimelines.removePhoto(tl, "route-0").clips.size)
        assertTrue(RecapTimelines.totalMs(trip, removed, photos) < RecapTimelines.totalMs(trip, tl, photos))
    }

    @Test fun aiScript() {
        val t = tl
        val s = JSONObject("""{"cover":"台東兩天慢慢走","outro":"下次見","clips":{
            "photo-1":{"caption":"鐵花村聽音樂","emphasis":"long"},
            "photo-3":{"caption":"三仙台看日出","emphasis":"short"},
            "route-0":{"caption":"出發往市區","emphasis":"long"},
            "ghost":{"caption":"不存在的片段"}}}""")
        val scripted = RecapTimelines.applyScriptSuggestion(t, s, keepExisting = false)
        fun dur(tt: RecapTimeline, id: String) = tt.clips.first { it.clipId == id }.durationMs
        assertEquals("鐵花村聽音樂", scripted.clips.first { it.clipId == "photo-1" }.caption)
        assertEquals("台東兩天慢慢走", scripted.clips[0].caption)
        assertEquals(t.clips.map { it.clipId }, scripted.clips.map { it.clipId })
        assertTrue(dur(scripted, "photo-1") > dur(t, "photo-1"))
        assertTrue(dur(scripted, "photo-3") < dur(t, "photo-3"))
        assertEquals(dur(t, "route-0"), dur(scripted, "route-0"))
        val kept = RecapTimelines.applyScriptSuggestion(RecapTimelines.setCaption(t, "cover", "我自己寫的"),
            JSONObject("""{"cover":"AI 寫的開場","clips":{"photo-1":{"caption":"AI 照片字幕"}}}"""), keepExisting = true)
        assertEquals("我自己寫的", kept.clips[0].caption)
        assertEquals("AI 照片字幕", kept.clips.first { it.clipId == "photo-1" }.caption)
        assertEquals(t.clips.size, RecapTimelines.applyScriptSuggestion(t, null, false).clips.size)
    }

    @Test fun captionPromptListsRouteAndPhotoClipsWithNotes() {
        val p = RecapTimelines.captionPrompt(trip, tl, "台東兩天", mapOf("b" to "晚上有樂團表演"))
        assertTrue(p.contains("route-0｜路線｜前往「鐵花村」（第 1 天，"))
        assertTrue(p.contains("photo-1｜照片｜「鐵花村」（第 1 天，停留 0 分鐘）｜旅記備註：晚上有樂團表演"))
        assertTrue(p.contains("photo-3｜照片｜「三仙台」（第 2 天"))
        assertFalse("換日片段不寫字幕", p.contains("day-2｜"))
        assertTrue(p.contains("不可編造天氣"))
        // 實用資訊接在照片片段後面；沒給就不出現
        assertFalse(p.contains("｜實用資訊："))
        val withFacts = RecapTimelines.captionPrompt(trip, tl, "台東兩天", emptyMap(), mapOf("d" to "門票 50 元"))
        assertTrue(withFacts.contains("「三仙台」（第 2 天，停留 0 分鐘）｜實用資訊：門票 50 元"))
        assertTrue(withFacts.contains("數字照抄"))
    }

    @Test fun signatureChangesWithTemplateStopsOrPhotos() {
        val base = RecapTimelines.signature("standard", trip, photos)
        assertFalse(base == RecapTimelines.signature("brisk", trip, photos))
        assertFalse(base == RecapTimelines.signature("standard", trip, photos.take(1)))
        assertEquals(base, RecapTimelines.signature("standard", trip, photos.reversed()))
    }

    @Test fun timelineSerializesWithWebFieldNames() {
        // 後端讀 type、durationMs、fromStopId、toStopId、assetRef.stopId、caption、templateId；null 欄位不輸出
        val json = com.google.gson.Gson().toJson(RecapTimelines.setCaption(tl, "photo-1", "字幕"))
        val o = JSONObject(json)
        assertEquals("standard", o.getString("templateId"))
        val photo = (0 until o.getJSONArray("clips").length()).map { o.getJSONArray("clips").getJSONObject(it) }
            .first { it.getString("clipId") == "photo-1" }
        assertEquals("b", photo.getJSONObject("assetRef").getString("stopId"))
        assertEquals("字幕", photo.getString("caption"))
        assertFalse(photo.has("fromStopId"))
    }

    // ── Reels（時間軸第 2 版）：算法本身由 RecapReelsParityTest 逐段對照網頁，這裡測 App 自己加的部分 ──

    private val reelsPhotos = listOf(
        RecapPhoto(0, "https://x/a0.jpg", "a0"), RecapPhoto(1, "https://x/b0.jpg", "b0"),
        RecapPhoto(1, "https://x/b1.jpg", "b1"), RecapPhoto(3, "https://x/d0.jpg", "d0")
    )
    private val reels get() = RecapTimelines.buildRecommended(trip, "reels", reelsPhotos)

    @Test fun reelsIsDefaultChoiceButNotBackendDefault() {
        assertEquals("reels", RecapTimelines.RECOMMENDED_TEMPLATE_ID)
        assertEquals("standard", RecapTimelines.DEFAULT_TEMPLATE_ID)
        assertTrue(RecapTimelines.isAnchor("reels"))
        assertFalse(RecapTimelines.isAnchor("standard"))
        assertEquals(2, reels.schemaVersion)
        assertEquals(1, tl.schemaVersion)
    }

    @Test fun dayCardsShortened() {
        // A10：標準 800、快節奏 600、慢遊 800、電影感 700
        assertEquals(listOf(800.0, 600.0, 800.0, 700.0), ids.map { RecapTimelines.template(it).dayTransitionMs })
        assertEquals(800L, tl.clips.first { it.type == "timeline" }.durationMs)
    }

    @Test fun reelsPromptCoversRevealSummaryAndPhotoCount() {
        val p = RecapTimelines.captionPrompt(trip, reels, "台東兩天", emptyMap())
        assertTrue(p.contains("reveal｜路線揭曉｜從「台東車站」到「都蘭」"))
        assertTrue(p.contains("summary｜旅程收束｜完整路線（5 個景點"))
        assertTrue(p, p.contains("「鐵花村」（第 1 天，停留 0 分鐘，2 張照片，主角景點）"))
    }

    @Test fun setCoverOnlyTouchesCover() {
        val withCover = RecapTimelines.setCover(reels, 3)
        val cover = withCover.clips.first { it.type == "cover" }
        assertEquals("photo", cover.source)
        assertEquals(3, cover.coverStopIndex)
        assertEquals(null, withCover.edited)
        assertEquals(reels.clips.drop(1), withCover.clips.drop(1))
        assertEquals("illustration", RecapTimelines.setCover(withCover, null).clips.first().source)
        assertEquals(tl, RecapTimelines.setCover(tl, 3))   // 第 1 版沒有自選封面
    }

    @Test fun reelsSignatureTracksPhotosLengthAndHero() {
        val base = RecapTimelines.signature("reels", trip, reelsPhotos)
        assertFalse(base == RecapTimelines.signature("reels", trip, reelsPhotos.dropLast(1)))
        assertFalse(base == RecapTimelines.signature("reels", trip, reelsPhotos, lengthMode = "15"))
        assertFalse(base == RecapTimelines.signature("reels", trip, reelsPhotos, heroStopId = "d"))
        // 第 1 版的簽章格式不變（舊的編輯版仍適用）
        assertEquals("standard|a,b,c,d,e|1,3", RecapTimelines.signature("standard", trip, photos))
    }

    @Test fun settingsCoverNeedsPhotosAndHeroNeedsStop() {
        val s = com.example.travellink_ai.ui.recap.RecapSettings("reels", "all", heroStopId = "zz", coverStopId = "c")
        assertEquals(null, s.coverIndex(trip, reelsPhotos))   // c 站沒有照片 → 用行程插圖
        assertEquals(3, s.copy(coverStopId = "d").coverIndex(trip, reelsPhotos))
        assertEquals(null, s.hero(trip))                       // 不在行程裡的站不算
        assertEquals(null, s.copy(templateId = "standard", coverStopId = "d").coverIndex(trip, reelsPhotos))
        val rec = s.copy(heroStopId = "d", coverStopId = "d").recommended(trip, reelsPhotos)
        assertEquals(true, rec.clips.first { it.clipId == "photo-3" }.hero)
        assertEquals("photo", rec.clips.first().source)
    }

    @Test fun aiEmphasisKeepsFifteenSecondLength() {
        val photoClips = { tl: RecapTimeline -> tl.clips.filter { it.type == "photo" } }
        val long = { tl: RecapTimeline ->
            JSONObject().put("clips", JSONObject().apply { photoClips(tl).forEach { put(it.clipId, JSONObject().put("emphasis", "long")) } })
        }
        val all = reels
        val stretched = RecapTimelines.applyScriptSuggestion(all, long(all), false)
        assertTrue(photoClips(stretched).zip(photoClips(all)).all { (a, b) -> a.durationMs > b.durationMs })
        val fixed = RecapTimelines.buildRecommended(trip, "reels", reelsPhotos, "15")
        val kept = RecapTimelines.applyScriptSuggestion(fixed, long(fixed), false)
        assertEquals(photoClips(fixed).map { it.durationMs }, photoClips(kept).map { it.durationMs })
    }

    @Test fun coverPhotoMovesToFrontOfItsStopOnly() {
        val s = com.example.travellink_ai.ui.recap.RecapSettings("reels", "all", coverStopId = "b", coverPhotoId = "b1")
        assertEquals("b1", s.coverPhoto(trip, reelsPhotos)?.photoId)
        assertEquals(listOf("a0", "b1", "b0", "d0"), s.requestPhotos(trip, reelsPhotos).map { it.photoId })
        // 選的那張不在了 → 那站第一張、不動順序
        assertEquals(reelsPhotos, s.copy(coverPhotoId = "gone").requestPhotos(trip, reelsPhotos))
        // 行程插圖封面、或不是 Reels → 不動
        assertEquals(reelsPhotos, s.copy(coverStopId = null).requestPhotos(trip, reelsPhotos))
        assertEquals(reelsPhotos, s.copy(templateId = "standard").requestPhotos(trip, reelsPhotos))
        // 那站有沒有 photoId 的照片（後端依位置對）→ 不動
        val noId = reelsPhotos.map { if (it.photoId == "b0") it.copy(photoId = null) else it }
        assertEquals(noId, s.requestPhotos(trip, noId))
    }
}
