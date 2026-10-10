package com.ondumusic.player.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.LocalSession
import com.ondumusic.player.ui.components.Action
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.components.TrackRow
import com.ondumusic.player.ui.navigation.LocalNavigator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun PlaylistScreen(id: UUID) {
    val container = LocalContainer.current
    val library = container.library
    val sync = LocalSession.current.sync
    val navigator = LocalNavigator.current
    val playlistId = rememberFollowedPlaylistId(id)
    val playlist by remember(playlistId) { library.observePlaylist(playlistId) }.collectAsStateWithLifecycle(null)
    val entries by remember(playlistId) {
        combine(library.observePlaylistItems(playlistId), library.observeTracksForPlaylist(playlistId), ::playlistEntries)
    }.collectAsStateWithLifecycle(null)
    val online by container.network.isOnline.collectAsStateWithLifecycle()
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val playingItemId = playerState.currentItem?.id
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val reorder = rememberDragReorderState<PlaylistEntry>(listState, keyOf = { it.item.id }) { entry, from, to, original ->
        val position = positionForMove(original.map { it.item.position }, from, to)
        if (position == null) {
            // Positions the client can't order between came from the server; a fresh sync
            // should replace them.
            container.scope.launch { sync.sync() }
            false
        } else {
            container.scope.launch { container.playlists.moveItem(entry.item.id, position, playlistId) }
            true
        }
    }
    LaunchedEffect(reorder, entries) { entries?.let(reorder::update) }
    val rows = reorder.items

    val playable = rows.any { online || it.track.isDownloaded }
    fun play(start: PlaylistEntry? = null, shuffle: Boolean = false) {
        val queue = queueItems(rows, online)
        if (queue.isEmpty()) return
        val player = container.player
        player.requestPlay {
            if (shuffle) player.playShuffledQueue(queue)
            else player.playNowQueue(queue, startingAt = start?.let { s -> queue.indexOfFirst { it.id == s.item.id } }?.coerceAtLeast(0) ?: 0)
        }
    }
    fun remove(entry: PlaylistEntry) = container.scope.launch { container.playlists.removeItem(entry.item.id, playlistId) }

    ScreenScaffold(
        title = playlist?.name.orEmpty(),
        showBack = true,
        actions = {
            IconButton(onClick = { menuOpen = true }, enabled = playlist != null) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val current = playlist ?: return@DropdownMenu
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; dialog = PlaylistDialog.Rename(current) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_move_to_folder)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; dialog = PlaylistDialog.Move(current) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; dialog = PlaylistDialog.Delete(current) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item(key = "header") {
                playlist?.let {
                    PlaylistHeader(it.name, rows.size, playable, onPlay = { play() }, onShuffle = { play(shuffle = true) })
                }
            }
            items(rows, key = { it.item.id }) { entry ->
                val dragging = reorder.draggingKey == entry.item.id
                EntryRow(
                    entry,
                    isCurrent = entry.item.id == playingItemId,
                    dragging = dragging,
                    reorder = reorder,
                    onPlay = { play(start = entry) },
                    onRemove = { remove(entry) },
                    modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offset }
                    else Modifier.animateItem(),
                )
            }
            if (entries?.isEmpty() == true) {
                item(key = "empty") { EmptyState(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.playlist_empty)) }
            }
        }
    }

    PlaylistDialogHost(dialog, onDismiss = { dialog = null }, onDeleted = navigator::pop)
}

@Composable
private fun PlaylistHeader(name: String, count: Int, playable: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            pluralStringResource(R.plurals.song_count, count, count),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, enabled = playable, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(onClick = onShuffle, enabled = playable, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_shuffle))
            }
        }
    }
}

/** A track in the playlist: swipe it away to remove it, or hold the handle to move it. */
@Composable
private fun EntryRow(
    entry: PlaylistEntry,
    isCurrent: Boolean,
    dragging: Boolean,
    reorder: DragReorderState<PlaylistEntry>,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipe = rememberSwipeToDismissBoxState()
    val removeAction = Action(stringResource(R.string.playlist_remove_item), Icons.Outlined.RemoveCircleOutline, destructive = true, onClick = onRemove)
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !dragging,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.playlist_remove), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        // Opaque, so the swipe background doesn't show through; raised while held.
        Surface(
            color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
            shadowElevation = if (dragging) 6.dp else 0.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackRow(
                    entry.track,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    showArtwork = true,
                    showNumber = false,
                    showArtist = true,
                    isCurrent = isCurrent,
                    extraActions = listOf(listOf(removeAction)),
                )
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = stringResource(R.string.playlist_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.dragHandle(reorder, entry.item.id).padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}
