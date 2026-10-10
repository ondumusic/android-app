package com.ondumusic.player.util

object AudioFormat {
    /** A file extension for an audio MIME type, or [fallback] if it's unknown. */
    fun fileExtension(mimetype: String, fallback: String = "mp3"): String = when (mimetype.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "audio/vorbis" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> fallback
    }

    /**
     * Whether ExoPlayer plays this MIME type without the server transcoding it first.
     *
     * Not the same set as AVPlayer's on iOS: Ogg Vorbis and Opus play here, but AIFF
     * doesn't — Media3 has no AIFF extractor, so it has to be transcoded like any other
     * unsupported format.
     */
    fun isPlayableNatively(mimetype: String): Boolean = when (mimetype.lowercase()) {
        "audio/mpeg", "audio/mp3",
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac",
        "audio/flac", "audio/x-flac",
        "audio/ogg", "audio/vorbis", "audio/opus",
        "audio/wav", "audio/x-wav" -> true
        else -> false
    }
}
