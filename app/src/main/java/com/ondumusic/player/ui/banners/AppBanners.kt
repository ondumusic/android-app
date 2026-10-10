package com.ondumusic.player.ui.banners

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.ServerCompatibility
import com.ondumusic.player.model.ServerNotice
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.LocalSession
import com.ondumusic.player.ui.downloads.DownloadNoticeHost
import com.ondumusic.player.ui.downloads.LocalDownloadNoticeHost
import com.ondumusic.player.ui.downloads.icon
import com.ondumusic.player.ui.downloads.isOverridable
import com.ondumusic.player.ui.downloads.message
import com.ondumusic.player.ui.downloads.noticeReason
import kotlinx.coroutines.launch

/**
 * Strips across the top for what the user should know but can do without: an expired
 * session, something the server reports, an app and server that disagree, downloads held
 * up by the device. They sit above
 * the app rather than over it — a library that's already here still plays.
 */
@Composable
fun WithAppBanners(content: @Composable () -> Unit) {
    val container = LocalContainer.current
    val sync = LocalSession.current.sync
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("banners", Context.MODE_PRIVATE) }

    val expired by container.auth.isSessionExpired.collectAsStateWithLifecycle()
    val notices by sync.notices.collectAsStateWithLifecycle()
    val compatibility by sync.compatibility.collectAsStateWithLifecycle()
    var dismissedVersion by remember { mutableStateOf(prefs.getString(KEY_DISMISSED_VERSION, "").orEmpty()) }
    var showReAuth by remember { mutableStateOf(false) }
    val downloads by container.downloads.state.collectAsStateWithLifecycle()
    val noticeHost = remember { DownloadNoticeHost() }
    val downloadNotice = downloads.noticeReason.takeIf { noticeHost.screensShowingNotice == 0 }

    // Asked for straight away, as iOS does; the strip stays for when it's put off.
    LaunchedEffect(expired) { showReAuth = expired }

    val showVersion = compatibility.shouldShow(dismissedVersion)
    val any = expired || notices.isNotEmpty() || showVersion || downloadNotice != null

    Column(Modifier.fillMaxSize()) {
        if (any) {
            Column(Modifier.statusBarsPadding()) {
                if (expired) {
                    Banner(Icons.Outlined.Lock, stringResource(R.string.banner_session_expired), warning = true) {
                        TextButton(onClick = { showReAuth = true }) { Text(stringResource(R.string.banner_reauthenticate)) }
                    }
                }
                notices.forEach { notice ->
                    Banner(
                        if (notice.level == ServerNotice.Level.Warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                        notice.text(),
                        warning = notice.level == ServerNotice.Level.Warning,
                    ) {
                        // Dismissing clears it for every client, which is what the server does with it.
                        TextButton(onClick = { container.scope.launch { sync.dismiss(notice) } }) { Text(stringResource(R.string.banner_dismiss)) }
                    }
                }
                downloadNotice?.let { reason ->
                    Banner(reason.icon, reason.message(), warning = !reason.isOverridable) {
                        if (reason.isOverridable) {
                            TextButton(onClick = { container.scope.launch { container.downloads.ignoreBatterySaver() } }) {
                                Text(stringResource(R.string.downloads_download_anyway))
                            }
                        }
                    }
                }
                if (showVersion) {
                    Banner(Icons.Outlined.WarningAmber, compatibility.text(), warning = true) {
                        IconButton(onClick = {
                            // Remembered per server version: an upgrade that doesn't fix it speaks up again.
                            dismissedVersion = compatibility.serverVersion.orEmpty()
                            prefs.edit { putString(KEY_DISMISSED_VERSION, dismissedVersion) }
                        }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.banner_dismiss)) }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).then(if (any) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            CompositionLocalProvider(LocalDownloadNoticeHost provides noticeHost) { content() }
        }
    }
    if (showReAuth) ReAuthDialog(onDismiss = { showReAuth = false })
}

@Composable
private fun Banner(icon: ImageVector, text: String, warning: Boolean, action: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (warning) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (warning) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action()
        }
    }
}

/** Our own words for codes this build knows, so they're translated and say what to do; the server's English otherwise. */
@Composable
private fun ServerNotice.text(): String = when (known) {
    ServerNotice.Known.LibraryUnavailable -> stringResource(R.string.notice_library_unavailable)
    null -> message
}

/** Names the side that's behind first, so the first words say who has to act. */
@Composable
private fun ServerCompatibility.text(): String = when (this) {
    is ServerCompatibility.AppTooOld -> stringResource(R.string.compat_app_too_old, serverVersion)
    is ServerCompatibility.ServerTooOld -> stringResource(R.string.compat_server_too_old, serverVersion)
    ServerCompatibility.Ok -> ""
}

private const val KEY_DISMISSED_VERSION = "dismissedServerVersionNotice"
