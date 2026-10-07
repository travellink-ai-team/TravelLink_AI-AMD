package com.example.travellink_ai.ui.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale

/**
 * 九宮格照片的載入與生命週期管理。
 *
 * ★ 這是整個功能最容易 OOM 的地方，設計圍繞「絕不全解析度解碼」：
 *   - 底圖鋪滿整張主視覺（3240×4320），若原圖直接解碼就是 50MB+ 一張，
 *     和逐格渲染想避免的正是同一件事。
 *   - 因此所有照片一律用 Coil 的 [ImageRequest.Builder.size] 降採樣到
 *     [maxEdgePx]，Coil 內部走 inSampleSize，不會把原圖整張讀進來。
 *   - 逐格渲染會重複繪製同一張照片（底圖跨九格），故快取起來重用，
 *     一趟結束再統一 [release]，不靠 GC。
 *
 * 用 Coil 而非自己寫 BitmapFactory：它內建 EXIF 轉正、inSampleSize 與
 * 執行緒管理，比手刻可靠（也避免了 `MemoryViewModel.compressToJpeg` 那種
 * 無降採樣、多份 Bitmap 不 recycle 的坑）。
 */
class PhotoBitmapCache(
    private val context: Context,
    /** 單張照片最長邊上限。1600 對 IG 縮圖（一格約 110px）綽綽有餘，佔滿寬度的
     *  底圖放大到 3240 顯示，肉眼在 IG 壓縮後看不出差別，但記憶體降到 ~10MB/張。 */
    private val maxEdgePx: Int = 1600
) {
    private val tag = "TravelLink_Poster"
    private val cache = LinkedHashMap<String, Bitmap>()

    // 關掉 Coil 記憶體快取：回傳的 Bitmap 由本類獨佔管理，才能安全 recycle
    private val loader = ImageLoader.Builder(context)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()

    /**
     * 載入一張照片（已降採樣、已依 EXIF 轉正）。同一 URL 只載一次。
     * @return null 表示載入失敗（網路、權限或非圖片）
     */
    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache[url]?.takeIf { !it.isRecycled }?.let { return it }

        val request = ImageRequest.Builder(context)
            .data(url)
            .size(maxEdgePx)
            .scale(Scale.FILL)
            .allowHardware(false)   // 要能畫進 software Canvas 並逐格重繪
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

        val bmp = runCatching {
            (loader.execute(request).drawable as? BitmapDrawable)?.bitmap
        }.onFailure { Log.w(tag, "照片載入失敗：$url", it) }.getOrNull() ?: return null

        cache[url] = bmp
        return bmp
    }

    /** 一次載入多張，回傳成功的 url → Bitmap。失敗的略過，由呼叫端決定怎麼呈現。 */
    suspend fun loadAll(urls: List<String>): Map<String, Bitmap> =
        urls.distinct().mapNotNull { url -> load(url)?.let { url to it } }.toMap()

    val loadedCount: Int get() = cache.size

    /** 峰值控管用：目前快取佔用的位元組。 */
    val residentBytes: Long
        get() = cache.values.sumOf { it.allocationByteCount.toLong() }

    fun release() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }
}
