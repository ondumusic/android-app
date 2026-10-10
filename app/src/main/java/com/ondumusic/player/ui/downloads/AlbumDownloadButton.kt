package com.ondumusic.player.ui.downloads

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.data.observeAlbumDownloadCount
import com.ondumusic.player.model.Album
import com.ondumusic.player.ui.LocalContainer

/**
 * The album header's download control: start it, watch it and offer to cancel it, or show
 * it's done and offer to remove it. Both ask first — a progress ring is easy to tap by accident.
 */
@Composable
fun AlbumDownloadButton(album: Album, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val ui = LocalDownloadUi.current
    val count by remember(album.id) { container.library.observeAlbumDownloadCount(album.id) }.collectAsStateWithLifecycle(null)
    val state by container.downloads.state.collectAsStateWithLifecycle()
    val progress by container.downloads.progress.collectAsStateWithLifecycle()
    val confirmCancel = cancelAlbumConfirmation(album)
    val confirmRemove = removeConfirmation(album.name, R.string.download_remove_album_message)
    val current = count?.takeIf { it.total > 0 } ?: return

    when {
        state.isAlbumDownloading(album.id) -> FilledTonalIconButton(onClick = { ui.confirm(confirmCancel) }, modifier) {
            CircularProgressIndicator(
                progress = { albumDownloadFraction(current, state, progress, album.id) },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
            )
        }
        current.isFull -> FilledTonalIconButton(onClick = { ui.confirm(confirmRemove { ui.perform { removeAlbum(album.id) } }) }, modifier) {
            Icon(Icons.Filled.DownloadDone, contentDescription = stringResource(R.string.download_remove))
        }
        else -> FilledTonalIconButton(onClick = { ui.start { downloadAlbum(album.id) } }, modifier) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download_action))
        }
    }
}
