package com.ondumusic.player.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ondumusic.player.R
import com.ondumusic.player.auth.LocalNetwork
import okhttp3.HttpUrl

/** Android's own prompt only names the permission, so this says why first. */
@Composable
fun LocalNetworkExplanation(onContinue: () -> Unit, onDismiss: () -> Unit, dismissLabel: String) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_network_title)) },
        text = { Text(stringResource(R.string.local_network_message)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.local_network_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Ondu's page in system settings: once refused, the permission can only be allowed there. */
fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

private enum class Step { Checking, Explaining, Asking, Refused, Done }

/**
 * For a session that's already signed in: after an update to Android 17, or a refusal
 * before, a server on the home network stops answering with no hint why. Asked once per
 * launch; [onGranted] lets whatever timed out try again. Coming back from system settings
 * does that on its own, as the app returns to the foreground.
 */
@Composable
fun LocalNetworkAccessCheck(serverUrl: HttpUrl, onGranted: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable(serverUrl.toString()) { mutableStateOf(Step.Checking) }
    LaunchedEffect(serverUrl) {
        if (step == Step.Checking) step = if (LocalNetwork.needsPermission(context, serverUrl)) Step.Explaining else Step.Done
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        step = if (granted) Step.Done else Step.Refused
        if (granted) onGranted()
    }
    when (step) {
        Step.Explaining -> LocalNetworkExplanation(
            onContinue = { step = Step.Asking; launcher.launch(LocalNetwork.PERMISSION) },
            onDismiss = { step = Step.Done },
            dismissLabel = stringResource(R.string.not_now),
        )
        Step.Refused -> AlertDialog(
            onDismissRequest = { step = Step.Done },
            title = { Text(stringResource(R.string.local_network_refused_title)) },
            text = { Text(stringResource(R.string.local_network_refused_message)) },
            confirmButton = {
                TextButton(onClick = { step = Step.Done; openAppSettings(context) }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = { step = Step.Done }) { Text(stringResource(R.string.close)) } },
        )
        Step.Checking, Step.Asking, Step.Done -> Unit
    }
}
