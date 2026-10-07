package com.example.travellink_ai.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import com.example.travellink_ai.ui.theme.DmSerif
import com.example.travellink_ai.ui.theme.DesignTokens
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent      = DesignTokens.Accent
private val AccentLight = DesignTokens.AccentLight

@Composable
fun LoginScreen(viewModel: AuthViewModel) {
    var isLogin by remember { mutableStateOf(true) }
    var email    by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name     by remember { mutableStateOf("") }
    var showPw   by remember { mutableStateOf(false) }

    val isLoading by viewModel.isLoading.collectAsState()
    val error     by viewModel.error.collectAsState()
    val focusMgr  = LocalFocusManager.current
    val context   = LocalContext.current
    val emailFocusRequester = remember { FocusRequester() }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isLogin) {
        viewModel.clearError()
        password = ""
        kotlinx.coroutines.delay(150)
        if (isLogin) {
            emailFocusRequester.requestFocus()
        } else {
            nameFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1B5E4F), Color(0xFF2A6B5E), Color(0xFF4CAF93)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // imePadding 要在 verticalScroll「外面」，可視區才會被鍵盤縮小，
                // 聚焦的欄位才會被自動捲到鍵盤上方（放裡面只會墊空間、不會上移）
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            // Logo 區：🗺 emoji（對齊網頁，無框）
            Text("🗺", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                // 字標對齊網頁襯線標題（.lm-title DM Serif）
                "TravelLinkAI",
                fontFamily = DmSerif,
                fontSize = 30.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-0.5).sp,
                color = Color.White
            )
            Text(
                "台東旅遊智慧規劃",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f)
            )

            Spacer(Modifier.height(36.dp))

            // 卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {

                    // 分頁切換
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5), RoundedCornerShape(10.dp))
                            .padding(4.dp)
                    ) {
                        listOf("登入" to true, "註冊" to false).forEach { (label, isLoginTab) ->
                            Button(
                                onClick = { isLogin = isLoginTab },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isLogin == isLoginTab) Accent else Color.Transparent,
                                    contentColor   = if (isLogin == isLoginTab) Color.White else Color.Gray
                                ),
                                elevation = ButtonDefaults.buttonElevation(0.dp)
                            ) {
                                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // 姓名欄（僅註冊時顯示）
                    AnimatedVisibility(!isLogin) {
                        Column {
                            AuthTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "暱稱",
                                leadingIcon = { Icon(Icons.Default.Person, null, tint = Accent) },
                                imeAction = ImeAction.Next,
                                onImeAction = { focusMgr.moveFocus(FocusDirection.Down) },
                                focusRequester = nameFocusRequester
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    // Email
                    AuthTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = "Email",
                        leadingIcon = { Icon(Icons.Default.Email, null, tint = Accent) },
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        onImeAction = { focusMgr.moveFocus(FocusDirection.Down) },
                        focusRequester = emailFocusRequester
                    )
                    Spacer(Modifier.height(12.dp))

                    // 密碼
                    AuthTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密碼",
                        leadingIcon = { Icon(Icons.Default.Lock, null, tint = Accent) },
                        keyboardType = KeyboardType.Password,
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(
                                    if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null,
                                    tint = Color.Gray
                                )
                            }
                        },
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            focusMgr.clearFocus()
                            if (isLogin) viewModel.login(email, password)
                            else viewModel.register(email, password, name)
                        }
                    )

                    // 錯誤訊息
                    AnimatedVisibility(error is AuthError.Message) {
                        Text(
                            (error as? AuthError.Message)?.text ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            focusMgr.clearFocus()
                            if (isLogin) viewModel.login(email, password)
                            else viewModel.register(email, password, name)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                if (isLogin) "登入" else "建立帳號",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // OR 分隔線
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                        Text("  或  ", fontSize = 13.sp, color = Color.Gray)
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE0E0E0))
                    }

                    Spacer(Modifier.height(12.dp))

                    // Google 登入按鈕
                    OutlinedButton(
                        onClick = {
                            focusMgr.clearFocus()
                            viewModel.signInWithGoogle(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFDDDDDD)),
                        enabled = !isLoading
                    ) {
                        Text("G", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
                        Spacer(Modifier.width(10.dp))
                        Text("以 Google 帳號登入", fontSize = 15.sp, color = Color(0xFF333333))
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF2A6B5E),
            focusedLabelColor  = Color(0xFF2A6B5E),
            cursorColor        = Color(0xFF2A6B5E)
        )
    )
}
