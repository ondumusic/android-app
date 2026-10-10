package com.ondumusic.player.ui.playlists

import androidx.compose.runtime.Composable
import com.ondumusic.player.ui.components.ScreenScaffold
import java.util.UUID

/** The root list, or a folder's contents when [folderId] is set. */
@Composable
fun PlaylistsScreen(actions: @Composable () -> Unit, folderId: UUID? = null) = ScreenScaffold("Playlists", showBack = folderId != null, actions = { actions() }) {}

@Composable
fun PlaylistScreen(id: UUID) = ScreenScaffold("Playlist", showBack = true) {}

@Composable
fun AddToPlaylistSheet(trackIds: List<UUID>, onDismiss: () -> Unit) {}
