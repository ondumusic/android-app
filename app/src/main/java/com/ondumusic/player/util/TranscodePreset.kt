package com.ondumusic.player.util

import com.ondumusic.player.settings.DownloadQuality
import com.ondumusic.player.settings.StreamingQuality

/**
 * Which server preset a quality level asks for: the Ogg Opus presets, which play
 * gapless, can be scrubbed while the server is still transcoding, and have their size
 * up front so a download starts at once with real progress. Every Android this app
 * runs on decodes Opus.
 */
object TranscodePreset {
    const val MIMETYPE = "audio/ogg"

    /** Null means the original file plays as it is. */
    fun forStreaming(quality: StreamingQuality, mimetype: String): String? {
        if (quality == StreamingQuality.Original && AudioFormat.isPlayableNatively(mimetype)) return null
        return if (quality == StreamingQuality.Mobile) "mobile_ogg" else "default_ogg"
    }

    /** Null means the original file is downloaded as it is. */
    fun forDownload(quality: DownloadQuality, mimetype: String): String? {
        if (quality == DownloadQuality.Original && AudioFormat.isPlayableNatively(mimetype)) return null
        return if (quality == DownloadQuality.Mobile) "mobile_ogg" else "default_ogg"
    }
}
