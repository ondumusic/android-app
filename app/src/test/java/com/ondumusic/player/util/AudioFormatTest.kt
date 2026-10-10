package com.ondumusic.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatTest {

    @Test fun mp3Supported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/mpeg"))
        assertTrue(AudioFormat.isPlayableNatively("audio/mp3"))
    }

    @Test fun m4aSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/mp4"))
        assertTrue(AudioFormat.isPlayableNatively("audio/m4a"))
        assertTrue(AudioFormat.isPlayableNatively("audio/x-m4a"))
    }

    @Test fun aacSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/aac"))
    }

    @Test fun flacSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/flac"))
        assertTrue(AudioFormat.isPlayableNatively("audio/x-flac"))
    }

    @Test fun wavSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/wav"))
        assertTrue(AudioFormat.isPlayableNatively("audio/x-wav"))
    }

    @Test fun oggSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/ogg"))
        assertTrue(AudioFormat.isPlayableNatively("audio/vorbis"))
    }

    @Test fun opusSupported() {
        assertTrue(AudioFormat.isPlayableNatively("audio/opus"))
    }

    // Media3 has no AIFF extractor, so unlike on iOS it goes through the transcoder.
    @Test fun aiffUnsupported() {
        assertFalse(AudioFormat.isPlayableNatively("audio/aiff"))
        assertFalse(AudioFormat.isPlayableNatively("audio/x-aiff"))
    }

    @Test fun webmUnsupported() {
        assertFalse(AudioFormat.isPlayableNatively("audio/webm"))
    }

    @Test fun unknownUnsupported() {
        assertFalse(AudioFormat.isPlayableNatively("audio/unknown"))
        assertFalse(AudioFormat.isPlayableNatively("video/mp4"))
        assertFalse(AudioFormat.isPlayableNatively(""))
    }

    @Test fun caseInsensitive() {
        assertTrue(AudioFormat.isPlayableNatively("Audio/MPEG"))
        assertTrue(AudioFormat.isPlayableNatively("AUDIO/MP4"))
        assertTrue(AudioFormat.isPlayableNatively("Audio/Flac"))
    }

    @Test fun fileExtensionForCommonTypes() {
        val expected = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg",
            "audio/vorbis" to "ogg",
            "audio/opus" to "opus",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
        )
        for ((mimetype, ext) in expected) {
            assertEquals(mimetype, ext, AudioFormat.fileExtension(mimetype))
        }
    }

    @Test fun fileExtensionDefaultFallback() {
        assertEquals("mp3", AudioFormat.fileExtension("audio/unknown"))
        assertEquals("mp3", AudioFormat.fileExtension(""))
    }

    @Test fun fileExtensionCustomFallback() {
        assertEquals("bin", AudioFormat.fileExtension("audio/unknown", fallback = "bin"))
        assertEquals("", AudioFormat.fileExtension("audio/unknown", fallback = ""))
    }

    @Test fun fileExtensionCaseInsensitive() {
        assertEquals("mp3", AudioFormat.fileExtension("Audio/MPEG"))
        assertEquals("flac", AudioFormat.fileExtension("AUDIO/FLAC"))
    }
}
