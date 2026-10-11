package com.example.travellink_ai.util

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * 存 JPEG 到使用者相簿的共用工具。
 *
 * ★ 為何需要這支：原本 `switch.kt` 直接對 MediaStore 塞 `RELATIVE_PATH`，
 *   而該欄位是 **API 29 (Q) 才有**的，minSdk 26 的裝置會在 `insert()` 拋
 *   IllegalArgumentException —— 也就是 API 26～28 上「下載插圖」一直是壞的。
 *   IG 九宮格要連存 9 張，同一段邏輯會被放大，因此抽成單一來源。
 *
 * 分支：
 *   - API 29+：MediaStore + RELATIVE_PATH + IS_PENDING（寫入期間相簿看不到半成品）
 *   - API 26~28：需 WRITE_EXTERNAL_STORAGE，走傳統 Pictures 路徑後通知 MediaStore
 *   - 權限被拒：退回 App 私有目錄，回傳 [SaveResult.AppDirOnly] 讓呼叫端提示「尚未存入相簿」
 */
object MediaStoreSaver {

    private const val TAG = "TravelLink_Save"
    private const val ALBUM = "TravelLink"

    sealed interface SaveResult {
        /** 已存入相簿。 */
        data class Gallery(val uri: Uri) : SaveResult
        /** 沒有權限，只存進 App 私有目錄；呼叫端須告知使用者尚未進相簿。 */
        data class AppDirOnly(val file: File) : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    enum class Format(
        val compressFormat: Bitmap.CompressFormat,
        val mime: String,
        val ext: String
    ) {
        JPEG(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg"),
        /** 需要逐像素精確時用（例如單像素標記圖，JPEG 的色度次取樣會糊掉）。 */
        PNG(Bitmap.CompressFormat.PNG, "image/png", "png")
    }

    /**
     * @param displayName 不含副檔名的檔名，例如 `TravelLink_台東_1of9`
     * @param quality PNG 為無損，此值會被忽略
     */
    fun saveJpeg(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int = 95,
        format: Format = Format.JPEG
    ): SaveResult = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreQ(context, bitmap, displayName, quality, format)
        } else {
            saveLegacy(context, bitmap, displayName, quality, format)
        }
    } catch (e: Exception) {
        Log.e(TAG, "存檔失敗：$displayName", e)
        SaveResult.Failed(e.message ?: "未知錯誤")
    }

    // ── 影片 ────────────────────────────────────────────────────
    /** 存 mp4 bytes 到相簿（Movies/TravelLink）；API<29 無權限時退回 App 私有目錄。 */
    fun saveVideo(context: Context, bytes: ByteArray, displayName: String): SaveResult = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveVideoQ(context, bytes, displayName)
        else saveVideoLegacy(context, bytes, displayName)
    } catch (e: Exception) {
        Log.e(TAG, "影片存檔失敗：$displayName", e)
        SaveResult.Failed(e.message ?: "未知錯誤")
    }

    private fun saveVideoQ(context: Context, bytes: ByteArray, displayName: String): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult.Failed("無法建立相簿項目")
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("無法開啟輸出串流")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            SaveResult.Gallery(uri)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun saveVideoLegacy(context: Context, bytes: ByteArray, displayName: String): SaveResult {
        if (!hasLegacyWritePermission(context)) return videoToAppDir(context, bytes, displayName)
        return try {
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val albumDir = File(moviesDir, ALBUM).apply { if (!exists()) mkdirs() }
            val file = File(albumDir, "$displayName.mp4").apply { writeBytes(bytes) }
            @Suppress("DEPRECATION")
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATA, file.absolutePath)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { SaveResult.Gallery(it) } ?: SaveResult.Gallery(Uri.fromFile(file))
        } catch (e: Exception) {
            Log.w(TAG, "外部影片寫入失敗，改存 App 私有", e)
            videoToAppDir(context, bytes, displayName)
        }
    }

    private fun videoToAppDir(context: Context, bytes: ByteArray, displayName: String): SaveResult {
        val dir = File(context.filesDir, "shared_videos").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$displayName.mp4").apply { writeBytes(bytes) }
        Log.w(TAG, "無儲存權限，影片存 App 私有目錄：${file.absolutePath}")
        return SaveResult.AppDirOnly(file)
    }

    // ── API 29+ ────────────────────────────────────────────────
    private fun saveViaMediaStoreQ(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int,
        format: Format
    ): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.${format.ext}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            // 寫入完成前不讓其他 App 看到半成品（連存 9 張時特別明顯）
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult.Failed("無法建立相簿項目")

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(format.compressFormat, quality, out)) {
                    error("${format.name} 壓縮失敗")
                }
            } ?: error("無法開啟輸出串流")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)

            SaveResult.Gallery(uri)
        } catch (e: Exception) {
            // 失敗時清掉 pending 項目，避免相簿留下永久隱形的空檔
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    // ── API 26~28 ──────────────────────────────────────────────
    private fun saveLegacy(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int,
        format: Format
    ): SaveResult {
        if (!hasLegacyWritePermission(context)) {
            return saveToAppDir(context, bitmap, displayName, quality, format)
        }

        // 有權限不代表寫得成功：外部儲存可能未掛載、唯讀，或權限剛授予但本 process
        // 的掛載命名空間尚未更新（API < 29 的已知行為）。失敗時降級而非直接回 Failed，
        // 讓使用者至少拿得到檔案。
        return try {
            @Suppress("DEPRECATION")
            val picturesDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val albumDir = File(picturesDir, ALBUM)
            if (!albumDir.exists() && !albumDir.mkdirs()) {
                error("無法建立相簿目錄：${albumDir.absolutePath}")
            }
            val file = File(albumDir, "$displayName.${format.ext}")

            FileOutputStream(file).use { out ->
                if (!bitmap.compress(format.compressFormat, quality, out)) {
                    error("${format.name} 壓縮失敗")
                }
            }

            // 舊版沒有 RELATIVE_PATH，寫完檔案後補登記讓相簿看得到
            @Suppress("DEPRECATION")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            uri?.let { SaveResult.Gallery(it) } ?: SaveResult.Gallery(Uri.fromFile(file))
        } catch (e: Exception) {
            Log.w(TAG, "外部儲存寫入失敗，改存 App 私有目錄", e)
            saveToAppDir(context, bitmap, displayName, quality, format)
        }
    }

    /** 權限被拒時的降級：存 App 私有目錄，可再交由 FileProvider 分享。 */
    private fun saveToAppDir(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        quality: Int,
        format: Format
    ): SaveResult {
        val dir = File(context.filesDir, "shared_images").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$displayName.${format.ext}")
        FileOutputStream(file).use { out ->
            bitmap.compress(format.compressFormat, quality, out)
        }
        Log.w(TAG, "無儲存權限，改存 App 私有目錄：${file.absolutePath}")
        return SaveResult.AppDirOnly(file)
    }

    /**
     * 刪除本 App 寫進相簿、且檔名以 [prefixes] 開頭的圖片。
     *
     * 只用來清理除錯產物；範圍限定在本 App 建立的項目（MediaStore 本來就只允許
     * 刪自己寫入的），並以檔名前綴再收一次，避免誤刪使用者的照片。
     *
     * @return 實際刪除的筆數
     */
    fun deleteOwnImagesByPrefix(context: Context, prefixes: List<String>): Int {
        if (prefixes.isEmpty()) return 0
        val resolver = context.contentResolver
        val selection = prefixes.joinToString(" OR ") {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        }
        val args = prefixes.map { "$it%" }.toTypedArray()

        return runCatching {
            resolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, args)
        }.onFailure { Log.w(TAG, "清理除錯圖失敗", it) }.getOrDefault(0)
    }

    private fun hasLegacyWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
}
