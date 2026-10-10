package com.ondumusic.player.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The app's snackbars, shown in the one host the main screen places above the mini player
 * and navigation. Owned by the main screen rather than whatever asks: a screen that removes
 * something is often gone before its snackbar times out.
 *
 * Only one snackbar shows at a time and a new one replaces the current one, since the
 * newest describes how things are now.
 */
class AppSnackbars internal constructor(
    val host: SnackbarHostState,
    private val scope: CoroutineScope,
    private val undoLabel: String,
) {
    /** Sheets in front of the main host show the snackbars themselves while they cover it. */
    internal var overlays by mutableIntStateOf(0)

    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    /**
     * Shows [message] with an Undo action. Exactly one of [onUndo] or [onExpired] runs:
     * [onUndo] when the action is pressed, [onExpired] when the snackbar times out, is
     * replaced or swiped away, or the host leaves composition — so a change held back
     * until the snackbar goes is never left hanging. The snackbar goes early once
     * [stillUndoable] turns false, when there's nothing left to undo.
     */
    fun showUndo(message: String, stillUndoable: Flow<Boolean>, onUndo: () -> Unit, onExpired: () -> Unit = {}) {
        scope.launch {
            var undone = false
            try {
                host.currentSnackbarData?.dismiss()
                undone = channelFlow {
                    launch { send(host.showSnackbar(message, undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) }
                    stillUndoable.first { !it }
                    send(false)
                }.first()
            } finally {
                if (undone) onUndo() else onExpired()
            }
        }
    }
}

val LocalSnackbars = staticCompositionLocalOf<AppSnackbars> { error("No AppSnackbars provided") }

/**
 * The shared snackbars, shown inside a sheet that covers the main host — a modal sheet is a
 * window of its own, drawn over everything in the main one. The main host stands down while
 * this is shown so the same snackbar never appears twice.
 */
@Composable
fun SnackbarOverlayHost(modifier: Modifier = Modifier) {
    val snackbars = LocalSnackbars.current
    DisposableEffect(snackbars) {
        snackbars.overlays++
        onDispose { snackbars.overlays-- }
    }
    SnackbarHost(snackbars.host, modifier)
}

/** The main screen's host, hidden while a sheet shows the snackbars instead. */
@Composable
fun MainSnackbarHost(modifier: Modifier = Modifier) {
    val snackbars = LocalSnackbars.current
    if (snackbars.overlays == 0) SnackbarHost(snackbars.host, modifier)
}
