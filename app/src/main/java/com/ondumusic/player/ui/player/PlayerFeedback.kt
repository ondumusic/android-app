package com.ondumusic.player.ui.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.ondumusic.player.R
import com.ondumusic.player.player.PlayerService.QueueConfirmationKind
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.LocalSnackbars
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * What the player has to tell the user wherever they are: that tracks were queued, or that
 * the queue changed in a way they can undo.
 */
@Composable
fun PlayerFeedback() {
    val player = LocalContainer.current.player
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current

    LaunchedEffect(player) {
        // Consuming clears the flow, so this can't be keyed on its value: the null that
        // follows would restart the effect.
        player.queueConfirmation.filterNotNull().collect { c ->
            player.consumeQueueConfirmation()
            fun counted(one: Int, many: Int) =
                if (c.count == 1) resources.getString(one) else resources.getQuantityString(many, c.count, c.count)
            val message = when (c.kind) {
                QueueConfirmationKind.PlayNext -> counted(R.string.queue_playing_next, R.plurals.queue_playing_next_count)
                QueueConfirmationKind.AddedToQueue -> counted(R.string.queue_added, R.plurals.queue_added_count)
                QueueConfirmationKind.Replaced -> resources.getString(R.string.queue_replaced)
                QueueConfirmationKind.Removed -> counted(R.string.queue_removed, R.plurals.queue_removed_count)
                QueueConfirmationKind.ClearedUpNext -> resources.getString(R.string.queue_up_next_cleared)
                QueueConfirmationKind.ClearedHistory -> resources.getString(R.string.queue_history_cleared)
            }
            val undo = c.undo
            if (undo == null) snackbars.show(message)
            else snackbars.showUndo(message, player.undoable.map { it === undo }, onUndo = { player.undo(undo) })
        }
    }
}

/**
 * Lifts the snackbars clear of the bottom navigation and the mini player, which float over
 * content at the bottom of the window. Over the full player only the system bar is in the way.
 */
@Composable
fun Modifier.playerSnackbarPadding(fullPlayerShowing: Boolean, hasMiniPlayer: Boolean, navigation: NavigationSuiteType): Modifier {
    val systemBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    if (fullPlayerShowing) return padding(bottom = systemBar)
    val navigationBar = if (navigation == NavigationSuiteType.NavigationBar) NavigationBarHeight else 0.dp
    return padding(bottom = systemBar + navigationBar + if (hasMiniPlayer) MiniPlayerHeight else 0.dp)
}

/** Material's navigation bar height, which the scaffold doesn't expose. */
private val NavigationBarHeight = 80.dp
