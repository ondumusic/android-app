package com.ondumusic.player.ui.songs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.R
import com.ondumusic.player.model.Track
import com.ondumusic.player.model.TrackSort
import com.ondumusic.player.model.TrackSortColumn
import com.ondumusic.player.settings.LibraryFilter
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.browse.SortChoice
import com.ondumusic.player.ui.browse.SortFilterMenu
import com.ondumusic.player.ui.components.EmptyState
import com.ondumusic.player.ui.components.LibraryRefresh
import com.ondumusic.player.ui.components.ScreenScaffold
import com.ondumusic.player.ui.components.TrackRow
import com.ondumusic.player.ui.navigation.ScrollToTopOnReselect
import com.ondumusic.player.ui.navigation.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** A run of songs under one letter; [letter] is null when the order has no letters to file by. */
class SongSection(val letter: String?, val tracks: List<Track>)

/** The columns worth choosing on a phone: track and disc numbers mean nothing outside an album. */
private val songSortColumns = listOf(
    TrackSortColumn.Title, TrackSortColumn.Artist, TrackSortColumn.Album, TrackSortColumn.Year,
    TrackSortColumn.Duration, TrackSortColumn.Plays, TrackSortColumn.LastPlayed,
)

/**
 * Songs under a letter for each initial of whatever the order is alphabetical by: the album
 * artist in the library's own order, as iOS files them, or the title. Orders by number
 * or date have no letters, so they're one unbroken run.
 */
fun songSections(tracks: List<Track>, sort: TrackSort?): List<SongSection> {
    val letterOf: ((Track) -> String)? = when (sort?.column) {
        null, TrackSortColumn.Artist -> { t -> initial(t.albumArtistSortName) }
        TrackSortColumn.Title -> { t -> initial(t.name) }
        else -> null
    }
    if (letterOf == null) return if (tracks.isEmpty()) emptyList() else listOf(SongSection(null, tracks))
    val sections = mutableListOf<SongSection>()
    var start = 0
    for (i in 1..tracks.size) {
        if (i == tracks.size || letterOf(tracks[i]) != letterOf(tracks[start])) {
            sections += SongSection(letterOf(tracks[start]), tracks.subList(start, i))
            start = i
        }
    }
    return sections
}

/** Accents fold into their letter, which is where natural ordering puts them anyway. */
private fun initial(name: String): String {
    val first = name.firstOrNull() ?: return "#"
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
    return if (base.isLetter()) base.toString() else "#"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongsScreen(actions: @Composable () -> Unit) {
    val container = LocalContainer.current
    val settings = container.settings
    val filter by settings.libraryFilter.flow.collectAsStateWithLifecycle()
    val sort by settings.songListSort.flow.collectAsStateWithLifecycle()
    val allTracks by remember { container.library.observeAllTracks() }.collectAsStateWithLifecycle(null)

    // Sorting and sectioning tens of thousands of songs is too slow for the main thread.
    val shown by produceState<Pair<List<Track>, List<SongSection>>?>(null, allTracks, filter, sort) {
        val tracks = allTracks ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val filtered = if (filter == LibraryFilter.Downloaded) tracks.filter { it.isDownloaded } else tracks
            val sorted = sort?.sorted(filtered) ?: filtered
            sorted to songSections(sorted, sort)
        }
    }

    fun play(start: Track? = null, shuffle: Boolean = false) {
        val tracks = shown?.first ?: return
        val online = container.network.isOnline.value
        val available = if (online) tracks else tracks.filter { it.isDownloaded }
        if (available.isEmpty()) return
        val player = container.player
        val index = start?.let { s -> available.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        player.requestPlay { if (shuffle) player.playShuffled(available) else player.playNow(available, startingAt = index) }
    }

    val listState = rememberLazyListState()
    ScrollToTopOnReselect(Tab.Songs, listState)

    ScreenScaffold(
        stringResource(R.string.tab_songs),
        actions = {
            SortFilterMenu(
                filter = filter,
                onFilter = { settings.libraryFilter.value = it },
                choices = listOf(
                    SortChoice(stringResource(R.string.sort_library_order), selected = sort == null, ascending = true, direction = null) {
                        settings.songListSort.value = null
                    },
                ) + songSortColumns.map { column ->
                    val selected = sort?.column == column
                    val ascending = if (selected) sort!!.ascending else column.defaultAscending
                    SortChoice(
                        label = stringResource(column.label),
                        selected = selected,
                        ascending = ascending,
                        direction = stringResource(column.directionLabel(ascending)),
                    ) {
                        settings.songListSort.value = TrackSort(column, if (selected) !ascending else column.defaultAscending)
                    }
                },
            )
            actions()
        },
    ) { padding ->
        LibraryRefresh(Modifier.padding(top = padding.calculateTopPadding())) {
            val sections = shown?.second
            if (sections?.isEmpty() == true) {
                EmptyState(
                    Icons.Outlined.MusicNote,
                    stringResource(if (filter == LibraryFilter.Downloaded) R.string.no_downloaded_songs else R.string.no_songs),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
                if (!sections.isNullOrEmpty()) {
                    item(key = "play-buttons") { PlayButtons(onPlay = { play() }, onShuffle = { play(shuffle = true) }) }
                }
                sections.orEmpty().forEachIndexed { index, section ->
                    section.letter?.let { letter ->
                        // Indexed: a reversed or re-sorted order can file the same letter twice.
                        stickyHeader(key = "header-$index-$letter", contentType = "header") { LetterHeader(letter) }
                    }
                    items(section.tracks, key = { it.id }, contentType = { "track" }) { track ->
                        TrackRow(track, onClick = { play(start = track) }, showArtwork = true, showNumber = false, showArtist = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.play_all))
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Shuffle, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.shuffle_all))
        }
    }
}

@Composable
private fun LetterHeader(letter: String) {
    Surface(Modifier.fillMaxWidth()) {
        Text(letter, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

private val TrackSortColumn.label: Int
    get() = when (this) {
        TrackSortColumn.Title -> R.string.sort_title
        TrackSortColumn.Artist -> R.string.sort_artist
        TrackSortColumn.Album -> R.string.sort_album
        TrackSortColumn.Year -> R.string.sort_year
        TrackSortColumn.Duration -> R.string.sort_duration
        TrackSortColumn.Plays -> R.string.sort_plays
        TrackSortColumn.LastPlayed -> R.string.sort_last_played
        TrackSortColumn.Number, TrackSortColumn.Disc -> error("Not offered on the song list")
    }

private fun TrackSortColumn.directionLabel(ascending: Boolean): Int = when (this) {
    TrackSortColumn.Year -> if (ascending) R.string.sort_oldest_first else R.string.sort_newest_first
    TrackSortColumn.Duration -> if (ascending) R.string.sort_shortest_first else R.string.sort_longest_first
    TrackSortColumn.Plays -> if (ascending) R.string.sort_least_played else R.string.sort_most_played
    TrackSortColumn.LastPlayed -> if (ascending) R.string.sort_longest_ago else R.string.sort_most_recent
    else -> if (ascending) R.string.sort_a_to_z else R.string.sort_z_to_a
}
