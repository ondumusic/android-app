package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.Track
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.downloads.albumDownloadActions
import com.ondumusic.player.ui.downloads.artistDownloadActions
import com.ondumusic.player.ui.downloads.trackDownloadActions
import com.ondumusic.player.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Queue actions shared by everything that resolves to a list of tracks. [tracks] is called
 * when an action runs, not when the sheet opens — for an album or artist that's a query.
 */
@Composable
private fun queueActions(tracks: suspend () -> List<Track>, artworkUrl: String? = null, shuffle: Boolean): List<List<Action>> {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val player = container.player
    val lastUsedId by container.playlists.lastUsedPlaylistId.collectAsStateWithLifecycle()
    val lastUsed by produceState<Playlist?>(null, lastUsedId) {
        value = lastUsedId?.let { container.library.playlist(it) }
    }
    // The sheet is gone by the time an action runs, so the work can't live in its scope.
    // Offline, only what's downloaded can play.
    fun withTracks(block: (List<Track>) -> Unit) = container.scope.launch {
        val online = container.network.isOnline.value
        tracks().filter { online || it.isDownloaded }.takeIf { it.isNotEmpty() }?.let(block)
    }
    // A playlist holds the music whether or not it can play right now.
    fun withTrackIds(block: suspend (List<UUID>) -> Unit) = container.scope.launch {
        tracks().map { it.id }.takeIf { it.isNotEmpty() }?.let { block(it) }
    }

    val play = Action(stringResource(R.string.action_play), Icons.Outlined.PlayArrow) {
        withTracks { player.requestPlay { player.playNow(it, artworkUrl = artworkUrl) } }
    }
    val shuffleAction = Action(stringResource(R.string.action_shuffle), Icons.Outlined.Shuffle) {
        withTracks { player.requestPlay { player.playShuffled(it, artworkUrl = artworkUrl) } }
    }
    val playNext = Action(stringResource(R.string.action_play_next), Icons.Outlined.SkipNext) {
        withTracks { player.playNext(it, artworkUrl = artworkUrl) }
    }
    val addToQueue = Action(stringResource(R.string.action_add_to_queue), Icons.AutoMirrored.Outlined.QueueMusic) {
        withTracks { player.addToEnd(it, artworkUrl = artworkUrl) }
    }
    val addToLast = lastUsed?.takeIf { !it.isFolder }?.let { playlist ->
        Action(stringResource(R.string.action_add_to_named, playlist.name), Icons.Outlined.AddCircleOutline) {
            withTrackIds { container.playlists.addTracks(it, playlist.id) }
        }
    }
    val addToPlaylist = Action(stringResource(R.string.action_add_to_playlist), Icons.AutoMirrored.Outlined.PlaylistAdd) {
        withTrackIds { navigator.addToPlaylist(it) }
    }
    return listOf(
        listOfNotNull(play, shuffleAction.takeIf { shuffle }),
        listOfNotNull(playNext, addToQueue, addToLast, addToPlaylist),
    )
}

@Composable
fun TrackActionSheet(track: Track, onDismiss: () -> Unit, onPlay: (() -> Unit)? = null, extra: List<List<Action>> = emptyList()) {
    val navigator = LocalNavigator.current
    val queue = queueActions({ listOf(track) }, shuffle = false)
    val play = onPlay?.let { listOf(Action(stringResource(R.string.action_play), Icons.Outlined.PlayArrow, onClick = it)) }
    val goTo = listOfNotNull(
        Action(stringResource(R.string.action_go_to_artist), Icons.Outlined.Person) { navigator.showArtist(track.albumArtistId) },
        track.album?.let { Action(stringResource(R.string.action_go_to_album), Icons.Outlined.Album) { navigator.showAlbum(it.id) } },
    )
    ActionSheet(
        title = track.name,
        subtitle = listOfNotNull(track.artist.name, track.album?.name).joinToString(" · "),
        groups = listOfNotNull(play) + queue.drop(1) + listOf(goTo, trackDownloadActions(track)) + extra,
        onDismiss = onDismiss,
        leading = { Artwork(track.album?.artworkUrl, Modifier.size(48.dp), cornerRadius = 4.dp) },
    )
}

@Composable
fun AlbumActionSheet(album: Album, onDismiss: () -> Unit) {
    val library = LocalContainer.current.library
    val navigator = LocalNavigator.current
    val goToArtist = Action(stringResource(R.string.action_go_to_artist), Icons.Outlined.Person) { navigator.showArtist(album.artistId) }
    ActionSheet(
        title = album.name,
        subtitle = listOfNotNull(album.artistName, album.releaseYear?.toString()).joinToString(" · "),
        groups = queueActions({ library.tracksForAlbum(album.id) }, album.artworkUrl, shuffle = true) +
            listOf(listOf(goToArtist), albumDownloadActions(album)),
        onDismiss = onDismiss,
        leading = { Artwork(album.artwork, album.artworkUrl, ArtworkSize.Thumbnail, Modifier.size(48.dp), cornerRadius = 4.dp) },
    )
}

@Composable
fun ArtistActionSheet(artist: Artist, onDismiss: () -> Unit, extra: List<List<Action>> = emptyList()) {
    val library = LocalContainer.current.library
    ActionSheet(
        title = artist.name,
        subtitle = null,
        groups = queueActions({ library.tracksForAlbumArtistInOrder(artist.id) }, shuffle = true) + listOf(artistDownloadActions(artist)) + extra,
        onDismiss = onDismiss,
        leading = { ArtistArtwork(artist, 48.dp) },
    )
}

