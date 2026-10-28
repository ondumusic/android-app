package com.ondumusic.player.model

import com.ondumusic.player.util.AudioFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A transcoding preset the server offers. Servers list theirs in `/info`: the ids have
 * changed before and that forced an app update, so the app no longer hardcodes them.
 */
@Serializable
data class TranscodePreset(
    val id: String,
    /** Already in the user's language: the server translates it from the `Accept-Language` header sent on every request. */
    val name: String,
    val codec: String,
    val container: String,
    val mimetype: String,
    @SerialName("extension") val fileExtension: String,
    val bitrate: Int,
)

/** The presets a server offers, as it last reported them. */
@Serializable
data class TranscodePresetList(val defaultId: String, val presets: List<TranscodePreset>) {

    val defaultPreset: TranscodePreset?
        get() = presets.firstOrNull { it.id == defaultId } ?: presets.firstOrNull()

    /**
     * Null means the file plays or downloads as it is. A preset the server no longer has
     * falls back to its default here because the server won't: it answers an unknown id
     * with 400 rather than substituting.
     */
    fun resolve(quality: TranscodeQuality, mimetype: String): TranscodePreset? = when (quality) {
        TranscodeQuality.Original -> if (AudioFormat.isPlayableNatively(mimetype)) null else defaultPreset
        is TranscodeQuality.Preset -> presets.firstOrNull { it.id == quality.id } ?: defaultPreset
    }

    companion object {
        /**
         * What every server offered before they started saying: the Ogg Opus presets, which
         * play gapless, can be scrubbed while the server is still transcoding, and have their
         * size up front so a download starts at once with real progress. Also what a server
         * too old to list its presets is taken to have.
         */
        val builtIn = TranscodePresetList(
            defaultId = "default_ogg",
            presets = listOf(
                TranscodePreset(
                    id = "default_ogg", name = "Default (192kbps)", codec = "opus", container = "ogg",
                    mimetype = "audio/ogg", fileExtension = "opus", bitrate = 192,
                ),
                TranscodePreset(
                    id = "mobile_ogg", name = "Mobile (96kbps)", codec = "opus", container = "ogg",
                    mimetype = "audio/ogg", fileExtension = "opus", bitrate = 96,
                ),
            ),
        )
    }
}

/** What to ask the server for: the file as it is, or one of its presets by id. */
sealed interface TranscodeQuality {
    val raw: String

    data object Original : TranscodeQuality {
        override val raw = "original"
    }

    data class Preset(val id: String) : TranscodeQuality {
        override val raw get() = id
    }

    companion object {
        fun fromRaw(raw: String): TranscodeQuality = if (raw == Original.raw) Original else Preset(raw)
    }
}
