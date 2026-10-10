package com.ondumusic.player.ui.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.data.observeAlbumDownloadCount
import com.ondumusic.player.data.observeArtistDownloadCount
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.Track
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.Action

/** The download entries of a song's menu, by where its download stands. */
@Composable
fun trackDownloadActions(track: Track): List<Action> {
    val ui = LocalDownloadUi.current
    val state by LocalContainer.current.downloads.state.collectAsStateWithLifecycle()
    val id = track.id
    val cancel = Action(stringResource(R.string.download_cancel), Icons.Outlined.Cancel, destructive = true) { ui.perform { cancel(id) } }
    return when (state.statusOf(id, track.isDownloaded)) {
        // Unconfirmed, as on iOS: one song is quick to get back.
        TrackDownloadStatus.Downloaded -> listOf(
            Action(stringResource(R.string.download_remove), Icons.Outlined.Delete, destructive = true) { ui.perform { remove(setOf(id)) } },
        )
        TrackDownloadStatus.Downloading -> listOf(
            Action(stringResource(R.string.download_pause), Icons.Outlined.PauseCircle) { ui.perform { pause(id) } },
            cancel,
        )
        TrackDownloadStatus.Paused -> listOf(
            Action(stringResource(R.string.download_resume), Icons.Outlined.PlayCircle) { ui.perform { resume(id) } },
            cancel,
        )
        TrackDownloadStatus.Queued -> listOf(cancel)
        TrackDownloadStatus.NotDownloaded -> listOf(
            Action(stringResource(R.string.download_action), Icons.Outlined.Download) { ui.perform { download(track) } },
        )
    }
}

@Composable
fun albumDownloadActions(album: Album): List<Action> {
    val container = LocalContainer.current
    val ui = LocalDownloadUi.current
    val count by remember(album.id) { container.library.observeAlbumDownloadCount(album.id) }.collectAsStateWithLifecycle(null)
    val state by container.downloads.state.collectAsStateWithLifecycle()
    val actions = collectionDownloadActions(count ?: return emptyList(), state.isAlbumDownloading(album.id))
    val confirmCancel = cancelAlbumConfirmation(album)
    val confirmRemove = removeConfirmation(album.name, R.string.download_remove_album_message)
    return listOfNotNull(
        Action(stringResource(R.string.download_action), Icons.Outlined.Download) { ui.perform { downloadAlbum(album.id) } }
            .takeIf { actions.download },
        Action(stringResource(R.string.download_cancel), Icons.Outlined.Cancel, destructive = true) { ui.confirm(confirmCancel) }
            .takeIf { actions.cancel },
        Action(stringResource(R.string.download_remove), Icons.Outlined.Delete, destructive = true) {
            ui.confirm(confirmRemove { ui.perform { removeAlbum(album.id) } })
        }.takeIf { actions.remove },
    )
}

@Composable
fun artistDownloadActions(artist: Artist): List<Action> {
    val container = LocalContainer.current
    val ui = LocalDownloadUi.current
    val count by remember(artist.id) { container.library.observeArtistDownloadCount(artist.id) }.collectAsStateWithLifecycle(null)
    // The queue doesn't track artists; Downloads is where an artist's downloads are cancelled.
    val actions = collectionDownloadActions(count ?: return emptyList(), isDownloading = false)
    val confirmRemove = removeConfirmation(artist.name, R.string.download_remove_artist_message)
    return listOfNotNull(
        Action(stringResource(R.string.download_action), Icons.Outlined.Download) { ui.perform { downloadArtist(artist.id) } }
            .takeIf { actions.download },
        Action(stringResource(R.string.download_remove), Icons.Outlined.Delete, destructive = true) {
            ui.confirm(confirmRemove { ui.perform { removeArtist(artist.id) } })
        }.takeIf { actions.remove },
    )
}

/** Built while composing, for the strings; confirmed later, from a sheet that's gone. */
@Composable
fun removeConfirmation(name: String, message: Int): (onConfirm: () -> Unit) -> Confirmation {
    val title = stringResource(R.string.download_remove_title)
    val text = stringResource(message, name)
    val confirm = stringResource(R.string.download_remove_confirm)
    val dismiss = stringResource(R.string.cancel)
    return { onConfirm -> Confirmation(title, text, confirm, dismiss, onConfirm = onConfirm) }
}

@Composable
fun cancelAlbumConfirmation(album: Album): Confirmation {
    val ui = LocalDownloadUi.current
    return Confirmation(
        title = stringResource(R.string.download_cancel_album_title),
        message = stringResource(R.string.download_cancel_album_message, album.name),
        confirmLabel = stringResource(R.string.download_cancel_album_confirm),
        dismissLabel = stringResource(R.string.download_keep_downloading),
        onConfirm = { ui.perform { cancelAlbum(album.id) } },
    )
}
