package com.ondumusic.player.ui.playlists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import com.ondumusic.player.ui.downloads.Confirmation
import com.ondumusic.player.ui.downloads.LocalDownloadUi
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ondumusic.player.AppContainer
import com.ondumusic.player.R
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.Action
import com.ondumusic.player.ui.components.ActionSheet
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.service.PendingPlaylistEdits
import com.ondumusic.player.ui.components.AppSnackbars
import com.ondumusic.player.ui.components.LocalSnackbars
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Queue items carrying each playlist item's id, so the playing row can be told apart from
 * another occurrence of the same track. Offline, only what's downloaded can play.
 */
fun queueItems(entries: List<PlaylistEntry>, online: Boolean): List<QueueItem> =
    entries.filter { online || it.track.isDownloaded }
        .map { QueueItem(it.track, artworkUrl = it.track.album?.artworkUrl, id = it.item.id) }

/**
 * For adding to a queue that may already hold this playlist: the queue's ids must stay
 * unique, so these don't reuse the item ids.
 */
private fun List<QueueItem>.withFreshIds() = map { it.copy(id = UUID.randomUUID()) }

private suspend fun AppContainer.playableEntries(playlistId: UUID): List<QueueItem> {
    val hidden = playlistEdits.hidden.value
    val items = library.playlistItems(playlistId).filterNot(hidden::hides)
    return queueItems(playlistEntries(items, library.tracksForPlaylist(playlistId)), network.isOnline.value)
}

/** Every playlist and folder, less those deleted while their undo snackbar still shows. */
fun AppContainer.visiblePlaylists(): Flow<List<Playlist>> =
    combine(library.observePlaylists(), playlistEdits.hidden) { all, hidden -> withoutDeleted(all, hidden.playlistIds) }

/** A playlist's songs in order, less those removed while their undo snackbar still shows. */
fun AppContainer.visiblePlaylistEntries(playlistId: UUID): Flow<List<PlaylistEntry>> =
    combine(library.observePlaylistItems(playlistId), library.observeTracksForPlaylist(playlistId), playlistEdits.hidden) { items, tracks, hidden ->
        playlistEntries(items.filterNot(hidden::hides), tracks)
    }

/**
 * Removes a song from its playlist at once, with an Undo snackbar. Nothing is written or
 * sent until the snackbar goes, so undoing leaves no trace on the server.
 */
@Composable
fun rememberRemoveFromPlaylist(): (PlaylistItem) -> Unit {
    val edits = LocalContainer.current.playlistEdits
    val snackbars = LocalSnackbars.current
    val message = stringResource(R.string.playlist_item_removed)
    return remember(edits, snackbars, message) { { item -> snackbars.offerUndo(edits, edits.removeItem(item), message) } }
}

/** Deletes a playlist or folder at once, with an Undo snackbar; held back like a removed song. */
@Composable
fun rememberDeletePlaylist(): (Playlist) -> Unit {
    val edits = LocalContainer.current.playlistEdits
    val snackbars = LocalSnackbars.current
    val playlistMessage = stringResource(R.string.playlist_deleted)
    val folderMessage = stringResource(R.string.folder_deleted)
    return remember(edits, snackbars, playlistMessage, folderMessage) {
        { playlist -> snackbars.offerUndo(edits, edits.deletePlaylist(playlist), if (playlist.isFolder) folderMessage else playlistMessage) }
    }
}

private fun AppSnackbars.offerUndo(edits: PendingPlaylistEdits, token: PendingPlaylistEdits.Token, message: String) =
    showUndo(message, edits.isPending(token), onUndo = { edits.undo(token) }, onExpired = { edits.commit(token) })

/** What a long press or the overflow button offers on a playlist or folder row. */
@Composable
fun PlaylistActionSheet(
    playlist: Playlist,
    childCount: Int,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = LocalContainer.current
    val player = container.player
    // The sheet is gone by the time an action runs, so the work can't live in its scope.
    fun withQueue(block: (List<QueueItem>) -> Unit) = container.scope.launch {
        container.playableEntries(playlist.id).takeIf { it.isNotEmpty() }?.let(block)
    }

    val rename = Action(stringResource(R.string.playlist_rename), Icons.Outlined.Edit, onClick = onRename)
    val groups = if (playlist.isFolder) {
        listOf(
            listOf(rename, Action(stringResource(R.string.folder_move), Icons.AutoMirrored.Outlined.DriveFileMove, onClick = onMove)),
            listOf(Action(stringResource(R.string.folder_delete), Icons.Outlined.Delete, destructive = true, onClick = onDelete)),
        )
    } else {
        listOf(
            listOf(
                Action(stringResource(R.string.action_play), Icons.Outlined.PlayArrow) { withQueue { q -> player.requestPlay { player.playNowQueue(q) } } },
                Action(stringResource(R.string.action_shuffle), Icons.Outlined.Shuffle) { withQueue { q -> player.requestPlay { player.playShuffledQueue(q) } } },
            ),
            listOf(
                Action(stringResource(R.string.action_play_next), Icons.Outlined.SkipNext) { withQueue { player.playNextQueue(it.withFreshIds()) } },
                Action(stringResource(R.string.action_add_to_queue), Icons.AutoMirrored.Outlined.QueueMusic) { withQueue { player.addToEndQueue(it.withFreshIds()) } },
            ),
            listOf(playlistDownloadAction(playlist)),
            listOf(rename, Action(stringResource(R.string.playlist_move_to_folder), Icons.AutoMirrored.Outlined.DriveFileMove, onClick = onMove)),
            listOf(Action(stringResource(R.string.playlist_delete), Icons.Outlined.Delete, destructive = true, onClick = onDelete)),
        )
    }
    ActionSheet(
        title = playlist.name,
        subtitle = if (playlist.isFolder) pluralStringResource(R.plurals.folder_item_count, childCount, childCount)
        else pluralStringResource(R.plurals.song_count, playlist.trackCount, playlist.trackCount),
        groups = groups,
        onDismiss = onDismiss,
        leading = { PlaylistIcon(playlist.isFolder) },
    )
}

/**
 * Downloading a playlist keeps it downloaded: songs added later, here or on another device,
 * download too, until the download is removed.
 */
@Composable
fun playlistDownloadAction(playlist: Playlist): Action {
    val container = LocalContainer.current
    val downloadUi = LocalDownloadUi.current
    return if (playlist.autoDownload) {
        val title = stringResource(R.string.download_remove_title)
        val message = stringResource(R.string.download_remove_playlist_message, playlist.name)
        val confirm = stringResource(R.string.download_remove_confirm)
        val cancel = stringResource(R.string.cancel)
        Action(stringResource(R.string.download_remove), Icons.Outlined.Delete, destructive = true) {
            downloadUi.confirm(Confirmation(title, message, confirm, cancel) {
                container.scope.launch { container.playlists.disableAutoDownloadAndRemove(playlist.id) }
            })
        }
    } else {
        Action(stringResource(R.string.download_action), Icons.Outlined.Download) {
            downloadUi.start { container.playlists.enableAutoDownload(playlist.id) }
        }
    }
}

private val remappedIds = ConcurrentHashMap<UUID, UUID>()
private val collectingRemaps = AtomicBoolean(false)

/**
 * [id], or the server's id for it once a playlist created offline has synced. Remaps are
 * remembered for the whole process, because a screen waiting in a back stack isn't
 * composed when its playlist's id changes.
 */
@Composable
fun rememberFollowedPlaylistId(id: UUID): UUID {
    val container = LocalContainer.current
    if (collectingRemaps.compareAndSet(false, true)) {
        container.scope.launch { container.library.playlistIdRemapped.collect { (temp, real) -> remappedIds[temp] = real } }
    }
    var current by rememberSaveable(id) { mutableStateOf(remappedIds[id] ?: id) }
    LaunchedEffect(container.library) {
        remappedIds[current]?.let { current = it }
        container.library.playlistIdRemapped.collect { (temp, real) -> if (temp == current) current = real }
    }
    return current
}
