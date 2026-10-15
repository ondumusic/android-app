package com.ondumusic.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.Track
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.AlbumActionSheet
import com.ondumusic.player.ui.components.Artwork
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.components.TrackRow
import com.ondumusic.player.ui.downloads.AlbumDownloadButton
import com.ondumusic.player.ui.navigation.LocalNavigator
import java.util.UUID

/** [downloadedOnly] lists only the downloaded songs, and leaves once there are none. */
@Composable
fun AlbumScreen(albumId: UUID, downloadedOnly: Boolean = false) {
    val container = LocalContainer.current
    val library = container.library
    val navigator = LocalNavigator.current
    val album by remember(albumId) { library.observeAlbum(albumId) }.collectAsStateWithLifecycle(null)
    val allTracks by remember(albumId) { library.observeTracksForAlbum(albumId) }.collectAsStateWithLifecycle(null)
    val tracks = if (downloadedOnly) allTracks?.filter { it.isDownloaded } else allTracks
    val online by container.network.isOnline.collectAsStateWithLifecycle()
    var showActions by rememberSaveable { mutableStateOf(false) }

    val available = tracks.orEmpty().filter { online || it.isDownloaded }
    // Only albums that span discs get headings: numbering restarts on each disc, which
    // reads as duplicate rows without something in between.
    val discs = tracks.orEmpty().groupBy { it.disc }.toSortedMap()
    val multiDisc = discs.size > 1
    // A compilation or a split release: the album's artist says nothing about who's on
    // each track, so the rows say it themselves.
    val multipleArtists = tracks.orEmpty().distinctBy { it.artist.id }.size > 1

    if (downloadedOnly && tracks?.isEmpty() == true) LaunchedEffect(Unit) { navigator.pop() }

    fun play(start: Track? = null, shuffle: Boolean = false) {
        if (available.isEmpty()) return
        val player = container.player
        val artwork = album?.artworkUrl
        player.requestPlay {
            if (shuffle) player.playShuffled(available, artwork)
            else player.playNow(available, startingAt = start?.let { available.indexOf(it) }?.coerceAtLeast(0) ?: 0, artworkUrl = artwork)
        }
    }

    ScreenScaffold(
        title = album?.name.orEmpty(),
        showBack = true,
        actions = {
            IconButton(onClick = { showActions = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                album?.let { a ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(a.artwork, a.artworkUrl, ArtworkSize.Large, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f), cornerRadius = 12.dp)
                        Spacer(Modifier.size(16.dp))
                        Text(a.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        a.artistName?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { navigator.showArtist(a.artistId) }.padding(4.dp))
                        }
                        val count = tracks?.size ?: 0
                        val total = allTracks?.size ?: 0
                        val songs = if (count < total) {
                            pluralStringResource(R.plurals.download_songs_of_total, total, count, total)
                        } else {
                            pluralStringResource(R.plurals.song_count, count, count)
                        }
                        Text(
                            listOfNotNull(a.releaseYear?.toString(), songs).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { play() }, enabled = available.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.action_play))
                            }
                            FilledTonalButton(onClick = { play(shuffle = true) }, enabled = available.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Shuffle, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.action_shuffle))
                            }
                            AlbumDownloadButton(a)
                        }
                    }
                }
            }
            discs.forEach { (disc, discTracks) ->
                if (multiDisc) {
                    item(key = "disc-$disc") {
                        Text(stringResource(R.string.disc_number, disc), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                    }
                }
                items(discTracks, key = { it.id }) { track -> TrackRow(track, onClick = { play(start = track) }, showArtist = multipleArtists) }
            }
            if (tracks?.isEmpty() == true) item { EmptyState(Icons.Outlined.MusicNote, stringResource(R.string.no_tracks)) }
        }
    }
    album?.let { if (showActions) AlbumActionSheet(it, onDismiss = { showActions = false }) }
}
