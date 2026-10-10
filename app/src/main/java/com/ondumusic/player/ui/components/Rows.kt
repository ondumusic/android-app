package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.Track
import com.ondumusic.player.ui.LocalContainer

/**
 * One track. [isCurrent] defaults to matching the playing track's id, which is wrong for
 * lists that can hold the same track twice (playlists, the queue) — those pass it in.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
    showNumber: Boolean = true,
    showArtist: Boolean = false,
    isCurrent: Boolean? = null,
    extraActions: List<List<Action>> = emptyList(),
) {
    val container = LocalContainer.current
    val state by container.player.state.collectAsStateWithLifecycle()
    val online by container.network.isOnline.collectAsStateWithLifecycle()
    val current = isCurrent ?: (state.currentTrack?.id == track.id)
    val available = online || track.isDownloaded
    var showActions by rememberSaveable { mutableStateOf(false) }

    val dim = if (available) 1f else 0.38f
    ListItem(
        headlineContent = {
            Text(
                track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = if (showArtist) {
            { Text(track.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = when {
            showArtwork -> {
                { Artwork(track.album?.artworkUrl, Modifier.size(48.dp), cornerRadius = 4.dp) }
            }
            showNumber -> {
                {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
                        if (current) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.now_playing),
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            Text(track.number?.toString() ?: "–", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                        }
                    }
                }
            }
            else -> null
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (track.isDownloaded) {
                    Icon(Icons.Filled.DownloadForOffline, contentDescription = stringResource(R.string.downloaded),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Text(track.formattedDuration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { showActions = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.alpha(dim).rowGestures(enabled = available, onClick = onClick, onLongClick = { showActions = true }),
    )
    if (showActions) TrackActionSheet(track, onDismiss = { showActions = false }, extra = extraActions)
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier, albumCount: Int? = artist.albumCount) {
    var showActions by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = albumCount?.let { { Text(pluralStringResource(R.plurals.album_count, it, it)) } },
        leadingContent = { ArtistArtwork(artist, 48.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.rowGestures(onClick = onClick, onLongClick = { showActions = true }),
    )
    if (showActions) ArtistActionSheet(artist, onDismiss = { showActions = false })
}

/** A cover with its title and year beneath, for grids. */
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = album.releaseYear?.toString()) {
    var showActions by remember { mutableStateOf(false) }
    Column(modifier.rowGestures(onClick = onClick, onLongClick = { showActions = true }).padding(bottom = 4.dp)) {
        Artwork(album.artwork, album.artworkUrl, ArtworkSize.Medium, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        // A blank line rather than nothing, so every card in a row is the same height.
        Text(subtitle ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showActions) AlbumActionSheet(album, onDismiss = { showActions = false })
}
