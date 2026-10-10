package com.ondumusic.player.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.UUID

/**
 * What screens ask of the shell: show something, or open one of the app-wide sheets.
 * Going to an artist or album from a menu switches to that tab first, as on iOS.
 */
interface Navigator {
    fun push(route: Route)
    fun pop()
    fun showArtist(id: UUID)
    fun showAlbum(id: UUID)
    fun showPlaylist(id: UUID)
    fun addToPlaylist(trackIds: List<UUID>)
    fun showFullPlayer()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }
