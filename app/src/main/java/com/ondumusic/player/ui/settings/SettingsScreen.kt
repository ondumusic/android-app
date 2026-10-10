package com.ondumusic.player.ui.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.settings.AccentColor
import com.ondumusic.player.settings.DownloadQuality
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.settings.StreamingQuality
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.LocalSession
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.theme.accentSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Labelled by hand: the system formatter counts in thousands, which turns 2 GiB into "2.1 GB". */
private val cacheLimits = listOf(
    500L * 1024 * 1024 to "500 MB", 1024L * 1024 * 1024 to "1 GB", 2L * 1024 * 1024 * 1024 to "2 GB",
    5L * 1024 * 1024 * 1024 to "5 GB", 10L * 1024 * 1024 * 1024 to "10 GB", 0L to null,
)
private val concurrentDownloadOptions = listOf(1, 2, 3, 4, 6, 8)

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    ScreenScaffold(stringResource(R.string.menu_settings), showBack = true) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { PlaybackSection(settings) }
            item { AppearanceSection(settings) }
            item { CacheSection() }
            item { QualitySection(settings) }
            item { DownloadsSection(settings) }
            item { LibrarySection() }
            item { AccountSection() }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A setting chosen from a short list, as a dialog of radio buttons. */
@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ValueRow(title, label(selected)) { open = true }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(option == selected, role = Role.RadioButton) { onSelect(option); open = false }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PlaybackSection(settings: SettingsStore) {
    val crossfade by settings.crossfadeDuration.flow.collectAsStateWithLifecycle()
    val normalize by settings.normalizeVolume.flow.collectAsStateWithLifecycle()
    val recordPlays by settings.recordPlays.flow.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.settings_playback))
    SwitchRow(stringResource(R.string.settings_crossfade), crossfade > 0, { settings.crossfadeDuration.value = if (it) 1f else 0f })
    if (crossfade > 0) {
        // Dragging updates the label; the setting is written when the thumb is let go.
        var dragging by remember(crossfade) { mutableFloatStateOf(crossfade) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(value = dragging, onValueChange = { dragging = it }, onValueChangeFinished = { settings.crossfadeDuration.value = dragging },
                valueRange = 1f..12f, steps = 10, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.settings_seconds, dragging.toInt()), Modifier.padding(start = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Footer(stringResource(R.string.settings_crossfade_footer))
    SwitchRow(stringResource(R.string.settings_normalize), normalize, { settings.normalizeVolume.value = it })
    Footer(stringResource(R.string.settings_normalize_footer))
    SwitchRow(stringResource(R.string.settings_record_plays), recordPlays, { settings.recordPlays.value = it })
    Footer(stringResource(R.string.settings_record_plays_footer))
}

@Composable
private fun AppearanceSection(settings: SettingsStore) {
    val tinted by settings.artworkTintedPlayer.flow.collectAsStateWithLifecycle()
    val haptics by settings.hapticsEnabled.flow.collectAsStateWithLifecycle()
    val accent by settings.accentColor.flow.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.settings_appearance))
    SwitchRow(stringResource(R.string.settings_tinted_player), tinted, { settings.artworkTintedPlayer.value = it },
        supporting = stringResource(R.string.settings_tinted_player_footer))
    SwitchRow(stringResource(R.string.settings_haptics), haptics, { settings.hapticsEnabled.value = it })

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_accent)) },
        supportingContent = { Text(stringResource(accent.labelRes())) },
    )
    FlowRow(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentColor.entries.forEach { option ->
            AccentSwatch(option, selected = option == accent) { settings.accentColor.value = option }
        }
    }
}

/**
 * The wallpaper option previews the wallpaper's own colours, split the way Android's
 * wallpaper picker shows a palette, so it reads as "the phone's colours" rather than as
 * one more colour.
 */
@Composable
private fun AccentSwatch(option: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = stringResource(option.labelRes())
    val wallpaper = remember(option) {
        if (option == AccentColor.Wallpaper) dynamicLightColorScheme(context) else null
    }
    val outline = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(44.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            if (wallpaper != null) {
                drawArc(wallpaper.primary, 180f, 180f, useCenter = true)
                drawArc(wallpaper.tertiary, 0f, 90f, useCenter = true)
                drawArc(wallpaper.secondary, 90f, 90f, useCenter = true)
            } else {
                drawCircle(option.accentSeed(dark = false))
            }
            if (selected) drawCircle(outline, style = Stroke(width = 3.dp.toPx()))
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

private fun AccentColor.labelRes(): Int = when (this) {
    AccentColor.Wallpaper -> R.string.accent_wallpaper
    AccentColor.Blue -> R.string.accent_blue
    AccentColor.Indigo -> R.string.accent_indigo
    AccentColor.Purple -> R.string.accent_purple
    AccentColor.Pink -> R.string.accent_pink
    AccentColor.Red -> R.string.accent_red
    AccentColor.Orange -> R.string.accent_orange
    AccentColor.Yellow -> R.string.accent_yellow
    AccentColor.Green -> R.string.accent_green
    AccentColor.Teal -> R.string.accent_teal
}

@Composable
private fun CacheSection() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val limit by container.settings.playbackCacheLimit.flow.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf(container.playbackCacheUsage()) }
    var confirmClear by remember { mutableStateOf(false) }
    // The cache fills as music plays; refreshed while this is on screen so the figure isn't stale.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            usage = container.playbackCacheUsage()
        }
    }
    val size = Formatter.formatShortFileSize(context, usage.first)

    SectionHeader(stringResource(R.string.settings_cache))
    ChoiceRow(stringResource(R.string.settings_cache_limit), cacheLimits.map { it.first }, limit,
        label = { bytes -> cacheLimits.firstOrNull { it.first == bytes }?.second ?: stringResource(R.string.settings_unlimited) },
        onSelect = { container.settings.playbackCacheLimit.value = it })
    ValueRow(stringResource(R.string.settings_cache_size), "$size · ${pluralStringResource(R.plurals.track_count, usage.second, usage.second)}")
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_clear_cache), color = MaterialTheme.colorScheme.error) },
        modifier = Modifier.clickable(enabled = usage.second > 0) { confirmClear = true },
    )
    Footer(stringResource(R.string.settings_cache_footer))
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_message, size)) },
            confirmButton = {
                TextButton(onClick = {
                    container.clearPlaybackCache()
                    usage = container.playbackCacheUsage()
                    confirmClear = false
                }) { Text(stringResource(R.string.settings_clear_cache), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun QualitySection(settings: SettingsStore) {
    val streaming by settings.streamingQuality.flow.collectAsStateWithLifecycle()
    val download by settings.downloadQuality.flow.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_quality))
    ChoiceRow(stringResource(R.string.settings_streaming_quality), StreamingQuality.entries, streaming,
        label = { stringResource(it.labelRes()) }, onSelect = { settings.streamingQuality.value = it })
    ChoiceRow(stringResource(R.string.settings_download_quality), DownloadQuality.entries, download,
        label = { stringResource(it.labelRes()) }, onSelect = { settings.downloadQuality.value = it })
    Footer(stringResource(R.string.settings_quality_footer))
}

private fun StreamingQuality.labelRes() = when (this) {
    StreamingQuality.Original -> R.string.quality_original
    StreamingQuality.DefaultAdts -> R.string.quality_default
    StreamingQuality.MobileAdts -> R.string.quality_mobile
}

private fun DownloadQuality.labelRes() = when (this) {
    DownloadQuality.Original -> R.string.quality_original
    DownloadQuality.DefaultAac -> R.string.quality_default
    DownloadQuality.MobileAac -> R.string.quality_mobile
}

@Composable
private fun DownloadsSection(settings: SettingsStore) {
    val concurrent by settings.maxConcurrentDownloads.flow.collectAsStateWithLifecycle()
    val auto by settings.autoDownloadNewTracks.flow.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.menu_downloads))
    ChoiceRow(stringResource(R.string.settings_concurrent_downloads), concurrentDownloadOptions, concurrent,
        label = { it.toString() }, onSelect = { settings.maxConcurrentDownloads.value = it })
    SwitchRow(stringResource(R.string.settings_auto_download), auto, { settings.autoDownloadNewTracks.value = it })
    Footer(stringResource(R.string.settings_auto_download_footer))
}

@Composable
private fun LibrarySection() {
    val container = LocalContainer.current
    val sync = LocalSession.current.sync
    val syncing by sync.isSyncing.collectAsStateWithLifecycle()
    val last by sync.lastSyncDate.collectAsStateWithLifecycle()
    val error by sync.error.collectAsStateWithLifecycle()
    var confirmRebuild by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.settings_library))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_last_synced)) },
        supportingContent = {
            Text(last?.let { DateUtils.getRelativeTimeSpanString(it.toEpochMilli()).toString() } ?: stringResource(R.string.settings_never))
        },
        trailingContent = if (syncing) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_sync_now), color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(enabled = !syncing) { container.scope.launch { sync.sync() } },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_rebuild)) },
        supportingContent = { Text(stringResource(R.string.settings_rebuild_message)) },
        modifier = Modifier.clickable(enabled = !syncing) { confirmRebuild = true },
    )
    error?.let { Footer(it.resolve()) }
    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(stringResource(R.string.settings_rebuild_title)) },
            text = { Text(stringResource(R.string.settings_rebuild_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    container.scope.launch { sync.rebuildLibrary() }
                }) { Text(stringResource(R.string.settings_rebuild_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AccountSection() {
    val container = LocalContainer.current
    val session = LocalSession.current
    val user by container.auth.currentUser.collectAsStateWithLifecycle()
    val serverVersion by session.sync.serverVersion.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.settings_account))
    user?.let { ValueRow(stringResource(R.string.settings_signed_in_as), it.username) }
    ValueRow(stringResource(R.string.login_server), session.api.baseUrl.host)
    serverVersion?.let { ValueRow(stringResource(R.string.settings_server_version), it) }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    ListItem(
        headlineContent = { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) },
        modifier = Modifier.clickable { confirmSignOut = true },
    )
    if (confirmSignOut) SignOutDialog(onDismiss = { confirmSignOut = false })
}

/** Signing out discards everything on the device for this account, so it always asks. */
@Composable
fun SignOutDialog(onDismiss: () -> Unit) {
    val auth = LocalContainer.current.auth
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = { Text(stringResource(R.string.sign_out_message)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); auth.logout() }) {
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
