package com.ondumusic.player.service.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.downloadQueue
import com.ondumusic.player.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DownloadAllScaleTest {

    @Test
    fun downloadAllAtLibraryScale() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = MusicLibrary(db, File(context.cacheDir, "scale-${UUID.randomUUID()}"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val now = Instant.parse("2026-10-01T12:00:00Z")
            val artists = mutableListOf<SyncArtist>()
            val albums = mutableListOf<SyncAlbum>()
            val tracks = mutableListOf<SyncTrack>()
            for (a in 0 until 200) {
                val artistId = UUID.randomUUID()
                artists += SyncArtist(artistId, "Artist $a", null, "artist-$a", albumCount = 2, insertedAt = now)
                for (b in 0 until 2) {
                    val albumId = UUID.randomUUID()
                    albums += SyncAlbum(albumId, artistId, "Album $a-$b", null, "album-$a-$b", 2000, null, emptyList(), false, 11, now)
                    for (t in 0 until 11) {
                        val id = UUID.randomUUID()
                        tracks += SyncTrack(id, artistId, artistId, albumId, "Track $t", 1, t + 1, 200.0, "audio/mp4", "/stream/$id", "/download/$id", now)
                    }
                }
            }
            library.upsertArtists(artists)
            library.upsertAlbums(albums)
            library.upsertTracks(tracks)

            val settings = SettingsStore(context.getSharedPreferences("scale-test", Context.MODE_PRIVATE))
            val service = DownloadService(
                library, settings, OkHttpClient(), MutableStateFlow(null), MutableStateFlow(true),
                object : DownloadConditions {
                    override fun freeBytes() = Long.MAX_VALUE
                    override val isPowerSaveMode = MutableStateFlow(false)
                },
                scope,
            )
            // Isolate the enqueue path: this measures the scan, not the transfers.
            service.pauseAll()

            val start = System.nanoTime()
            service.downloadAll()
            val elapsed = (System.nanoTime() - start) / 1_000_000

            assertEquals(tracks.size, service.state.value.queued.size)
            assertEquals(tracks.size, library.downloadQueue().size)
            // A query per track plus a scan of the queue hung the iOS app on the click that
            // started it. The bound is deliberately loose: it catches a return to that shape,
            // not milliseconds.
            assertTrue("downloadAll took ${elapsed}ms for ${tracks.size} tracks", elapsed < 2000)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            db.close()
        }
    }
}
