package com.example.travellink_ai

import com.example.travellink_ai.data.transit.ParkingDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParkingDirectoryTest {

    // 格式照實際 TDX 回應（2026-09-24 logcat）
    private val parks = """
        {"UpdateTime":"2023-10-13T14:54:57+08:00","CarParks":[
          {"CarParkID":"TTT010","CarParkName":{"Zh_tw":"台東火車站四處路外停車場(A區)"},
           "CarParkPosition":{"PositionLat":22.794769,"PositionLon":121.124633}},
          {"CarParkID":"TTT019","CarParkName":{"Zh_tw":"台東新生地下停車場"},
           "CarParkPosition":{"PositionLat":22.752660,"PositionLon":121.146}},
          {"CarParkID":"BAD","CarParkName":{"Zh_tw":"沒有座標"}}
        ]}
    """.trimIndent()
    private val spaces = """{"ParkingSpaces":[{"CarParkID":"TTT019","TotalSpaces":301},{"CarParkID":"TTT010","TotalSpaces":112}]}"""

    @Test
    fun `組出清單並帶上車位數，缺座標的略過`() {
        val lots = ParkingDirectory.parse(parks, spaces)!!
        assertEquals(listOf("TTT010", "TTT019"), lots.map { it.id })
        assertEquals(301, lots.first { it.id == "TTT019" }.totalSpaces)
    }

    @Test
    fun `車位數缺了一樣可用，車位記 0`() {
        val lots = ParkingDirectory.parse(parks, null)!!
        assertEquals(0, lots.first().totalSpaces)
    }

    @Test
    fun `被限流時不是清單，回 null 不可存快取`() {
        assertNull(ParkingDirectory.parse("""{"message":"API rate limit exceeded"}""", null))
    }

    // 格式照 scripts/export_parking_data.mjs 的輸出
    private val local = """
        {"version":1,"lots":[
          {"name":"停五公有停車場","lat":22.7552484,"lng":121.1448522,"smallSpots":73,"motoSpots":0,"source":"taitung_gov_parking"},
          {"name":"加路蘭遊憩區停車場","lat":22.8063665,"lng":121.1969974,"smallSpots":28,"motoSpots":20,"source":"taitung_gov_parking"},
          {"name":"新生地下（縣府版）","lat":22.7527,"lng":121.1460,"smallSpots":0,"source":"taitung_gov_parking"},
          {"name":"沒座標"}
        ]}
    """.trimIndent()

    @Test
    fun `縣府清單解析，缺座標的略過`() {
        val lots = ParkingDirectory.parseLocal(local)
        assertEquals(3, lots.size)
        assertEquals(20, lots.first { it.name == "加路蘭遊憩區停車場" }.motoSpots)
        assertEquals("taitung_gov_parking", lots.first().source)
    }

    @Test
    fun `合併：同一座（60m 內）只留縣府那筆，缺的車位數由 TDX 補`() {
        val merged = ParkingDirectory.merge(ParkingDirectory.parseLocal(local), ParkingDirectory.parse(parks, spaces)!!)
        // 縣府 3 ＋ TDX 2，其中 TDX 新生地下與縣府版重疊 → 4
        assertEquals(4, merged.size)
        assertEquals(301, merged.first { it.name == "新生地下（縣府版）" }.totalSpaces)
    }

    @Test
    fun `候選：1 公里內由近到遠`() {
        val lots = ParkingDirectory.merge(ParkingDirectory.parseLocal(local), ParkingDirectory.parse(parks, spaces)!!)
        // 台東鐵道藝術村 (鐵花新聚落)
        val c = ParkingDirectory.candidates(lots, 22.7536658, 121.1459495)
        assertEquals("新生地下（縣府版）", c.first().name)
        assertTrue(c.size in 2..ParkingDirectory.CANDIDATE_LIMIT)
        // 加路蘭：過去 TDX 查不到，縣府清單有遊憩區停車場
        assertEquals("加路蘭遊憩區停車場", ParkingDirectory.candidates(lots, 22.806865, 121.197)[0].name)
        // 富岡地質公園：1 公里內沒有
        assertTrue(ParkingDirectory.candidates(lots, 22.7947736, 121.1981802).isEmpty())
    }

    @Test
    fun `排除月租與供宿客，TDX 同一座但名稱沒標月租的也一起排除`() {
        val gov = ParkingDirectory.parseLocal("""
            {"lots":[
              {"name":"長濱鄉一號停車場(月租)","lat":23.3160,"lng":121.4480},
              {"name":"豐榮路停車場(月租型)","lat":22.7440,"lng":121.1380},
              {"name":"旗魚飯店停車場(供宿客)","lat":22.7500,"lng":121.1500},
              {"name":"阿華停車場(供遊客)","lat":22.7600,"lng":121.1600}
            ]}
        """.trimIndent())
        // 實際資料：TDX 的長濱鄉壹號與縣府的一號(月租)座標差約 600m，要靠名稱認出是同一座
        val tdx = ParkingDirectory.parse("""
            {"CarParks":[{"CarParkID":"TTT016","CarParkName":{"Zh_tw":"長濱鄉壹號停車場"},
              "CarParkPosition":{"PositionLat":23.3214,"PositionLon":121.4480}}]}
        """.trimIndent(), null)!!
        assertEquals(listOf("阿華停車場(供遊客)"), ParkingDirectory.usable(gov, tdx).map { it.name })
    }

    @Test
    fun `名稱相同就算同一座，分區不同的不算`() {
        val gov = ParkingDirectory.parseLocal("""
            {"lots":[
              {"name":"台東市新生地下停車場","lat":22.7527,"lng":121.1460},
              {"name":"台東火車站四處路外停車場(A區)","lat":22.7960,"lng":121.1260},
              {"name":"台東火車站四處路外停車場(B區)","lat":22.7965,"lng":121.1265}
            ]}
        """.trimIndent())
        val tdx = ParkingDirectory.parse("""
            {"CarParks":[
              {"CarParkID":"TTT019","CarParkName":{"Zh_tw":"台東新生地下停車場"},"CarParkPosition":{"PositionLat":22.7540,"PositionLon":121.1470}},
              {"CarParkID":"TTT010","CarParkName":{"Zh_tw":"台東火車站四處路外停車場(A區)"},"CarParkPosition":{"PositionLat":22.7948,"PositionLon":121.1246}}
            ]}
        """.trimIndent(), null)!!
        // TDX 兩筆都與縣府重複 → 仍是 3 座，且 B 區沒被 A 區吃掉
        assertEquals(3, ParkingDirectory.merge(gov, tdx).size)
    }

    // 格式照網頁 parking-data.js 的 OSM 資料（2026-09-28 版）：名稱多半就叫「停車場」
    private val withOsm = """
        {"lots":[
          {"name":"停五公有停車場","lat":22.7552484,"lng":121.1448522,"smallSpots":73,"source":"taitung_gov_parking"},
          {"name":"停車場","lat":22.7553,"lng":121.1449,"source":"osm","notes":"收費"},
          {"name":"停車場","lat":22.8721225,"lng":121.2258594,"source":"osm","notes":"免費"},
          {"name":"停車場","lat":22.8873145,"lng":121.2107543,"source":"osm","notes":"免費"},
          {"name":"停車場","lat":23.1202783,"lng":121.2226173,"smallSpots":25,"source":"osm"},
          {"name":"池上遊客服務中心停車場","lat":23.1345118,"lng":121.2332899,"smallSpots":13,"source":"osm","notes":"免費"}
        ]}
    """.trimIndent()

    @Test
    fun `OSM 同名「停車場」各自是一座，id 不重複`() {
        val lots = ParkingDirectory.usable(ParkingDirectory.parseLocal(withOsm), emptyList())
        // 停五旁邊那筆 OSM 併進縣府，其餘 4 筆 OSM 各自保留
        assertEquals(5, lots.size)
        assertEquals(lots.size, lots.map { it.id }.toSet().size)
        assertEquals(3, lots.count { it.name == "停車場" })
    }

    @Test
    fun `OSM 與縣府同一座：留縣府的名稱與車位，補上收費資訊`() {
        val lot = ParkingDirectory.usable(ParkingDirectory.parseLocal(withOsm), emptyList())
            .first { it.name == "停五公有停車場" }
        assertEquals("taitung_gov_parking", lot.source)
        assertEquals(73, lot.totalSpaces)
        assertEquals("收費", lot.feeNote)
    }

    @Test
    fun `通用名稱被同一座的具名資料取代，但通用名稱之間不比對`() {
        val osm = ParkingDirectory.parseLocal("""
            {"lots":[{"name":"停車場","lat":22.7900,"lng":121.1200,"source":"osm"}]}
        """.trimIndent())
        val tdx = ParkingDirectory.parse("""
            {"CarParks":[
              {"CarParkID":"T1","CarParkName":{"Zh_tw":"某某公有停車場"},"CarParkPosition":{"PositionLat":22.79002,"PositionLon":121.12002}},
              {"CarParkID":"T2","CarParkName":{"Zh_tw":"停車場"},"CarParkPosition":{"PositionLat":22.8500,"PositionLon":121.2000}}
            ]}
        """.trimIndent(), null)!!
        val merged = ParkingDirectory.merge(osm, tdx)
        // T1 在 60m 內 → 併入並改用具名名稱；T2 也叫「停車場」但遠在 8 公里外 → 另一座
        assertEquals(listOf("某某公有停車場", "停車場"), merged.map { it.name })
    }

    @Test
    fun `排除員工、車庫、停車棚、大型車`() {
        listOf("員工停車場", "垃圾車車庫", "停車棚", "大型車停車場").forEach {
            assertTrue(it, ParkingDirectory.isRestricted(it))
        }
        listOf("停車場", "小型車停車場", "池上遊客服務中心停車場").forEach {
            assertFalse(it, ParkingDirectory.isRestricted(it))
        }
    }

    @Test
    fun `內建資料檔含縣府與 OSM，id 全部不重複`() {
        val lots = ParkingDirectory.parseLocal(File("src/main/assets/parking_taitung.json").readText(Charsets.UTF_8))
        assertTrue(lots.count { it.source == "osm" } > 100)
        assertTrue(lots.count { it.source == "taitung_gov_parking" } >= 30)
        assertEquals(lots.size, lots.map { it.id }.toSet().size)
        // 三仙台：只有縣府清單時 1 公里內找不到，加上 OSM 就有
        val usable = ParkingDirectory.usable(lots, emptyList())
        assertTrue(ParkingDirectory.candidates(usable.filter { it.source != "osm" }, 23.12316, 121.41104).isEmpty())
        assertTrue(ParkingDirectory.candidates(usable, 23.12316, 121.41104).isNotEmpty())
    }

    @Test
    fun `步行推估同網頁：593m 約 11 分鐘`() {
        val lot = ParkingDirectory.parseLocal(local).first()
        // 往北約 593m 的點
        assertEquals(11, ParkingDirectory.estimateWalkMins(lot, 22.7552484 + 593 / 111_195.0, 121.1448522))
    }
}
