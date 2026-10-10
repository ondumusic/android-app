package com.ondumusic.player.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(AccentColor.Blue, s.accentColor.value)
        assertTrue(s.hapticsEnabled.value)
        assertFalse(s.autoDownloadNewTracks.value)
    }

    @Test
    fun valuesPersistAcrossInstances() {
        SettingsStore(prefs).apply {
            volume.value = 0.4f
            repeatMode.value = RepeatMode.All
            downloadQuality.value = DownloadQuality.MobileAac
            accentColor.value = AccentColor.Wallpaper
        }
        val reloaded = SettingsStore(prefs)
        assertEquals(0.4f, reloaded.volume.value)
        assertEquals(RepeatMode.All, reloaded.repeatMode.value)
        assertEquals(DownloadQuality.MobileAac, reloaded.downloadQuality.value)
        assertEquals(AccentColor.Wallpaper, reloaded.accentColor.value)
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
}
