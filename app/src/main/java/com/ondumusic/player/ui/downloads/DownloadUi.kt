package com.ondumusic.player.ui.downloads

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ondumusic.player.service.downloads.DownloadService
import com.ondumusic.player.ui.LocalContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A question asked before a download action that's costly to undo: losing files, or starting a very large download. */
class Confirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val destructive: Boolean = true,
    val onConfirm: () -> Unit,
)

/**
 * What download actions need from the app around them. Owned by the main screen rather than
 * the sheet an action is chosen in: the sheet is gone by the time the action runs, and with
 * it anything it could have shown or launched.
 */
class DownloadUi internal constructor(
    private val downloads: DownloadService,
    private val scope: CoroutineScope,
) {
    internal var confirmation by mutableStateOf<Confirmation?>(null)

    /** On the app's scope, since the screen that asked may be gone before it finishes. */
    fun perform(action: suspend DownloadService.() -> Unit) {
        scope.launch { downloads.action() }
    }

    fun confirm(confirmation: Confirmation) {
        this.confirmation = confirmation
    }
}

val LocalDownloadUi = staticCompositionLocalOf<DownloadUi> { error("No DownloadUi provided") }

@Composable
fun ProvideDownloadUi(content: @Composable () -> Unit) {
    val container = LocalContainer.current
    // No notification permission is asked for: downloads run at full speed in the foreground
    // either way, and Android's bare prompt explained nothing. Without it the progress
    // notification is hidden; it shows if notifications are turned on in system settings.
    val ui = remember(container) { DownloadUi(container.downloads, container.scope) }
    CompositionLocalProvider(LocalDownloadUi provides ui) { content() }

    ui.confirmation?.let { c ->
        AlertDialog(
            onDismissRequest = { ui.confirmation = null },
            title = { Text(c.title) },
            text = { Text(c.message) },
            confirmButton = {
                TextButton(onClick = { ui.confirmation = null; c.onConfirm() }) {
                    Text(c.confirmLabel, color = if (c.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { ui.confirmation = null }) { Text(c.dismissLabel) } },
        )
    }
}

