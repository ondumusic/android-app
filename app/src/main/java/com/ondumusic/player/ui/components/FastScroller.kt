package com.ondumusic.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A draggable handle along the right edge for getting across a long list quickly, the
 * Android counterpart of the iOS A–Z index: it shows while the list scrolls, and while
 * dragged a bubble names the section under it. [label] gives that name for an item index.
 *
 * Place it over the list, filling its height; only the handle takes touches.
 */
@Composable
fun FastScroller(state: LazyListState, modifier: Modifier = Modifier, label: (Int) -> String? = { null }) {
    val info = state.layoutInfo
    FastScroller(
        first = state.firstVisibleItemIndex,
        visible = info.visibleItemsInfo.size,
        total = info.totalItemsCount,
        isScrolling = state.isScrollInProgress,
        scrollTo = { state.scrollToItem(it) },
        label = label,
        modifier = modifier,
    )
}

@Composable
fun FastScroller(state: LazyGridState, modifier: Modifier = Modifier, label: (Int) -> String? = { null }) {
    val info = state.layoutInfo
    FastScroller(
        first = state.firstVisibleItemIndex,
        visible = info.visibleItemsInfo.size,
        total = info.totalItemsCount,
        isScrolling = state.isScrollInProgress,
        scrollTo = { state.scrollToItem(it) },
        label = label,
        modifier = modifier,
    )
}

/** The label of the section an index falls in, from each section's first index and name, in order. */
fun sectionLabels(starts: List<Pair<Int, String>>): (Int) -> String? = { index ->
    val i = starts.binarySearch { it.first.compareTo(index) }
    starts.getOrNull(if (i >= 0) i else -i - 2)?.second
}

@Composable
private fun FastScroller(
    first: Int,
    visible: Int,
    total: Int,
    isScrolling: Boolean,
    scrollTo: suspend (Int) -> Unit,
    label: (Int) -> String?,
    modifier: Modifier,
) {
    // A few screens' worth is quick enough to fling through.
    val range = total - visible
    if (visible == 0 || total < visible * MIN_SCREENS || range <= 0) return

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) shown = true
        else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScroller")
    val fraction = if (dragging) dragFraction else (first.toFloat() / range).coerceIn(0f, 1f)
    val current = label((fraction * range).roundToInt())
    LaunchedEffect(current) { if (dragging && current != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }

    BoxWithConstraints(modifier.fillMaxHeight().width(TOUCH_WIDTH)) {
        val density = LocalDensity.current
        val thumbPx = with(density) { TOUCH_HEIGHT.toPx() }
        val trackPx = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val y = (fraction * trackPx).roundToInt()

        if (dragging && current != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    // Wider than the strip, so laid out centred on it; the offset is from there.
                    .offset { IntOffset(-(BUBBLE_SIZE / 2 + TOUCH_WIDTH / 2 + 8.dp).roundToPx(), y + ((thumbPx - BUBBLE_SIZE.toPx()) / 2).roundToInt()) }
                    .requiredSize(BUBBLE_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center) { Text(current, style = MaterialTheme.typography.headlineSmall) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(0, y) }
                .size(TOUCH_WIDTH, TOUCH_HEIGHT)
                .graphicsLayer { this.alpha = alpha }
                // The right edge is the system back gesture's; the handle needs it more.
                .then(if (shown) Modifier.systemGestureExclusion() else Modifier)
                .pointerInput(shown) {
                    if (!shown) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = fraction
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                        val target = (dragFraction * range).roundToInt()
                        scope.launch { scrollTo(target) }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(THUMB_WIDTH, TOUCH_HEIGHT - 16.dp)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(THUMB_WIDTH / 2),
                    ),
            )
        }
    }
}

private const val MIN_SCREENS = 4
private const val HIDE_AFTER_MS = 1500L
private val TOUCH_WIDTH = 40.dp
private val TOUCH_HEIGHT = 64.dp
private val THUMB_WIDTH = 6.dp
private val BUBBLE_SIZE = 64.dp
