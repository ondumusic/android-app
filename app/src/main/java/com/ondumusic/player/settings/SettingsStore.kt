package com.ondumusic.player.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ondumusic.player.model.AlbumSortOption
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.model.TrackSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A level rather than a server preset: which preset a level maps to depends on what the server offers, see `TranscodePreset`. */
enum class DownloadQuality(val raw: String) {
    Original("original"), High("high"), Mobile("mobile");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Original
    }
}

enum class StreamingQuality(val raw: String) {
    Original("original"), High("high"), Mobile("mobile");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Original
    }
}

/**
 * [Wallpaper] is Android's own: Material You's colours taken from the wallpaper, the way the
 * rest of the phone is coloured. The presets match iOS, with [Blue] being the app icon's blue.
 * iOS's free colour picker isn't offered: Android apps follow the wallpaper rather than ask,
 * which is also why it's the default here where iOS defaults to blue.
 */
enum class AccentColor(val raw: String) {
    Wallpaper("wallpaper"), Blue("blue"), Indigo("indigo"), Purple("purple"), Pink("pink"),
    Red("red"), Orange("orange"), Yellow("yellow"), Green("green"), Teal("teal");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Wallpaper
    }
}

/** Whether the album and song lists show the whole library or only what's on the device. Shared by both, as on iOS. */
enum class LibraryFilter(val raw: String) {
    All("all"), Downloaded("downloaded");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: All
    }
}

/**
 * Read synchronously at startup — the player needs its volume, repeat mode and crossfade
 * before anything is on screen — and observable as flows afterwards.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    /** One stored value, published as a flow and written through on every change. */
    inner class Setting<T>(
        private val key: String,
        read: SharedPreferences.(String) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(prefs.read(key))
        val flow: StateFlow<T> = state.asStateFlow()
        var value: T
            get() = state.value
            set(new) {
                state.value = new
                prefs.edit { write(key, new) }
            }
    }

    private fun bool(key: String, default: Boolean) =
        Setting(key, { getBoolean(it, default) }, { k, v -> putBoolean(k, v) })

    private fun float(key: String, default: Float) =
        Setting(key, { getFloat(it, default) }, { k, v -> putFloat(k, v) })

    private fun int(key: String, default: Int) =
        Setting(key, { getInt(it, default) }, { k, v -> putInt(k, v) })

    private fun long(key: String, default: Long) =
        Setting(key, { getLong(it, default) }, { k, v -> putLong(k, v) })

    private fun <E> enum(key: String, parse: (String?) -> E, raw: (E) -> String) =
        Setting(key, { parse(getString(it, null)) }, { k, v -> putString(k, raw(v)) })

    /** Output volume, 0..1. Applied to the players rather than the system volume, so it scales the crossfade ramp instead of fighting it. */
    val volume = float("volume", 1f)

    /** Seconds; 0 disables crossfade. */
    val crossfadeDuration = float("crossfadeDuration", 1f)

    val maxConcurrentDownloads = int("maxConcurrentDownloads", 3)

    val isShuffled = bool("isShuffled", false)

    /**
     * Report finished listens to the server, which counts them across every device. Off
     * stops this device reporting; it doesn't hide what's already counted.
     */
    val recordPlays = bool("recordPlays", true)

    /** Scale playback so every track lands near the same loudness. See `LoudnessNormalization` for why it can only make things quieter. */
    val normalizeVolume = bool("normalizeVolume", true)

    val repeatMode = enum("repeatMode", RepeatMode::fromRaw) { it.raw }

    /** Maximum playback cache size in bytes. 0 means unlimited. */
    val playbackCacheLimit = long("playbackCacheLimit", 2L * 1024 * 1024 * 1024)

    val downloadQuality = enum("downloadQuality", DownloadQuality::fromRaw) { it.raw }

    val streamingQuality = enum("streamingQuality", StreamingQuality::fromRaw) { it.raw }

    val artworkTintedPlayer = bool("artworkTintedPlayer", true)

    val accentColor = enum("accentColor", AccentColor::fromRaw) { it.raw }


    val hapticsEnabled = bool("hapticsEnabled", true)

    val autoDownloadNewTracks = bool("autoDownloadNewTracks", false)

    val libraryFilter = enum("libraryFilter", LibraryFilter::fromRaw) { it.raw }

    val albumListSort = enum("albumListSort", { raw -> AlbumSortOption.entries.firstOrNull { it.name == raw } ?: AlbumSortOption.Artist }) { it.name }

    val albumListSortAscending = bool("albumListSortAscending", true)

    /** Null is the library's own order; see [TrackSort]. */
    val songListSort = Setting<TrackSort?>(
        "songListSort",
        { getString(it, null)?.let(TrackSort::fromRawValue) },
        { k, v -> if (v == null) remove(k) else putString(k, v.rawValue) },
    )
}
