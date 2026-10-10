package com.ondumusic.player.player

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.Loudness
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.Track
import com.ondumusic.player.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.Instant
import java.util.UUID

private val testArtist = Artist(UUID.randomUUID(), "Artist", "Artist", "artist")

fun makeTrack(
    name: String = "Track",
    albumId: UUID = UUID.randomUUID(),
    disc: Int = 1,
    number: Int? = 1,
    mimetype: String = "audio/mp4",
    streamUrl: String = "/stream/test",
    lufs: Double? = null,
) = Track(
    id = UUID.randomUUID(), artistId = testArtist.id, albumArtistId = testArtist.id, albumId = albumId,
    artist = testArtist, albumArtistSortName = testArtist.sortName, name = name, disc = disc, number = number,
    duration = 200.0, mimetype = mimetype, streamUrl = streamUrl, lufs = lufs,
)

fun makeQueueItem(name: String = "Track", albumId: UUID = UUID.randomUUID(), disc: Int = 1, number: Int? = 1) =
    QueueItem(makeTrack(name, albumId, disc, number))

/** Preferences under a name no other test uses, emptied so a previous run can't leak in. */
fun freshPrefs(name: String): SharedPreferences =
    ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("$name.${UUID.randomUUID()}", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

fun makeSettings(name: String) = SettingsStore(freshPrefs(name))

fun makeStore(scope: TestScope, name: String) = PlaybackStateStore(
    File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "$name/${UUID.randomUUID()}.json"),
    freshPrefs(name),
    scope.backgroundScope,
)

/**
 * Room's suspend queries run on [dispatcher] — the test's — so a restore that reads the
 * library finishes inside `runCurrent` instead of on a thread the test can't wait for.
 */
fun makeLibrary(dispatcher: CoroutineDispatcher): MusicLibrary {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCoroutineContext(dispatcher)
        .build()
    return MusicLibrary(db, File(context.cacheDir, "lib-${UUID.randomUUID()}").apply { mkdirs() })
}

fun MusicLibrary.close() = db.close()

/**
 * One artist, one album, a track per name in order (numbered from 1), returning the ids.
 * [lufs] is per track; [albumLufs] is what the server measured for the album as a whole.
 */
suspend fun MusicLibrary.seed(
    names: List<String>,
    lufs: List<Double?> = names.map { null },
    albumLufs: Double? = null,
): List<UUID> {
    val now = Instant.now()
    val artistId = UUID.randomUUID()
    val albumId = UUID.randomUUID()
    upsertArtists(listOf(SyncArtist(artistId, "Artist", "Artist", "artist", albumCount = 1, insertedAt = now)))
    upsertAlbums(listOf(SyncAlbum(
        id = albumId, artistId = artistId, name = "Album", slug = "album",
        isUnknown = false, trackCount = names.size, insertedAt = now, loudness = Loudness(albumLufs),
    )))
    val tracks = names.mapIndexed { i, name ->
        SyncTrack(
            id = UUID.randomUUID(), artistId = artistId, albumArtistId = artistId, albumId = albumId,
            name = name, disc = 1, number = i + 1, duration = 200.0, mimetype = "audio/mp4",
            streamUrl = "/stream/$i", downloadUrl = "/download/$i", insertedAt = now, loudness = Loudness(lufs[i]),
        )
    }
    upsertTracks(tracks)
    return tracks.map { it.id }
}

val testHooks = PlayerService.Hooks(baseUrl = { "http://localhost/".toHttpUrl() })

/**
 * A player on the test's scheduler. Its long-lived collectors live in `backgroundScope`, so
 * `runCurrent` drives a launched load or restore without the test waiting on them to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.makePlayer(
    engine: FakePlaybackEngine = FakePlaybackEngine(),
    settings: SettingsStore = makeSettings("player"),
    store: PlaybackStateStore = makeStore(this, "player"),
    library: MusicLibrary? = null,
    hooks: PlayerService.Hooks = testHooks,
): PlayerService {
    val player = PlayerService(engine, settings, library, store, backgroundScope, hooks)
    // Starts the init coroutines: settings collectors subscribe, the restore runs.
    runCurrent()
    return player
}

val PlayerService.s: PlayerService.State get() = state.value
