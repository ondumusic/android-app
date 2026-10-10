package com.ondumusic.player.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    val offset = remember { Animatable(0f) }
    val canGoBack = state.queue.isNotEmpty() && (progress > 3 || state.currentIndex > 0)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.clip(RoundedCornerShape(16.dp))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onOpen)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val dragging = offset.value + delta
                                // Resist a drag towards something that isn't there.
                                val allowed = if (dragging < 0) state.canSkip else canGoBack
                                offset.snapTo(offset.value + if (allowed) delta else delta * 0.2f)
                            }
                        },
                        onDragStopped = {
                            val dx = offset.value
                            val action = when {
                                dx < -SWIPE_THRESHOLD && state.canSkip -> player::skip
                                dx > SWIPE_THRESHOLD && canGoBack -> player::back
                                else -> null
                            }
                            if (action == null) {
                                offset.animateTo(0f, tween(200))
                            } else {
                                haptic(HapticFeedbackType.SegmentTick)
                                val out = if (dx < 0) -600f else 600f
                                offset.animateTo(out, tween(150))
                                action()
                                offset.snapTo(-out)
                                offset.animateTo(0f, tween(200))
                            }
                        },
                    )
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .alpha(1f - minOf(abs(offset.value) / 600f, 0.5f))
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Artwork(state.currentItem?.artworkUrl, Modifier.size(44.dp), cornerRadius = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(Modifier.size(12.dp).padding(end = 0.dp), strokeWidth = 1.5.dp)
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
                        Text(track.artist.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

private const val SWIPE_THRESHOLD = 120f
