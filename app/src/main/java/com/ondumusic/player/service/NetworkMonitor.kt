package com.ondumusic.player.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether there's a usable network, and whether it's metered — downloads care about the second. */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isMetered = MutableStateFlow(connectivity.isActiveNetworkMetered)
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    /** Called once the network comes back, for whatever was waiting on it. */
    var onRestored: () -> Unit = {}

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val wasOnline = _isOnline.value
                _isOnline.value = online
                _isMetered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (online && !wasOnline) onRestored()
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
            }
        })
    }

    /** Data Saver only restricts metered networks, so it counts only on one. */
    val isDataSaverOn: Boolean
        get() = _isMetered.value && connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    private fun currentlyOnline(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
