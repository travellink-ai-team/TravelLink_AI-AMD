package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.AssistantPlaces
import com.example.travellink_ai.ui.planning.PlaceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隨行管家只能推薦本地資料裡的真實地點（實測 gpt-oss 會編出「咖啡弄 (Coffee Lab)」） */
class AssistantPlacesTest {

    private fun place(name: String, type: String, lat: Double, lng: Double) =
        PlaceCost(name, lat, lng, null, "", null, "", -1, typeName = type)

    private val center = 22.7570 to 121.1500   // 台東市區
    private val places = listOf(
        place("鐵花村音樂聚落", "景點", 22.7536, 121.1488),
        place("臺東森林公園", "景點", 22.7682, 121.1563),
        place("藍蜻蜓速食專賣店", "餐廳", 22.7530, 121.1490),
        place("榕樹下米苔目(中華路創始老店-別無分店)", "餐廳", 22.7547, 121.1534),
        place("野室珈琲W.G.cafe", "咖啡廳", 22.7560, 121.1470),
        place("三仙台", "景點", 23.1236, 121.4128),          // 50 公里外
        place("台東航空站停車場", "停車場", 22.7550, 121.1010) // 不是可推薦的類型
    )

    @Test
    fun pick_nearbyOnly_excludesStopsAlreadyInTrip_andOtherTypes() {
        val c = AssistantPlaces.pick(places, center, excludeNames = listOf("臺東森林公園"))
        val names = c.map { it.place.name }
        assertTrue("鐵花村音樂聚落" in names)
        assertTrue("已在行程中的排除", "臺東森林公園" !in names)
        assertTrue("超過 12 公里排除", "三仙台" !in names)
        assertTrue("停車場不是可推薦類型", names.none { it.contains("停車場") })
        assertEquals("依距離排序", c.map { it.km }, c.map { it.km }.sorted())
    }

    @Test
    fun canonical_rejectsInventedNames_mapsNearMatches() {
        val c = AssistantPlaces.pick(places, center, emptyList())
        assertNull("編出來的店名擋下", AssistantPlaces.canonical("咖啡弄 (Coffee Lab)", c))
        assertNull(AssistantPlaces.canonical("小野咖啡 (Ono Coffee)", c))
        assertEquals("鐵花村音樂聚落", AssistantPlaces.canonical("鐵花村音樂聚落", c))
        assertEquals("省略分店名也對得到", "榕樹下米苔目(中華路創始老店-別無分店)", AssistantPlaces.canonical("榕樹下米苔目", c))
        assertNull("太短的字不做包含比對", AssistantPlaces.canonical("咖啡", c))
    }
}
