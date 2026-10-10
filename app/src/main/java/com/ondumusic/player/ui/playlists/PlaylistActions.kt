package com.ondumusic.player.ui.playlists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Delete
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

private suspend fun AppContainer.playableEntries(playlistId: UUID): List<QueueItem> =
    queueItems(playlistEntries(library.playlistItems(playlistId), library.tracksForPlaylist(playlistId)), network.isOnline.value)

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
