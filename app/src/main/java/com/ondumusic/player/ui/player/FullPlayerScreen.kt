package com.ondumusic.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.rememberDynamicColorScheme
import com.ondumusic.player.R
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.model.Track
import com.ondumusic.player.player.PlayerService
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.TrackActionSheet
import com.ondumusic.player.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Now playing, over everything else. Drag it down or press back to return to the app.
 *
 * There is deliberately no volume slider: Android's volume keys and the system volume
 * panel already own media volume, and an in-app slider here would scale a second gain
 * stage on top of it.
 */
@Composable
fun FullPlayerScreen(onClose: () -> Unit) {
    val container = LocalContainer.current
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val tintSetting by container.settings.artworkTintedPlayer.flow.collectAsStateWithLifecycle()
    val artworkUrl = state.currentItem?.artworkUrl
    // Both halves matter: the setting is the user's choice, and a cover has to exist for there
    // to be anything to wear.
    val tinted = tintSetting && artworkUrl != null
    val seed = rememberArtworkSeedColor(artworkUrl.takeIf { tinted })
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }

    PlayerTheme(tinted, seed) {
        DragToDismiss(onClose) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                if (tinted) ArtworkBackdrop(artworkUrl, Modifier.matchParentSize())
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    // Side by side once the window is wider than tall and has room for both:
                    // landscape phones, unfolded foldables, tablets.
                    val wide = maxWidth > maxHeight && maxWidth >= 560.dp
                    val short = maxHeight < 420.dp
                    Column(Modifier.fillMaxSize()) {
                        TopBar(onClose = onClose, onMore = { showActions = true }, hasTrack = state.currentTrack != null)
                        if (wide) {
                            Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Cover(artworkUrl)
                                }
                                Spacer(Modifier.size(32.dp))
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                                    Controls(state, compact = short, onQueue = { showQueue = true })
                                }
                            }
                        } else {
                            Box(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Cover(artworkUrl, Modifier.widthIn(max = 560.dp))
                            }
                            Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                                Controls(state, compact = false, onQueue = { showQueue = true })
                            }
                        }
                    }
                }
            }
        }

        if (showQueue) QueueSheet(onDismiss = { showQueue = false })
        val track = state.currentTrack
        if (showActions && track != null) TrackActionSheet(track, onDismiss = { showActions = false })
    }
}

/**
 * Over the cover's blurred backdrop the player is always dark, as on iOS: light text is what
 * stays legible over an arbitrary dimmed image. Its accent is grown from the cover's own
 * colour; a cover too grey to have one gets a neutral dark scheme instead of losing the tint.
 * Untinted, it's simply the app's theme. Changes animate, so a new track eases in.
 */
@Composable
private fun PlayerTheme(tinted: Boolean, seed: Color?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val fromArtwork = rememberDynamicColorScheme(
        seedColor = seed ?: NEUTRAL_SEED,
        isDark = true,
        style = if (seed != null) PaletteStyle.Vibrant else PaletteStyle.Neutral,
    )
    val scheme = animateColorScheme(if (tinted) fromArtwork else base)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        // The player draws its own background rather than sitting on a Surface, so text
        // has to be told which colour reads on it.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

private val NEUTRAL_SEED = Color(0xFF9E9E9E)

/**
 * Pull the player down to put it away, like a sheet. Released past a quarter of the way,
 * or flung, it closes from where it is; otherwise it springs back.
 */
@Composable
private fun DragToDismiss(onClose: () -> Unit, content: @Composable () -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = { velocity ->
                    if (offset.value > height * 0.25f || velocity > 2_000f) onClose()
                    else offset.animateTo(0f, spring(stiffness = 600f))
                },
            )
            .offset { IntOffset(0, offset.value.roundToInt()) },
    ) { content() }
}

@Composable
private fun TopBar(onClose: () -> Unit, onMore: () -> Unit, hasTrack: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_close), modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMore, enabled = hasTrack) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier = Modifier) {
    CrossfadingArtwork(url, RoundedCornerShape(28.dp), modifier.aspectRatio(1f))
}

@Composable
private fun Controls(state: PlayerService.State, compact: Boolean, onQueue: () -> Unit) {
    val track = state.currentTrack
    if (track != null) TrackInfo(track, state, compact)
    Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
    Scrubber(state.duration, Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
    Transport(state, compact)
    Spacer(Modifier.height(if (compact) 0.dp else 16.dp))
    BottomActions(onQueue)
}

@Composable
private fun TrackInfo(track: Track, state: PlayerService.State, compact: Boolean) {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            track.name,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            // Long titles scroll rather than truncate: the title is the one thing on this
            // screen the user came to read.
            modifier = Modifier.padding(horizontal = 8.dp).basicMarquee(initialDelayMillis = 2_000),
        )
        LinkText(track.artist.name, MaterialTheme.typography.titleMedium) { navigator.showArtist(track.albumArtistId) }
        val error = state.playbackError
        if (error != null) {
            Text(error.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
        } else if (!compact) {
            track.album?.let { album ->
                LinkText(album.name, MaterialTheme.typography.bodyMedium) { navigator.showAlbum(album.id) }
            }
        }
    }
}

/** A line of secondary text that goes somewhere, with a touch target and ripple shaped to the words. */
@Composable
private fun LinkText(text: String, style: androidx.compose.ui.text.TextStyle, onClick: () -> Unit) {
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Its own composable so the twice-a-second progress only redraws this. While the thumb is
 * held the labels follow the finger, and the seek happens once, on release — seeking on
 * every move would make the stream rebuffer all the way along.
 */
@Composable
private fun Scrubber(duration: Double, modifier: Modifier = Modifier) {
    val player = LocalContainer.current.player
    val progress by player.progress.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val upper = duration.toFloat().coerceAtLeast(1f)
    val position = (dragging ?: progress.toFloat()).coerceIn(0f, upper)
    val seekLabel = stringResource(R.string.player_seek)
    Column(modifier) {
        Slider(
            value = position,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seek(it.toDouble()) }
                dragging = null
            },
            valueRange = 0f..upper,
            enabled = duration > 0,
            modifier = Modifier.semantics { contentDescription = seekLabel },
        )
        Row(Modifier.fillMaxWidth()) {
            val labelColor = if (dragging != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatPlaybackTime(position.toDouble()), style = MaterialTheme.typography.labelMedium, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.player_time_remaining, formatPlaybackTime(remainingSeconds(position.toDouble(), duration))),
                style = MaterialTheme.typography.labelMedium, color = labelColor,
            )
        }
    }
}

/** Taps that feel like pressing a physical control, when the user hasn't turned haptics off. */
@Composable
private fun Transport(state: PlayerService.State, compact: Boolean) {
    val player = LocalContainer.current.player
    val haptic = rememberPlayerHaptics()
    val progress = player.progress.collectAsStateWithLifecycle()
    // Read through derivedStateOf so the row recomposes when "back" changes meaning, not on every tick.
    val pastStart by remember { derivedStateOf { progress.value > 3 } }
    val canGoBack = state.queue.isNotEmpty() && (pastStart || state.currentIndex > 0)
    val skipSize = if (compact) 56.dp else 64.dp
    val playSize = if (compact) 72.dp else 88.dp

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleIcon(
            checked = state.isShuffled,
            onClick = { haptic(if (state.isShuffled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); player.toggleShuffle() },
            icon = Icons.Filled.Shuffle,
            label = stringResource(R.string.player_shuffle),
        )
        IconButton(
            onClick = { haptic(HapticFeedbackType.SegmentTick); player.back() },
            enabled = canGoBack,
            modifier = Modifier.size(skipSize),
        ) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous), modifier = Modifier.size(skipSize * 0.6f))
        }
        PlayPauseButton(state, playSize) {
            haptic(if (state.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            player.togglePlayPause()
        }
        IconButton(
            onClick = { haptic(HapticFeedbackType.SegmentTick); player.skip() },
            enabled = state.canSkip,
            modifier = Modifier.size(skipSize),
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next), modifier = Modifier.size(skipSize * 0.6f))
        }
        val repeatLabel = stringResource(
            when (state.repeatMode) {
                RepeatMode.Off -> R.string.player_repeat_off
                RepeatMode.All -> R.string.player_repeat_all
                RepeatMode.One -> R.string.player_repeat_one
            }
        )
        ToggleIcon(
            checked = state.repeatMode != RepeatMode.Off,
            onClick = {
                haptic(if (state.repeatMode == RepeatMode.One) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                player.cycleRepeatMode()
            },
            icon = if (state.repeatMode == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            label = repeatLabel,
        )
    }
}

@Composable
private fun ToggleIcon(checked: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * Round while paused, squarer while playing — the shape says what state the player is in
 * before the icon is read. A ring around it means the track is still loading.
 */
@Composable
private fun PlayPauseButton(state: PlayerService.State, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val corner by animateDpAsState(if (state.isPlaying) size * 0.3f else size / 2, spring(dampingRatio = 0.6f, stiffness = 500f), label = "corner")
    Box(contentAlignment = Alignment.Center) {
        FilledIconButton(onClick = onClick, shape = RoundedCornerShape(corner), modifier = Modifier.size(size)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size(size * 0.45f),
            )
        }
        if (state.isBuffering) {
            val loading = stringResource(R.string.player_buffering)
            CircularProgressIndicator(
                Modifier.size(size + 12.dp).semantics { contentDescription = loading },
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun BottomActions(onQueue: () -> Unit) {
    val context = LocalContext.current
    val route = rememberOutputRoute()
    val outputLabel = stringResource(R.string.player_output)
    val neutral = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Named the way the system media controls name it, and highlighted once it's
        // somewhere other than the device itself, so music going to a forgotten speaker shows.
        TextButton(
            onClick = { showSystemOutputSwitcher(context) },
            colors = if (route.isExternal) ButtonDefaults.textButtonColors() else neutral,
            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "$outputLabel: ${route.name}" },
        ) {
            Icon(route.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onQueue, colors = neutral) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.player_queue))
        }
    }
}
