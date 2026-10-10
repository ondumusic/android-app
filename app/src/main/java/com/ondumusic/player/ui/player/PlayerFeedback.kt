package com.ondumusic.player.ui.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.player.PlayerService.QueueConfirmationKind
import com.ondumusic.player.ui.LocalContainer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * What the player has to tell the user wherever they are: a snackbar confirming that
 * tracks were queued, and the question before a hand-built queue is replaced.
 */
@Composable
fun PlayerFeedback(snackbars: SnackbarHostState) {
    val player = LocalContainer.current.player
    val resources = LocalResources.current
    val askToReplace by player.replaceQueuePrompt.collectAsStateWithLifecycle()

    LaunchedEffect(player) {
        // Consuming clears the flow, so this can't be keyed on its value: the null that
        // follows would cancel the snackbar it had just started showing.
        player.queueConfirmation.filterNotNull().collectLatest { c ->
            player.consumeQueueConfirmation()
            val message = when (c.kind) {
                QueueConfirmationKind.PlayNext ->
                    if (c.count == 1) resources.getString(R.string.queue_playing_next)
                    else resources.getQuantityString(R.plurals.queue_playing_next_count, c.count, c.count)
                QueueConfirmationKind.AddedToQueue ->
                    if (c.count == 1) resources.getString(R.string.queue_added)
                    else resources.getQuantityString(R.plurals.queue_added_count, c.count, c.count)
            }
            // A second confirmation replaces the first rather than queueing behind it: the
            // newest one describes the queue as it is now.
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    if (askToReplace) {
        AlertDialog(
            onDismissRequest = player::cancelPlay,
            title = { Text(stringResource(R.string.replace_queue_title)) },
            text = { Text(stringResource(R.string.replace_queue_message)) },
            // Replacing loses nothing the user made and is what they just asked for, so
            // it's the plain confirm action rather than a destructive one.
            confirmButton = { TextButton(onClick = player::confirmPlay) { Text(stringResource(R.string.replace_queue_confirm)) } },
            dismissButton = { TextButton(onClick = player::cancelPlay) { Text(stringResource(R.string.replace_queue_cancel)) } },
        )
    }
}

/**
 * Lifts the player's snackbars clear of the bottom navigation and the mini player, which
 * float over content at the bottom of the window. Over the full player only the system bar
 * is in the way.
 */
@Composable
fun Modifier.playerSnackbarPadding(fullPlayerShowing: Boolean, hasMiniPlayer: Boolean): Modifier {
    val systemBar = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    if (fullPlayerShowing) return padding(bottom = systemBar)
    val navigation = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val navigationBar = if (navigation == NavigationSuiteType.NavigationBar) NavigationBarHeight else 0.dp
    return padding(bottom = systemBar + navigationBar + if (hasMiniPlayer) MiniPlayerHeight else 0.dp)
}

/** Material's navigation bar height, which the scaffold doesn't expose. */
private val NavigationBarHeight = 80.dp
