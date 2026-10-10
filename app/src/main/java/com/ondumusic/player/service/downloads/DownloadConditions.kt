package com.ondumusic.player.service.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the device says about whether downloads should run right now. */
interface DownloadConditions {
    /** Bytes free on the volume downloads are written to. */
    fun freeBytes(): Long

    /** Battery Saver, Android's counterpart of iOS's Low Power Mode. */
    val isPowerSaveMode: StateFlow<Boolean>
}

class SystemDownloadConditions(context: Context) : DownloadConditions {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    private val _isPowerSaveMode = MutableStateFlow(power.isPowerSaveMode)
    override val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    _isPowerSaveMode.value = power.isPowerSaveMode
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun freeBytes(): Long = StatFs(appContext.filesDir.path).availableBytes
}
