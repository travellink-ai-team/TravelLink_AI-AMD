package com.example.travellink_ai

import com.example.travellink_ai.data.model.MemoryPhoto
import com.example.travellink_ai.data.model.SpotMemory
import com.example.travellink_ai.data.model.TripMemory
import com.example.travellink_ai.data.model.TripPhoto
import com.example.travellink_ai.data.model.canEditTripDoc
import com.example.travellink_ai.data.model.dayKeyOf
import com.example.travellink_ai.data.model.legacyPhotos
import com.example.travellink_ai.data.model.mergeTripPhotos
import com.example.travellink_ai.data.model.ownerLabels
import com.example.travellink_ai.data.model.parseExifDateTime
import com.example.travellink_ai.data.model.timestampFromStoragePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 共同相簿 photos 的跨端契約：欄位白名單、拍攝時間、撞名標籤、新舊雙讀去重。 */
class TripPhotoTest {

    private val taipei = ZoneId.of("Asia/Taipei")

    private fun photo(
        id: String, owner: String = "u1", name: String = "小明",
        path: String = "trip-photos/$owner/t1/$id.jpg", url: String = "https://x/$id",
        stopId: String = "s1", at: Long = 0L, order: Long? = null
    ) = TripPhoto(
        photoId = id, tripId = "t1", stopId = stopId, stopName = "景點", dayKey = "2026-09-25",
        capturedAt = at, manualOrder = order, ownerUid = owner, ownerName = name,
        url = url, storagePath = path
    )

    // ── 欄位白名單（Rules hasOnly＋hasAll，多一欄整筆被拒）──

    @Test fun `寫入欄位都在白名單內且必填欄位齊全`() {
        val full = photo("p1").copy(
            capturedTimezone = "Asia/Taipei", hash = "abc", createdAt = 1, uploadedAt = 1, updatedAt = 1,
            legacy = true   // 記憶體內的旗標，不可寫出去
        )
        val keys = full.toFirestoreMap().keys
        assertTrue("多寫了：${keys - TripPhoto.ALLOWED_FIELDS}", TripPhoto.ALLOWED_FIELDS.containsAll(keys))
        assertTrue(keys.containsAll(TripPhoto.REQUIRED_FIELDS))
        assertEquals(18, keys.size)
    }

    @Test fun `manualOrder 沒有值也要寫 null、status 一律 synced、選填空值不寫`() {
        val map = photo("p1").copy(status = "uploading", hash = "").toFirestoreMap()
        assertTrue(map.containsKey("manualOrder"))
        assertNull(map["manualOrder"])
        assertEquals("synced", map["status"])
        assertFalse(map.containsKey("hash"))
        assertEquals(TripPhoto.REQUIRED_FIELDS + "mimeType", map.keys)
    }

    @Test fun `讀回來欄位不變`() {
        val p = photo("p1", at = 1_758_000_000_000, order = 3).copy(capturedTimezone = "Asia/Taipei", hash = "h")
        assertEquals(p, TripPhoto.fromFirestore("p1", p.toFirestoreMap()))
    }

    // ── 拍攝時間：本地時間解讀，有 OffsetTimeOriginal 就照它 ──

    @Test fun `EXIF 沒有時區時以裝置時區解讀`() {
        val t = parseExifDateTime("2026:09:25 08:30:00", null, taipei)!!
        assertEquals(java.time.Instant.parse("2026-09-25T00:30:00Z").toEpochMilli(), t)
        assertEquals("2026-09-25", dayKeyOf(t, taipei))
    }

    @Test fun `有 OffsetTimeOriginal 就照它，不看裝置時區`() {
        val t = parseExifDateTime("2026:09:25 08:30:00", "+09:00", taipei)!!
        assertEquals(java.time.Instant.parse("2026-09-24T23:30:00Z").toEpochMilli(), t)
        assertEquals("2026-09-25", dayKeyOf(t, taipei))
    }

    @Test fun `相機沒設時間或格式錯誤回 null`() {
        assertNull(parseExifDateTime("0000:00:00 00:00:00", null, taipei))
        assertNull(parseExifDateTime("", null, taipei))
        assertNull(parseExifDateTime(null, null, taipei))
    }

    @Test fun `舊檔名反推上傳時間`() {
        assertEquals(1_783_000_000_000, timestampFromStoragePath("trip-photos/u/t/s1_1783000000000.jpg"))
        assertEquals(1_783_000_000_000, timestampFromStoragePath("trip-photos/u/t/1783000000000.jpg"))
        assertNull(timestampFromStoragePath("trip-photos/u/t/2f6c9a0e-uuid.jpg"))
    }

    // ── 撞名：只有真的撞到才加 uid 尾四碼 ──

    @Test fun `撞名才加尾碼，沒撞的維持原名`() {
        val labels = ownerLabels(listOf(
            photo("a", owner = "uidAAAA1111", name = "小明"),
            photo("b", owner = "uidBBBB2222", name = "小明"),
            photo("c", owner = "uidCCCC3333", name = "阿華")
        ))
        assertEquals("小明 #1111", labels["uidAAAA1111"])
        assertEquals("小明 #2222", labels["uidBBBB2222"])
        assertEquals("阿華", labels["uidCCCC3333"])
    }

    // ── 新舊雙讀：storagePath 或 url 任一撞到就是同一張 ──

    @Test fun `舊版照片新版已有的不重複出現`() {
        val newer = listOf(photo("p1", path = "trip-photos/u1/t1/s1_1783000000000.jpg", url = "https://x/1"))
        val legacy = listOf(
            photo("r1", path = "trip-photos/u1/t1/s1_1783000000000.jpg", url = "https://x/1-新token").copy(legacy = true),
            photo("r2", path = "", url = "https://x/2").copy(legacy = true),
            photo("r3", path = "", url = "https://x/2").copy(legacy = true)
        )
        val merged = mergeTripPhotos(newer, legacy)
        assertEquals(listOf("p1", "r2"), merged.map { it.photoId })
    }

    @Test fun `排序：manualOrder 優先，其次拍攝時間`() {
        val merged = mergeTripPhotos(
            listOf(photo("late", at = 300), photo("early", at = 100), photo("pinned", at = 999, order = 0)),
            emptyList()
        )
        assertEquals(listOf("pinned", "early", "late"), merged.map { it.photoId })
    }

    @Test fun `memories 轉成舊版照片：時間從檔名來、歸在原站`() {
        val m = TripMemory(
            ownerUid = "u1", ownerName = "小明",
            spots = mapOf("s2" to SpotMemory(
                stopId = "s2", spotName = "三仙台",
                photos = listOf(MemoryPhoto("x", "https://x/a", "trip-photos/u1/t1/s2_1783000000000.jpg"))
            ))
        )
        val p = m.legacyPhotos("t1", taipei).single()
        assertTrue(p.legacy)
        assertEquals("s2", p.stopId)
        assertEquals("三仙台", p.stopName)
        assertEquals(1_783_000_000_000, p.capturedAt)
        assertEquals("u1", p.ownerUid)
    }

    @Test fun `已搬進 photos 的舊照片不再從 memories 讀出（被刪了也不會復活）`() {
        val m = TripMemory(
            ownerUid = "u1",
            spots = mapOf("s1" to SpotMemory(
                stopId = "s1",
                photos = listOf(
                    MemoryPhoto("a", "https://x/a", "trip-photos/u1/t1/s1_1.jpg"),
                    MemoryPhoto("b", "https://x/b", "")   // 舊資料沒有路徑 → 以 url 為 key
                )
            )),
            migratedPhotoKeys = listOf("trip-photos/u1/t1/s1_1.jpg", "https://x/b")
        )
        assertTrue(m.legacyPhotos("t1", taipei).isEmpty())
        // 欄位要能存進 Firestore 再讀回來
        assertEquals(m.migratedPhotoKeys, TripMemory.fromFirestore(m.toFirestoreMap()).migratedPhotoKeys)
    }

    // ── 方案 B 權限判斷（對齊 Rules isEditor）──

    @Test fun `owner 與 editor 可管理，一般成員不行`() {
        val doc = mapOf(
            "ownerUid" to "owner", "editorEmails" to listOf("Ed@Mail.com"),
            "memberEmails" to listOf("ed@mail.com", "viewer@mail.com")
        )
        assertTrue(canEditTripDoc(doc, "owner", ""))
        assertTrue(canEditTripDoc(doc, "x", "ed@mail.com"))
        assertFalse(canEditTripDoc(doc, "y", "viewer@mail.com"))
        assertTrue(canEditTripDoc(mapOf("userEmail" to "me@mail.com"), "z", "me@mail.com"))
    }
}
