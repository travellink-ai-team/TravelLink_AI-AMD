package com.example.travellink_ai.ui.planning

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.data.model.CollabMemberInfo
import com.example.travellink_ai.data.model.PresenceInfo
import com.example.travellink_ai.ui.friends.FriendBrief
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// ── 設計色 ─────────────────────────────────────────────────────
private val CAccent  = DesignTokens.Accent
private val CIndigo  = Color(0xFF6366F1)   // App 專有次要靛色（網頁無對應 token）
private val CSurface = DesignTokens.Surface2
private val CInk     = DesignTokens.Ink
private val CInk2    = DesignTokens.Ink2

// ════════════════════════════════════════════════════════════════
// QR Code 生成工具
// ════════════════════════════════════════════════════════════════

fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bmp
    } catch (e: Exception) { null }
}

// ════════════════════════════════════════════════════════════════
// LINE 分享邀請（對齊網頁 #view-members「💬 透過 LINE 分享邀請」與 buildShareText 文案）
// ════════════════════════════════════════════════════════════════

private const val LINE_PACKAGE = "jp.naver.line.android"
private val LineGreen = Color(0xFF06C755)

fun buildLineInviteText(tripTitle: String, inviteCode: String, shareLink: String): String = buildString {
    append("嗨！我用 TravelLinkAI 規劃了「${tripTitle.ifBlank { "未命名行程" }}」✨\n\n")
    append("邀請碼【 ${inviteCode.ifBlank { "尚未建立" }} 】\n")
    if (shareLink.isNotBlank()) append("加入連結：$shareLink\n")
    append("\n加入後可以一起共編行程。")
}

/** 有裝 LINE 直接開 LINE 分享；沒裝就開 line.me 網頁版（與網頁端退路相同） */
fun shareInviteViaLine(context: android.content.Context, text: String) {
    val direct = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        setPackage(LINE_PACKAGE)
    }
    try {
        context.startActivity(direct)
    } catch (e: android.content.ActivityNotFoundException) {
        val url = "https://line.me/R/msg/text/?" + android.net.Uri.encode(text)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) { }
    }
}

@Composable
fun LineShareButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    Button(
        onClick = { shareInviteViaLine(context, text) },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LineGreen)
    ) {
        Text("💬 透過 LINE 分享邀請", fontWeight = FontWeight.Bold)
    }
}

// ════════════════════════════════════════════════════════════════
// CollabInviteDialog：擁有者邀請旅伴（QR Code + 邀請碼 + 分享連結）
// ════════════════════════════════════════════════════════════════

@Composable
fun CollabInviteDialog(
    shareLink: String,
    joinPin: String,
    onlineMembers: List<PresenceInfo> = emptyList(),
    friends: List<FriendBrief> = emptyList(),                       // 已成立好友
    invitedEmails: Set<String> = emptySet(),                       // 已是本行程成員的 email（顯示「已加入」）
    onInviteFriend: (email: String, name: String) -> Unit = { _, _ -> },
    tripTitle: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(shareLink) { generateQrBitmap(shareLink) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "邀請旅伴共同編輯",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 19.sp,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            // 要能捲動：對話框內容超過螢幕高度時會被裁掉。加了 LINE 分享按鈕後，
            // 最下面的「邀請好友共編」在一般手機上就整段看不到
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                // ── QR Code ────────────────────────────────────
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 4.dp,
                        color = Color.White,
                        modifier = Modifier.size(180.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "共編 QR Code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                        )
                    }
                    Text(
                        "旅伴掃描此 QR Code 即可加入",
                        fontSize = 13.sp,
                        color = Color(0xFF5A5750),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "行程尚未儲存，請先確認行程後再邀請",
                        fontSize = 15.sp,
                        color = Color(0xFFAA4444),
                        textAlign = TextAlign.Center
                    )
                }

                // ── 分隔線 ─────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                    Text(
                        "  或  ",
                        fontSize = 13.sp,
                        color = Color(0xFF9E9E9E),
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                }

                // ── 邀請碼（統一格式，如 8WMV-34ZV）；6 位數 PIN 僅舊行程殘留 ──
                val isPinFormat = joinPin.length == 6 && joinPin.all { it.isDigit() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(if (isPinFormat) "房間密碼" else "邀請碼",
                        fontSize = 14.sp, color = Color(0xFF5A5750),
                        fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CSurface
                    ) {
                        if (joinPin.isNotBlank()) {
                            Text(
                                text = if (isPinFormat)
                                    "${joinPin.take(3)} ${joinPin.drop(3)}" else joinPin,
                                fontSize = if (isPinFormat) 32.sp else 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = if (isPinFormat) 4.sp else 2.sp,
                                color = CIndigo,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = CIndigo
                                )
                            }
                        }
                    }
                    Text(
                        if (isPinFormat) "密碼有效期：48 小時" else "邀請碼長期有效，旅伴輸入即可加入",
                        fontSize = 12.sp,
                        color = Color(0xFF9E9E9E)
                    )
                }

                LineShareButton(
                    text = buildLineInviteText(tripTitle, joinPin, shareLink),
                    enabled = joinPin.isNotBlank()
                )

                // ── 邀請好友（從已成立好友直接邀請共編）─────────────
                HorizontalDivider(color = Color(0xFFE0E0E0))
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "邀請好友共編",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CInk
                    )
                    if (friends.isEmpty()) {
                        Text(
                            "還沒有好友。到側欄「好友」加好友後，就能一鍵邀請共編。",
                            fontSize = 12.sp,
                            color = CInk2
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            friends.forEach { friend ->
                                val joined = friend.email.lowercase() in invitedEmails
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE8F3F0)),
                                        contentAlignment = Alignment.Center
                                    ) { Text(friend.emoji, fontSize = 18.sp) }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(friend.name, fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold, color = CInk)
                                        Text(friend.email, fontSize = 11.sp, color = CInk2)
                                    }
                                    if (joined) {
                                        Text("已加入", fontSize = 12.sp, color = CAccent,
                                            fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Button(
                                            onClick = { onInviteFriend(friend.email, friend.name) },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = CAccent)
                                        ) { Text("邀請", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 分享連結按鈕（保留原有功能）
            Button(
                onClick = {
                    if (shareLink.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareLink)
                        }
                        context.startActivity(Intent.createChooser(intent, "邀請旅伴"))
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CIndigo),
                shape = RoundedCornerShape(12.dp),
                enabled = shareLink.isNotBlank()
            ) {
                Text("分享連結", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        }
    )
}

// ════════════════════════════════════════════════════════════════
// OnlineMembersDialog：點擊「👥 N 人在線」徽章時顯示的成員清單
// 若 isOwner=true，可點擊成員切換角色（editor ↔ viewer）
// ════════════════════════════════════════════════════════════════

@Composable
fun OnlineMembersDialog(
    members:        List<CollabMemberInfo>,
    myUserId:       String,
    isOwner:        Boolean = false,
    onRoleChange:   (uid: String, newRole: String) -> Unit = { _, _ -> },
    onKick:         ((uid: String) -> Unit)? = null,   // owner 踢出成員（null 隱藏按鈕）
    onLeave:        (() -> Unit)? = null,              // 非 owner 退出共編（null 隱藏按鈕）
    onDismiss:      () -> Unit,
    // 相容舊呼叫（PresenceInfo）
    legacyMembers:  List<PresenceInfo> = emptyList()
) {
    var kickTarget by remember { mutableStateOf<CollabMemberInfo?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    kickTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text("移除成員", fontWeight = FontWeight.ExtraBold) },
            text  = { Text("確定要將「${target.displayName}」移出共編嗎？") },
            confirmButton = {
                TextButton(
                    onClick = { onKick?.invoke(target.uid); kickTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) { Text("移除", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { kickTarget = null }) { Text("取消") }
            }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("退出共編", fontWeight = FontWeight.ExtraBold) },
            text  = { Text("確定要退出此行程的共同編輯嗎？退出後需要重新取得邀請才能加入。") },
            confirmButton = {
                TextButton(
                    onClick = { showLeaveConfirm = false; onLeave?.invoke() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) { Text("退出", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("取消") }
            }
        )
    }
    val effectiveMembers = if (members.isNotEmpty()) members else legacyMembers.map {
        CollabMemberInfo(it.uid, it.displayName, "🌟", "viewer", it.isOnline)
    }
    val sorted = remember(effectiveMembers) {
        effectiveMembers.sortedWith(
            compareByDescending<CollabMemberInfo> { it.uid == myUserId }.thenBy { it.displayName }
        )
    }

    fun roleLabel(role: String) = when (role) {
        "owner"  -> "擁有者"
        "editor" -> "可編輯"
        else     -> "唯讀"
    }
    fun roleColor(role: String) = when (role) {
        "owner"  -> Color(0xFFF59E0B)
        "editor" -> CAccent
        else     -> CInk2
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape            = RoundedCornerShape(20.dp),
        containerColor   = Color.White,
        title = {
            Column {
                Text(
                    "共編成員",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color      = CInk
                )
                Text(
                    "${effectiveMembers.size} 位旅伴${if (isOwner) "・點擊可調整角色" else ""}",
                    fontSize = 13.sp,
                    color    = CInk2
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sorted.forEach { member ->
                    val isMe = member.uid == myUserId
                    val canToggle = isOwner && !isMe && member.role != "owner"
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (canToggle) Modifier.clickable {
                                val next = if (member.role == "editor") "viewer" else "editor"
                                onRoleChange(member.uid, next)
                            } else Modifier)
                            .padding(vertical = 4.dp)
                    ) {
                        // 頭像（emoji）
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isMe) CIndigo.copy(alpha = 0.15f) else Color(0xFFE8F3F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(member.emoji, fontSize = 20.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment    = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    member.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color      = CInk
                                )
                                if (isMe) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = CIndigo.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            "我",
                                            modifier   = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            fontSize = 11.sp,
                                            color      = CIndigo,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // 角色標籤（owner 可點擊切換）
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = roleColor(member.role).copy(alpha = 0.12f)
                        ) {
                            Text(
                                text     = roleLabel(member.role) + if (canToggle) " ›" else "",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 12.sp,
                                color    = roleColor(member.role),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 踢出按鈕（owner 對非 owner 成員）
                        if (canToggle && onKick != null) {
                            IconButton(
                                onClick  = { kickTarget = member },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.PersonRemove,
                                    contentDescription = "移除成員",
                                    tint = Color(0xFFDC2626).copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (member != sorted.last()) {
                        HorizontalDivider(color = Color(0xFFEEEEEE))
                    }
                }
            }
        },
        confirmButton = {
            // 非 owner 可退出共編
            if (!isOwner && onLeave != null) {
                TextButton(
                    onClick = { showLeaveConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text("退出共編", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
