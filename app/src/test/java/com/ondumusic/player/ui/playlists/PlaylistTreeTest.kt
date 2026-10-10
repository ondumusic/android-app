package com.ondumusic.player.ui.playlists

import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.player.makeTrack
import com.ondumusic.player.service.FractionalIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.UUID

class PlaylistTreeTest {
    private lateinit var savedLocale: Locale

    @Before fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun playlist(name: String, parent: Playlist? = null, folder: Boolean = false) =
        Playlist(UUID.randomUUID(), name, 0, Instant.EPOCH, isFolder = folder, parentId = parent?.id)

    @Test fun childrenListFoldersFirstThenNaturalNameOrder() {
        val rock = playlist("Rock", folder = true)
        val ambient = playlist("ambient", folder = true)
        val mix10 = playlist("Mix 10")
        val mix2 = playlist("Mix 2")
        val nested = playlist("Nested", parent = rock)
        val all = listOf(mix10, rock, nested, mix2, ambient)

        assertEquals(listOf(ambient, rock, mix2, mix10), childrenOf(all, null))
        assertEquals(listOf(nested), childrenOf(all, rock.id))
    }

    @Test fun childCountsCountDirectChildrenOnly() {
        val outer = playlist("Outer", folder = true)
        val inner = playlist("Inner", outer, folder = true)
        val all = listOf(outer, inner, playlist("A", inner), playlist("B", inner), playlist("C", outer))

        val counts = childCounts(all)
        assertEquals(2, counts[outer.id])
        assertEquals(2, counts[inner.id])
    }

    @Test fun descendantsIncludeTheFolderAndEverythingNested() {
        val outer = playlist("Outer", folder = true)
        val inner = playlist("Inner", outer, folder = true)
        val deep = playlist("Deep", inner)
        val sibling = playlist("Sibling")

        assertEquals(setOf(outer.id, inner.id, deep.id), descendantIds(listOf(outer, inner, deep, sibling), outer.id))
    }

    @Test fun moveDestinationsExcludeAFolderAndItsDescendants() {
        val a = playlist("A", folder = true)
        val aChild = playlist("A child", a, folder = true)
        val b = playlist("B", folder = true)
        val bChild = playlist("B child", b, folder = true)
        val all = listOf(a, aChild, b, bChild, playlist("Song list"))

        val destinations = moveDestinations(all, a)
        assertEquals(listOf(b to 0, bChild to 1), destinations.map { it.folder to it.depth })
    }

    @Test fun moveDestinationsForAPlaylistOfferEveryFolder() {
        val a = playlist("A", folder = true)
        val aChild = playlist("A child", a, folder = true)
        val moving = playlist("List", a)

        assertEquals(listOf(a to 0, aChild to 1), moveDestinations(listOf(a, aChild, moving), moving).map { it.folder to it.depth })
    }

    @Test fun addTargetsSkipFoldersAndClusterByPath() {
        val work = playlist("Work", folder = true)
        val focus = playlist("Focus", work, folder = true)
        val deep = playlist("Deep", focus)
        val zed = playlist("Zed")
        val alpha = playlist("Alpha")
        val commute = playlist("Commute", work)

        val targets = addTargets(listOf(work, focus, deep, zed, alpha, commute))
        assertEquals(listOf(alpha, zed, commute, deep), targets.map { it.playlist })
        assertEquals(listOf("Work", "Focus"), targets.last().folderPath)
        assertEquals(emptyList<String>(), targets.first().folderPath)
    }

    @Test fun entriesKeepDuplicateTracksAndDropMissingOnes() {
        val track = makeTrack("Song")
        val listId = UUID.randomUUID()
        fun item(trackId: UUID, position: String) = PlaylistItem(UUID.randomUUID(), listId, trackId, position, Instant.EPOCH)
        val items = listOf(item(track.id, "a0"), item(UUID.randomUUID(), "a1"), item(track.id, "a2"))

        val entries = playlistEntries(items, listOf(track))
        assertEquals(listOf(items[0].id, items[2].id), entries.map { it.item.id })
        assertTrue(entries.all { it.track == track })
    }

    @Test fun moveDownLandsBetweenTheNewNeighbours() {
        val positions = listOf("a0", "a1", "a2", "a3")
        val position = positionForMove(positions, from = 0, to = 2)!!
        assertTrue(position > "a2" && position < "a3")
    }

    @Test fun moveUpLandsBetweenTheNewNeighbours() {
        val positions = listOf("a0", "a1", "a2", "a3")
        val position = positionForMove(positions, from = 3, to = 1)!!
        assertTrue(position > "a0" && position < "a1")
    }

    @Test fun moveToEitherEndHasOnlyOneNeighbour() {
        val positions = listOf("a0", "a1", "a2")
        assertTrue(positionForMove(positions, from = 2, to = 0)!! < "a0")
        assertTrue(positionForMove(positions, from = 0, to = 2)!! > "a2")
        assertTrue(FractionalIndex.isValid(positionForMove(positions, from = 1, to = 2)!!))
    }

    @Test fun duplicatePositionsCantBeOrdered() {
        assertNull(positionForMove(listOf("a0", "a1", "a1"), from = 0, to = 1))
    }

    @Test fun malformedPositionsCantBeOrdered() {
        assertNull(positionForMove(listOf("a0", "a10", "a2"), from = 2, to = 1))
    }
}
