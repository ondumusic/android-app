package com.ondumusic.player.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ondumusic.player.OnduApplication
import com.ondumusic.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The private session from the notification shade, where a hand that's mid-workout can
 * reach it without opening the app. Greyed out with Record plays off: there is nothing
 * to be private from.
 */
class PrivateSessionTileService : TileService() {
    private val settings get() = (application as OnduApplication).container.settings
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                combine(settings.recordPlays.flow, settings.privateSessionEndsAt.flow) { _, _ -> }.collect { render() }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        settings.togglePrivateSession()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = settings.isPrivateSession
        tile.state = when {
            !settings.recordPlays.value -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_private_session)
        tile.subtitle = if (active) getString(R.string.tile_private_session_active) else null
        tile.updateTile()
    }
}
