package com.ondumusic.player.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.AlbumSortOption
import com.ondumusic.player.settings.LibraryFilter
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.browse.SortChoice
import com.ondumusic.player.ui.browse.SortFilterMenu
import com.ondumusic.player.ui.components.AlbumCard
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.LibraryRefresh
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.ScrollToTopOnReselect
import com.ondumusic.player.ui.navigation.Tab
import kotlinx.coroutines.flow.flowOf

@Composable
fun AlbumsScreen(actions: @Composable () -> Unit) {
    val container = LocalContainer.current
    val library = container.library
    val settings = container.settings
    val navigator = LocalNavigator.current

    val filter by settings.libraryFilter.flow.collectAsStateWithLifecycle()
    val sort by settings.albumListSort.flow.collectAsStateWithLifecycle()
    val ascending by settings.albumListSortAscending.flow.collectAsStateWithLifecycle()

    val albums by remember { library.observeAlbums() }.collectAsStateWithLifecycle(null)
    // Each is only read while it matters: the stats cost a pass over every played track.
    val playStats by remember(sort.needsPlayStats) {
        if (sort.needsPlayStats) library.observeAlbumPlayStats() else flowOf(emptyMap())
    }.collectAsStateWithLifecycle(emptyMap())
    val downloadedIds by remember(filter) {
        if (filter == LibraryFilter.Downloaded) library.observeDownloadedAlbumIds() else flowOf(null)
    }.collectAsStateWithLifecycle(null)

    val shown = remember(albums, filter, downloadedIds, sort, ascending, playStats) {
        albums?.let { all ->
            val source = if (filter == LibraryFilter.Downloaded) {
                // Still loading: showing nothing yet beats flashing "No Downloaded Albums".
                val ids = downloadedIds ?: return@let null
                all.filter { it.id in ids }
            } else all
            sort.sorted(source, ascending, playStats)
        }
    }

    val gridState = rememberLazyGridState()
    ScrollToTopOnReselect(Tab.Albums, gridState)

    ScreenScaffold(
        stringResource(R.string.tab_albums),
        actions = {
            SortFilterMenu(
                filter = filter,
                onFilter = { settings.libraryFilter.value = it },
                choices = AlbumSortOption.entries.map { option ->
                    val selected = option == sort
                    SortChoice(
                        label = stringResource(option.label),
                        selected = selected,
                        ascending = ascending,
                        direction = stringResource(if (ascending) option.ascendingLabel else option.descendingLabel),
                        onClick = {
                            if (selected) {
                                settings.albumListSortAscending.value = !ascending
                            } else {
                                settings.albumListSort.value = option
                                settings.albumListSortAscending.value = option.defaultAscending
                            }
                        },
                    )
                },
            )
            actions()
        },
    ) { padding ->
        LibraryRefresh(Modifier.padding(top = padding.calculateTopPadding())) {
            if (shown?.isEmpty() == true) {
                EmptyState(
                    Icons.Outlined.Album,
                    stringResource(if (filter == LibraryFilter.Downloaded) R.string.no_downloaded_albums else R.string.no_albums),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown.orEmpty(), key = { it.id }) { album ->
                    AlbumCard(
                        album,
                        onClick = { navigator.push(Route.Album(album.id)) },
                        subtitle = album.artistName,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private val AlbumSortOption.label: Int
    get() = when (this) {
        AlbumSortOption.Artist -> R.string.sort_artist
        AlbumSortOption.DateAdded -> R.string.sort_date_added
        AlbumSortOption.Name -> R.string.sort_name
        AlbumSortOption.ReleaseYear -> R.string.sort_release_year
        AlbumSortOption.Plays -> R.string.sort_plays
        AlbumSortOption.LastPlayed -> R.string.sort_last_played
    }

private val AlbumSortOption.ascendingLabel: Int
    get() = when (this) {
        AlbumSortOption.Artist, AlbumSortOption.Name -> R.string.sort_a_to_z
        AlbumSortOption.DateAdded, AlbumSortOption.ReleaseYear -> R.string.sort_oldest_first
        AlbumSortOption.Plays -> R.string.sort_least_played
        AlbumSortOption.LastPlayed -> R.string.sort_longest_ago
    }

private val AlbumSortOption.descendingLabel: Int
    get() = when (this) {
        AlbumSortOption.Artist, AlbumSortOption.Name -> R.string.sort_z_to_a
        AlbumSortOption.DateAdded, AlbumSortOption.ReleaseYear -> R.string.sort_newest_first
        AlbumSortOption.Plays -> R.string.sort_most_played
        AlbumSortOption.LastPlayed -> R.string.sort_most_recent
    }
