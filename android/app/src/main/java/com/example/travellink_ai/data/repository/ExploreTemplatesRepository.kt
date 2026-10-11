package com.example.travellink_ai.data.repository

import android.content.Context
import android.util.Log
import com.example.travellink_ai.data.model.ExploreTemplates
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 官方精選範本來源：
 *   ① Firestore `explore_templates/current`（scripts/upload_explore_templates.py 上傳，網頁景點更新後重跑即可）
 *   ② 讀不到（離線、規則未部署、還沒上傳）就用 App 內建的 assets/explore_templates.json
 * 兩者都是 scripts/export_explore_templates.mjs 用網頁自己的程式產生的同一份格式。
 */
@Singleton
class ExploreTemplatesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FirebaseFirestore
) {
    private val tag = "TravelLink_Debug"
    @Volatile private var cached: ExploreTemplates? = null

    suspend fun load(): ExploreTemplates? {
        cached?.let { return it }
        val remote = withTimeoutOrNull(4_000) {
            try {
                val data = db.collection("explore_templates").document("current").get().await().data
                    ?: return@withTimeoutOrNull null
                ExploreTemplates.parse(JSONObject(data).toString())
            } catch (e: Exception) {
                Log.w(tag, "⚠️ 雲端精選範本讀取失敗，改用內建：${e.message}")
                null
            }
        }
        val result = remote ?: withContext(Dispatchers.IO) {
            try {
                context.assets.open("explore_templates.json").bufferedReader(Charsets.UTF_8)
                    .use { ExploreTemplates.parse(it.readText()) }
            } catch (e: Exception) {
                Log.e(tag, "❌ 內建精選範本讀取失敗：${e.message}")
                null
            }
        }
        cached = result
        return result
    }
}
