package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.settings.SettingsStore
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
import java.util.UUID
import kotlin.math.log10

/**
 * The path from the Settings toggle to the gain on the playing track: the setting's flow,
 * the collector PlayerService installs on it, and the album-versus-track choice.
 *
 * Settings here are StateFlows, which don't re-emit a value that hasn't changed, where
 * Swift's `didSet` fires on every assignment. So each test starts with normalisation off
 * and turns it on, rather than relying on assigning the default to trigger a refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LoudnessWiringTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun library() = makeLibrary(dispatcher).also { libraries += it }

    /** One album, one track per entry in [lufs], returning the ids in order. */
    private suspend fun MusicLibrary.seed(lufs: List<Double?>, albumLufs: Double? = null) =
        seed(lufs.indices.map { "Track $it" }, lufs, albumLufs)

    private fun settings() = makeSettings("loudness-wiring").apply { normalizeVolume.value = false }

    /** Restores a queue so there's a current track without anything having played. */
    private suspend fun TestScope.restored(lib: MusicLibrary, ids: List<UUID>, settings: SettingsStore, engine: FakePlaybackEngine): PlayerService {
        val store = makeStore(this, "loudness-wiring")
        store.save(PlaybackState(ids, 0, null))
        store.flush()
        return makePlayer(engine, settings = settings, store = store, library = lib)
    }

    /** What the engine was last told to apply to the track already playing. */
    private val FakePlaybackEngine.primaryGain get() = currentGains.lastOrNull() ?: 1f

    private fun dB(gain: Float) = 20 * log10(gain.toDouble())

    private fun TestScope.setNormalize(settings: SettingsStore, on: Boolean) {
        settings.normalizeVolume.value = on
        testScheduler.runCurrent()
    }

    @Test
    fun togglingTheSettingReappliesToTheTrackAlreadyPlaying() = test {
        val lib = library()
        // Mixed album so the queue isn't a single-album one: this is track gain.
        val ids = lib.seed(listOf(-4.0, -23.0))
        val settings = settings()
        val engine = FakePlaybackEngine()
        restored(lib, listOf(ids[0]), settings, engine)

        setNormalize(settings, true)
        assertTrue(dB(engine.primaryGain) < -9)

        setNormalize(settings, false)
        assertEquals(1f, engine.primaryGain)
    }

    @Test
    fun aQueueOfOneAlbumTakesTheAlbumGain() = test {
        val lib = library()
        val ids = lib.seed(listOf(-4.0, -23.0), albumLufs = -7.0)
        val settings = settings()
        val engine = FakePlaybackEngine()
        restored(lib, ids, settings, engine)

        setNormalize(settings, true)
        // The album sits at -7 LUFS, so its gain is shallower than the -10 the loud track
        // would get on its own.
        val albumGain = dB(engine.primaryGain)
        assertTrue(albumGain > -9)
        assertTrue(albumGain < -5)
    }

    @Test
    fun aMixedQueueTakesTheTrackGain() = test {
        val lib = library()
        val loud = lib.seed(listOf(-4.0))
        val quiet = lib.seed(listOf(-23.0))
        val settings = settings()
        val engine = FakePlaybackEngine()
        restored(lib, listOf(loud[0], quiet[0]), settings, engine)

        setNormalize(settings, true)
        assertTrue(dB(engine.primaryGain) < -9)
    }

    @Test
    fun anUnmeasuredTrackIsLeftAlone() = test {
        val lib = library()
        val ids = lib.seed(listOf(null, null))
        val settings = settings()
        val engine = FakePlaybackEngine()
        restored(lib, ids, settings, engine)

        setNormalize(settings, true)
        assertEquals(1f, engine.primaryGain)
    }

    /** A lone track is levelled as a track even though it trivially shares an album with the whole queue. */
    @Test
    fun oneTrackOnItsOwnTakesTheTrackGain() = test {
        val lib = library()
        val ids = lib.seed(listOf(-4.0, -23.0), albumLufs = -7.0)
        val settings = settings()
        val engine = FakePlaybackEngine()
        restored(lib, listOf(ids[0]), settings, engine)

        setNormalize(settings, true)
        assertTrue(dB(engine.primaryGain) < -9)
    }

    // Gain at the moment a track starts, which iOS couldn't observe without audio

    @Test
    fun aTrackStartsAtItsGain() = test {
        val lib = library()
        val ids = lib.seed(listOf(-4.0, -23.0), albumLufs = -7.0)
        val settings = makeSettings("loudness-wiring")
        val engine = FakePlaybackEngine()
        val player = restored(lib, ids, settings, engine)

        player.togglePlayPause()
        testScheduler.runCurrent()

        val albumGain = dB(engine.plays.single().gain)
        assertTrue(albumGain > -9 && albumGain < -5)
    }

    /** The track lined up behind the current one carries its own gain to the boundary. */
    @Test
    fun theLinedUpTrackCarriesItsGain() = test {
        val lib = library()
        val loud = lib.seed(listOf(-4.0))
        val quiet = lib.seed(listOf(-30.0))
        val settings = makeSettings("loudness-wiring")
        val engine = FakePlaybackEngine()
        val player = restored(lib, listOf(quiet[0], loud[0]), settings, engine)

        player.togglePlayPause()
        testScheduler.runCurrent()

        assertEquals(1f, engine.plays.single().gain)
        assertTrue(dB(engine.armedCrossfade!!.gain) < -9)
    }
}
