package com.ondumusic.player.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.clickableRow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Adds [trackIds] to a playlist picked from every playlist in the library, or to one made
 * on the spot. Serves tracks, albums and artists alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(trackIds: List<UUID>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val playlists = container.playlists
    val all by remember { container.library.observePlaylists() }.collectAsStateWithLifecycle(null)
    val targets = remember(all) { all?.let(::addTargets).orEmpty() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }

    fun close() {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }
    // The sheet closes as the add starts, so the work can't live in its scope.
    fun addTo(playlistId: UUID) {
        container.scope.launch { playlists.addTracks(trackIds, playlistId) }
        close()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.playlist_add_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.padding(bottom = 8.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.playlist_new_ellipsis)) },
                        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow { creating = true },
                    )
                }
                if (targets.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(
                            pluralStringResource(R.plurals.playlist_add_songs_to, trackIds.size, trackIds.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(targets, key = { it.playlist.id }) { target ->
                    ListItem(
                        headlineContent = { Text(target.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = target.folderPath.takeIf { it.isNotEmpty() }?.let { path ->
                            { Text(path.joinToString(FOLDER_PATH_SEPARATOR), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = { PlaylistIcon(isFolder = false, size = 40.dp) },
                        trailingContent = {
                            Text(target.playlist.trackCount.toString(), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickableRow { addTo(target.playlist.id) },
                    )
                }
            }
        }
    }

    if (creating) {
        PlaylistNameDialog(
            NameDialogMode.CreatePlaylist,
            onDismiss = { creating = false },
            onSave = { name ->
                container.scope.launch {
                    playlists.createPlaylist(name)?.let { playlists.addTracks(trackIds, it.id) }
                }
                close()
            },
        )
    }
}
