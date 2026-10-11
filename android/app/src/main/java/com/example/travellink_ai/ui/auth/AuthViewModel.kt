package com.example.travellink_ai.ui.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.R
import com.example.travellink_ai.data.model.UserProfile
import com.example.travellink_ai.data.repository.FriendRepository
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed class AuthState {
    object Loading : AuthState()
    object Unauthenticated : AuthState()
    data class Authenticated(val profile: UserProfile) : AuthState()
}

sealed class AuthError {
    data class Message(val text: String) : AuthError()
    object None : AuthError()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val friendRepo: FriendRepository
) : ViewModel() {

    private val tag = "AuthViewModel"

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState = _authState.asStateFlow()

    private val _error = MutableStateFlow<AuthError>(AuthError.None)
    val error = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init {
        checkCurrentUser()
    }

    private fun checkCurrentUser() {
        val user = auth.currentUser
        if (user == null) {
            _authState.value = AuthState.Unauthenticated
        } else {
            viewModelScope.launch { loadProfile(user.uid) }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _error.value = AuthError.Message("請填寫 Email 和密碼")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = AuthError.None
            try {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
                val uid = auth.currentUser?.uid ?: return@launch
                loadProfile(uid)
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _error.value = AuthError.Message("Email 或密碼錯誤")
            } catch (e: Exception) {
                _error.value = AuthError.Message("登入失敗：${e.message}")
                Log.e(tag, "login failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // 剛建立的新帳號（註冊或第一次 Google 登入）→ MainActivity 引導設定個人喜好（對齊網頁 openPrefWizard）
    private val _isNewUser = MutableStateFlow(false)
    val isNewUser: StateFlow<Boolean> = _isNewUser.asStateFlow()
    fun consumeNewUser() { _isNewUser.value = false }

    fun register(email: String, password: String, name: String) {
        if (email.isBlank() || password.isBlank() || name.isBlank()) {
            _error.value = AuthError.Message("請填寫所有欄位")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = AuthError.None
            try {
                val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
                val uid = result.user?.uid ?: return@launch
                val profile = UserProfile(uid = uid, email = email.trim(), name = name)
                db.collection("users").document(uid)
                    .set(profile.toFirestoreMap() + mapOf(
                        "createdAt" to com.google.firebase.Timestamp.now(),
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    ))
                    .await()
                _isNewUser.value = true
                _authState.value = AuthState.Authenticated(profile)
                runCatching { friendRepo.syncMyProfile(uid, email.trim(), name, profile.emoji) }
            } catch (e: FirebaseAuthWeakPasswordException) {
                _error.value = AuthError.Message("密碼至少需要 6 個字元")
            } catch (e: FirebaseAuthUserCollisionException) {
                _error.value = AuthError.Message("此 Email 已被使用，請直接登入")
            } catch (e: Exception) {
                _error.value = AuthError.Message("註冊失敗：${e.message}")
                Log.e(tag, "register failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _authState.value = AuthState.Unauthenticated
    }

    /**
     * 修改密碼（對齊網頁「修改密碼」）：先用目前密碼重新驗證，再更新為新密碼。
     * 以 callback 回報結果（成功 / 錯誤訊息），不污染登入頁共用的 _error。
     */
    fun changePassword(currentPassword: String, newPassword: String, onResult: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            onResult(false, "尚未登入"); return
        }
        // Google 登入的帳號沒有密碼 provider，無法改密碼
        if (user.providerData.none { it.providerId == "password" }) {
            onResult(false, "此帳號以 Google 登入，無法修改密碼"); return
        }
        if (newPassword.length < 8) {
            onResult(false, "新密碼至少 8 個字元"); return
        }
        viewModelScope.launch {
            try {
                val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, currentPassword)
                user.reauthenticate(credential).await()
                user.updatePassword(newPassword).await()
                onResult(true, null)
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                onResult(false, "目前密碼錯誤")
            } catch (e: Exception) {
                Log.e(tag, "changePassword failed", e)
                onResult(false, "修改失敗：${e.message}")
            }
        }
    }

    fun updateProfile(name: String, emoji: String, interests: List<String>, pace: String, theme: String) {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val updates = mapOf(
                    "name"                  to name,
                    "emoji"                 to emoji,
                    "preferences.interests" to interests,
                    "preferences.pace"      to pace,
                    "preferences.theme"     to theme,
                    "updatedAt"             to com.google.firebase.Timestamp.now()
                )
                db.collection("users").document(uid).update(updates).await()
                val current = (_authState.value as? AuthState.Authenticated)?.profile ?: return@launch
                _authState.value = AuthState.Authenticated(
                    current.copy(name = name, emoji = emoji, interests = interests, pace = pace, theme = theme)
                )
                runCatching { friendRepo.syncMyProfile(uid, current.email, name, emoji) }
            } catch (e: Exception) {
                _error.value = AuthError.Message("更新失敗：${e.message}")
                Log.e(tag, "updateProfile failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = AuthError.None
            try {
                val credentialManager = CredentialManager.create(context)
                val googleOption = GetSignInWithGoogleOption.Builder(
                    context.getString(R.string.web_client_id)
                ).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val result = credentialManager.getCredential(context = context, request = request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
                    val firebaseCred = GoogleAuthProvider.getCredential(googleToken.idToken, null)
                    val authResult = auth.signInWithCredential(firebaseCred).await()
                    val uid = authResult.user?.uid ?: return@launch
                    val email = authResult.user?.email ?: ""
                    val displayName = authResult.user?.displayName ?: email.substringBefore("@")
                    val snap = db.collection("users").document(uid).get().await()
                    val profile = if (snap.exists()) {
                        UserProfile.fromFirestore(snap.data ?: emptyMap())
                    } else {
                        val newProfile = UserProfile(uid = uid, email = email, name = displayName)
                        db.collection("users").document(uid)
                            .set(newProfile.toFirestoreMap() + mapOf(
                                "createdAt" to Timestamp.now(),
                                "updatedAt" to Timestamp.now()
                            ), SetOptions.merge())
                            .await()
                        _isNewUser.value = true
                        newProfile
                    }
                    _authState.value = AuthState.Authenticated(profile)
                    runCatching { friendRepo.syncMyProfile(uid, email, profile.name, profile.emoji) }
                } else {
                    _error.value = AuthError.Message("不支援的 Google 憑證類型")
                }
            } catch (e: GetCredentialException) {
                _error.value = AuthError.Message("Google 登入取消或失敗")
                Log.w(tag, "Google sign-in cancelled/failed", e)
            } catch (e: Exception) {
                _error.value = AuthError.Message("Google 登入失敗：${e.message}")
                Log.e(tag, "Google sign-in error", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() { _error.value = AuthError.None }

    private suspend fun loadProfile(uid: String) {
        try {
            val snap = db.collection("users").document(uid).get().await()
            val profile = if (snap.exists()) {
                UserProfile.fromFirestore(snap.data ?: emptyMap())
            } else {
                val email = auth.currentUser?.email ?: ""
                val fallback = UserProfile(uid = uid, email = email, name = email.substringBefore("@"))
                db.collection("users").document(uid)
                    .set(fallback.toFirestoreMap() + mapOf(
                        "createdAt" to com.google.firebase.Timestamp.now(),
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    ), SetOptions.merge())
                    .await()
                fallback
            }
            _authState.value = AuthState.Authenticated(profile)
            runCatching { friendRepo.syncMyProfile(profile.uid, profile.email, profile.name, profile.emoji) }
        } catch (e: Exception) {
            Log.e(tag, "loadProfile failed", e)
            _authState.value = AuthState.Unauthenticated
        }
    }
}
