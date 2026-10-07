package com.example.travellink_ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.travellink_ai.util.MediaStoreSaver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `MediaStoreSaver` 的裝置測試。
 *
 * ★ 存在理由：原本 `switch.kt` 直接對 MediaStore 塞 `RELATIVE_PATH`，
 *   而該欄位是 API 29 (Q) 才有的，API 26～28 會在 insert() 拋例外 ——
 *   也就是「下載插圖」在舊機一直是壞的，而且沒有任何測試會發現。
 *
 * ⚠️ 這支測試的價值取決於**跑在哪個 API**：
 *   - API 29+ → 覆蓋 MediaStore + RELATIVE_PATH + IS_PENDING 分支
 *   - API 26~28 → 覆蓋 legacy 分支（原本壞掉的那條，**最需要驗的**）
 *
 *   跑法：
 *     ./gradlew connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.example.travellink_ai.MediaStoreSaverTest
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSaverTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val created = mutableListOf<android.net.Uri>()

    /** API 29+ 不需要此權限（manifest 已設 maxSdkVersion=28）。 */
    private val hasLegacyWritePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun grantLegacyWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.WRITE_EXTERNAL_STORAGE"
        ).close()
        // 授權生效需要一點時間
        repeat(20) {
            if (hasLegacyWritePermission) return
            Thread.sleep(100)
        }
    }

    private fun makeBitmap(w: Int = 64, h: Int = 64): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(42, 107, 94))   // DesignTokens.Accent
        }

    @After
    fun cleanUp() {
        created.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /**
     * ★ 斷言「走了預期的那條分支」，而不只是「沒失敗」。
     * 否則 API 28 上測試通過也可能只是走了 AppDirOnly 降級，
     * 真正曾經壞掉的 legacy MediaStore 路徑仍然沒被驗到。
     */
    @Test
    fun saveJpeg_takesExpectedBranch() {
        val name = "TLTest_jpeg_${System.currentTimeMillis()}"
        val bmp = makeBitmap()
        val result = MediaStoreSaver.saveJpeg(context, bmp, name)
        bmp.recycle()

        val api = Build.VERSION.SDK_INT
        val granted = hasLegacyWritePermission

        when (result) {
            is MediaStoreSaver.SaveResult.Gallery -> {
                created += result.uri
                assertTrue(
                    "API $api 且未授權，不該寫進相簿",
                    granted
                )
                assertDecodable(result.uri)
            }
            is MediaStoreSaver.SaveResult.AppDirOnly -> {
                assertTrue("AppDirOnly 只應發生在 API < 29", api < Build.VERSION_CODES.Q)
                assertTrue("AppDirOnly 只應發生在未授權時", !granted)
                assertTrue(
                    "落到 AppDirOnly，但檔案不存在或為空",
                    result.file.exists() && result.file.length() > 0
                )
            }
            is MediaStoreSaver.SaveResult.Failed ->
                throw AssertionError("API $api（授權=$granted）存檔失敗：${result.reason}")
        }
    }

    /**
     * ★★ 這支才是原本壞掉的路徑：API 26～28 + 已授權 → 走 legacy MediaStore insert。
     * 舊程式在這裡塞 `RELATIVE_PATH`（API 29+ 欄位），會拋 IllegalArgumentException。
     */
    @Test
    fun saveJpeg_legacyGalleryPath_whenPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return   // 此路徑不適用

        grantLegacyWritePermission()
        assertTrue("無法取得 WRITE_EXTERNAL_STORAGE，此案例無法驗證", hasLegacyWritePermission)

        val name = "TLTest_legacy_${System.currentTimeMillis()}"
        val bmp = makeBitmap()
        val result = MediaStoreSaver.saveJpeg(context, bmp, name)
        bmp.recycle()

        val uri = (result as? MediaStoreSaver.SaveResult.Gallery)?.uri
            ?: throw AssertionError("API ${Build.VERSION.SDK_INT} 已授權，應寫入相簿，實得 $result")
        created += uri
        assertDecodable(uri)

        // legacy 分支靠 DATA 欄位登記實體檔，確認檔案真的存在於外部儲存
        @Suppress("DEPRECATION")
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null
        )?.use { c ->
            assertTrue("查不到剛寫入的項目", c.moveToFirst())
            val path = c.getString(0)
            assertTrue("DATA 欄位為空", !path.isNullOrBlank())
            assertTrue("DATA 指向的檔案不存在：$path", java.io.File(path).exists())
            assertTrue("未寫進 Pictures/TravelLink：$path", path.contains("TravelLink"))
        } ?: throw AssertionError("query 回傳 null")
    }

    @Test
    fun savePng_keepsExactPixels() {
        val name = "TLTest_png_${System.currentTimeMillis()}"
        val src = makeBitmap(8, 8)
        val expected = src.getPixel(3, 3)

        val result = MediaStoreSaver.saveJpeg(
            context, src, name, format = MediaStoreSaver.Format.PNG
        )
        src.recycle()

        val decoded = when (result) {
            is MediaStoreSaver.SaveResult.Gallery -> {
                created += result.uri
                context.contentResolver.openInputStream(result.uri)
                    ?.use { BitmapFactory.decodeStream(it) }
            }
            is MediaStoreSaver.SaveResult.AppDirOnly -> BitmapFactory.decodeFile(result.file.path)
            is MediaStoreSaver.SaveResult.Failed ->
                throw AssertionError("PNG 存檔失敗：${result.reason}")
        } ?: throw AssertionError("存檔後無法解碼")

        // PNG 無損：這是 Spike diff 圖依賴的性質（JPEG 色度次取樣會糊掉單像素）
        assertEquals("PNG 應逐像素無損", expected, decoded.getPixel(3, 3))
        decoded.recycle()
    }

    @Test
    fun savedFile_isVisibleToMediaStore() {
        // API < 29 沒有 RELATIVE_PATH，且可能沒權限，此檢查不適用
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val name = "TLTest_visible_${System.currentTimeMillis()}"
        val bmp = makeBitmap()
        val result = MediaStoreSaver.saveJpeg(context, bmp, name)
        bmp.recycle()

        val uri = (result as? MediaStoreSaver.SaveResult.Gallery)?.uri
            ?: throw AssertionError("API ${Build.VERSION.SDK_INT} 應存入相簿，實得 $result")
        created += uri

        // IS_PENDING 必須已清成 0，否則其他 App（相簿）看不到
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            assertTrue("查不到剛寫入的項目", c.moveToFirst())
            assertEquals("IS_PENDING 未清除，相簿看不到", 0, c.getInt(0))
            assertTrue("檔名不含預期名稱", c.getString(1).contains(name))
        } ?: throw AssertionError("query 回傳 null")
    }

    private fun assertDecodable(uri: android.net.Uri) {
        val bmp = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it) }
        assertTrue("寫入的檔案無法解碼（API ${Build.VERSION.SDK_INT}）", bmp != null)
        bmp?.recycle()
    }
}
