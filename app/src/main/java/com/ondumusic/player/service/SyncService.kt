package com.ondumusic.player.service

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.ApiException
import com.ondumusic.player.api.ErrorMessages
import com.ondumusic.player.api.SUPPORTED_API_VERSION
import com.ondumusic.player.api.ServerInfo
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.ServerCompatibility
import com.ondumusic.player.model.ServerNotice
import com.ondumusic.player.model.TranscodePresetList
import com.ondumusic.player.model.covering
import com.ondumusic.player.model.toNotice
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pages `/sync` into the library. The first sync after sign-in (or a rebuild) clears the
 * tables and pulls everything; later ones send the stored cursor and get only changes.
 */
class SyncService(
    private val api: ApiClient,
    private val library: MusicLibrary,
    private val settings: SettingsStore,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
) {
    /** What sync hands off to the services around it. Defaults do nothing, for tests and before those exist. */
    class Hooks(
        /** Drains queued playlist edits so the server's view reflects them before we pull it back. */
        val flushOutbox: suspend () -> Unit = {},
        val onUnauthorized: () -> Unit = {},
        val downloadTracks: (Set<UUID>) -> Unit = {},
        /**
         * Puts download state back from the files on disk after a sync completes. Told
         * whether it was an initial sync: only then has the whole library just arrived,
         * which is what makes it safe to delete files whose track is gone.
         */
        val reconcileDownloads: suspend (initialSync: Boolean) -> Unit = {},
        val prefetchArtwork: (List<String>) -> Unit = {},
        val localNetworkBlocked: suspend (HttpUrl) -> Boolean = { false },
    )

    data class Progress(val artistCount: Int = 0, val albumCount: Int = 0, val trackCount: Int = 0)

    private val running = AtomicBoolean(false)

    private val _isSyncing = MutableStateFlow(false)
    /** A sync is actually running. */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isShowingActivity = MutableStateFlow(false)
    /**
     * Whether to show one. Held past the end of a quick sync — a delta with nothing to
     * fetch is over before a spinner registers, which reads as the refresh having done
     * nothing — so this is what the UI watches, and [isSyncing] keeps meaning what it says.
     */
    val isShowingActivity: StateFlow<Boolean> = _isShowingActivity.asStateFlow()

    private val _hasCompletedInitialSync = MutableStateFlow(prefs.getString(KEY_CURSOR, null) != null)
    val hasCompletedInitialSync: StateFlow<Boolean> = _hasCompletedInitialSync.asStateFlow()

    private val _lastSyncDate = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    val lastSyncDate: StateFlow<Instant?> = _lastSyncDate.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _error = MutableStateFlow<UiText?>(null)
    val error: StateFlow<UiText?> = _error.asStateFlow()

    private val _compatibility = MutableStateFlow<ServerCompatibility>(ServerCompatibility.Ok)
    /**
     * Whether this build and the server agree about the API. Checked off the back of every
     * sync, since the server can be updated while the app is running.
     */
    val compatibility: StateFlow<ServerCompatibility> = _compatibility.asStateFlow()

    private val _notices = MutableStateFlow<List<ServerNotice>>(emptyList())
    /** What the server is currently reporting. Replaced by every sync, so a cleared one disappears on its own. */
    val notices: StateFlow<List<ServerNotice>> = _notices.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    @Volatile private var isSessionExpired = false
    @Volatile private var syncRun = 0

    private var cursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) = prefs.edit { putString(KEY_CURSOR, value) }

    fun markSessionExpired() {
        isSessionExpired = true
    }

    fun markSessionRestored() {
        isSessionExpired = false
    }

    suspend fun sync() {
        if (isSessionExpired) {
            hooks.onUnauthorized()
            return
        }
        if (!running.compareAndSet(false, true)) return
        // From the start, not from the first page: the server check and the outbox flush
        // are part of what someone pulling to refresh is waiting on.
        _isShowingActivity.value = true
        var synced = false
        try {
            checkServerInfo()
            hooks.flushOutbox()
            synced = true
            runSync()
        } finally {
            running.set(false)
            // runSync clears it on its own schedule; if it never got that far, nothing else will.
            if (!synced) _isShowingActivity.value = false
        }
    }

    /** At most every five minutes — for foregrounding, which happens far more often than the library changes. */
    suspend fun syncIfNeeded() {
        if (running.get() || isSessionExpired) return
        val last = _lastSyncDate.value
        if (last != null && Duration.between(last, Instant.now()) < FOREGROUND_COOLDOWN) return
        sync()
    }

    private suspend fun runSync() {
        val run = ++syncRun
        val startedAt = System.currentTimeMillis()
        _isSyncing.value = true
        _isShowingActivity.value = true
        _progress.value = Progress()
        _error.value = null

        val isInitialSync = cursor == null
        if (isInitialSync) {
            // Downloads survive: the files are still valid, and reconciliation re-marks
            // them from the disk once the tracks come back.
            library.clearAll()
        }

        var pageCursor = cursor
        val artworkUrls = mutableListOf<String>()
        val newTrackIds = mutableSetOf<UUID>()
        val artistIds = HashSet<UUID>()
        val albumIds = HashSet<UUID>()
        val trackIds = HashSet<UUID>()

        try {
            var page = 0
            while (true) {
                page++
                val response = try {
                    api.fetchSync(pageCursor)
                } catch (e: ApiException.Http) {
                    // The server refuses a cursor it can't read rather than ignoring it,
                    // and a stored one that never gets replaced would fail every sync
                    // from here on. Paging from the start again is all upserts, so the
                    // library stays usable while it happens.
                    if (e.code != 400 || pageCursor == null) throw e
                    pageCursor = null
                    cursor = null
                    continue
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "page $page — ${response.artists.size} artists, ${response.albums.size} albums, " +
                        "${response.tracks.size} tracks, ${response.deletions.size} deletions, complete: ${response.complete}")
                }

                library.upsertArtists(response.artists)
                // The one rendition every artist view draws from.
                response.artists.mapNotNullTo(artworkUrls) { it.artwork.covering(ArtworkSize.Medium.pixels)?.url }
                val newAlbumIds = library.upsertAlbums(response.albums)
                newTrackIds += library.upsertTracks(response.tracks)
                library.upsertPlaylists(response.playlists)
                library.upsertPlaylistItems(response.playlistItems)
                library.applyDeletions(response.deletions)

                // Only newly added albums are worth prefetching, in the rendition the grid draws.
                response.albums.filter { it.id in newAlbumIds }
                    .mapNotNullTo(artworkUrls) { it.artwork.covering(ArtworkSize.Medium.pixels)?.url ?: it.resolvedArtworkUrl }

                _notices.value = response.notices.map { it.toNotice() }

                response.artists.mapTo(artistIds) { it.id }
                response.albums.mapTo(albumIds) { it.id }
                response.tracks.mapTo(trackIds) { it.id }
                _progress.value = Progress(artistIds.size, albumIds.size, trackIds.size)

                pageCursor = response.cursor
                if (response.complete) {
                    cursor = pageCursor
                    val now = Instant.now()
                    _lastSyncDate.value = now
                    prefs.edit { putLong(KEY_LAST_SYNC, now.toEpochMilli()) }
                    _hasCompletedInitialSync.value = true
                    hooks.reconcileDownloads(isInitialSync)
                    break
                }
            }
        } catch (e: CancellationException) {
            // Not a failure to report: whoever started this went away. The next sync
            // picks up from the same cursor.
            throw e
        } catch (e: ApiException.Unauthorized) {
            isSessionExpired = true
            hooks.onUnauthorized()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "sync failed", e)
            _error.value = ErrorMessages.sync(e, ErrorMessages.isUnreachable(e) && hooks.localNetworkBlocked(api.baseUrl))
        } finally {
            _isSyncing.value = false
            _progress.value = null
            holdActivity(run, startedAt)
        }

        if (artworkUrls.isNotEmpty()) hooks.prefetchArtwork(artworkUrls)
        queueAutoDownloads(newTrackIds, isInitialSync)
    }

    /**
     * A delta with nothing to fetch finishes in well under the time it takes to notice a
     * spinner. Held to a floor so there's something to see — and tied to this run, so a
     * sync starting during the hold doesn't have its indicator cleared by it.
     */
    private fun holdActivity(run: Int, startedAt: Long) {
        val remaining = MINIMUM_VISIBLE_MILLIS - (System.currentTimeMillis() - startedAt)
        if (remaining <= 0) {
            _isShowingActivity.value = false
            return
        }
        scope.launch {
            delay(remaining)
            if (syncRun == run) _isShowingActivity.value = false
        }
    }

    private suspend fun queueAutoDownloads(newTrackIds: Set<UUID>, isInitialSync: Boolean) {
        // Never on an initial sync: a rebuild or fresh sign-in reinserts the whole library,
        // and "new tracks" means ones added on the server since last time.
        val globalIds = if (settings.autoDownloadNewTracks.value && !isInitialSync) newTrackIds else emptySet()
        if (globalIds.isNotEmpty()) hooks.downloadTracks(globalIds)

        // Anything in an auto-downloaded playlist that isn't downloaded yet. Only what was
        // queued above is subtracted: with the global setting off, a new track that also
        // sits in such a playlist must still be queued here.
        val playlistIds = library.tracksNeedingAutoDownload() - globalIds
        if (playlistIds.isNotEmpty()) hooks.downloadTracks(playlistIds)
    }

    /** Gone for everyone once this returns. A failure leaves the notice showing rather than pretending it worked. */
    suspend fun dismiss(notice: ServerNotice) {
        val remaining = try {
            api.dismissNotice(notice.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _notices.value = remaining.map { it.toNotice() }
    }

    /** A failure is left alone: a server that can't answer `/info` has bigger problems the sync itself will report. */
    private suspend fun checkServerInfo() {
        val info = try {
            api.fetchServerInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        applyServerInfo(info)
    }

    fun applyServerInfo(info: ServerInfo) {
        _compatibility.value = ServerCompatibility.compare(info.apiVersion, info.version, SUPPORTED_API_VERSION)
        _serverVersion.value = info.version
        settings.serverPresets.value = info.presetList ?: TranscodePresetList.builtIn
    }

    /**
     * Throws away the cursor and re-pages the whole library: the way back when the local
     * copy has drifted from the server in a way a delta can't repair. Downloaded files
     * are kept. A field the app newly reads doesn't need this, see
     * [ApiClient.SYNC_SCHEMA_VERSION].
     */
    suspend fun rebuildLibrary() {
        clearState(prefs)
        _hasCompletedInitialSync.value = false
        sync()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val KEY_CURSOR = "syncCursor"
        private const val KEY_LAST_SYNC = "lastSyncDate"
        private val FOREGROUND_COOLDOWN = Duration.ofMinutes(5)
        /** Long enough to register as "it synced" rather than as a flicker. */
        private const val MINIMUM_VISIBLE_MILLIS = 600L

        fun clearState(prefs: SharedPreferences) = prefs.edit {
            remove(KEY_CURSOR)
            remove(KEY_LAST_SYNC)
        }
    }
}
