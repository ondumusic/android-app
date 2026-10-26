package com.ondumusic.player.player

import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.settings.StreamingQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * What iOS couldn't test without audio: how the player lines up the next track, what it
 * does with the engine's reports, and the stream URLs it hands over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerTransitionTest {
    private val engine = FakePlaybackEngine()

    private fun album(count: Int, albumId: UUID = UUID.randomUUID()) =
        (1..count).map { QueueItem(makeTrack("T$it", albumId, number = it, streamUrl = "/stream/$it")) }

    private fun TestScope.playing(items: List<QueueItem>, startingAt: Int = 0, crossfade: Float = 1f, repeat: RepeatMode = RepeatMode.Off): PlayerService {
        val settings = makeSettings("transition").apply {
            crossfadeDuration.value = crossfade
            repeatMode.value = repeat
        }
        val player = makePlayer(engine, settings = settings)
        player.playNowQueue(items, startingAt)
        runCurrent()
        return player
    }

    // Gapless versus crossfade

    @Test
    fun sequentialAlbumTracksPlayGapless() = runTest {
        playing(album(3))

        assertEquals("http://localhost/stream/2", engine.linedUpGapless?.source?.uri)
        assertNull(engine.armedCrossfade)
    }

    @Test
    fun theNextDiscsFirstTrackIsSequentialToo() = runTest {
        val albumId = UUID.randomUUID()
        playing(listOf(
            QueueItem(makeTrack("1-9", albumId, disc = 1, number = 9)),
            QueueItem(makeTrack("2-1", albumId, disc = 2, number = 1, streamUrl = "/stream/disc2")),
        ))

        assertEquals("http://localhost/stream/disc2", engine.linedUpGapless?.source?.uri)
    }

    @Test
    fun tracksFromDifferentAlbumsCrossfade() = runTest {
        playing(listOf(makeQueueItem("A"), QueueItem(makeTrack("B", streamUrl = "/stream/b"))), crossfade = 3f)

        assertNull(engine.linedUpGapless)
        val fade = engine.armedCrossfade!!
        assertEquals("http://localhost/stream/b", fade.source?.uri)
        assertEquals(3.0, fade.fadeSeconds, 0.0)
    }

    /** Out of order within one album is not a continuation. */
    @Test
    fun aSkipWithinTheAlbumCrossfades() = runTest {
        val tracks = album(3)
        playing(listOf(tracks[0], tracks[2]))

        assertNull(engine.linedUpGapless)
        assertNotNull(engine.armedCrossfade)
    }

    @Test
    fun withCrossfadeOffNonSequentialTracksJustFollowOn() = runTest {
        playing(listOf(makeQueueItem("A"), makeQueueItem("B")), crossfade = 0f)

        assertNull(engine.linedUpGapless)
        assertNull(engine.armedCrossfade)
    }

    @Test
    fun repeatOneLinesUpNothing() = runTest {
        playing(album(3), repeat = RepeatMode.One)

        assertNull(engine.linedUpGapless)
        assertNull(engine.armedCrossfade)
    }

    @Test
    fun theLastTrackLinesUpNothingWithRepeatOff() = runTest {
        playing(album(2), startingAt = 1)

        assertNull(engine.linedUpGapless)
        assertNull(engine.armedCrossfade)
    }

    @Test
    fun repeatAllLinesUpTheFirstTrackFromTheLast() = runTest {
        playing(listOf(makeQueueItem("A"), QueueItem(makeTrack("B", streamUrl = "/stream/b"))), startingAt = 1, repeat = RepeatMode.All)

        assertEquals("http://localhost/stream/test", engine.armedCrossfade?.source?.uri)
    }

    /** A change to what plays next takes back whatever was lined up for the old next track. */
    @Test
    fun playNextReplacesTheLinedUpTrack() = runTest {
        val player = playing(album(3))

        player.playNextQueue(listOf(QueueItem(makeTrack("X", streamUrl = "/stream/x"))))
        runCurrent()

        assertNull(engine.linedUpGapless)
        assertEquals("http://localhost/stream/x", engine.armedCrossfade?.source?.uri)
    }

    @Test
    fun switchingToRepeatOneTakesBackTheLinedUpTrack() = runTest {
        val player = playing(album(3))

        player.setRepeatMode(RepeatMode.One)
        runCurrent()

        assertNull(engine.linedUpGapless)
        assertNull(engine.armedCrossfade)
    }

    // Engine reports

    @Test
    fun advancingGaplessMovesOnAndLinesUpTheNext() = runTest {
        val player = playing(album(3))

        engine.advanceGapless()
        runCurrent()

        assertEquals(1, player.s.currentIndex)
        assertEquals("T2", player.s.currentTrack?.name)
        assertTrue(player.s.isPlaying)
        assertEquals("http://localhost/stream/3", engine.linedUpGapless?.source?.uri)
        // The engine is already playing it; nothing is restarted.
        assertEquals(1, engine.plays.size)
    }

    @Test
    fun aCompletedCrossfadeMovesOn() = runTest {
        val player = playing(listOf(makeQueueItem("A"), makeQueueItem("B"), makeQueueItem("C")))

        engine.completeCrossfade()
        runCurrent()

        assertEquals(1, player.s.currentIndex)
        assertEquals("B", player.s.currentTrack?.name)
        assertEquals(1, engine.plays.size)
        assertNotNull(engine.armedCrossfade)
    }

    @Test
    fun endingMidQueuePlaysTheNextTrack() = runTest {
        val player = playing(listOf(makeQueueItem("A"), makeQueueItem("B")), crossfade = 0f)

        engine.end()
        runCurrent()

        assertEquals(1, player.s.currentIndex)
        assertEquals("B", player.s.currentTrack?.name)
        assertEquals(2, engine.plays.size)
    }

    /** The index moving isn't enough: the track that loads has to be the one it now points at. */
    @Test
    fun skipBackAndSkipToLoadTheTrackTheyLandOn() = runTest {
        val player = playing(album(3))

        player.skip()
        runCurrent()
        assertEquals("http://localhost/stream/2", engine.plays.last().source.uri)

        player.skipTo(2)
        runCurrent()
        assertEquals("http://localhost/stream/3", engine.plays.last().source.uri)

        player.back()
        runCurrent()
        assertEquals("http://localhost/stream/2", engine.plays.last().source.uri)
        assertEquals("T2", player.s.currentTrack?.name)
    }

    @Test
    fun endingTheQueueStopsAndCuesTheFirstTrack() = runTest {
        val player = playing(album(3), startingAt = 2)

        engine.end()
        runCurrent()

        assertEquals(1, engine.stopCount)
        assertFalse(player.s.isPlaying)
        assertEquals(0, player.s.currentIndex)
        assertEquals("T1", player.s.currentTrack?.name)
        assertEquals(0.0, player.progress.value, 0.0)
    }

    /** The engine was stopped, so play has to load the cued track rather than resume nothing. */
    @Test
    fun playAfterTheQueueEndedStartsFromTheTop() = runTest {
        val player = playing(album(3), startingAt = 2)
        engine.end()
        runCurrent()

        player.togglePlayPause()
        runCurrent()

        assertEquals(0, engine.resumeCount)
        assertEquals("http://localhost/stream/1", engine.plays.last().source.uri)
        assertTrue(player.s.isPlaying)
    }

    @Test
    fun endingWithRepeatOneReplaysTheTrack() = runTest {
        val player = playing(album(3), repeat = RepeatMode.One)

        engine.end()
        runCurrent()

        assertEquals(0, player.s.currentIndex)
        assertEquals(listOf("http://localhost/stream/1", "http://localhost/stream/1"), engine.plays.map { it.source.uri })
    }

    @Test
    fun anExternalPauseIsReflected() = runTest {
        val player = playing(album(2))

        engine.playingChangedExternally(false)

        assertFalse(player.s.isPlaying)
    }

    // Failure

    @Test
    fun aStreamThatNeverBecomesReadyFails() = runTest {
        val player = playing(album(2))
        assertTrue(player.s.isBuffering)

        advanceTimeBy(15_001)

        assertEquals(PlaybackError.Unreachable, player.s.playbackError)
        assertFalse(player.s.isPlaying)
        assertFalse(player.s.isBuffering)
    }

    @Test
    fun aStreamThatBecomesReadyIsLeftAlone() = runTest {
        val player = playing(album(2))
        engine.ready()

        advanceTimeBy(15_001)

        assertNull(player.s.playbackError)
        assertTrue(player.s.isPlaying)
        assertFalse(player.s.isBuffering)
    }

    @Test
    fun aRejectedSessionAsksForReauth() = runTest {
        var unauthorized = 0
        val player = makePlayer(engine, hooks = PlayerService.Hooks(baseUrl = testHooks.baseUrl, onUnauthorized = { unauthorized++ }))
        player.playNowQueue(album(2))
        runCurrent()

        engine.fail(PlaybackError.Unauthorized)

        assertEquals(1, unauthorized)
        assertEquals(PlaybackError.Unauthorized, player.s.playbackError)
        assertFalse(player.s.isPlaying)
    }

    @Test
    fun otherErrorsDoNotAskForReauth() = runTest {
        var unauthorized = 0
        val player = makePlayer(engine, hooks = PlayerService.Hooks(baseUrl = testHooks.baseUrl, onUnauthorized = { unauthorized++ }))
        player.playNowQueue(album(2))
        runCurrent()

        engine.fail(PlaybackError.Unplayable)

        assertEquals(0, unauthorized)
    }

    @Test
    fun signingBackInResumesWhatTheExpiryStopped() = runTest {
        val player = makePlayer(engine)
        player.playNowQueue(album(2))
        runCurrent()
        player.seek(42.0)
        engine.fail(PlaybackError.Unauthorized)
        assertTrue(player.resumesAfterSignIn)

        player.resumeAfterSignIn()
        runCurrent()

        assertEquals(2, engine.plays.size)
        assertEquals(engine.plays[0].source, engine.plays[1].source)
        assertEquals(42.0, engine.plays[1].startAt, 0.0)
        assertTrue(player.s.isPlaying)
        assertFalse(player.resumesAfterSignIn)
    }

    @Test
    fun signingInAfterAnUnrelatedFailureDoesNotStartPlayback() = runTest {
        val player = makePlayer(engine)
        player.playNowQueue(album(2))
        runCurrent()
        engine.fail(PlaybackError.Unplayable)

        player.resumeAfterSignIn()
        runCurrent()

        assertEquals(1, engine.plays.size)
        assertFalse(player.s.isPlaying)
    }

    @Test
    fun clearingTheQueueAfterAnExpiryCancelsTheResume() = runTest {
        val player = makePlayer(engine)
        player.playNowQueue(album(2))
        runCurrent()
        engine.fail(PlaybackError.Unauthorized)

        player.stopAndClear()
        player.resumeAfterSignIn()
        runCurrent()

        assertEquals(1, engine.plays.size)
        assertFalse(player.s.isPlaying)
    }

    /** Signed out there's nothing to resolve a relative stream against. */
    @Test
    fun aStreamWithNowhereToComeFromFailsAsUnplayable() = runTest {
        val player = makePlayer(engine, hooks = PlayerService.Hooks())
        player.playNowQueue(album(1))
        runCurrent()

        assertTrue(engine.plays.isEmpty())
        assertEquals(PlaybackError.Unplayable, player.s.playbackError)
    }

    // Stream URLs

    private fun TestScope.urlPlayer(quality: StreamingQuality = StreamingQuality.Original, base: String? = "https://music.example.com/") =
        makePlayer(
            settings = makeSettings("stream-url").apply { streamingQuality.value = quality },
            hooks = PlayerService.Hooks(baseUrl = { base?.toHttpUrl() }),
        )

    @Test
    fun aRelativeStreamResolvesAgainstTheServer() = runTest {
        val url = urlPlayer().streamUrl(makeTrack(streamUrl = "/api/stream/abc"))
        assertEquals("https://music.example.com/api/stream/abc", url.toString())
    }

    @Test
    fun anAbsoluteStreamIsUsedAsIs() = runTest {
        val url = urlPlayer().streamUrl(makeTrack(streamUrl = "https://cdn.example.com/s/1?sig=x"))
        assertEquals("https://cdn.example.com/s/1?sig=x", url.toString())
    }

    @Test
    fun aRelativeStreamWhileSignedOutHasNoUrl() = runTest {
        assertNull(urlPlayer(base = null).streamUrl(makeTrack(streamUrl = "/api/stream/abc")))
    }

    @Test
    fun aFormatThatPlaysHereStreamsOriginal() = runTest {
        val url = urlPlayer().streamUrl(makeTrack(mimetype = "audio/flac", streamUrl = "/s/1"))!!
        assertNull(url.queryParameter("transcode"))
    }

    /** ExoPlayer has no AIFF extractor, so the server converts it. */
    @Test
    fun aFormatThatDoesNotPlayHereIsTranscoded() = runTest {
        val url = urlPlayer().streamUrl(makeTrack(mimetype = "audio/aiff", streamUrl = "/s/1"))!!
        assertEquals("1", url.queryParameter("transcode"))
        assertEquals("default_ogg", url.queryParameter("preset"))
    }

    @Test
    fun aLowerStreamingQualityTranscodesWithItsPreset() = runTest {
        val url = urlPlayer(StreamingQuality.Mobile).streamUrl(makeTrack(mimetype = "audio/flac", streamUrl = "/s/1?x=1"))!!
        assertEquals("1", url.queryParameter("x"))
        assertEquals("1", url.queryParameter("transcode"))
        assertEquals("mobile_ogg", url.queryParameter("preset"))
    }
}
