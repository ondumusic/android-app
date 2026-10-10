package com.ondumusic.player.ui.playlists

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer

/** Why a queued playlist change was dropped, shown once and then cleared. */
@Composable
fun PlaylistSyncErrorDialog() {
    val outbox = LocalContainer.current.outbox
    val error by outbox.lastError.collectAsStateWithLifecycle()
    error?.let { message ->
        AlertDialog(
            onDismissRequest = outbox::clearLastError,
            title = { Text(stringResource(R.string.playlist_sync_issue)) },
            text = { Text(message.resolve()) },
            confirmButton = { TextButton(onClick = outbox::clearLastError) { Text(stringResource(R.string.playlist_ok)) } },
        )
    }
}
