package com.ondumusic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.ondumusic.player.R
import com.ondumusic.player.ui.albums.AlbumsScreen
import com.ondumusic.player.ui.components.LocalBottomContentPadding
import com.ondumusic.player.ui.downloads.DownloadsScreen
import com.ondumusic.player.ui.library.AlbumScreen
import com.ondumusic.player.ui.library.ArtistScreen
import com.ondumusic.player.ui.library.ArtistsScreen
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.LocalTabReselects
import com.ondumusic.player.ui.navigation.Navigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.Tab
import com.ondumusic.player.ui.player.FullPlayerScreen
import com.ondumusic.player.ui.player.MiniPlayer
import com.ondumusic.player.ui.player.MiniPlayerHeight
import com.ondumusic.player.ui.player.PlayerFeedback
import com.ondumusic.player.ui.player.playerSnackbarPadding
import com.ondumusic.player.ui.playlists.AddToPlaylistSheet
import com.ondumusic.player.ui.playlists.PlaylistScreen
import com.ondumusic.player.ui.playlists.PlaylistsScreen
import com.ondumusic.player.ui.search.SearchScreen
import com.ondumusic.player.ui.settings.SettingsScreen
import com.ondumusic.player.ui.songs.SongsScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

private data class TabStyle(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabStyles = mapOf(
    Tab.Artists to TabStyle(R.string.tab_artists, Icons.Outlined.Mic, Icons.Filled.Mic),
    Tab.Albums to TabStyle(R.string.tab_albums, Icons.Outlined.Album, Icons.Filled.Album),
    Tab.Songs to TabStyle(R.string.tab_songs, Icons.Outlined.MusicNote, Icons.Filled.MusicNote),
    Tab.Search to TabStyle(R.string.tab_search, Icons.Outlined.Search, Icons.Filled.Search),
    Tab.Playlists to TabStyle(R.string.tab_playlists, Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic),
)

/**
 * The signed-in app: five tabs, each with its own back stack, a mini player above the
 * navigation, and the full player over everything. The navigation is a bottom bar on a
 * phone and becomes a rail on wider windows on its own.
 */
@Composable
fun MainScreen() {
    val container = LocalContainer.current
    var tab by rememberSaveable { mutableStateOf(Tab.Artists) }
    val stacks: Map<Tab, NavBackStack<NavKey>> = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    val stack = stacks.getValue(tab)
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf<List<UUID>?>(null) }
    val reselects = remember { MutableSharedFlow<Tab>(extraBufferCapacity = 1) }
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val hasMiniPlayer = playerState.currentTrack != null
    val snackbars = remember { SnackbarHostState() }

    val navigator = remember(stacks) {
        object : Navigator {
            override fun push(route: Route) {
                stacks.getValue(tab).add(route)
            }

            override fun pop() {
                stacks.getValue(tab).let { if (it.size > 1) it.removeAt(it.lastIndex) }
            }

            /** Switches to the tab that owns the destination and opens it from that tab's root, as iOS does. */
            private fun showIn(target: Tab, route: Route) {
                showFullPlayer = false
                tab = target
                stacks.getValue(target).apply {
                    popToRoot()
                    add(route)
                }
            }

            override fun showArtist(id: UUID) = showIn(Tab.Artists, Route.Artist(id))
            override fun showAlbum(id: UUID) = showIn(Tab.Albums, Route.Album(id))
            override fun showPlaylist(id: UUID) = showIn(Tab.Playlists, Route.Playlist(id))
            override fun addToPlaylist(trackIds: List<UUID>) { addToPlaylist = trackIds }
            override fun showFullPlayer() { showFullPlayer = true }
        }
    }

    val accountMenu: @Composable () -> Unit = { AccountMenu(onDownloads = { navigator.push(Route.Downloads) }, onSettings = { navigator.push(Route.Settings) }) }
    val tabHolder = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalNavigator provides navigator, LocalTabReselects provides reselects) {
        Box(Modifier.fillMaxSize()) {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    Tab.entries.forEach { t ->
                        val style = tabStyles.getValue(t)
                        item(
                            selected = t == tab,
                            onClick = {
                                if (t == tab) {
                                    // Reselecting goes back to the tab's root, then to the top of it.
                                    val s = stacks.getValue(t)
                                    if (s.size > 1) s.popToRoot() else reselects.tryEmit(t)
                                }
                                tab = t
                            },
                            icon = { Icon(if (t == tab) style.selectedIcon else style.icon, contentDescription = null) },
                            label = { Text(stringResource(style.label)) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomContentPadding provides if (hasMiniPlayer) MiniPlayerHeight else androidx.compose.ui.unit.Dp(0f)) {
                        // Each tab keeps its scroll positions and state while another is showing.
                        tabHolder.SaveableStateProvider(tab) {
                            NavDisplay(
                                backStack = stack,
                                onBack = { navigator.pop() },
                                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                                entryProvider = entryProvider {
                                    entry<Route.Artists> { ArtistsScreen(accountMenu) }
                                    entry<Route.Albums> { AlbumsScreen(accountMenu) }
                                    entry<Route.Songs> { SongsScreen(accountMenu) }
                                    entry<Route.Search> { SearchScreen(accountMenu) }
                                    entry<Route.Playlists> { PlaylistsScreen(accountMenu) }
                                    entry<Route.Artist> { ArtistScreen(it.id) }
                                    entry<Route.Album> { AlbumScreen(it.id) }
                                    entry<Route.Playlist> { PlaylistScreen(it.id) }
                                    entry<Route.Folder> { PlaylistsScreen(accountMenu, folderId = it.id) }
                                    entry<Route.Settings> { SettingsScreen() }
                                    entry<Route.Downloads> { DownloadsScreen() }
                                },
                            )
                        }
                    }
                    MiniPlayer(onOpen = { showFullPlayer = true }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }

            AnimatedVisibility(
                visible = showFullPlayer && hasMiniPlayer,
                enter = slideInVertically(tween(300)) { it },
                exit = slideOutVertically(tween(250)) { it },
            ) {
                BackHandler { showFullPlayer = false }
                FullPlayerScreen(onClose = { showFullPlayer = false })
            }

            PlayerFeedback(snackbars)
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).playerSnackbarPadding(showFullPlayer && hasMiniPlayer, hasMiniPlayer))
        }
        addToPlaylist?.let { ids -> AddToPlaylistSheet(ids, onDismiss = { addToPlaylist = null }) }
    }

    LaunchedEffect(hasMiniPlayer) { if (!hasMiniPlayer) showFullPlayer = false }
}

@Composable
private fun AccountMenu(onDownloads: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(R.string.menu_account))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_downloads)) },
            leadingIcon = { Icon(Icons.Outlined.DownloadForOffline, contentDescription = null) },
            onClick = { open = false; onDownloads() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_settings)) },
            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            onClick = { open = false; onSettings() },
        )
    }
}

private fun NavBackStack<NavKey>.popToRoot() {
    while (size > 1) removeAt(lastIndex)
}
