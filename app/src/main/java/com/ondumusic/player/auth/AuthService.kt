package com.ondumusic.player.auth

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.ApiException
import com.ondumusic.player.api.ErrorMessages
import com.ondumusic.player.model.User
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class AuthService(
    private val prefs: SharedPreferences,
    private val tokens: TokenStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Whether reaching this server needs local network access that Ondu doesn't have. */
    private val localNetworkBlocked: suspend (HttpUrl) -> Boolean = { false },
) {
    private val _api = MutableStateFlow<ApiClient?>(null)
    /** Non-null while signed in, including offline: a stored session is used until the server rejects it. */
    val api: StateFlow<ApiClient?> = _api.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _isSessionExpired = MutableStateFlow(false)
    val isSessionExpired: StateFlow<Boolean> = _isSessionExpired.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    private val _isRestoringSession = MutableStateFlow(true)
    /** True until the stored session has been read back, so the login screen doesn't flash before we know. */
    val isRestoringSession: StateFlow<Boolean> = _isRestoringSession.asStateFlow()

    val storedServerUrl: String? get() = prefs.getString(KEY_SERVER_URL, null)
    val storedUsername: String? get() = prefs.getString(KEY_USERNAME, null)

    /** Reads the stored session back at launch and validates it in the background — the UI must not wait on the network. */
    suspend fun restoreSession() {
        try {
            val baseUrl = storedServerUrl?.toHttpUrlOrNull() ?: return
            when (val outcome = withContext(Dispatchers.IO) { tokens.load() }) {
                is TokenStore.LoadOutcome.Found -> {
                    _api.value = ApiClient(baseUrl, outcome.token, http)
                    scope.launch { validateSession() }
                }
                TokenStore.LoadOutcome.Missing -> Unit
                is TokenStore.LoadOutcome.Failed -> {
                    // The session may well be intact — say so rather than presenting a login
                    // screen that implies the user was signed out.
                    if (BuildConfig.DEBUG) Log.d(TAG, "token read failed", outcome.error)
                    _error.value = UiText.Res(R.string.error_stored_sign_in_unreadable)
                }
            }
        } finally {
            _isRestoringSession.value = false
        }
    }

    suspend fun login(baseUrl: HttpUrl, username: String, password: String) {
        _isLoading.value = true
        _error.value = null
        try {
            val response = ApiClient.login(http, baseUrl, username, password)
            val token = response.sessionToken
            val user = response.currentUser
            if (response.ok && token != null && user != null) {
                prefs.edit {
                    putString(KEY_SERVER_URL, baseUrl.toString())
                    putString(KEY_USERNAME, username)
                }
                withContext(Dispatchers.IO) { tokens.save(token) }
                _currentUser.value = user
                _api.value = ApiClient(baseUrl, token, http)
            } else {
                _error.value = response.fields?.password?.message?.let(UiText::Raw) ?: UiText.Res(R.string.error_login_failed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = ErrorMessages.login(e, ErrorMessages.isUnreachable(e) && localNetworkBlocked(baseUrl))
        } finally {
            _isLoading.value = false
        }
    }

    /** Signs back in as the stored user. Never logs out on failure: that would cost the user their library. */
    suspend fun reAuthenticate(password: String): Boolean {
        val baseUrl = storedServerUrl?.toHttpUrlOrNull() ?: return false
        val username = storedUsername ?: return false
        _isLoading.value = true
        _error.value = null
        try {
            val response = ApiClient.login(http, baseUrl, username, password)
            val token = response.sessionToken
            val user = response.currentUser
            if (response.ok && token != null && user != null) {
                withContext(Dispatchers.IO) { tokens.save(token) }
                _currentUser.value = user
                _api.value = ApiClient(baseUrl, token, http)
                _isSessionExpired.value = false
                return true
            }
            _error.value = response.fields?.password?.message?.let(UiText::Raw) ?: UiText.Res(R.string.error_reauth_failed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = ErrorMessages.login(e, ErrorMessages.isUnreachable(e) && localNetworkBlocked(baseUrl))
        } finally {
            _isLoading.value = false
        }
        return false
    }

    /** Server URL and username are kept to prefill the login form. */
    fun logout() {
        _api.value?.let { api -> scope.launch { api.deleteSession() } }
        tokens.clear()
        _currentUser.value = null
        _api.value = null
        _isSessionExpired.value = false
        _isOffline.value = false
    }

    /** Anything that gets a 401 calls this. It asks for the password again; it never signs out. */
    fun markSessionExpired() {
        _isSessionExpired.value = true
    }

    fun clearError() {
        _error.value = null
    }

    suspend fun validateSession() {
        val api = _api.value ?: return
        try {
            _currentUser.value = api.fetchCurrentUser()
            _isOffline.value = false
            _isSessionExpired.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.Unauthorized) {
            _isSessionExpired.value = true
        } catch (e: Exception) {
            // Network or server trouble — stay signed in for offline playback.
            _isOffline.value = true
        }
    }

    private companion object {
        const val TAG = "AuthService"
        const val KEY_SERVER_URL = "serverURL"
        const val KEY_USERNAME = "username"
    }
}
