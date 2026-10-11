package com.example.travellink_ai.data.local

import android.content.Context
import java.util.UUID

/**
 * 管理設備級別的協作身份（取代 Firebase Auth）：
 * - userId：首次啟動時自動生成 UUID，永久儲存在 SharedPreferences
 * - displayName：使用者在共編邀請時設定，顯示給其他旅伴
 */
class CollabIdentityManager(context: Context) {
    private val prefs = context.getSharedPreferences("collab_identity", Context.MODE_PRIVATE)

    /** 設備唯一 ID（永不改變） */
    val userId: String
        get() {
            var id = prefs.getString("userId", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("userId", id).apply()
            }
            return id
        }

    /** 共編顯示名稱（唯讀；透過 setDisplayName() 變更） */
    val displayName: String
        get() = prefs.getString("displayName", "旅伴") ?: "旅伴"

    /** 是否曾主動設定過顯示名稱（false = 使用預設「旅伴」，加入共編時提示設定） */
    val isDisplayNameCustomized: Boolean
        get() = prefs.getBoolean("displayNameCustomized", false)

    /** 設定顯示名稱，並標記已主動設定 */
    fun setDisplayName(name: String) {
        prefs.edit()
            .putString("displayName", name.trim().ifBlank { "旅伴" })
            .putBoolean("displayNameCustomized", true)
            .apply()
    }
}
