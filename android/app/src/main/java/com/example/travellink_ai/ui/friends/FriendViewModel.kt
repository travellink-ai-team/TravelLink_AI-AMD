package com.example.travellink_ai.ui.friends

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.model.FriendNotification
import com.example.travellink_ai.data.model.FriendProfile
import com.example.travellink_ai.data.model.Friendship
import com.example.travellink_ai.data.model.UserProfile
import com.example.travellink_ai.data.repository.FriendRepository
import com.example.travellink_ai.data.repository.NotificationRepository
import com.example.travellink_ai.data.util.IdentityKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 加好友搜尋狀態。 */
sealed class FriendSearchState {
    object Idle : FriendSearchState()
    object Loading : FriendSearchState()
    data class Found(val profile: FriendProfile) : FriendSearchState()
    object NotFound : FriendSearchState()
    data class Error(val message: String) : FriendSearchState()
}

/** 邀請好友共編清單用的精簡好友資料。 */
data class FriendBrief(val email: String, val name: String, val emoji: String = "🌟")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FriendViewModel @Inject constructor(
    private val friendRepo: FriendRepository,
    private val notificationRepo: NotificationRepository
) : ViewModel() {

    private val myEmailFlow = MutableStateFlow("")
    /** 監聽目前是否處於出錯狀態；bind 時若是，就立刻重新監聽（不等退避時間） */
    @Volatile private var listenFailing = false
    private val restartTick = MutableStateFlow(0)
    private var myName = ""

    private val _friendships = MutableStateFlow<List<Friendship>>(emptyList())
    private val _notifications = MutableStateFlow<List<FriendNotification>>(emptyList())
    val notifications = _notifications.asStateFlow()

    private val _search = MutableStateFlow<FriendSearchState>(FriendSearchState.Idle)
    val search = _search.asStateFlow()

    /** 單次提示訊息（toast）；UI 顯示後呼叫 [consumeToast] 清除。 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    val accepted = _friendships
        .map { list -> list.filter { it.status == "accepted" } }
        .stateInList()

    val incoming = combine(_friendships, myEmailFlow) { list, email ->
        list.filter { it.status == "pending" && it.toEmail == email }
    }.stateInList()

    val outgoing = combine(_friendships, myEmailFlow) { list, email ->
        list.filter { it.status == "pending" && it.fromEmail == email }
    }.stateInList()

    /** 已成立好友的精簡資料（對方 email＋顯示名），供「邀請好友共編」清單使用。 */
    val acceptedFriends = combine(_friendships, myEmailFlow) { list, myEmail ->
        list.filter { it.status == "accepted" }.mapNotNull { f ->
            val other = f.emails.firstOrNull { !it.equals(myEmail, ignoreCase = true) }
                ?: return@mapNotNull null
            val name = f.names[IdentityKeys.identityKey(other)]
                ?: f.names[IdentityKeys.legacyMemberKey(other)]
                ?: other
            FriendBrief(email = other, name = name)
        }
    }.stateInList()

    val unreadCount = _notifications
        .map { list -> list.count { !it.read } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // 錯誤處理放在 flatMapLatest 裡面：過去 catch 在外層，監聽只要出錯一次（例如登出再登入時
        // 權限暫時被拒）整條就結束，清單永遠是空的、也沒有 log，直到 App 重開
        viewModelScope.launch {
            combine(myEmailFlow, restartTick) { email, _ -> email }
                .flatMapLatest { email ->
                    if (email.isBlank()) flowOf(emptyList())
                    else friendRepo.observeFriendships(email).resilient("好友")
                }
                .collect { _friendships.value = it }
        }
        viewModelScope.launch {
            combine(myEmailFlow, restartTick) { email, _ -> email }
                .flatMapLatest { email ->
                    if (email.isBlank()) flowOf(emptyList())
                    else notificationRepo.observeNotifications(email).resilient("好友通知")
                }
                .collect { _notifications.value = it }
        }
    }

    /** 由畫面在登入後綁定目前使用者；email 變動才重啟監聽。 */
    fun bind(profile: UserProfile) {
        myName = profile.name
        if (myEmailFlow.value != profile.email) myEmailFlow.value = profile.email
        else if (listenFailing) restartTick.value++
    }

    /** 出錯時記錄原因並退避重試（最長每分鐘一次），清單保留上一次的內容，不清空 */
    private fun <T> Flow<List<T>>.resilient(what: String): Flow<List<T>> =
        retryWhen { cause, attempt ->
            listenFailing = true
            val wait = minOf(60_000L, 2_000L shl attempt.coerceAtMost(5).toInt())
            Log.w(TAG, "$what 監聽失敗（第 ${attempt + 1} 次），${wait / 1000} 秒後重試：${cause.message}")
            delay(wait)
            true
        }.map { listenFailing = false; it }

    private val me: FriendProfile
        get() = FriendProfile(email = myEmailFlow.value, name = myName)

    // ── 加好友 ──
    fun searchByEmail(rawEmail: String) {
        val q = rawEmail.trim()
        _search.value = FriendSearchState.Idle
        if (q.isEmpty()) { _search.value = FriendSearchState.Error("請輸入 email。"); return }
        if (q.equals(myEmailFlow.value, ignoreCase = true)) {
            _search.value = FriendSearchState.Error("不能加自己為好友。"); return
        }
        _search.value = FriendSearchState.Loading
        viewModelScope.launch {
            _search.value = try {
                friendRepo.findProfileByEmail(q)
                    ?.let { FriendSearchState.Found(it) }
                    ?: FriendSearchState.NotFound
            } catch (e: Exception) {
                FriendSearchState.Error(e.message ?: "搜尋失敗，請稍後再試。")
            }
        }
    }

    fun clearSearch() { _search.value = FriendSearchState.Idle }

    fun sendInvite(target: FriendProfile) {
        viewModelScope.launch {
            try {
                val result = friendRepo.sendInvite(me, target)
                _search.value = FriendSearchState.Idle
                _toast.value = "✉️ 邀請已送出，等待對方確認"
                // F1：好友邀請通知（失敗靜默）
                notificationRepo.push(
                    toEmail = target.email,
                    id = notificationRepo.nid(listOf("friend_invite", result.id, result.cycleId)),
                    type = "friend_invite",
                    fromName = myName
                )
            } catch (e: Exception) {
                _toast.value = e.message ?: "送出失敗，請稍後再試。"
            }
        }
    }

    // ── 待確認邀請 ──
    fun acceptInvite(f: Friendship) {
        viewModelScope.launch {
            try {
                friendRepo.acceptInvite(f.id)
                _toast.value = "🎉 已成為好友！"
                if (f.fromEmail.isNotBlank()) {
                    notificationRepo.push(
                        toEmail = f.fromEmail,
                        id = notificationRepo.nid(listOf("friend_accept", f.id, f.cycleId ?: "legacy")),
                        type = "friend_accept",
                        fromName = myName
                    )
                }
            } catch (e: Exception) {
                _toast.value = "接受失敗：${e.message ?: e}"
            }
        }
    }

    /** kind: reject（拒絕收到）/ cancel（取消已送）/ unfriend（解除好友）。 */
    fun removeFriendship(f: Friendship, kind: String) {
        viewModelScope.launch {
            try {
                friendRepo.removeFriendship(f.id)
                _toast.value = when (kind) {
                    "unfriend" -> "已解除好友"
                    "cancel" -> "已取消邀請"
                    else -> "已拒絕邀請"
                }
            } catch (e: Exception) {
                _toast.value = "操作失敗：${e.message ?: e}"
            }
        }
    }

    // ── 通知 ──
    fun markAllRead() {
        viewModelScope.launch { notificationRepo.markAllRead(myEmailFlow.value, _notifications.value) }
    }

    fun markRead(itemId: String) {
        viewModelScope.launch { notificationRepo.markRead(myEmailFlow.value, itemId) }
    }

    fun consumeToast() { _toast.value = null }

    /** 依 friendship 的 names 對照表取「對方」顯示名稱，退回 email。 */
    fun otherPartyName(f: Friendship): String {
        val myEmail = myEmailFlow.value
        val otherEmail = f.emails.firstOrNull { it != myEmail } ?: f.emails.firstOrNull() ?: ""
        val byId = f.names[com.example.travellink_ai.data.util.IdentityKeys.identityKey(otherEmail)]
        val byLegacy = f.names[com.example.travellink_ai.data.util.IdentityKeys.legacyMemberKey(otherEmail)]
        return (byId ?: byLegacy)?.ifBlank { otherEmail } ?: otherEmail
    }

    fun otherPartyEmail(f: Friendship): String {
        val myEmail = myEmailFlow.value
        return f.emails.firstOrNull { it != myEmail } ?: f.emails.firstOrNull() ?: ""
    }

    private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.stateInList() =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

private const val TAG = "TravelLink_Friends"
