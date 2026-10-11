package com.ondumusic.player.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.data.DownloadSummary
import com.ondumusic.player.data.DownloadedArtist
import com.ondumusic.player.data.observeDownloadSummary
import com.ondumusic.player.service.downloads.ActiveDownload
import com.ondumusic.player.service.downloads.DownloadsState
import com.ondumusic.player.service.downloads.FailedDownload
import com.ondumusic.player.service.downloads.QueuedDownload
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.ArtistActionSheet
import com.ondumusic.player.ui.components.ArtistArtwork
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.components.rowGestures
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route

/**
 * What's downloading, what failed, and what's on the device by artist. The artists lead to
 * pages that show only their downloaded albums and songs.
 */
@Composable
fun DownloadsScreen() {
    val container = LocalContainer.current
    val downloads = container.downloads
    val ui = LocalDownloadUi.current
    val state by downloads.state.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val summary by remember { container.library.observeDownloadSummary() }.collectAsStateWithLifecycle(null)
    // Re-asked whenever the queue or what's downloaded changes; the library's track list is cached.
    val hasUndownloaded by produceState(false, state, summary) { value = downloads.hasUndownloadedTracks() }

    val noticeHost = LocalDownloadNoticeHost.current
    DisposableEffect(noticeHost) {
        noticeHost.screensShowingNotice++
        onDispose { noticeHost.screensShowingNotice-- }
    }

    val confirmDownloadAll = Confirmation(
        title = stringResource(R.string.downloads_download_all_title),
        message = stringResource(R.string.downloads_download_all_message),
        confirmLabel = stringResource(R.string.downloads_download_all),
        dismissLabel = stringResource(R.string.cancel),
        destructive = false,
        onConfirm = { ui.perform { downloadAll() } },
    )
    val confirmRemoveAll = Confirmation(
        title = stringResource(R.string.downloads_remove_all_title),
        message = stringResource(R.string.downloads_remove_all_message),
        confirmLabel = stringResource(R.string.downloads_remove_all_confirm),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = { ui.perform { removeAll() } },
    )
    val hasLibrary = summary?.artists?.isNotEmpty() == true

    ScreenScaffold(
        stringResource(R.string.menu_downloads),
        showBack = true,
        actions = {
            if (hasUndownloaded || hasLibrary) {
                OverflowMenu(
                    onDownloadAll = { ui.confirm(confirmDownloadAll) }.takeIf { hasUndownloaded },
                    onRemoveAll = { ui.confirm(confirmRemoveAll) }.takeIf { hasLibrary },
                )
            }
        },
    ) { padding ->
        val current = summary
        if (current != null && current.artists.isEmpty() && !state.hasActivity) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.Outlined.DownloadForOffline, stringResource(R.string.downloads_empty_title),
                    message = stringResource(R.string.downloads_empty_message))
            }
        } else {
            DownloadsList(state, progress, current, padding)
        }
    }
}

@Composable
private fun DownloadsList(state: DownloadsState, progress: Map<java.util.UUID, Float>, summary: DownloadSummary?, padding: PaddingValues) {
    val ui = LocalDownloadUi.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // A list keeps its first visible row in place when rows arrive above it, so a section
    // appearing at the top would open off screen; at the top, stay at the top.
    val sections = listOf(state.active.isNotEmpty() || state.queued.isNotEmpty(), state.failed.isNotEmpty())
    var shownSections by remember { mutableStateOf(sections) }
    if (sections != shownSections) {
        // Read only when the sections change, so this doesn't recompose with every scroll.
        @Suppress("FrequentlyChangingValue")
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (atTop) listState.requestScrollToItem(0)
        shownSections = sections
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        // Outside the list, so it's seen however far down the list is scrolled.
        state.noticeReason?.let { reason ->
            DownloadPausedCard(reason, onDownloadAnyway = { ui.perform { ignoreBatterySaver() } },
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
            if (state.active.isNotEmpty() || state.queued.isNotEmpty()) {
                item(key = "downloading-header") {
                    SectionHeader(stringResource(R.string.downloads_section_downloading)) {
                        if (state.isPaused) {
                            TextButton(onClick = { ui.perform { resumeAll() } }) { Text(stringResource(R.string.downloads_resume_all)) }
                        } else {
                            TextButton(onClick = { ui.perform { pauseAll() } }) { Text(stringResource(R.string.downloads_pause_all)) }
                        }
                        TextButton(onClick = { ui.perform { cancelAll() } }) {
                            Text(stringResource(R.string.downloads_cancel_all), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                items(state.active, key = { "active-${it.trackId}" }) { download ->
                    ActiveRow(download, progress[download.trackId] ?: 0f)
                }
                if (state.queued.isNotEmpty()) {
                    item(key = "waiting") {
                        Text(
                            pluralStringResource(R.plurals.downloads_waiting, state.queued.size, state.queued.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    // Only the next few: Download All can queue thousands, and the library is below them.
                    items(state.queued.take(QUEUED_SHOWN), key = { "queued-${it.trackId}" }) { QueuedRow(it) }
                    val more = state.queued.size - QUEUED_SHOWN
                    if (more > 0) {
                        item(key = "queued-more") {
                            Text(
                                pluralStringResource(R.plurals.downloads_waiting_more, more, more),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            if (state.failed.isNotEmpty()) {
                item(key = "failed-header") {
                    SectionHeader(stringResource(R.string.downloads_section_failed)) {
                        // One failure has its own buttons; these are for many.
                        if (state.failed.size > 1) {
                            TextButton(onClick = { ui.perform { state.failed.forEach { retry(it.trackId) } } }) {
                                Text(stringResource(R.string.downloads_retry_all))
                            }
                            TextButton(onClick = { ui.perform { clearFailures() } }) {
                                Text(stringResource(R.string.downloads_clear_all), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                items(state.failed, key = { "failed-${it.trackId}" }) { FailedRow(it) }
            }

            if (summary != null && summary.artists.isNotEmpty()) {
                item(key = "library-header") {
                    SectionHeader(stringResource(R.string.downloads_section_library), trailing = Formatter.formatShortFileSize(context, summary.totalSize))
                }
                items(summary.artists, key = { "artist-${it.artist.id}" }) { item ->
                    DownloadedArtistRow(item, onClick = { navigator.push(Route.Artist(item.artist.id, downloadedOnly = true)) })
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(onDownloadAll: (() -> Unit)?, onRemoveAll: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onDownloadAll?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.downloads_download_all)) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    onClick = { open = false; action() },
                )
            }
            onRemoveAll?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.downloads_remove_all), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp).padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
        }
        actions()
    }
}

@Composable
private fun ActiveRow(download: ActiveDownload, fraction: Float) {
    val ui = LocalDownloadUi.current
    ListItem(
        headlineContent = { Text(download.trackName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (download.isPaused) stringResource(R.string.downloads_paused_item, download.artistName) else download.artistName,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                when {
                    download.isPaused -> LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.outline)
                    // Nothing has arrived yet: the server may still be converting it.
                    fraction <= 0f -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
                }
            }
        },
        trailingContent = {
            Row {
                if (download.isPaused) {
                    IconButton(onClick = { ui.perform { resume(download.trackId) } }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.download_resume))
                    }
                } else {
                    IconButton(onClick = { ui.perform { pause(download.trackId) } }) {
                        Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.download_pause))
                    }
                }
                IconButton(onClick = { ui.perform { cancel(download.trackId) } }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.download_cancel))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun QueuedRow(download: QueuedDownload) {
    val ui = LocalDownloadUi.current
    ListItem(
        headlineContent = { Text(download.trackName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(download.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = { ui.perform { cancel(download.trackId) } }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.download_cancel))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun FailedRow(failure: FailedDownload) {
    val ui = LocalDownloadUi.current
    ListItem(
        headlineContent = { Text(failure.trackName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(failure.reason.resolve(), color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row {
                IconButton(onClick = { ui.perform { retry(failure.trackId) } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retry))
                }
                IconButton(onClick = { ui.perform { dismissFailure(failure.trackId) } }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.banner_dismiss))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun DownloadedArtistRow(item: DownloadedArtist, onClick: () -> Unit) {
    val context = LocalContext.current
    var showActions by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(item.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOf(
                    pluralStringResource(R.plurals.album_count, item.albumCount, item.albumCount),
                    Formatter.formatShortFileSize(context, item.size),
                ).joinToString(" · "),
                maxLines = 1,
            )
        },
        leadingContent = { ArtistArtwork(item.artist, 48.dp) },
        trailingContent = {
            IconButton(onClick = { showActions = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.rowGestures(onClick = onClick, onLongClick = { showActions = true }),
    )
    if (showActions) ArtistActionSheet(item.artist, onDismiss = { showActions = false })
}

private const val QUEUED_SHOWN = 20
