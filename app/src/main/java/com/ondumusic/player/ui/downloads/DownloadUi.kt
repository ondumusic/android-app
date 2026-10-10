package com.ondumusic.player.ui.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
    private val beforeFirstDownload: () -> Unit,
) {
    internal var confirmation by mutableStateOf<Confirmation?>(null)

    /** Every user-started download goes through here, so the first one can ask for the notification permission. */
    fun start(action: suspend DownloadService.() -> Unit) {
        beforeFirstDownload()
        perform(action)
    }

    /** For everything else: on the app's scope, since the screen that asked may be gone before it finishes. */
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
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // Nothing to do with the answer: downloads run either way, only the notification differs.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val ui = remember(container) {
        DownloadUi(container.downloads, container.scope) {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (shouldRequestNotificationPermission(Build.VERSION.SDK_INT, granted, prefs.getBoolean(KEY_ASKED, false))) {
                prefs.edit { putBoolean(KEY_ASKED, true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
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

private const val PREFS = "downloads_ui"
private const val KEY_ASKED = "askedNotificationPermission"
