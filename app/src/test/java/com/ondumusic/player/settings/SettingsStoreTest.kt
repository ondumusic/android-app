package com.ondumusic.player.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.model.AlbumSortOption
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.model.TrackSort
import com.ondumusic.player.model.TrackSortColumn
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
        assertEquals(DownloadQuality.Original, s.downloadQuality.value)
        assertEquals(StreamingQuality.Original, s.streamingQuality.value)
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
            downloadQuality.value = DownloadQuality.Mobile
            accentColor.value = AccentColor.Teal
        }
        val reloaded = SettingsStore(prefs)
        assertEquals(0.4f, reloaded.volume.value)
        assertEquals(RepeatMode.All, reloaded.repeatMode.value)
        assertEquals(DownloadQuality.Mobile, reloaded.downloadQuality.value)
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
        prefs.edit().putString("repeatMode", "sideways").putString("downloadQuality", "lossless_plus").commit()
        val s = SettingsStore(prefs)
        assertEquals(RepeatMode.Off, s.repeatMode.value)
        assertEquals(DownloadQuality.Original, s.downloadQuality.value)
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
