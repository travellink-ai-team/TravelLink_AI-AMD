package com.example.travellink_ai.ui.friends

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.example.travellink_ai.ui.theme.DesignTokens

/** 加好友：email 精確搜尋 user_profiles_by_email → 送出邀請。 */
@Composable
fun AddFriendDialog(
    viewModel: FriendViewModel,
    myEmail: String,
    onDismiss: () -> Unit
) {
    val search by viewModel.search.collectAsState()
    var input by remember { mutableStateOf("") }

    fun doSearch() = viewModel.searchByEmail(input)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("關閉", color = DesignTokens.Ink2) }
        },
        title = { Text("➕ 加好友", fontWeight = FontWeight.ExtraBold, color = DesignTokens.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "輸入對方的 email（對方需登入過 App 才能被搜尋到）。",
                    fontSize = 13.sp, color = DesignTokens.Ink2
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("friend@example.com") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { doSearch() },
                        colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                    ) { Text("搜尋") }
                }

                when (val s = search) {
                    is FriendSearchState.Loading ->
                        Text("搜尋中…", fontSize = 13.sp, color = DesignTokens.Ink3)
                    is FriendSearchState.NotFound ->
                        Text("找不到這個 email——對方需先登入過 App。", fontSize = 13.sp, color = DesignTokens.Red)
                    is FriendSearchState.Error ->
                        Text(s.message, fontSize = 13.sp, color = DesignTokens.Red)
                    is FriendSearchState.Found -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DesignTokens.Surface2,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${s.profile.emoji} ${s.profile.name}",
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignTokens.Ink
                                    )
                                    Text(s.profile.email, fontSize = 12.sp, color = DesignTokens.Ink3)
                                }
                                Button(
                                    onClick = {
                                        viewModel.sendInvite(s.profile)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
                                ) { Text("送出邀請") }
                            }
                        }
                    }
                    FriendSearchState.Idle -> Unit
                }
            }
        },
        containerColor = DesignTokens.Surface
    )
}
