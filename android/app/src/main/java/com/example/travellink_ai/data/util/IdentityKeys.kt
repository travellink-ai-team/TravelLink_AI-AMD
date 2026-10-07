package com.example.travellink_ai.data.util

/**
 * 與網頁端 collab.js 對齊的身分 key。
 *
 * - [identityKey]：正規化 email 的 UTF-8 hex（無碰撞），好友／通知文件路徑一律用它。
 *   對齊網頁 `email.trim().toLowerCase()` 後 `TextEncoder().encode()` 逐 byte `toString(16).padStart(2,'0')`。
 * - [legacyMemberKey]：舊版 memberKey，只保留給既有共編 members schema 與相容判斷。
 */
object IdentityKeys {

    private const val HEX = "0123456789abcdef"

    /** email.trim().lowercase() 的 UTF-8 bytes → 每 byte 兩位小寫 hex；空 email 回 ""。 */
    fun identityKey(email: String): String {
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty()) return ""
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF   // 防 Byte 負值 sign-extend，才能對上網頁 hex
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    /** 舊版 memberKey：小寫後把非 a-z0-9 全換成 '_'。 */
    fun legacyMemberKey(email: String): String =
        email.lowercase().replace(Regex("[^a-z0-9]"), "_")
}
