package com.example.travellink_ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.local.ItineraryDao
import com.example.travellink_ai.data.local.LocalItinerary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room DAO 整合測試：使用 in-memory 資料庫，
 * 每次測試前重建，互不影響。
 */
@RunWith(AndroidJUnit4::class)
class ItineraryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ItineraryDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.itineraryDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ── 測試資料工廠 ─────────────────────────────────────────────

    private fun makeItinerary(
        title: String = "台東一日遊",
        createdAt: Long = System.currentTimeMillis()
    ) = LocalItinerary(
        title     = title,
        aiTitle   = "AI 版 $title",
        aiReply   = "很棒的選擇！",
        region    = "台東",
        days      = "2026/06/01 09:00 - 2026/06/01 18:00",
        people    = "2 大人",
        stops     = emptyList(),
        createdAt = createdAt
    )

    // ── insert / query ───────────────────────────────────────────

    @Test
    fun insertAndRetrieve() = runTest {
        val id = dao.insertItinerary(makeItinerary("鹿野行程"))
        assertTrue(id > 0)

        val all = dao.getAllItineraries().first()
        assertEquals(1, all.size)
        assertEquals("鹿野行程", all[0].title)
    }

    @Test
    fun insertMultiple_returnedInDescendingOrder() = runTest {
        val now = System.currentTimeMillis()
        dao.insertItinerary(makeItinerary("第一筆", createdAt = now - 2000))
        dao.insertItinerary(makeItinerary("第二筆", createdAt = now - 1000))
        dao.insertItinerary(makeItinerary("第三筆", createdAt = now))

        val all = dao.getAllItineraries().first()
        assertEquals(3, all.size)
        assertEquals("第三筆", all[0].title) // 最新在最前
        assertEquals("第一筆", all[2].title)
    }

    // ── getItineraryById ─────────────────────────────────────────

    @Test
    fun getById_existingId_returnsItem() = runTest {
        val id = dao.insertItinerary(makeItinerary("池上行程"))
        val item = dao.getItineraryById(id)
        assertNotNull(item)
        assertEquals("池上行程", item!!.title)
    }

    @Test
    fun getById_nonExistingId_returnsNull() = runTest {
        val item = dao.getItineraryById(9999L)
        assertNull(item)
    }

    // ── delete ───────────────────────────────────────────────────

    @Test
    fun deleteItinerary_removesFromDb() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        val item = dao.getItineraryById(id)!!
        dao.deleteItinerary(item)

        val all = dao.getAllItineraries().first()
        assertTrue(all.isEmpty())
    }

    @Test
    fun deleteItinerariesByIds_removesOnlySpecified() = runTest {
        val id1 = dao.insertItinerary(makeItinerary("行程 A"))
        val id2 = dao.insertItinerary(makeItinerary("行程 B"))
        val id3 = dao.insertItinerary(makeItinerary("行程 C"))

        dao.deleteItinerariesByIds(listOf(id1, id2))

        val all = dao.getAllItineraries().first()
        assertEquals(1, all.size)
        assertEquals("行程 C", all[0].title)
    }

    // ── updateFeedbackSubmitted ──────────────────────────────────

    @Test
    fun updateFeedbackSubmitted_setsTrue() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        dao.updateFeedbackSubmitted(id, true)

        val item = dao.getItineraryById(id)
        assertEquals(true, item?.feedbackSubmitted)
    }

    @Test
    fun updateFeedbackSubmitted_默認為未填寫() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        val item = dao.getItineraryById(id)
        assertEquals(false, item?.feedbackSubmitted)
    }

    // ── updateOverallRating ──────────────────────────────────────

    @Test
    fun updateOverallRating_savesCorrectly() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        dao.updateOverallRating(id, 5)

        val item = dao.getItineraryById(id)
        assertEquals(5, item?.overallRating)
    }

    @Test
    fun updateOverallRating_默認為零() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        val item = dao.getItineraryById(id)
        assertEquals(0, item?.overallRating)
    }

    // ── updateFirestoreDocId ─────────────────────────────────────

    @Test
    fun updateFirestoreDocId_savesAndRetrievesDocId() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        dao.updateFirestoreDocId(id, "my_1234567890")

        val item = dao.getItineraryById(id)
        assertEquals("my_1234567890", item?.firestoreDocId)
    }

    // ── deleteOlderThan ──────────────────────────────────────────

    @Test
    fun deleteOlderThan_removesOldRecords() = runTest {
        val now = System.currentTimeMillis()
        val twoWeeksAgo = now - (14L * 24 * 60 * 60 * 1000)

        dao.insertItinerary(makeItinerary("舊行程", createdAt = twoWeeksAgo - 1000))
        dao.insertItinerary(makeItinerary("新行程", createdAt = now))

        // v9 多使用者改版後 deleteOlderThan 多了 userId 參數。
        // makeItinerary 未指定 userId（預設空字串＝舊資料／本機行程），
        // 而 DAO 條件是 `userId = :userId OR userId = ''`，故傳空字串即可維持原測試意圖。
        dao.deleteOlderThan(twoWeeksAgo, "")

        val all = dao.getAllItineraries().first()
        assertEquals(1, all.size)
        assertEquals("新行程", all[0].title)
    }

    @Test
    fun deleteOlderThan_保留門檻時間之後的行程() = runTest {
        val now = System.currentTimeMillis()
        dao.insertItinerary(makeItinerary("近期行程", createdAt = now))
        dao.deleteOlderThan(now - 1000, "") // 只刪 1 秒前的

        val all = dao.getAllItineraries().first()
        assertEquals(1, all.size)
    }

    // ── updateImageUrl ───────────────────────────────────────────

    @Test
    fun updateImageUrl_savesUrl() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        dao.updateImageUrl(id, "https://storage.googleapis.com/test/image.jpg")

        val item = dao.getItineraryById(id)
        assertEquals("https://storage.googleapis.com/test/image.jpg", item?.imageUrl)
    }

    // ── updateStops ──────────────────────────────────────────────

    @Test
    fun updateStops_emptiesStopList() = runTest {
        val id = dao.insertItinerary(makeItinerary())
        dao.updateStops(id, emptyList())

        val item = dao.getItineraryById(id)
        assertTrue(item?.stops?.isEmpty() == true)
    }
}
