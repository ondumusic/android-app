package com.ondumusic.player.ui.player

import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.Artwork
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Height the mini player takes, margins included, for content to scroll clear of. */
val MiniPlayerHeight = 76.dp

/** The current track above the navigation bar. Tap for the full player; swipe sideways to skip or go back. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val player = LocalContainer.current.player
    val state by player.state.collectAsStateWithLifecycle()
    val progress by player.progress.collectAsStateWithLifecycle()
    val track = state.currentTrack ?: return
    val haptic = rememberPlayerHaptics()
    val scope = rememberCoroutineScope()
    val tintSetting by LocalContainer.current.settings.artworkTintedPlayer.flow.collectAsStateWithLifecycle()
    val artworkUrl = state.currentItem?.artworkUrl
    // Dressed like the full player, so the two read as one object.
    val tinted = tintSetting && artworkUrl != null
    val canGoBack = state.queue.isNotEmpty() && (progress > 3 || state.currentIndex > 0)
    // Plain state, written synchronously by the drag: an Animatable fed from a coroutine per
    // movement let those coroutines race the settle animation and strand the row mid-swipe.
    var dragX by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    var width by remember { mutableIntStateOf(0) }

    fun settleTo(target: Float, then: suspend () -> Unit = {}) {
        settle?.cancel()
        settle = scope.launch {
            animate(dragX, target, animationSpec = tween(if (target == 0f) 200 else 150)) { value, _ -> dragX = value }
            then()
        }
    }

    fun release(velocity: Float) {
        val threshold = width * SWIPE_FRACTION
        val forward = dragX < -threshold || velocity < -FLING_VELOCITY
        val backward = dragX > threshold || velocity > FLING_VELOCITY
        val action = when {
            forward && dragX < 0 && state.canSkip -> player::skip
            backward && dragX > 0 && canGoBack -> player::back
            else -> null
        }
        if (action == null) return settleTo(0f)
        haptic(HapticFeedbackType.SegmentTick)
        val out = if (dragX < 0) -width.toFloat() else width.toFloat()
        // Off one side, change track, then in from the other, so the new track arrives from
        // the direction it's in.
        settleTo(out) {
            action()
            dragX = -out
            animate(dragX, 0f, animationSpec = tween(200)) { value, _ -> dragX = value }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .onSizeChanged { width = it.width }
            // Swipes start near the screen edges, where the system back gesture would take them.
            .systemGestureExclusion(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (tinted) Color.White else MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Box {
            // A smaller radius than the full player's: across a strip this short, a wide blur
            // collapses the whole cover into a single colour.
            if (tinted) ArtworkBackdrop(artworkUrl, Modifier.matchParentSize(), blurRadius = 16.dp, dimming = 0.5f)
            Column(Modifier.clip(RoundedCornerShape(16.dp))) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onOpen)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                // Resist a drag towards something that isn't there.
                                val allowed = if (dragX + delta < 0) state.canSkip else canGoBack
                                dragX += if (allowed) delta else delta * 0.2f
                            },
                            onDragStarted = { settle?.cancel() },
                            onDragStopped = { velocity -> release(velocity) },
                        )
                        .offset { IntOffset(dragX.roundToInt(), 0) }
                        .alpha(1f - if (width > 0) minOf(abs(dragX) / width, 0.5f) else 0f)
                        .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                ) {
                    Artwork(artworkUrl, Modifier.size(44.dp), cornerRadius = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.isBuffering) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp,
                                    color = if (tinted) Color.White else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(track.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val error = state.playbackError
                        if (error != null) {
                            Text(error.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text(track.artist.name, style = MaterialTheme.typography.bodySmall,
                                color = if (tinted) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = player::togglePlayPause) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                        )
                    }
                    IconButton(onClick = player::skip, enabled = state.canSkip) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next))
                    }
                }
                LinearProgressIndicator(
                    progress = { if (state.duration > 0) (progress / state.duration).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                    color = if (tinted) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary,
                    trackColor = if (tinted) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** How far across a drag has to go to count, as a share of the bar's width. */
private const val SWIPE_FRACTION = 0.25f

/** A flick this fast counts however short it was, in pixels per second. */
private const val FLING_VELOCITY = 1200f
