package com.ondumusic.player.ui.playlists

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.LibraryRefresh
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.components.rowGestures
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.ScrollToTopOnReselect
import com.ondumusic.player.ui.navigation.Tab
import java.util.UUID

/** The root list, or a folder's contents when [folderId] is set. */
@Composable
fun PlaylistsScreen(actions: @Composable () -> Unit, folderId: UUID? = null) {
    val library = LocalContainer.current.library
    val navigator = LocalNavigator.current
    val parentId = folderId?.let { rememberFollowedPlaylistId(it) }
    val all by remember { library.observePlaylists() }.collectAsStateWithLifecycle(null)
    val entries = remember(all, parentId) { all?.let { childrenOf(it, parentId) } }
    val counts = remember(all) { childCounts(all.orEmpty()) }
    val folder = remember(all, parentId) { parentId?.let { id -> all?.find { it.id == id } } }
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var actionsFor by remember { mutableStateOf<Playlist?>(null) }
    val listState = rememberLazyListState()
    if (folderId == null) ScrollToTopOnReselect(Tab.Playlists, listState)

    ScreenScaffold(
        title = if (folderId == null) stringResource(R.string.tab_playlists) else folder?.name.orEmpty(),
        showBack = folderId != null,
        actions = {
            CreateMenu(onCreate = { isFolder -> dialog = PlaylistDialog.Create(isFolder, parentId) })
            if (folderId == null) actions()
        },
    ) { padding ->
        LibraryRefresh(Modifier.padding(top = padding.calculateTopPadding())) {
            if (entries?.isEmpty() == true) {
                EmptyState(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(if (folderId == null) R.string.no_playlists else R.string.folder_empty))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                items(entries.orEmpty(), key = { it.id }) { entry ->
                    PlaylistRow(
                        entry,
                        childCount = counts[entry.id] ?: 0,
                        onClick = { navigator.push(if (entry.isFolder) Route.Folder(entry.id) else Route.Playlist(entry.id)) },
                        onActions = { actionsFor = entry },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    actionsFor?.let { entry ->
        PlaylistActionSheet(
            entry,
            childCount = counts[entry.id] ?: 0,
            onDismiss = { actionsFor = null },
            onRename = { dialog = PlaylistDialog.Rename(entry) },
            onMove = { dialog = PlaylistDialog.Move(entry) },
            onDelete = { dialog = PlaylistDialog.Delete(entry) },
        )
    }
    PlaylistDialogHost(dialog, onDismiss = { dialog = null })
}

@Composable
private fun CreateMenu(onCreate: (isFolder: Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.playlist_create_menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.playlist_new)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = null) },
            onClick = { open = false; onCreate(false) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.folder_new)) },
            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            onClick = { open = false; onCreate(true) },
        )
    }
}

@Composable
private fun PlaylistRow(entry: Playlist, childCount: Int, onClick: () -> Unit, onActions: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (entry.isFolder) pluralStringResource(R.plurals.folder_item_count, childCount, childCount)
                else pluralStringResource(R.plurals.song_count, entry.trackCount, entry.trackCount)
            )
        },
        leadingContent = { PlaylistIcon(entry.isFolder) },
        trailingContent = {
            IconButton(onClick = onActions) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.rowGestures(onClick = onClick, onLongClick = onActions),
    )
}

@Composable
fun PlaylistScreen(id: UUID) = ScreenScaffold("Playlist", showBack = true) {}

@Composable
fun AddToPlaylistSheet(trackIds: List<UUID>, onDismiss: () -> Unit) {}

