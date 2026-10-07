package com.example.travellink_ai

import com.example.travellink_ai.ui.planning.RouteValidity
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteValidityTest {
    private val seg = listOf(LatLng(22.79, 121.12), LatLng(22.78, 121.13))

    @Test
    fun `每段都有折線才算查到，沒網路的空折線加 0 分不算`() {
        assertTrue(RouteValidity.complete(listOf(seg, seg), listOf(2, 20)))
        assertFalse(RouteValidity.complete(listOf(emptyList(), emptyList()), listOf(0, 0)))      // 全部查不到
        assertFalse(RouteValidity.complete(listOf(seg, emptyList()), listOf(2, 0)))              // 部分查不到
        assertFalse(RouteValidity.complete(emptyList(), emptyList()))
    }

    @Test
    fun `不到 1 分鐘的步行段是 0 分但有折線，仍算查到`() {
        assertTrue(RouteValidity.complete(listOf(seg, seg, seg), listOf(5, 0, 12)))
    }

    @Test
    fun `快取車程大多是 0（斷網時存的）就不用`() {
        assertFalse(RouteValidity.cachedTransitUsable(List(15) { 0L }))
        assertFalse(RouteValidity.cachedTransitUsable(emptyList()))
        assertTrue(RouteValidity.cachedTransitUsable(listOf(2, 20, 28, 16, 3, 1, 10, 12, 11, 14, 9, 7, 5, 5, 12)))
        assertTrue(RouteValidity.cachedTransitUsable(listOf(5, 0, 12, 8, 9)))                   // 1/5 是 0：可用
    }
}
