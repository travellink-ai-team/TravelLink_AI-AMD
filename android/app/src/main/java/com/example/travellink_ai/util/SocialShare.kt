package com.example.travellink_ai.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * 分享成品到 Instagram 及其他社群。
 *
 * 為什麼是「開分享選單」而不是「自動貼文」：Instagram 官方沒有開放第三方 App 自動
 * 發文到個人帳號（Graph API 內容發布僅限商業帳號 + 後端 + 審核）。業界標準做法是用
 * Android 的 ACTION_SEND 開系統分享選單，把媒體交給使用者選定的 App（Instagram、
 * Threads、Line、Facebook… 只要裝了就會出現），最後一下發佈由使用者按。
 */
object SocialShare {

    private const val DEFAULT_TITLE = "分享到 Instagram 及其他社群"

    /** 分享單一媒體（圖片或影片）。雲端 http(s) 連結則改分享文字連結。 */
    fun shareMedia(context: Context, uri: Uri, mime: String, title: String = DEFAULT_TITLE) {
        if (uri.scheme == "http" || uri.scheme == "https") {
            shareText(context, uri.toString(), title)
            return
        }
        val shareUri = normalize(context, uri) ?: run {
            Toast.makeText(context, "找不到可分享的檔案", Toast.LENGTH_SHORT).show(); return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, title)
    }

    /**
     * 分享影片：雲端 http(s) 來源先下載成本地檔再分享（否則社群 App 拿到的是連結、無法貼片）。
     * 本地 content://、file:// 則直接分享。需在 coroutine 呼叫（會做 IO 下載）。
     */
    suspend fun shareVideoSmart(context: Context, uri: Uri, title: String = DEFAULT_TITLE) {
        val shareUri: Uri? = withContext(Dispatchers.IO) {
            if (uri.scheme == "http" || uri.scheme == "https") {
                runCatching {
                    val f = File(context.cacheDir, "share_recap.mp4")
                    URL(uri.toString()).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                }.getOrNull()
            } else {
                normalize(context, uri)
            }
        }
        withContext(Dispatchers.Main) {
            if (shareUri == null) {
                Toast.makeText(context, "準備分享失敗，請稍後再試", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchChooser(context, intent, title)
        }
    }

    /** 分享多張圖片（九宮格 9 張一次丟給選定 App）。 */
    fun shareImages(context: Context, uris: List<Uri>, title: String = DEFAULT_TITLE) {
        val list = ArrayList(uris.mapNotNull { normalize(context, it) })
        if (list.isEmpty()) {
            Toast.makeText(context, "找不到可分享的圖片", Toast.LENGTH_SHORT).show(); return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, title)
    }

    private fun shareText(context: Context, text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        launchChooser(context, intent, title)
    }

    /** content:// 直接用；file:// 需轉 FileProvider（API 24+ 否則 FileUriExposedException）。 */
    private fun normalize(context: Context, uri: Uri): Uri? = try {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        } else uri
    } catch (e: Exception) {
        null
    }

    private fun launchChooser(context: Context, intent: Intent, title: String) {
        try {
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "無法開啟分享：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
