package com.example.travellink_ai.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
private val Accent      = DesignTokens.Accent
private val AccentLight = DesignTokens.AccentLight

private val EMOJI_OPTIONS = listOf("🌟", "✈️", "🏖️", "🗺️", "🍜", "🎒", "🌄", "🐠", "🌿", "📸")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val authState by authViewModel.authState.collectAsState()
    val isLoading by authViewModel.isLoading.collectAsState()
    val error     by authViewModel.error.collectAsState()

    val profile = (authState as? AuthState.Authenticated)?.profile ?: return

    var name      by remember(profile.name)  { mutableStateOf(profile.name) }
    var emoji     by remember(profile.emoji) { mutableStateOf(profile.emoji) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showChangePwdDialog by remember { mutableStateOf(false) }
    // 是否為 Email/密碼帳號（Google 登入無密碼，不顯示修改密碼）
    val isPasswordAccount = com.google.firebase.auth.FirebaseAuth.getInstance()
        .currentUser?.providerData?.any { it.providerId == "password" } == true

    val isDirty = name != profile.name || emoji != profile.emoji

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("個人資料", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { showSignOutDialog = true }) {
                        Icon(Icons.Default.Logout, null, tint = Color.Gray)
                    }
                    if (isDirty) {
                        TextButton(
                            onClick = {
                                authViewModel.updateProfile(name, emoji, emptyList(), "平衡", "經典旅人")
                            },
                            enabled = !isLoading
                        ) {
                            Text("儲存", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF8F5F0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                // imePadding 放 verticalScroll 外面，聚焦欄位才會自動捲到鍵盤上方
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Emoji 頭像
            ProfileSection("頭像") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(AccentLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 40.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(8.dp)
                    ) {
                        EMOJI_OPTIONS.forEach { e ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (emoji == e) AccentLight else Color(0xFFF0EFEB))
                                    .border(
                                        width = if (emoji == e) 2.dp else 0.dp,
                                        color = if (emoji == e) Accent else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { emoji = e },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(e, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }

            // 名稱
            ProfileSection("暱稱") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        focusedLabelColor  = Accent,
                        cursorColor        = Accent
                    )
                )
                Text(
                    profile.email,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (error is AuthError.Message) {
                Text(
                    (error as AuthError.Message).text,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }

            // 帳號安全：修改密碼（對齊網頁；Google 帳號無密碼故隱藏）
            if (isPasswordAccount) {
                ProfileSection("帳號安全") {
                    OutlinedButton(
                        onClick = { showChangePwdDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("修改密碼", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("登出") },
            text = { Text("確定要登出帳號嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    authViewModel.signOut()
                }) {
                    Text("登出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("取消") }
            }
        )
    }

    if (showChangePwdDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePwdDialog = false },
            onSubmit = { current, new, cb -> authViewModel.changePassword(current, new, cb) }
        )
    }
}

@Composable
private fun PwdField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent
        )
    )
}

/** 修改密碼對話框（對齊網頁：目前密碼 → 新密碼 → 確認新密碼）。 */
@Composable
internal fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String, new: String, onResult: (Boolean, String?) -> Unit) -> Unit
) {
    var current by remember { mutableStateOf("") }
    var newPwd  by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (done) "密碼已更新 ✅" else "修改密碼", fontWeight = FontWeight.Bold) },
        text = {
            if (done) {
                Text("下次登入請使用新密碼。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PwdField(current, { current = it; errorMsg = null }, "目前密碼")
                    PwdField(newPwd, { newPwd = it; errorMsg = null }, "新密碼（至少 8 字元）")
                    PwdField(confirm, { confirm = it; errorMsg = null }, "確認新密碼")
                    errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("完成", color = Accent, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(
                    enabled = !submitting,
                    onClick = {
                        when {
                            current.isBlank() || newPwd.isBlank() -> errorMsg = "請填寫所有欄位"
                            newPwd.length < 8 -> errorMsg = "新密碼至少 8 個字元"
                            newPwd != confirm -> errorMsg = "兩次新密碼不一致"
                            else -> {
                                submitting = true; errorMsg = null
                                onSubmit(current, newPwd) { ok, msg ->
                                    submitting = false
                                    if (ok) done = true else errorMsg = msg
                                }
                            }
                        }
                    }
                ) { Text(if (submitting) "更新中…" else "更新密碼", color = Accent, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (!done) TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            content()
        }
    }
}
