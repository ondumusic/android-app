package com.ondumusic.player.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.ondumusic.player.api.SerialUuid
import kotlinx.serialization.Serializable

/** Every screen a tab can show. Serializable so back stacks survive process death. */
sealed interface Route : NavKey {
    @Serializable data object Artists : Route
    @Serializable data object Albums : Route
    @Serializable data object Songs : Route
    @Serializable data object Search : Route
    @Serializable data object Playlists : Route

    /** [downloadedOnly] shows only what's on the device: the artists and albums reached from Downloads. */
    @Serializable data class Artist(val id: SerialUuid, val downloadedOnly: Boolean = false) : Route
    @Serializable data class Album(val id: SerialUuid, val downloadedOnly: Boolean = false) : Route
    @Serializable data class Playlist(val id: SerialUuid) : Route
    @Serializable data class Folder(val id: SerialUuid) : Route

    @Serializable data object Settings : Route
    @Serializable data object Downloads : Route
}

enum class Tab(val root: Route) {
    Artists(Route.Artists),
    Albums(Route.Albums),
    Songs(Route.Songs),
    Search(Route.Search),
    Playlists(Route.Playlists),
}
