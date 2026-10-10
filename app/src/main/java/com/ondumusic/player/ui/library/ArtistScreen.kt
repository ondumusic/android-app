package com.ondumusic.player.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.AlbumCard
import com.ondumusic.player.ui.components.ArtistActionSheet
import com.ondumusic.player.ui.components.ArtistArtwork
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ArtistScreen(artistId: UUID) {
    val container = LocalContainer.current
    val library = container.library
    val navigator = LocalNavigator.current
    // Observed rather than passed in, so new artwork or a rename shows without leaving the page.
    val artist by remember(artistId) { library.observeArtist(artistId) }.collectAsStateWithLifecycle(null)
    val albums by remember(artistId) { library.observeAlbumsForArtist(artistId) }.collectAsStateWithLifecycle(null)
    var showActions by rememberSaveable { mutableStateOf(false) }
    val artwork = rememberArtworkChanger(artistId)

    fun playAll(shuffle: Boolean) = container.scope.launch {
        val online = container.network.isOnline.value
        val tracks = library.tracksForAlbumArtistInOrder(artistId).filter { online || it.isDownloaded }
        if (tracks.isEmpty()) return@launch
        val player = container.player
        player.requestPlay { if (shuffle) player.playShuffled(tracks) else player.playNow(tracks) }
    }

    ScreenScaffold(
        title = artist?.name.orEmpty(),
        showBack = true,
        actions = {
            IconButton(onClick = { showActions = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistHeader(
                    // Only when there is artwork, or one on its way: adding one lives in the menu.
                    hasArtwork = artist?.artwork?.isNotEmpty() == true || artwork.isUpdating,
                    artwork = {
                        Box(contentAlignment = Alignment.Center) {
                            artist?.let { ArtistArtwork(it, 140.dp) }
                            if (artwork.isUpdating) {
                                Box(Modifier.size(140.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                                }
                            }
                        }
                    },
                    onPlay = { playAll(shuffle = false) },
                    onShuffle = { playAll(shuffle = true) },
                )
            }
            items(albums.orEmpty(), key = { it.id }) { album ->
                AlbumCard(album, onClick = { navigator.push(Route.Album(album.id)) })
            }
            if (albums?.isEmpty() == true) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.Album, stringResource(R.string.no_albums)) }
            }
        }
    }
    artist?.let {
        if (showActions) ArtistActionSheet(it, onDismiss = { showActions = false }, extra = listOf(artwork.actions(hasArtwork = it.artwork.isNotEmpty())))
    }
    artwork.FailureDialog()
}

@Composable
private fun ArtistHeader(hasArtwork: Boolean, artwork: @Composable () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Only when there is artwork: at this size a placeholder glyph says nothing the title doesn't.
        if (hasArtwork) {
            Box(Modifier.padding(vertical = 8.dp)) { artwork() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.play_all))
            }
            FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Shuffle, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.shuffle_all))
            }
        }
    }
}
