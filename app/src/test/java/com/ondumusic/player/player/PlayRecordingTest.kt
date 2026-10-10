package com.ondumusic.player.player

import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.data.MusicLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

class PlayThresholdTest {
    @Test
    fun isHalfOfAShortTrack() = assertEquals(100.0, PlayerService.PlayThreshold.seconds(200.0), 0.0)

    /** A 20-minute side shouldn't need 10 minutes before it counts. */
    @Test
    fun capsLongTracksAtFourMinutes() = assertEquals(240.0, PlayerService.PlayThreshold.seconds(1200.0), 0.0)

    @Test
    fun isExactlyTheCapAtTheCrossover() = assertEquals(240.0, PlayerService.PlayThreshold.seconds(480.0), 0.0)

    /** A stream whose length isn't known yet falls back to the cap rather than counting immediately. */
    @Test
    fun fallsBackToTheCapWhenTheLengthIsUnknown() = assertEquals(240.0, PlayerService.PlayThreshold.seconds(0.0), 0.0)
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayRecordingTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun library() = makeLibrary(dispatcher).also { libraries += it }

    /** The track as a sync would deliver it, carrying the server's play count. */
    private suspend fun MusicLibrary.syncTrack(id: UUID, playCount: Int, lastPlayedAt: Instant?): SyncTrack =
        track(id)!!.let {
            SyncTrack(
                it.id, it.artistId, it.albumArtistId, it.albumId, it.name, it.disc, it.number, it.duration,
                it.mimetype, it.streamUrl, it.downloadUrl, Instant.now(), playCount = playCount, lastPlayedAt = lastPlayedAt,
            )
        }

    // Local play counting

    @Test
    fun countsThePlayLocally() = test {
        val lib = library()
        val trackId = lib.seed(listOf("Track")).single()
        lib.upsertTracks(listOf(lib.syncTrack(trackId, playCount = 4, lastPlayedAt = null)))
        val playedAt = Instant.ofEpochSecond(1_700_000_000)

        lib.recordLocalPlay(trackId, playedAt)

        val track = lib.track(trackId)!!
        assertEquals(5, track.playCount)
        assertEquals(playedAt, track.lastPlayedAt)
    }

    /**
     * The server counts plays from every device, so its total replaces ours rather than
     * adding to it — otherwise this device's optimistic bump would be counted twice.
     */
    @Test
    fun theServersTotalReplacesTheLocalOne() = test {
        val lib = library()
        val trackId = lib.seed(listOf("Track")).single()
        lib.upsertTracks(listOf(lib.syncTrack(trackId, playCount = 4, lastPlayedAt = null)))
        lib.recordLocalPlay(trackId, Instant.now())
        assertEquals(5, lib.track(trackId)!!.playCount)

        val serverLastPlayed = Instant.ofEpochSecond(1_700_000_500)
        lib.upsertTracks(listOf(lib.syncTrack(trackId, playCount = 9, lastPlayedAt = serverLastPlayed)))

        val track = lib.track(trackId)!!
        assertEquals(9, track.playCount)
        assertEquals(serverLastPlayed, track.lastPlayedAt)
    }

    @Test
    fun ignoresATrackThatIsNotInTheLibrary() = test {
        library().recordLocalPlay(UUID.randomUUID(), Instant.now())
    }

    // Reporting from the player

    private class Recorded(val trackId: UUID, val playedAt: Instant)

    private suspend fun TestScope.playingPlayer(lib: MusicLibrary, recorded: MutableList<Recorded>, recordPlays: Boolean = true): Pair<PlayerService, UUID> {
        val trackId = lib.seed(listOf("Track")).single()
        val settings = makeSettings("play-recording").apply { this.recordPlays.value = recordPlays }
        val hooks = PlayerService.Hooks(
            baseUrl = testHooks.baseUrl,
            recordPlay = { id, at -> recorded += Recorded(id, at) },
        )
        val player = makePlayer(settings = settings, library = lib, hooks = hooks)
        player.playNow(listOf(lib.track(trackId)!!))
        testScheduler.runCurrent()
        return player to trackId
    }

    /** Samples a second apart, like the ticker but without waiting on it. */
    private fun PlayerService.listen(from: Double, to: Double) {
        var t = from
        while (t <= to) {
            accumulateListeningForTest(t)
            t += 1.0
        }
    }

    @Test
    fun aTrackHeardPastTheThresholdIsReportedOnceAndCountedLocally() = test {
        val lib = library()
        val recorded = mutableListOf<Recorded>()
        val (player, trackId) = playingPlayer(lib, recorded)

        // 200s track: half is 100s of listening.
        player.listen(0.0, 99.0)
        assertTrue(recorded.isEmpty())
        player.listen(100.0, 150.0)
        testScheduler.runCurrent()

        assertEquals(listOf(trackId), recorded.map { it.trackId })
        assertEquals(1, lib.track(trackId)!!.playCount)
    }

    /** Seeking to the end isn't listening to it. */
    @Test
    fun aSeekDoesNotCountAsListening() = test {
        val recorded = mutableListOf<Recorded>()
        val (player, _) = playingPlayer(library(), recorded)

        player.listen(0.0, 10.0)
        player.seek(190.0)
        player.listen(190.0, 199.0)

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun nothingIsReportedWithPlayRecordingOff() = test {
        val lib = library()
        val recorded = mutableListOf<Recorded>()
        val (player, trackId) = playingPlayer(lib, recorded, recordPlays = false)

        player.listen(0.0, 150.0)
        testScheduler.runCurrent()

        assertTrue(recorded.isEmpty())
        assertEquals(0, lib.track(trackId)!!.playCount)
    }
}
