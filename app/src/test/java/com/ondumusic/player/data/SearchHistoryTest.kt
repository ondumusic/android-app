package com.ondumusic.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SearchHistoryTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("search-history-test", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun item(kind: SearchHistoryItem.Kind = SearchHistoryItem.Kind.Artist, name: String = "Test", id: UUID = UUID.randomUUID()) =
        SearchHistoryItem(id = id, kind = kind, name = name)

    @Test
    fun addInsertsAtFront() {
        val history = SearchHistory(prefs)
        history.add(item(name = "Beatles"))
        assertEquals(listOf("Beatles"), history.items.value.map { it.name })
    }

    @Test
    fun addDeduplicatesByIdAndKind() {
        val history = SearchHistory(prefs)
        val id = UUID.randomUUID()
        history.add(item(name = "Old", id = id))
        history.add(item(name = "New", id = id))
        assertEquals(listOf("New"), history.items.value.map { it.name })
    }

    @Test
    fun addSameIdDifferentKindKeepsBoth() {
        val history = SearchHistory(prefs)
        val id = UUID.randomUUID()
        history.add(item(kind = SearchHistoryItem.Kind.Artist, name = "Artist", id = id))
        history.add(item(kind = SearchHistoryItem.Kind.Album, name = "Album", id = id))
        assertEquals(2, history.items.value.size)
    }

    @Test
    fun addCapsAt20() {
        val history = SearchHistory(prefs)
        repeat(25) { history.add(item(name = "Item $it")) }
        assertEquals(20, history.items.value.size)
        assertEquals("Item 24", history.items.value.first().name)
    }

    @Test
    fun removeDeletesItem() {
        val history = SearchHistory(prefs)
        val toRemove = item(name = "ToRemove")
        history.add(toRemove)
        history.remove(toRemove)
        assertTrue(history.items.value.isEmpty())
    }

    @Test
    fun clearRemovesAll() {
        val history = SearchHistory(prefs)
        history.add(item(name = "A"))
        history.add(item(name = "B"))
        history.clear()
        assertTrue(history.items.value.isEmpty())
    }

    @Test
    fun persistsAcrossInstances() {
        val artistId = UUID.randomUUID()
        SearchHistory(prefs).add(
            SearchHistoryItem(UUID.randomUUID(), SearchHistoryItem.Kind.Song, "Persisted", subtitle = "Someone",
                artworkUrl = "https://example.com/a.jpg", artistId = artistId, albumId = UUID.randomUUID())
        )
        val reloaded = SearchHistory(prefs).items.value
        assertEquals(1, reloaded.size)
        assertEquals("Persisted", reloaded[0].name)
        assertEquals(SearchHistoryItem.Kind.Song, reloaded[0].kind)
        assertEquals(artistId, reloaded[0].artistId)
    }

    @Test
    fun unreadableHistoryStartsEmpty() {
        prefs.edit().putString("searchHistory", "not json").commit()
        assertTrue(SearchHistory(prefs).items.value.isEmpty())
    }
}
