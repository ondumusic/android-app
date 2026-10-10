package com.ondumusic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.auth.AuthService
import com.ondumusic.player.service.SyncService
import kotlinx.coroutines.launch

@Composable
fun SyncProgressScreen(sync: SyncService, auth: AuthService) {
    val error by sync.error.collectAsStateWithLifecycle()
    val progress by sync.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sync) { sync.sync() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = error
            if (message != null) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.sync_failed), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // No confirmation: nothing has synced yet, so there's nothing to lose.
                    OutlinedButton(onClick = auth::logout) { Text(stringResource(R.string.sign_out)) }
                    Button(onClick = { scope.launch { sync.sync() } }) { Text(stringResource(R.string.retry)) }
                }
            } else {
                CircularProgressIndicator(Modifier.size(48.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.sync_getting_ready), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sync_first_sync_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                progress?.takeIf { it.artistCount > 0 }?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    val artists = pluralStringResource(R.plurals.artist_count, p.artistCount, p.artistCount)
                    val albums = pluralStringResource(R.plurals.album_count, p.albumCount, p.albumCount)
                    val tracks = pluralStringResource(R.plurals.track_count, p.trackCount, p.trackCount)
                    Text(
                        when {
                            p.trackCount > 0 -> "$artists, $albums, $tracks"
                            p.albumCount > 0 -> "$artists, $albums"
                            else -> artists
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
