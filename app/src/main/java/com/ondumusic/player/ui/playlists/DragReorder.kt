package com.ondumusic.player.ui.playlists

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a keyed LazyColumn. While a row is held, [items] is a local order
 * that changes under the finger; the store is written once, on release, through [onMove].
 */
class DragReorderState<T>(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgeZone: Float,
    private val keyOf: (T) -> Any,
    /** Writes the move; false if it couldn't be, and the list goes back to the store's order. */
    private val onMove: (item: T, from: Int, to: Int, original: List<T>) -> Boolean,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    /** How far the held row is drawn from where the list lays it out. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var latest: List<T> = emptyList()
    private var original: List<T> = emptyList()
    private var startIndex = -1

    /** New contents from the store. Ignored mid-drag, so a sync can't yank the row away. */
    fun update(contents: List<T>) {
        latest = contents
        if (draggingKey == null) items = contents
    }

    fun start(key: Any) {
        val index = items.indexOfFirst { keyOf(it) == key }
        if (index < 0) return
        original = items
        startIndex = index
        offset = 0f
        draggingKey = key
        scope.launch { autoScroll() }
    }

    fun drag(dy: Float) {
        offset += dy
        swapPastNeighbours()
    }

    fun end() {
        val key = draggingKey ?: return
        val to = items.indexOfFirst { keyOf(it) == key }
        draggingKey = null
        offset = 0f
        // After a move the local order stays until the store re-emits with it written.
        val moved = to >= 0 && to != startIndex && onMove(items[to], startIndex, to, original)
        if (!moved) items = latest
    }

    private fun infoFor(key: Any?): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.find { it.key == key }

    /**
     * One swap at most: the list's layout only catches up with [items] on the next frame,
     * and until it has, its positions would read as the row having to go straight back.
     */
    private fun swapPastNeighbours() {
        val key = draggingKey ?: return
        val held = infoFor(key) ?: return
        val index = items.indexOfFirst { keyOf(it) == key }
        val center = held.offset + offset + held.size / 2f
        val next = items.getOrNull(index + 1)?.let { infoFor(keyOf(it)) }?.takeIf { it.index == held.index + 1 }
        val previous = items.getOrNull(index - 1)?.let { infoFor(keyOf(it)) }?.takeIf { it.index == held.index - 1 }
        when {
            next != null && center > next.offset + next.size / 2f -> {
                swap(index, index + 1, held, next)
                offset -= next.size
            }
            previous != null && center < previous.offset + previous.size / 2f -> {
                swap(index, index - 1, held, previous)
                offset += previous.size
            }
        }
    }

    private fun swap(from: Int, to: Int, held: LazyListItemInfo, other: LazyListItemInfo) {
        // A lazy list keeps its first visible item in place across a reorder, which would
        // scroll the whole list along with the held row; pin the scroll position instead.
        val first = listState.firstVisibleItemIndex
        if (held.index == first || other.index == first) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        items = items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Scrolls while the held row is pressed against the top or bottom edge. */
    private suspend fun autoScroll() {
        while (draggingKey != null) {
            withFrameNanos { }
            val held = infoFor(draggingKey) ?: continue
            val layout = listState.layoutInfo
            val top = held.offset + offset
            val bottom = top + held.size
            val speed = when {
                top < layout.viewportStartOffset + layout.beforeContentPadding + edgeZone -> -edgeZone / 6
                bottom > layout.viewportEndOffset - layout.afterContentPadding - edgeZone -> edgeZone / 6
                else -> 0f
            }
            if (speed == 0f) continue
            val scrolled = listState.scrollBy(speed)
            offset += scrolled
            swapPastNeighbours()
        }
    }
}

@Composable
fun <T> rememberDragReorderState(
    listState: LazyListState,
    keyOf: (T) -> Any,
    onMove: (item: T, from: Int, to: Int, original: List<T>) -> Boolean,
): DragReorderState<T> {
    val scope = rememberCoroutineScope()
    val edgeZone = with(LocalDensity.current) { 56.dp.toPx() }
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        DragReorderState(listState, scope, edgeZone, keyOf) { item, from, to, original -> currentOnMove(item, from, to, original) }
    }
}

/** The handle a row is dragged by; it picks up immediately, without a long press. */
@Composable
fun Modifier.dragHandle(state: DragReorderState<*>, key: Any): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(state, key) {
        detectDragGestures(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                state.start(key)
            },
            onDragEnd = {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                state.end()
            },
            onDragCancel = state::end,
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
        )
    }
}
