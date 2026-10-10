package com.ondumusic.player.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.ArtworkVariant
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ArtistArtworkEditorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private val artistId = UUID.fromString("b37eb394-6de0-4771-88e2-d238db9990f0")

    @Before
    fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        library = MusicLibrary(db, File(context.cacheDir, "art-${UUID.randomUUID()}"))
        library.upsertArtists(listOf(SyncArtist(artistId, "Amy", null, "amy", 1, Instant.EPOCH,
            listOf(ArtworkVariant("https://x/a.jpg", 640, 640)))))
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun editor(signedIn: Boolean = true) = ArtistArtworkEditor(
        { if (signedIn) ApiClient(server.url("/api"), "tok", OkHttpClient()) else null }, library, context.contentResolver,
    )

    @Test
    fun removingWritesWhatTheServerReturnsIntoTheLibrary() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"id": "$artistId", "name": "Amy", "slug": "amy", "artwork": []}""").build())

        editor().remove(artistId)

        assertTrue(library.artist(artistId)!!.artwork.isEmpty())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun aRejectionTheServerNamesIsSaidPlainly() = runTest {
        server.enqueue(MockResponse.Builder().code(422).body("""{"error": "too_large"}""").build())
        val error = runCatching { editor().remove(artistId) }.exceptionOrNull()
        // DELETE goes through the generic path, so it's a plain failure; uploads map 422s.
        assertTrue(error is ArtistArtworkEditor.Failure.Failed)
        assertEquals(1, library.artist(artistId)!!.artwork.size)
    }

    @Test
    fun signedOutFailsWithoutTouchingTheNetwork() = runTest {
        val error = runCatching { editor(signedIn = false).remove(artistId) }.exceptionOrNull()
        assertTrue(error is ArtistArtworkEditor.Failure.NotSignedIn)
        assertEquals(0, server.requestCount)
    }
}
