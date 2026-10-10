package com.ondumusic.player.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.player.PlayerService
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.Artwork
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.SnackbarOverlayHost
import com.ondumusic.player.ui.navigation.LocalNavigator

/**
 * The queue, as a sheet over the player — the player stays where it was underneath.
 *
 * History sits above what's playing, oldest first, so the list reads in play order and
 * opens scrolled to the current track; scrolling up goes back in time. Up next can be
 * dragged into a new order by its handles and swiped away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Clear of the status bar, but not of the navigation bar: the list ends in an
        // inset-sized spacer instead, so it scrolls behind that bar edge to edge.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
    ) {
        Box {
            QueueScreen()
            // Undoing a swipe or a clear needs the snackbar where the swipe happened.
            SnackbarOverlayHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

private sealed interface QueueEntry {
    val key: String

    data class Header(val title: Int) : QueueEntry { override val key = "header-$title" }
    data class Played(val item: QueueItem) : QueueEntry { override val key = item.id.toString() }
    data class Current(val item: QueueItem) : QueueEntry { override val key = item.id.toString() }
    data class Upcoming(val item: QueueItem) : QueueEntry { override val key = item.id.toString() }
    data object NothingNext : QueueEntry { override val key = "nothing-next" }
}

@Composable
fun QueueScreen() {
    val player = LocalContainer.current.player
    val state by player.state.collectAsStateWithLifecycle()
    val haptic = rememberPlayerHaptics()
    val listState = rememberLazyListState()
    val reorder = remember(listState) { QueueReorder(listState) }

    // During a drag the list shows the order under the finger; the player only hears
    // about it once, on release, so nothing re-lines-up the next track mid-gesture.
    val upNext = reorder.order ?: state.upNext.map { it.item }
    val history = state.history
    val current = state.currentItem
    val rows = buildList {
        if (history.isNotEmpty()) {
            add(QueueEntry.Header(R.string.queue_history))
            history.asReversed().forEach { add(QueueEntry.Played(it.item)) }
        }
        if (current != null) {
            add(QueueEntry.Header(R.string.queue_now_playing))
            add(QueueEntry.Current(current))
        }
        add(QueueEntry.Header(R.string.queue_up_next))
        if (upNext.isEmpty()) add(QueueEntry.NothingNext) else upNext.forEach { add(QueueEntry.Upcoming(it)) }
    }

    // Opened on what's playing, with whatever came before it a scroll away.
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        if (positioned) return@LaunchedEffect
        val index = rows.indexOfFirst { it is QueueEntry.Header && it.title == R.string.queue_now_playing }
        if (index > 0) listState.scrollToItem(index)
        positioned = true
    }
    reorder.showing = upNext

    Column(Modifier.fillMaxWidth()) {
        QueueHeader(state)
        if (state.queue.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                title = stringResource(R.string.queue_empty_title),
                message = stringResource(R.string.queue_empty_message),
            )
            return@Column
        }
        LaunchedEffect(reorder.draggingKey) {
            // Auto-scroll while a row is held near either edge, so it can be carried past
            // what fits on screen.
            if (reorder.draggingKey == null) return@LaunchedEffect
            while (true) {
                val speed = reorder.edgeScrollSpeed()
                if (speed != 0f) {
                    reorder.scrolled(listState.scrollBy(speed)) { haptic(HapticFeedbackType.SegmentFrequentTick) }
                }
                withFrameNanos { }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is QueueEntry.Header -> SectionHeader(stringResource(row.title), Modifier.animateItem())
                    is QueueEntry.NothingNext -> Text(
                        stringResource(R.string.queue_up_next_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    is QueueEntry.Current -> QueueRow(row.item, isCurrent = true, onClick = {}, modifier = Modifier.animateItem())
                    is QueueEntry.Played -> QueueRow(
                        row.item, isCurrent = false, dimmed = true,
                        onClick = {
                            // By id at tap time, so a row tapped as the list changes still plays what it showed.
                            val offset = player.state.value.history.indexOfFirst { it.item.id == row.item.id }
                            if (offset >= 0) player.skipTo(QueuePositions.history(player.state.value.currentIndex, offset))
                        },
                        modifier = Modifier.animateItem(),
                    )
                    is QueueEntry.Upcoming -> UpcomingRow(row.item, reorder, haptic)
                }
            }
            item(key = "bottom-inset") {
                Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun QueueHeader(state: PlayerService.State) {
    val container = LocalContainer.current
    val player = container.player
    val navigator = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        // Repeated from the player because this sheet covers it, and they decide what the
        // list below is about to do.
        FilterChip(
            selected = state.isShuffled,
            onClick = player::toggleShuffle,
            label = { Text(stringResource(R.string.player_shuffle)) },
            leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        Box(Modifier.size(8.dp))
        FilterChip(
            selected = state.repeatMode != RepeatMode.Off,
            onClick = player::cycleRepeatMode,
            label = {
                val label = when (state.repeatMode) {
                    RepeatMode.Off -> R.string.player_repeat
                    RepeatMode.All -> R.string.player_repeat_all
                    RepeatMode.One -> R.string.player_repeat_one
                }
                Text(stringResource(label))
            },
            leadingIcon = {
                Icon(if (state.repeatMode == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.queue_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val remaining = listOfNotNull(state.currentItem) + state.upNext.map { it.item }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_save_to_playlist)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                    enabled = remaining.isNotEmpty(),
                    onClick = { menu = false; navigator.addToPlaylist(remaining.map { it.track.id }) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_clear_up_next)) },
                    leadingIcon = { Icon(Icons.Outlined.ClearAll, contentDescription = null) },
                    enabled = state.upNext.isNotEmpty(),
                    onClick = { menu = false; player.clearUpNext() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_clear_history)) },
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    enabled = state.history.isNotEmpty(),
                    onClick = { menu = false; player.clearHistory() },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    containerColor: Color = Color.Transparent,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                item.track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = { Text(item.track.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Artwork(item.artworkUrl, Modifier.size(48.dp), cornerRadius = 6.dp)
                if (isCurrent) {
                    Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.now_playing),
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
        trailingContent = trailing ?: {
            Text(item.track.formattedDuration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = modifier.alpha(if (dimmed) 0.6f else 1f).clickable(enabled = !isCurrent, onClick = onClick),
    )
}

/** One up-next row: tap to play it, drag its handle to reorder, swipe it away to remove it. */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.UpcomingRow(
    item: QueueItem,
    reorder: QueueReorder,
    haptic: (HapticFeedbackType) -> Unit,
) {
    val player = LocalContainer.current.player
    val key = item.id.toString()
    val dragging = reorder.draggingKey == key
    // Offsets are looked up by id when an action runs, never captured with the row: the
    // list can change between composing a row and the user acting on it.
    fun offset() = player.state.value.upNext.indexOfFirst { it.item.id == item.id }

    val dismiss = rememberSwipeToDismissBoxState()
    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "lift")
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    val remove = stringResource(R.string.queue_remove)

    SwipeToDismissBox(
        state = dismiss,
        gesturesEnabled = reorder.draggingKey == null,
        onDismiss = {
            // Gone already means it played or was cleared while being swiped; the row
            // leaves with the next state either way.
            val at = offset()
            if (at >= 0) {
                haptic(HapticFeedbackType.GestureThresholdActivate)
                player.removeFromQueue(setOf(at))
            }
        },
        backgroundContent = {
            val toEnd = dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        modifier = (if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = reorder.dragOffset } else Modifier.animateItem())
            .semantics {
                // Dragging and swiping have no equivalent for TalkBack; these do the same work.
                customActions = listOf(
                    CustomAccessibilityAction(moveUp) {
                        val at = offset()
                        if (at > 0) player.moveQueueItems(setOf(at), QueuePositions.moveDestination(at, at - 1))
                        at > 0
                    },
                    CustomAccessibilityAction(moveDown) {
                        val at = offset()
                        val ok = at >= 0 && at < player.state.value.upNext.lastIndex
                        if (ok) player.moveQueueItems(setOf(at), QueuePositions.moveDestination(at, at + 1))
                        ok
                    },
                    CustomAccessibilityAction(remove) {
                        val at = offset()
                        if (at >= 0) player.removeFromQueue(setOf(at))
                        at >= 0
                    },
                )
            },
    ) {
        Surface(
            shadowElevation = elevation,
            tonalElevation = elevation,
            color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            QueueRow(
                item, isCurrent = false,
                onClick = {
                    val at = offset()
                    if (at >= 0) player.skipTo(QueuePositions.upNext(player.state.value.currentIndex, at))
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.track.formattedDuration, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = stringResource(R.string.queue_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(12.dp)
                                .pointerInput(key) {
                                    detectDragGestures(
                                        onDragStart = {
                                            haptic(HapticFeedbackType.GestureThresholdActivate)
                                            reorder.start(key)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            reorder.drag(amount.y) { haptic(HapticFeedbackType.SegmentFrequentTick) }
                                        },
                                        onDragEnd = { reorder.finish(player) },
                                        onDragCancel = { reorder.finish(player) },
                                    )
                                },
                        )
                    }
                },
            )
        }
    }
}

/**
 * The drag-to-reorder state for up next. Rows trade places as the dragged one's middle
 * crosses another's, with the dragged row kept under the finger by an offset that absorbs
 * each jump in its laid-out position.
 */
private class QueueReorder(private val listState: LazyListState) {
    /** The up-next order while a drag is under way; null otherwise. */
    var order by mutableStateOf<List<QueueItem>?>(null)
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** The up-next rows on screen, refreshed each composition, for [start] to snapshot. */
    var showing: List<QueueItem> = emptyList()

    /** The layout a swap was decided against; until the list is laid out again it's stale. */
    private var layoutAtSwap: Any? = null

    fun start(key: String) {
        if (showing.none { it.id.toString() == key }) return
        order = showing
        draggingKey = key
        dragOffset = 0f
    }

    fun drag(delta: Float, onSwap: () -> Unit) {
        if (draggingKey == null) return
        dragOffset += delta
        swapIfCrossed(onSwap)
    }

    /** The list scrolled under a held row; move the row with the finger, not the list. */
    fun scrolled(by: Float, onSwap: () -> Unit) {
        if (draggingKey == null) return
        dragOffset += by
        swapIfCrossed(onSwap)
    }

    private fun swapIfCrossed(onSwap: () -> Unit) {
        val key = draggingKey ?: return
        val layout = listState.layoutInfo
        if (layout === layoutAtSwap) return
        val keys = order.orEmpty().map { it.id.toString() }
        val visible = layout.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val middle = dragged.offset + dragOffset + dragged.size / 2f
        val target = visible.firstOrNull {
            it.key != key && it.key in keys && middle > it.offset && middle < it.offset + it.size
        } ?: return
        val list = order?.toMutableList() ?: return
        val from = list.indexOfFirst { it.id.toString() == key }
        val to = list.indexOfFirst { it.id.toString() == target.key }
        if (from < 0 || to < 0) return
        list.add(to, list.removeAt(from))
        order = list
        // The row is about to be laid out in the target's slot; take that jump out of the offset.
        dragOffset += dragged.offset - target.offset
        layoutAtSwap = layout
        onSwap()
    }

    /** Pixels per frame to scroll while the held row is near an edge of the list. */
    fun edgeScrollSpeed(): Float {
        val key = draggingKey ?: return 0f
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = dragged.offset + dragOffset
        val bottom = top + dragged.size
        val edge = dragged.size.toFloat()
        return when {
            top < info.viewportStartOffset + edge * 0.5f -> -((info.viewportStartOffset + edge * 0.5f - top) / edge).coerceIn(0f, 1f) * 20f
            bottom > info.viewportEndOffset - edge * 0.5f -> ((bottom - (info.viewportEndOffset - edge * 0.5f)) / edge).coerceIn(0f, 1f) * 20f
            else -> 0f
        }
    }

    fun finish(player: PlayerService) {
        val key = draggingKey
        val final = order
        draggingKey = null
        order = null
        dragOffset = 0f
        layoutAtSwap = null
        if (key == null || final == null) return
        val to = final.indexOfFirst { it.id.toString() == key }
        // Re-find the start against the live queue: it may have advanced during the drag.
        val from = player.state.value.upNext.indexOfFirst { it.item.id.toString() == key }
        if (from >= 0 && to >= 0 && from != to) player.moveQueueItems(setOf(from), QueuePositions.moveDestination(from, to))
    }
}
