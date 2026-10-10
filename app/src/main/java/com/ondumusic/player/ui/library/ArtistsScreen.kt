package com.ondumusic.player.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Artist
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.ArtistRow
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.LibraryRefresh
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.navigation.LocalNavigator
import com.ondumusic.player.ui.navigation.Route
import com.ondumusic.player.ui.navigation.ScrollToTopOnReselect
import com.ondumusic.player.ui.navigation.Tab

/** Artists by sort name, under a letter for each initial; anything that doesn't start with a letter files under #. */
fun artistSections(artists: List<Artist>): List<Pair<String, List<Artist>>> =
    artists.groupBy { artist ->
        val first = artist.sortName.firstOrNull()?.uppercaseChar()
        if (first != null && first.isLetter()) first.toString() else "#"
    }.toList()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistsScreen(actions: @Composable () -> Unit) {
    val library = LocalContainer.current.library
    val navigator = LocalNavigator.current
    val artists by remember { library.observeArtists() }.collectAsStateWithLifecycle(null)
    val sections = remember(artists) { artists?.let(::artistSections).orEmpty() }
    val listState = rememberLazyListState()
    ScrollToTopOnReselect(Tab.Artists, listState)

    ScreenScaffold(stringResource(R.string.tab_artists), actions = { actions() }) { padding ->
        LibraryRefresh(Modifier.padding(top = padding.calculateTopPadding())) {
            if (artists?.isEmpty() == true) {
                EmptyState(Icons.Outlined.Mic, stringResource(R.string.no_artists))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = padding.calculateBottomPadding())) {
                sections.forEach { (letter, group) ->
                    stickyHeader(key = "header-$letter") {
                        Surface(Modifier.fillMaxWidth()) {
                            Text(letter, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        }
                    }
                    items(group, key = { it.id }) { artist ->
                        ArtistRow(artist, onClick = { navigator.push(Route.Artist(artist.id)) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}
