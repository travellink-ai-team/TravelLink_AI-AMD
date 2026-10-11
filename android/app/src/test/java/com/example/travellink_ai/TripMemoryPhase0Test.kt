package com.example.travellink_ai

import com.example.travellink_ai.data.model.MemoryPhoto
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.data.model.deterministicStopId
import com.example.travellink_ai.data.model.orderFromDeterministicStopId
import com.example.travellink_ai.data.model.reconcileSpotKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旅程故事計畫 Phase 0 的驗收（§11.1）：
 * 「改名、換日與排序後 stopId 不變，照片與影片不脫落」「每張可輸出照片有 photoId」。
 */
class TripMemoryPhase0Test {

    private fun stop(name: String, order: Long, id: String = deterministicStopId(order, name)) =
        Stop(name = name, time = "09:00", desc = "", emoji = "📍", duration = 60, order = order, stopId = id)

    private fun photo(id: String) = MemoryPhoto(photoId = id, url = "https://x/$id", storagePath = "p/$id")

    // ── photoId ────────────────────────────────────────────────

    @Test
    fun `舊格式的裸 URL 會補上 photoId 與 storagePath`() {
        val url = "https://firebasestorage.googleapis.com/v0/b/bkt/o/" +
            "trip-photos%2Fuid1%2Ftrip1%2Fs_123.jpg?alt=media&token=abc"
        val p = MemoryPhoto.fromAny(url, ownerUid = "uid1")
        assertNotNull(p)
        assertTrue("photoId 不可為空", p!!.photoId.isNotBlank())
        assertEquals("trip-photos/uid1/trip1/s_123.jpg", p.storagePath)
        assertEquals("uid1", p.byUid)
    }

    @Test
    fun `新格式讀回時保留原本的 photoId`() {
        val p = MemoryPhoto.fromAny(
            mapOf("photoId" to "fixed-id", "url" to "https://x/1", "storagePath" to "a/b.jpg")
        )
        assertEquals("fixed-id", p!!.photoId)
        assertEquals("a/b.jpg", p.storagePath)
    }

    @Test
    fun `新舊格式混在同一個陣列也能讀`() {
        val spot = SpotMemory.fromMap(
            mapOf(
                "stopId" to "s1",
                "photos" to listOf(
                    "https://firebasestorage.googleapis.com/v0/b/bkt/o/a%2Fb.jpg?alt=media",
                    mapOf("photoId" to "keep-me", "url" to "https://x/2")
                )
            )
        )
        assertEquals(2, spot.photos.size)
        assertTrue(spot.photos.all { it.photoId.isNotBlank() })
        assertEquals("keep-me", spot.photos[1].photoId)
    }

    @Test
    fun `空值與壞資料會被略過而不是產生空照片`() {
        assertNull(MemoryPhoto.fromAny(""))
        assertNull(MemoryPhoto.fromAny(null))
        assertNull(MemoryPhoto.fromAny(mapOf("photoId" to "x")))  // 沒有 url
    }

    // ── stopId 推導 ────────────────────────────────────────────

    @Test
    fun `確定性 stopId 對同樣的 order 與 name 永遠一致`() {
        assertEquals(deterministicStopId(3, "鹿野高台"), deterministicStopId(3, "鹿野高台"))
        assertEquals(3L, orderFromDeterministicStopId(deterministicStopId(3, "鹿野高台")))
    }

    @Test
    fun `名稱中的 field-path 保留字元會被消毒`() {
        val id = deterministicStopId(1, "A.B/C[0]")
        assertTrue("不可含 Firestore field path 保留字元", id.none { it in ".~*/[]" })
    }

    @Test
    fun `不是推導格式的 id 反推 order 會得到 null`() {
        assertNull(orderFromDeterministicStopId("550e8400-e29b-41d4-a716-446655440000"))
    }

    // ── 脫落回憶的搶救 ─────────────────────────────────────────

    @Test
    fun `沒有孤兒時原樣返回且不產生 alias`() {
        val stops = listOf(stop("鹿野高台", 1), stop("池上伯朗大道", 2))
        val spots = mapOf(stops[0].stopId to SpotMemory(stops[0].stopId, "鹿野高台", photos = listOf(photo("p1"))))
        val r = reconcileSpotKeys(spots, stops)
        assertEquals(spots, r.spots)
        assertTrue(r.newAliases.isEmpty())
    }

    @Test
    fun `換順序但沒改名時以名稱接回`() {
        // 照片存進去時是 order=1，之後被拖到 order=2
        val oldKey = deterministicStopId(1, "鹿野高台")
        val stops = listOf(stop("池上伯朗大道", 1), stop("鹿野高台", 2))
        val spots = mapOf(oldKey to SpotMemory(oldKey, "鹿野高台", photos = listOf(photo("p1"))))

        val r = reconcileSpotKeys(spots, stops)

        val newKey = stops[1].stopId
        assertEquals(mapOf(oldKey to newKey), r.newAliases)
        assertEquals(1, r.spots[newKey]!!.photos.size)
        assertNull("舊 key 應已移除", r.spots[oldKey])
    }

    @Test
    fun `改名但沒換順序時以 order 接回`() {
        val oldKey = deterministicStopId(2, "鹿野高台")
        val stops = listOf(stop("池上伯朗大道", 1), stop("鹿野高台（縱谷）", 2))
        val spots = mapOf(oldKey to SpotMemory(oldKey, "鹿野高台", photos = listOf(photo("p1"))))

        val r = reconcileSpotKeys(spots, stops)

        assertEquals(stops[1].stopId, r.newAliases[oldKey])
        assertEquals(1, r.spots[stops[1].stopId]!!.photos.size)
    }

    @Test
    fun `已記錄的 alias 優先於推測`() {
        val oldKey = "web_9_舊名字"
        val stops = listOf(stop("A", 1), stop("B", 2))
        val spots = mapOf(oldKey to SpotMemory(oldKey, "舊名字", photos = listOf(photo("p1"))))

        val r = reconcileSpotKeys(spots, stops, knownAliases = mapOf(oldKey to stops[0].stopId))

        assertEquals(stops[0].stopId, r.newAliases[oldKey])
    }

    @Test
    fun `目標站已有照片時採合併不覆蓋`() {
        val oldKey = deterministicStopId(1, "鹿野高台")
        val stops = listOf(stop("鹿野高台", 2))
        val spots = mapOf(
            stops[0].stopId to SpotMemory(stops[0].stopId, "鹿野高台", note = "新的", photos = listOf(photo("new"))),
            oldKey to SpotMemory(oldKey, "鹿野高台", note = "舊的", photos = listOf(photo("old")))
        )

        val r = reconcileSpotKeys(spots, stops)

        val merged = r.spots[stops[0].stopId]!!
        assertEquals(2, merged.photos.size)
        assertTrue(merged.note.contains("新的") && merged.note.contains("舊的"))
    }

    @Test
    fun `合併時以 photoId 去重`() {
        val oldKey = deterministicStopId(1, "鹿野高台")
        val stops = listOf(stop("鹿野高台", 2))
        val dup = photo("same")
        val spots = mapOf(
            stops[0].stopId to SpotMemory(stops[0].stopId, "鹿野高台", photos = listOf(dup)),
            oldKey to SpotMemory(oldKey, "鹿野高台", photos = listOf(dup))
        )

        val r = reconcileSpotKeys(spots, stops)

        assertEquals(1, r.spots[stops[0].stopId]!!.photos.size)
    }

    @Test
    fun `完全對不上的孤兒必須保留而不是被刪掉`() {
        val orphanKey = "web_99_已經不存在的站"
        val stops = listOf(stop("鹿野高台", 1))
        val spots = mapOf(orphanKey to SpotMemory(orphanKey, "已經不存在的站", photos = listOf(photo("p1"))))

        val r = reconcileSpotKeys(spots, stops)

        assertTrue("孤兒不可遺失", r.spots.containsKey(orphanKey))
        assertTrue(r.newAliases.isEmpty())
    }
}
