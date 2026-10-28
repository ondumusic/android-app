package com.ondumusic.player.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.model.AlbumSortOption
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.model.TrackSort
import com.ondumusic.player.model.TrackSortColumn
import com.ondumusic.player.model.TranscodePreset
import com.ondumusic.player.model.TranscodePresetList
import com.ondumusic.player.model.TranscodeQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test
    fun defaultsMatchIos() {
        val s = SettingsStore(prefs)
        assertEquals(1f, s.volume.value)
        assertEquals(1f, s.crossfadeDuration.value)
        assertEquals(3, s.maxConcurrentDownloads.value)
        assertFalse(s.isShuffled.value)
        assertTrue(s.normalizeVolume.value)
        assertTrue(s.recordPlays.value)
        assertEquals(RepeatMode.Off, s.repeatMode.value)
        assertEquals(2L * 1024 * 1024 * 1024, s.playbackCacheLimit.value)
        assertEquals(TranscodeQuality.Original, s.downloadQuality.value)
        assertEquals(TranscodeQuality.Original, s.streamingQuality.value)
        assertEquals(TranscodePresetList.builtIn, s.serverPresets.value)
        assertTrue(s.artworkTintedPlayer.value)
        assertEquals(AccentColor.Wallpaper, s.accentColor.value)
        assertTrue(s.hapticsEnabled.value)
        assertFalse(s.autoDownloadNewTracks.value)
    }

    @Test
    fun valuesPersistAcrossInstances() {
        SettingsStore(prefs).apply {
            volume.value = 0.4f
            repeatMode.value = RepeatMode.All
            downloadQuality.value = TranscodeQuality.Preset("mobile_ogg")
            accentColor.value = AccentColor.Teal
        }
        val reloaded = SettingsStore(prefs)
        assertEquals(0.4f, reloaded.volume.value)
        assertEquals(RepeatMode.All, reloaded.repeatMode.value)
        assertEquals(TranscodeQuality.Preset("mobile_ogg"), reloaded.downloadQuality.value)
        assertEquals(AccentColor.Teal, reloaded.accentColor.value)
    }

    @Test
    fun flowsFollowChanges() {
        val s = SettingsStore(prefs)
        s.isShuffled.value = true
        assertTrue(s.isShuffled.flow.value)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        prefs.edit().putString("repeatMode", "sideways").putString("serverPresets", "{not json").commit()
        val s = SettingsStore(prefs)
        assertEquals(RepeatMode.Off, s.repeatMode.value)
        assertEquals(TranscodePresetList.builtIn, s.serverPresets.value)
    }

    /** Earlier builds stored a level and mapped it to a preset themselves; the mapping they used is what those values mean now. */
    @Test
    fun legacyQualityLevelsBecomeTheirPresets() {
        prefs.edit().putString("streamingQuality", "high").putString("downloadQuality", "mobile").commit()
        val s = SettingsStore(prefs)
        assertEquals(TranscodeQuality.Preset("default_ogg"), s.streamingQuality.value)
        assertEquals(TranscodeQuality.Preset("mobile_ogg"), s.downloadQuality.value)
    }

    /** Kept as stored: whether the server still has it is decided when the preset is resolved. */
    @Test
    fun anUnknownPresetIdIsKept() {
        prefs.edit().putString("downloadQuality", "lossless_plus").commit()
        assertEquals(TranscodeQuality.Preset("lossless_plus"), SettingsStore(prefs).downloadQuality.value)
    }

    @Test
    fun serverPresetsPersist() {
        val list = TranscodePresetList(
            defaultId = "hq",
            presets = listOf(
                TranscodePreset(
                    id = "hq", name = "HQ", codec = "opus", container = "ogg", mimetype = "audio/ogg",
                    fileExtension = "opus", bitrate = 256,
                ),
            ),
        )
        SettingsStore(prefs).serverPresets.value = list
        assertEquals(list, SettingsStore(prefs).serverPresets.value)
    }

    @Test
    fun qualityResolvesAgainstTheStoredServerList() {
        val s = SettingsStore(prefs)
        s.streamingQuality.value = TranscodeQuality.Preset("mobile_ogg")
        assertEquals("mobile_ogg", s.streamingPreset("audio/flac")?.id)
        assertNull(s.downloadPreset("audio/flac"))
    }

    @Test
    fun browseDefaultsAreTheWholeLibraryInItsOwnOrder() {
        val s = SettingsStore(prefs)
        assertEquals(LibraryFilter.All, s.libraryFilter.value)
        assertEquals(AlbumSortOption.Artist, s.albumListSort.value)
        assertTrue(s.albumListSortAscending.value)
        assertNull(s.songListSort.value)
    }

    @Test
    fun songSortRoundTripsAndClears() {
        val sort = TrackSort(TrackSortColumn.Plays, ascending = false)
        SettingsStore(prefs).songListSort.value = sort
        assertEquals(sort, SettingsStore(prefs).songListSort.value)

        SettingsStore(prefs).songListSort.value = null
        assertNull(SettingsStore(prefs).songListSort.value)
    }
}
