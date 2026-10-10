package com.ondumusic.player.service

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.removeDownloads
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.service.outbox.AddItemsPayload
import com.ondumusic.player.service.outbox.MoveItemsPayload
import com.ondumusic.player.service.outbox.OutboxService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-facing façade for playlist mutations: the local store is updated at once and the
 * server-side replay is queued on [OutboxService]. Errors that matter to the user surface
 * via [OutboxService.lastError].
 *
 * Every action is a suspend function that returns once the local write and the enqueue
 * are both done, but the work itself runs on [scope]: a screen that closes mid-action
 * cancels only its wait, never the gap between the write and the enqueue, which would
 * leave a local change the server never hears about. Actions run one at a time, so two
 * quick adds can't both compute their position from the same last item.
 */
class PlaylistController(
    private val musicLibrary: MusicLibrary,
    private val outbox: OutboxService,
    private val prefs: SharedPreferences,
    /** Should live as long as the app. */
    private val scope: CoroutineScope,
    /** Deletes downloads, cancelling any still queued. */
    private val removeDownloads: suspend (Set<UUID>) -> Unit = { musicLibrary.removeDownloads(it) },
    /** Queues downloads; expected to skip tracks already downloading. */
    private val downloadTracks: suspend (Set<UUID>) -> Unit,
) {
    private val lock = Mutex()

    /** Temporary playlist ids and the server's, for undoing an add made before the playlist was confirmed. */
    private val remappedPlaylists = ConcurrentHashMap<UUID, UUID>()

    private val _lastUsedPlaylistId = MutableStateFlow(prefs.getString(KEY_LAST_USED, null)?.let(::uuidOrNull))
    /** Powers the "Add to <name>" shortcut in track/album/artist menus. Set whenever tracks are added to a playlist. */
    val lastUsedPlaylistId: StateFlow<UUID?> = _lastUsedPlaylistId.asStateFlow()

    init {
        // A playlist made here starts under a temporary id; without following it to the
        // server's, the shortcut would point at a playlist that no longer exists.
        scope.launch {
            musicLibrary.playlistIdRemapped.collect { (temp, real) ->
                remappedPlaylists[temp] = real
                if (_lastUsedPlaylistId.value == temp) setLastUsed(real)
            }
        }
    }

    private fun setLastUsed(id: UUID?) {
        _lastUsedPlaylistId.value = id
        prefs.edit { if (id != null) putString(KEY_LAST_USED, id.toString()) else remove(KEY_LAST_USED) }
    }

    private suspend fun <T> perform(action: suspend () -> T): T =
        // Undispatched so actions take the lock in the order they were called.
        scope.async(start = CoroutineStart.UNDISPATCHED) { lock.withLock { action() } }.await()

    /** Marks a playlist as auto-downloaded and starts downloads for any of its tracks not yet downloaded. */
    suspend fun enableAutoDownload(playlistId: UUID) = perform {
        musicLibrary.setPlaylistAutoDownload(playlistId, true)
        val undownloaded = musicLibrary.tracksForPlaylist(playlistId).filterNot { it.isDownloaded }.map { it.id }.toSet()
        if (undownloaded.isNotEmpty()) downloadTracks(undownloaded)
    }

    /**
     * Clears the auto-download flag and removes the playlist's downloaded tracks — all of
     * them, as on iOS, even ones also downloaded with their album.
     */
    suspend fun disableAutoDownloadAndRemove(playlistId: UUID) = perform {
        musicLibrary.setPlaylistAutoDownload(playlistId, false)
        val ids = musicLibrary.tracksForPlaylist(playlistId).map { it.id }.toSet()
        if (ids.isNotEmpty()) removeDownloads(ids)
    }

    // Playlist CRUD

    suspend fun createPlaylist(name: String, parentId: UUID? = null): Playlist? = create(name, parentId, isFolder = false)

    suspend fun createFolder(name: String, parentId: UUID? = null): Playlist? = create(name, parentId, isFolder = true)

    private suspend fun create(name: String, parentId: UUID?, isFolder: Boolean): Playlist? = perform {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@perform null
        val optimistic = Playlist(UUID.randomUUID(), trimmed, 0, Instant.now(), isFolder, parentId)
        musicLibrary.insertLocalPlaylist(optimistic)
        if (isFolder) outbox.enqueueCreateFolder(optimistic.id, trimmed, parentId)
        else outbox.enqueueCreatePlaylist(optimistic.id, trimmed, parentId)
        optimistic
    }

    suspend fun renamePlaylist(id: UUID, name: String) = perform {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@perform
        musicLibrary.renameLocalPlaylist(id, trimmed)
        outbox.enqueueRenamePlaylist(id, trimmed)
    }

    /** Reparents a playlist or folder. Null [parentId] moves it to the root. */
    suspend fun movePlaylist(id: UUID, parentId: UUID?) = perform {
        if (id == parentId) return@perform
        musicLibrary.moveLocalPlaylist(id, parentId)
        outbox.enqueueMovePlaylist(id, parentId)
    }

    suspend fun deletePlaylist(id: UUID) = perform {
        // Before the row goes: a delete that doesn't land server-side comes back on the
        // next sync, and it would come back still wanting to download itself.
        musicLibrary.setPlaylistAutoDownload(id, false)
        musicLibrary.deleteLocalPlaylist(id)
        // If the playlist was never created server-side (its create is still queued),
        // there's nothing to delete remotely.
        val absorbedByPendingCreate = outbox.compactForDeletedPlaylist(id)
        if (!absorbedByPendingCreate) outbox.enqueueDeletePlaylist(id)
        if (_lastUsedPlaylistId.value == id) setLastUsed(null)
    }

    // Items

    suspend fun addTrack(trackId: UUID, playlistId: UUID) = addTracks(listOf(trackId), playlistId)

    /** Returns the items added, for [undoAdd]. */
    suspend fun addTracks(trackIds: List<UUID>, playlistId: UUID): List<PlaylistItem> = perform {
        if (trackIds.isEmpty()) return@perform emptyList()
        var cursor = musicLibrary.lastPlaylistItemPosition(playlistId)
        val entries = ArrayList<AddItemsPayload.Entry>(trackIds.size)
        val added = ArrayList<PlaylistItem>(trackIds.size)
        for (trackId in trackIds) {
            val position = FractionalIndex.after(cursor)
            cursor = position
            val item = PlaylistItem(UUID.randomUUID(), playlistId, trackId, position, Instant.now())
            musicLibrary.insertLocalPlaylistItem(item)
            entries += AddItemsPayload.Entry(item.id, trackId, position)
            added += item
        }
        outbox.enqueueAddItems(playlistId, entries)
        setLastUsed(playlistId)

        if (musicLibrary.playlist(playlistId)?.autoDownload == true) {
            val downloaded = musicLibrary.tracks(trackIds).values.filter { it.isDownloaded }.map { it.id }.toSet()
            val undownloaded = trackIds.toSet() - downloaded
            if (undownloaded.isNotEmpty()) downloadTracks(undownloaded)
        }
        added
    }

    /**
     * Takes back an add. Still queued, the add is simply dropped from the outbox; already
     * sent, the items are removed like any other. Either way they're found by playlist,
     * track and position, since the server may have given the items and the playlist ids
     * of their own meanwhile. Items already removed are skipped.
     */
    suspend fun undoAdd(items: List<PlaylistItem>) = perform {
        for ((originalPlaylist, added) in items.groupBy { it.playlistId }) {
            val playlistId = remappedPlaylists[originalPlaylist] ?: originalPlaylist
            val current = musicLibrary.playlistItems(playlistId)
            val ids = added.mapNotNull { item ->
                current.firstOrNull { it.id == item.id || (it.trackId == item.trackId && it.position == item.position) }?.id
            }
            if (ids.isEmpty()) continue
            ids.forEach { musicLibrary.deleteLocalPlaylistItem(it) }
            val stillNeedsServer = outbox.compactForRemovedItems(ids)
            if (stillNeedsServer.isNotEmpty()) outbox.enqueueRemoveItems(playlistId, stillNeedsServer)
        }
    }

    suspend fun removeItem(itemId: UUID, playlistId: UUID) = perform {
        musicLibrary.deleteLocalPlaylistItem(itemId)
        // If a pending add_items still holds this temp item, it's stripped from that payload
        // and no remove_items is needed — the server never saw the item.
        val stillNeedsServer = outbox.compactForRemovedItems(listOf(itemId))
        if (stillNeedsServer.isNotEmpty()) outbox.enqueueRemoveItems(playlistId, stillNeedsServer)
    }

    /** The caller computes [position] with [FractionalIndex.between] from the two neighbours in the destination order. */
    suspend fun moveItem(itemId: UUID, position: String, playlistId: UUID) = perform {
        musicLibrary.moveLocalPlaylistItem(itemId, position)
        outbox.enqueueMoveItems(playlistId, listOf(MoveItemsPayload.Entry(itemId, position)))
    }

    private companion object {
        const val KEY_LAST_USED = "playlists.lastUsedId"

        fun uuidOrNull(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()
    }
}
