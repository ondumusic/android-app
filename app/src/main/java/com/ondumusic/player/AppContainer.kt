package com.ondumusic.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import coil3.SingletonImageLoader
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.auth.AuthService
import com.ondumusic.player.auth.KeystoreTokenStore
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.player.ExoPlaybackEngine
import com.ondumusic.player.player.PlaybackCacheEvictor
import com.ondumusic.player.player.PlaybackStateStore
import com.ondumusic.player.player.PlayerService
import com.ondumusic.player.player.SessionPlayer
import com.ondumusic.player.service.NetworkMonitor
import com.ondumusic.player.service.PlaylistController
import com.ondumusic.player.service.SyncService
import com.ondumusic.player.service.outbox.OutboxService
import com.ondumusic.player.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Services that live as long as one signed-in API session. Rebuilt on sign-in and re-auth. */
class Session(
    val api: ApiClient,
    val sync: SyncService,
)

/**
 * Builds and owns the app's services. Process-wide ones live as long as the app;
 * [session] is rebuilt whenever the signed-in API client changes, the way the iOS app
 * rebuilds its services when `apiService` changes. The player is process-wide on purpose:
 * re-auth hands it a new token rather than replacing it, which would stop the music.
 */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val authPrefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val settings = SettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    val auth = AuthService(authPrefs, KeystoreTokenStore(context), http, scope)
    val library = MusicLibrary(LibraryDatabase.create(context), context.filesDir)
    val network = NetworkMonitor(context)

    val outbox = OutboxService(library, { auth.api.value }, scope, auth::markSessionExpired)

    val playlists = PlaylistController(
        library, outbox, context.getSharedPreferences("playlists", Context.MODE_PRIVATE), scope,
        downloadTracks = { /* Downloads arrive in a later phase. */ },
    )

    private val cacheEvictor = PlaybackCacheEvictor(settings.playbackCacheLimit.value)

    /** Streams cache as they play, up to the size the settings allow, least recently played going first. */
    private val playbackCache = SimpleCache(File(context.cacheDir, "playback"), cacheEvictor, StandaloneDatabaseProvider(context))

    /** Bytes of audio cached from streaming, and how many tracks that covers. */
    fun playbackCacheUsage(): Pair<Long, Int> = playbackCache.cacheSpace to playbackCache.keys.size

    fun clearPlaybackCache() {
        playbackCache.keys.toList().forEach(playbackCache::removeResource)
    }

    private val engine = ExoPlaybackEngine(context, playbackCache) { auth.api.value?.sessionToken }

    val player = PlayerService(
        engine, settings, library,
        PlaybackStateStore(File(context.filesDir, "playback-state.json"), context.getSharedPreferences("playback", Context.MODE_PRIVATE), scope),
        scope,
        PlayerService.Hooks(
            baseUrl = { auth.api.value?.baseUrl },
            recordPlay = { trackId, playedAt -> scope.launch { outbox.enqueuePlay(trackId, playedAt) } },
            onUnauthorized = auth::markSessionExpired,
        ),
    )

    val sessionPlayer = SessionPlayer(player, engine, scope)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        scope.launch { settings.playbackCacheLimit.flow.collect { cacheEvictor.limitBytes = it } }
        scope.launch {
            var hadSession = false
            auth.api.collect { api ->
                if (api != null) {
                    _session.value = makeSession(api)
                    hadSession = true
                    outbox.drainSoon()
                } else {
                    _session.value = null
                    // Signing out discards this account's data on purpose; a fresh launch with
                    // nobody signed in has nothing to discard.
                    if (hadSession) {
                        player.stopAndClear()
                        clearPlaybackCache()
                        SingletonImageLoader.get(appContext).apply {
                            memoryCache?.clear()
                            diskCache?.clear()
                        }
                        player.clearPersistedState()
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
            override fun onStop(owner: LifecycleOwner) = player.savePlaybackPosition()
        })

        network.onRestored = outbox::drainSoon
    }

    private fun makeSession(api: ApiClient): Session {
        val sync = SyncService(
            api, library, settings, syncPrefs, scope,
            SyncService.Hooks(
                flushOutbox = outbox::flush,
                onUnauthorized = auth::markSessionExpired,
            ),
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
        outbox.drainSoon()
        if (session != null && !auth.isRestoringSession.value) {
            scope.launch { session.sync.syncIfNeeded() }
        }
    }
}
