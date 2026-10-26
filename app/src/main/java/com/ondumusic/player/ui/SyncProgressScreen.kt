package com.ondumusic.player.ui

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import com.ondumusic.player.ui.components.LoadingWave
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
                LoadingWave()
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
                    SyncCounts(p.artistCount, p.albumCount, p.trackCount)
                }
            }
        }
    }
}

/**
 * Sync pages land a few hundred rows at a time; easing each count through the values in
 * between makes them tick up instead of jumping by the page size, as on iOS.
 */
@Composable
private fun SyncCounts(artistCount: Int, albumCount: Int, trackCount: Int) {
    val spec = tween<Int>(800, easing = EaseOut)
    val artistsShown by animateIntAsState(artistCount, spec, label = "artists")
    val albumsShown by animateIntAsState(albumCount, spec, label = "albums")
    val tracksShown by animateIntAsState(trackCount, spec, label = "tracks")
    val artists = pluralStringResource(R.plurals.artist_count, artistsShown, artistsShown)
    val albums = pluralStringResource(R.plurals.album_count, albumsShown, albumsShown)
    val tracks = pluralStringResource(R.plurals.track_count, tracksShown, tracksShown)
    Text(
        when {
            trackCount > 0 -> "$artists, $albums, $tracks"
            albumCount > 0 -> "$artists, $albums"
            else -> artists
        },
        // Even-width digits, so the line doesn't shuffle sideways as the numbers change.
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.outline,
    )
}
