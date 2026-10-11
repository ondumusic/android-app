package com.ondumusic.player.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.data.SearchHistoryItem
import com.ondumusic.player.data.SearchResults
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.Track
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.AlbumActionSheet
import com.ondumusic.player.ui.components.ArtistRow
import com.ondumusic.player.ui.components.Artwork
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.LocalBottomContentPadding
import com.ondumusic.player.ui.components.TrackRow
import com.ondumusic.player.ui.components.rowGestures
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.ScrollToTopOnReselect
import com.ondumusic.player.ui.navigation.Tab
import kotlinx.coroutines.delay

/** How many of each kind the results show, as on iOS: enough to find the one you meant without the songs burying the rest. */
private const val ARTIST_LIMIT = 5
private const val ALBUM_LIMIT = 5
private const val SONG_LIMIT = 10

/** Long enough to skip the searches between keystrokes, short enough to feel live. */
private const val DEBOUNCE_MS = 250L

/** A recent search with what it points at, looked up again so it shows current names and artwork. */
private class Recent(val item: SearchHistoryItem, val artist: Artist? = null, val album: Album? = null, val track: Track? = null)

/**
 * The field stays at the top rather than expanding over the screen: the tab exists for
 * searching, and results open artists and albums inside this tab, which an expanded
 * search would have to collapse around.
 */
@Composable
fun SearchScreen(actions: @Composable () -> Unit) {
    val container = LocalContainer.current
    val library = container.library
    val navigator = LocalNavigator.current
    val focusManager = LocalFocusManager.current
    val history = container.searchHistory
    val historyItems by history.items.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    // Re-run the search when the library changes underneath it, e.g. a sync or a finished download.
    val libraryTracks by remember { library.observeAllTracks() }.collectAsStateWithLifecycle(null)
    var results by remember { mutableStateOf<SearchResults?>(null) }
    LaunchedEffect(query, libraryTracks) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        if (libraryTracks == null) return@LaunchedEffect
        // Coming back to the tab there is nothing on screen to keep, so no reason to wait.
        if (results != null) delay(DEBOUNCE_MS)
        results = library.search(query.trim())
    }

    val recents by produceState(emptyList<Recent>(), historyItems, libraryTracks) {
        value = historyItems.mapNotNull { item ->
            // Entries whose target has left the library drop out quietly, as on iOS.
            when (item.kind) {
                SearchHistoryItem.Kind.Artist -> library.artist(item.id)?.let { Recent(item, artist = it) }
                SearchHistoryItem.Kind.Album -> library.album(item.id)?.let { Recent(item, album = it) }
                SearchHistoryItem.Kind.Song -> library.track(item.id)?.let { Recent(item, track = it) }
            }
        }
    }

    val listState = rememberLazyListState()
    ScrollToTopOnReselect(Tab.Search, listState)
    var lastQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != lastQuery) {
            lastQuery = query
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focusManager.clearFocus() }

    fun openArtist(artist: Artist, record: Boolean) {
        focusManager.clearFocus()
        if (record) history.add(SearchHistoryItem(artist.id, SearchHistoryItem.Kind.Artist, artist.name))
        navigator.push(Route.Artist(artist.id))
    }

    fun openAlbum(album: Album, record: Boolean) {
        focusManager.clearFocus()
        if (record) {
            history.add(SearchHistoryItem(album.id, SearchHistoryItem.Kind.Album, album.name, subtitle = album.artistName,
                artworkUrl = album.artworkUrl, artistId = album.artistId))
        }
        navigator.push(Route.Album(album.id))
    }

    /** Plays [from] and the songs after it in [list], skipping what can't play offline. */
    fun playSong(track: Track, from: List<Track>) {
        focusManager.clearFocus()
        val online = container.network.isOnline.value
        val available = if (online) from else from.filter { it.isDownloaded }
        val index = available.indexOfFirst { it.id == track.id }
        if (index < 0) return
        val player = container.player
        player.requestPlay { player.playNow(available, startingAt = index) }
        history.add(SearchHistoryItem(track.id, SearchHistoryItem.Kind.Song, track.name, subtitle = track.artist.name,
            artworkUrl = track.album?.artworkUrl, artistId = track.artistId, albumId = track.albumId))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SearchField(query, onQueryChange = { query = it }, onSearch = { focusManager.clearFocus() }, actions = actions) },
    ) { padding ->
        val bottom = padding.calculateBottomPadding() + LocalBottomContentPadding.current
        val current = results
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                query.isBlank() && recents.isEmpty() ->
                    EmptyState(Icons.Outlined.Search, stringResource(R.string.search_prompt_title), message = stringResource(R.string.search_prompt_message))
                current?.isEmpty == true ->
                    EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.search_no_results, query.trim()),
                        message = stringResource(R.string.search_no_results_message))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
                if (query.isBlank()) {
                    recentItems(recents, onClear = history::clear, onRemove = { history.remove(it) },
                        onArtist = { openArtist(it, record = false) },
                        onAlbum = { openAlbum(it, record = false) },
                        onSong = { playSong(it, listOf(it)) })
                } else if (current != null) {
                    resultItems(current,
                        onArtist = { openArtist(it, record = true) },
                        onAlbum = { openAlbum(it, record = true) },
                        onSong = { playSong(it, current.tracks) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit, actions: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Surface(
            shape = SearchBarDefaults.inputFieldShape,
            color = SearchBarDefaults.colors().containerColor,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onSearch() },
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    // The account menu lives in the bar, as other tabs have it in theirs; while
                    // typing that place goes to clearing the field.
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear_query))
                        }
                    } else {
                        actions()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(@StringRes title: Int, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        trailing?.invoke()
    }
}

private fun LazyListScope.resultItems(results: SearchResults, onArtist: (Artist) -> Unit, onAlbum: (Album) -> Unit, onSong: (Track) -> Unit) {
    if (results.artists.isNotEmpty()) {
        item(key = "artists", contentType = "header") { SectionHeader(R.string.tab_artists) }
        items(results.artists.take(ARTIST_LIMIT), key = { "artist-${it.id}" }, contentType = { "artist" }) { artist ->
            ArtistRow(artist, onClick = { onArtist(artist) })
        }
    }
    if (results.albums.isNotEmpty()) {
        item(key = "albums", contentType = "header") { SectionHeader(R.string.tab_albums) }
        items(results.albums.take(ALBUM_LIMIT), key = { "album-${it.id}" }, contentType = { "album" }) { album ->
            AlbumRow(album, onClick = { onAlbum(album) })
        }
    }
    if (results.tracks.isNotEmpty()) {
        item(key = "songs", contentType = "header") { SectionHeader(R.string.tab_songs) }
        items(results.tracks.take(SONG_LIMIT), key = { "song-${it.id}" }, contentType = { "song" }) { track ->
            TrackRow(track, onClick = { onSong(track) }, showArtwork = true, showNumber = false, showArtist = true)
        }
    }
}

private fun LazyListScope.recentItems(
    recents: List<Recent>,
    onClear: () -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onSong: (Track) -> Unit,
) {
    if (recents.isEmpty()) return
    item(key = "recent", contentType = "header") {
        SectionHeader(R.string.search_recent) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_recent)) }
        }
    }
    items(recents, key = { "recent-${it.item.kind}-${it.item.id}" }, contentType = { "recent" }) { recent ->
        SwipeToRemove(onRemove = { onRemove(recent.item) }, modifier = Modifier.animateItem()) {
            when {
                recent.artist != null -> ArtistRow(recent.artist, onClick = { onArtist(recent.artist) })
                recent.album != null -> AlbumRow(recent.album, onClick = { onAlbum(recent.album) })
                recent.track != null -> TrackRow(recent.track, onClick = { onSong(recent.track) }, showArtwork = true, showNumber = false, showArtist = true)
            }
        }
    }
}

/** Swiping a recent search away is the Android counterpart of iOS's swipe-to-delete. */
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onRemove()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.search_remove_recent),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Surface { content() }
    }
}

/** An album as a list row: search mixes it with artists and songs, where a grid card wouldn't fit. */
@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    var showActions by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = (album.artistName ?: album.releaseYear?.toString())?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Artwork(album.artwork, album.artworkUrl, ArtworkSize.Thumbnail, Modifier.size(48.dp), cornerRadius = 4.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.rowGestures(onClick = onClick, onLongClick = { showActions = true }),
    )
    if (showActions) AlbumActionSheet(album, onDismiss = { showActions = false })
}

