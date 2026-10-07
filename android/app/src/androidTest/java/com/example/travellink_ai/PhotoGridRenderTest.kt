package com.example.travellink_ai

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.travellink_ai.ui.poster.GridTiler
import com.example.travellink_ai.ui.poster.PhotoGridRenderer
import com.example.travellink_ai.ui.poster.TestLayouts
import com.example.travellink_ai.ui.poster.withPhotos
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 用合成照片渲染九宮格，把成品存到 externalCacheDir 供 `adb pull` 取出肉眼檢查。
 *
 * 不依賴登入 / Storage / Room —— 純驗排版管線。
 *
 * 產物：/sdcard/Android/data/<pkg>/cache/spike_e_whole.png
 */
@RunWith(AndroidJUnit4::class)
class PhotoGridRenderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renderAllTemplates_andExportForInspection() {
        val stops = listOf("鯉魚山", "鐵花村", "小野柳", "三仙台", "伯朗大道", "池上")

        // 四個對齊網頁的範本各出一張，看排版差異
        TestLayouts.all.forEach { template ->
            val need = 1 + template.cards.size
            val synthetic = SyntheticPhotos.forTrip(stops, need)
            val refs = synthetic.map { it.first }
            val photos = synthetic.associate { it.first.url to it.second }

            val layout = template.withPhotos(refs)
            val renderer = PhotoGridRenderer(layout, photos, "台東三日", "台東・3 天")
            val whole = GridTiler.renderWhole(renderer, 1080, 1440)
            export(whole, "grid_${template.templateKey}.png")
            whole.recycle()
            synthetic.forEach { it.second.recycle() }
        }
    }

    private fun export(bmp: Bitmap, name: String) {
        val dir = context.externalCacheDir ?: context.cacheDir
        val file = File(dir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name 未寫出", file.exists() && file.length() > 0)
    }
}
