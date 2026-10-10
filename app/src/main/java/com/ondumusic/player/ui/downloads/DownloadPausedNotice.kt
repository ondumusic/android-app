package com.ondumusic.player.ui.downloads

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ondumusic.player.R
import com.ondumusic.player.service.downloads.DownloadPausedReason

/**
 * Lets the app-wide banner step aside while the Downloads screen, which has the same
 * notice in its own list, is showing.
 */
class DownloadNoticeHost {
    var screensShowingNotice by mutableIntStateOf(0)
}

val LocalDownloadNoticeHost = staticCompositionLocalOf { DownloadNoticeHost() }

@Composable
fun DownloadPausedReason.message(): String = stringResource(
    when (this) {
        DownloadPausedReason.BatterySaver -> R.string.downloads_paused_battery_saver
        DownloadPausedReason.LowStorage -> R.string.downloads_paused_low_storage
    }
)

val DownloadPausedReason.icon: ImageVector
    get() = when (this) {
        DownloadPausedReason.BatterySaver -> Icons.Outlined.BatterySaver
        DownloadPausedReason.LowStorage -> Icons.Outlined.SdCardAlert
    }

/** Battery Saver is a preference the user may overrule for now; low storage is a real limit, so it gets no button. */
val DownloadPausedReason.isOverridable: Boolean get() = this == DownloadPausedReason.BatterySaver

/** The Downloads screen's form of the notice, at the top of its list. */
@Composable
fun DownloadPausedCard(reason: DownloadPausedReason, onDownloadAnyway: () -> Unit, modifier: Modifier = Modifier) {
    val warning = !reason.isOverridable
    val colors = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) colors.errorContainer else colors.secondaryContainer,
            contentColor = if (warning) colors.onErrorContainer else colors.onSecondaryContainer,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(reason.icon, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(reason.message(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (reason.isOverridable) {
                TextButton(onClick = onDownloadAnyway) { Text(stringResource(R.string.downloads_download_anyway)) }
            }
        }
    }
}
