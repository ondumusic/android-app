package com.ondumusic.player.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ondumusic.player.R

/** Where media is heard right now, for the output button to name — as the system's own media controls do. */
data class OutputRoute(val name: String, val icon: ImageVector, val isExternal: Boolean)

/**
 * The output media is routed to, kept current as devices connect and disconnect. Read
 * from the audio framework rather than a media router: it needs no permission and no
 * extra dependency, and the system output switcher is what actually changes the route.
 */
@Composable
fun rememberOutputRoute(): OutputRoute {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var device by remember { mutableStateOf(audio.currentMediaOutput()) }
    DisposableEffect(audio) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { device = audio.currentMediaOutput() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { device = audio.currentMediaOutput() }
        }
        audio.registerAudioDeviceCallback(callback, null)
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return device.toRoute()
}

@Composable
private fun AudioDeviceInfo?.toRoute(): OutputRoute {
    val kind = this?.type?.let(::kindOf) ?: OutputKind.BuiltIn
    // Bluetooth devices carry the name the user gave them; a wired jack's product name is
    // usually the phone's own model, which says nothing.
    val productName = this?.productName?.toString()?.takeIf { it.isNotBlank() && kind == OutputKind.Bluetooth }
    return when (kind) {
        OutputKind.BuiltIn -> OutputRoute(stringResource(R.string.player_output_this_device), Icons.Outlined.Speaker, isExternal = false)
        OutputKind.Bluetooth -> OutputRoute(productName ?: stringResource(R.string.player_output_bluetooth), Icons.Outlined.Bluetooth, true)
        OutputKind.Wired -> OutputRoute(stringResource(R.string.player_output_headphones), Icons.Outlined.Headphones, true)
        OutputKind.Usb -> OutputRoute(stringResource(R.string.player_output_usb), Icons.Outlined.Usb, true)
        OutputKind.Hdmi -> OutputRoute(stringResource(R.string.player_output_hdmi), Icons.Outlined.Tv, true)
    }
}

private enum class OutputKind { BuiltIn, Bluetooth, Wired, Usb, Hdmi }

private fun kindOf(type: Int): OutputKind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.BuiltIn
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputKind.Wired
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputKind.Hdmi
    else -> null
}

private val mediaAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

private fun AudioManager.currentMediaOutput(): AudioDeviceInfo? {
    // Android 13 can say where media would go. Before that, guess the way the policy
    // decides: the most recently connected external device wins over the speaker.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getAudioDevicesForAttributes(mediaAttributes).firstOrNull { kindOf(it.type) != null }?.let { return it }
    }
    val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val preference = listOf(OutputKind.Bluetooth, OutputKind.Wired, OutputKind.Usb, OutputKind.Hdmi)
    return preference.firstNotNullOfOrNull { kind -> outputs.lastOrNull { kindOf(it.type) == kind } }
}

/**
 * Opens the system's output switcher for this app — the same panel the media controls in
 * quick settings open — so switching to headphones, a speaker or a cast device works the
 * way it does everywhere else on the phone.
 *
 * Android 14 made this public API. Before that the only way in is the broadcast System UI
 * listens for, which some vendors drop; Bluetooth settings is the last resort, since that's
 * at least where a new output gets connected.
 */
fun showSystemOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        MediaRouter2.getInstance(context).showSystemOutputSwitcher()
    ) return

    val broadcast = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
        .setPackage("com.android.systemui")
        .putExtra("package_name", context.packageName)
    if (context.packageManager.queryBroadcastReceivers(broadcast, 0).isNotEmpty()) {
        context.sendBroadcast(broadcast)
        return
    }
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nowhere left to send the user; the button simply does nothing on such a device.
    }
}
