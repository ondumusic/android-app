package com.ondumusic.player

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.auth.AuthService
import com.ondumusic.player.auth.KeystoreTokenStore
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.service.SyncService
import com.ondumusic.player.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Services that live as long as one signed-in API session. Rebuilt on sign-in and re-auth. */
class Session(
    val api: ApiClient,
    val sync: SyncService,
)

/**
 * Builds and owns the app's services. Process-wide ones live as long as the app;
 * [session] is rebuilt whenever the signed-in API client changes, the way the iOS app
 * rebuilds its services when `apiService` changes.
 */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val settings = SettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    val auth = AuthService(authPrefs, KeystoreTokenStore(context), http, scope)
    val library = MusicLibrary(LibraryDatabase.create(context), context.filesDir)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        scope.launch {
            var hadSession = false
            auth.api.collect { api ->
                if (api != null) {
                    _session.value = makeSession(api)
                    hadSession = true
                } else {
                    _session.value = null
                    // Signing out discards this account's data on purpose; a fresh launch with
                    // nobody signed in has nothing to discard.
                    if (hadSession) {
                        SyncService.clearState(syncPrefs)
                        library.clearAll(includingDownloads = true)
                    }
                    hadSession = false
                }
            }
        }
        scope.launch { auth.restoreSession() }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onForeground()
        })
    }

    private fun makeSession(api: ApiClient): Session {
        val sync = SyncService(
            api, library, settings, syncPrefs, scope,
            SyncService.Hooks(onUnauthorized = auth::markSessionExpired),
        )
        return Session(api, sync)
    }

    private fun onForeground() {
        val session = _session.value
        scope.launch {
            if (session == null) {
                // The token may have been unreadable at launch; try again.
                if (!auth.isRestoringSession.value) auth.restoreSession()
            } else {
                auth.validateSession()
            }
        }
        if (session != null && auth.isRestoringSession.value.not()) {
            scope.launch { session.sync.syncIfNeeded() }
        }
    }
}
