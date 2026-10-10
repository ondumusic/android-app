package com.ondumusic.player.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.clickableRow
import kotlinx.coroutines.launch
import java.util.UUID

/** The square that stands in for a playlist's or folder's artwork. */
@Composable
fun PlaylistIcon(isFolder: Boolean, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(size / 6)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isFolder) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2),
        )
    }
}

enum class NameDialogMode(val title: Int, val confirm: Int) {
    CreatePlaylist(R.string.playlist_new, R.string.playlist_create),
    CreateFolder(R.string.folder_new, R.string.playlist_create),
    Rename(R.string.playlist_rename, R.string.playlist_save),
}

/** Asks for a name to create or rename with. Blank names can't be saved; what's saved is trimmed. */
@Composable
fun PlaylistNameDialog(mode: NameDialogMode, onDismiss: () -> Unit, onSave: (String) -> Unit, initialName: String = "") {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    val trimmed = value.text.trim()
    val focus = remember { FocusRequester() }
    fun save() {
        if (trimmed.isEmpty()) return
        onDismiss()
        onSave(trimmed)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(mode.title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.playlist_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::save, enabled = trimmed.isNotEmpty()) { Text(stringResource(mode.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Picks the folder to move [playlist] into, or the top level. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePlaylistSheet(playlist: Playlist, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val all by remember { container.visiblePlaylists() }.collectAsStateWithLifecycle(null)
    val destinations = remember(all, playlist) { all?.let { moveDestinations(it, playlist) }.orEmpty() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun moveTo(parentId: UUID?) {
        container.scope.launch { container.playlists.movePlaylist(playlist.id, parentId) }
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(if (playlist.isFolder) R.string.folder_move_title else R.string.playlist_move_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.padding(bottom = 8.dp)) {
                item {
                    DestinationRow(
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        name = stringResource(R.string.playlist_move_root),
                        depth = 0,
                        isCurrent = playlist.parentId == null,
                        onClick = { moveTo(null) },
                    )
                }
                items(destinations, key = { it.folder.id }) { destination ->
                    DestinationRow(
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        name = destination.folder.name,
                        depth = destination.depth,
                        isCurrent = destination.folder.id == playlist.parentId,
                        onClick = { moveTo(destination.folder.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationRow(icon: @Composable () -> Unit, name: String, depth: Int, isCurrent: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Box(Modifier.padding(start = (depth * 24).dp)) { icon() } },
        trailingContent = if (isCurrent) {
            { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.playlist_move_current)) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // Already there: nothing to do, as on iOS.
        modifier = if (isCurrent) Modifier else Modifier.clickableRow(onClick),
    )
}

/** The dialogs and sheets the playlist screens open over themselves. */
sealed interface PlaylistDialog {
    data class Create(val isFolder: Boolean, val parentId: UUID?) : PlaylistDialog
    data class Rename(val playlist: Playlist) : PlaylistDialog
    data class Move(val playlist: Playlist) : PlaylistDialog
}

@Composable
fun PlaylistDialogHost(dialog: PlaylistDialog?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val playlists = container.playlists
    // The dialog closes as the action starts, so the work can't live in its scope.
    fun run(block: suspend () -> Unit) = container.scope.launch { block() }
    when (dialog) {
        null -> Unit
        is PlaylistDialog.Create -> PlaylistNameDialog(
            if (dialog.isFolder) NameDialogMode.CreateFolder else NameDialogMode.CreatePlaylist,
            onDismiss = onDismiss,
            onSave = { name ->
                run { if (dialog.isFolder) playlists.createFolder(name, dialog.parentId) else playlists.createPlaylist(name, dialog.parentId) }
            },
        )
        is PlaylistDialog.Rename -> PlaylistNameDialog(
            NameDialogMode.Rename,
            initialName = dialog.playlist.name,
            onDismiss = onDismiss,
            onSave = { name -> run { playlists.renamePlaylist(dialog.playlist.id, name) } },
        )
        is PlaylistDialog.Move -> MovePlaylistSheet(dialog.playlist, onDismiss)
    }
}
