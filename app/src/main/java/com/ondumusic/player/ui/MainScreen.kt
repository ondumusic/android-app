package com.ondumusic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.ondumusic.player.ui.components.AppSnackbars
import com.ondumusic.player.ui.components.LocalBottomContentPadding
import com.ondumusic.player.ui.components.LocalSnackbars
import com.ondumusic.player.ui.components.MainSnackbarHost
import com.ondumusic.player.ui.downloads.DownloadsScreen
import com.ondumusic.player.ui.downloads.inProgressCount
import com.ondumusic.player.ui.library.AlbumScreen
import com.ondumusic.player.ui.library.ArtistScreen
import com.ondumusic.player.ui.library.ArtistsScreen
import com.ondumusic.player.ui.navigation.LaunchAction
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.LocalTabReselects
import com.ondumusic.player.ui.navigation.Navigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.Tab
import com.ondumusic.player.ui.player.ExpandingPlayer
import com.ondumusic.player.ui.player.PlayerExpansion
import com.ondumusic.player.ui.player.MiniPlayer
import com.ondumusic.player.ui.player.MiniPlayerHeight
import com.ondumusic.player.ui.player.PlayerFeedback
import com.ondumusic.player.ui.player.playerSnackbarPadding
import com.ondumusic.player.ui.playlists.AddToPlaylistSheet
import com.ondumusic.player.ui.playlists.PlaylistScreen
import com.ondumusic.player.ui.playlists.PlaylistSyncErrorDialog
import com.ondumusic.player.ui.playlists.PlaylistsScreen
import com.ondumusic.player.ui.search.SearchScreen
import com.ondumusic.player.ui.settings.LicensesScreen
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
    var savedExpanded by rememberSaveable { mutableStateOf(false) }
    val expansionScope = rememberCoroutineScope()
    val expansion = remember { PlayerExpansion(expansionScope, savedExpanded) }
    var miniBounds by remember { mutableStateOf(Rect.Zero) }
    // Not the fraction itself: reading that here would recompose the whole shell every frame.
    val playerShowing by remember { derivedStateOf { expansion.fraction > 0f } }
    var addToPlaylist by remember { mutableStateOf<List<UUID>?>(null) }
    val reselects = remember { MutableSharedFlow<Tab>(extraBufferCapacity = 1) }
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val hasMiniPlayer = playerState.currentTrack != null
    val snackbarScope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)
    val snackbars = remember { AppSnackbars(SnackbarHostState(), snackbarScope, undoLabel) }

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
                expansion.collapse()
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
            override fun showFullPlayer() = expansion.expand()
        }
    }

    val accountMenu: @Composable () -> Unit = { AccountMenu(onDownloads = { navigator.push(Route.Downloads) }, onSettings = { navigator.push(Route.Settings) }) }
    val tabHolder = rememberSaveableStateHolder()

    // At the root of any other tab, Back goes to the first tab rather than out of the app,
    // as Android's own tabbed apps do. Deeper in a tab, NavDisplay handles Back itself.
    BackHandler(enabled = tab != Tab.Artists && stack.size == 1) { tab = Tab.Artists }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalTabReselects provides reselects, LocalSnackbars provides snackbars) {
        Box(Modifier.fillMaxSize()) {
            val layout = navigationLayout()
            // With the bar gone, nothing below the content accounts for the gesture area any more.
            val bottomInset = if (layout == NavigationSuiteType.NavigationBar) 0.dp
                else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            NavigationSuiteScaffold(
                layoutType = layout,
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
                    CompositionLocalProvider(LocalBottomContentPadding provides bottomInset + if (hasMiniPlayer) MiniPlayerHeight else 0.dp) {
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
                                    entry<Route.Artist> { ArtistScreen(it.id, downloadedOnly = it.downloadedOnly) }
                                    entry<Route.Album> { AlbumScreen(it.id, downloadedOnly = it.downloadedOnly) }
                                    entry<Route.Playlist> { PlaylistScreen(it.id) }
                                    entry<Route.Folder> { PlaylistsScreen(accountMenu, folderId = it.id) }
                                    entry<Route.Settings> { SettingsScreen() }
                                    entry<Route.Licenses> { LicensesScreen() }
                                    entry<Route.Downloads> { DownloadsScreen() }
                                },
                            )
                        }
                    }
                    MiniPlayer(
                        onOpen = expansion::expand,
                        // Stretched across a landscape phone or a tablet it reads as a banner, not a player.
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).widthIn(max = 640.dp)
                            .onGloballyPositioned { miniBounds = it.boundsInRoot() }
                            // The copy in the expanding player stands in for it as soon as that starts.
                            .graphicsLayer { alpha = if (expansion.fraction > 0f) 0f else 1f }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta -> expansion.dragBy(-delta / miniBounds.top.coerceAtLeast(1f)) },
                                onDragStopped = { velocity -> expansion.release(-velocity / miniBounds.top.coerceAtLeast(1f)) },
                            ),
                    )
                }
            }

            if (hasMiniPlayer && playerShowing) ExpandingPlayer(expansion, miniBounds)

            PlayerFeedback()
            MainSnackbarHost(Modifier.align(Alignment.BottomCenter).playerSnackbarPadding(expansion.expanded && hasMiniPlayer, hasMiniPlayer, navigationLayout()))
            PlaylistSyncErrorDialog()
        }
        addToPlaylist?.let { ids -> AddToPlaylistSheet(ids, onDismiss = { addToPlaylist = null }) }
    }

    LaunchedEffect(hasMiniPlayer) { if (!hasMiniPlayer) expansion.hide() }
    LaunchedEffect(expansion.expanded) { savedExpanded = expansion.expanded }

    val launchAction by container.launchActions.current.collectAsStateWithLifecycle()
    LaunchedEffect(launchAction) {
        when (container.launchActions.consume()) {
            LaunchAction.Search -> {
                expansion.collapse()
                tab = Tab.Search
            }
            LaunchAction.Downloads -> {
                expansion.collapse()
                navigator.push(Route.Downloads)
            }
            LaunchAction.ShuffleAll -> {
                val online = container.network.isOnline.value
                val tracks = container.library.allTracks().filter { online || it.isDownloaded }
                if (tracks.isNotEmpty()) {
                    container.player.requestPlay { container.player.playShuffled(tracks) }
                    expansion.expand()
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun AccountMenu(onDownloads: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val downloads by LocalContainer.current.downloads.state.collectAsStateWithLifecycle()
    val count = downloads.inProgressCount
    IconButton(onClick = { open = true }) {
        // A dot, as on iOS: a count of a thousand-song queue only crowds the toolbar, and the menu has it.
        BadgedBox(badge = { if (count > 0) Badge() }) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(R.string.menu_account))
        }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(if (count > 0) stringResource(R.string.menu_downloads_count, count) else stringResource(R.string.menu_downloads)) },
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

/**
 * A bottom bar on a phone held upright; a rail down the side once the window is wide, or
 * short — in landscape a bottom bar, the mini player and a top bar leave room for about one row.
 */
@Composable
private fun navigationLayout(): NavigationSuiteType {
    val size = LocalWindowInfo.current.containerDpSize
    return if (size.width >= 600.dp || size.height < 480.dp) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar
}
