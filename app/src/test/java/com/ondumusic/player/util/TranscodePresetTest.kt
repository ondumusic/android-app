package com.ondumusic.player.util

import com.ondumusic.player.settings.DownloadQuality
import com.ondumusic.player.settings.StreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscodePresetTest {
    @Test fun originalPlaysAsItIsWhenExoPlayerCanPlayIt() {
        assertNull(TranscodePreset.forStreaming(StreamingQuality.Original, "audio/flac"))
        assertNull(TranscodePreset.forDownload(DownloadQuality.Original, "audio/ogg"))
    }

    /** AIFF never plays in ExoPlayer, so Original still has to be transcoded. */
    @Test fun unsupportedFormatsAreTranscodedEvenAtOriginal() {
        assertEquals("default_ogg", TranscodePreset.forStreaming(StreamingQuality.Original, "audio/aiff"))
        assertEquals("default_ogg", TranscodePreset.forDownload(DownloadQuality.Original, "audio/aiff"))
    }

    @Test fun levelsMapToTheOggPresets() {
        assertEquals("default_ogg", TranscodePreset.forStreaming(StreamingQuality.High, "audio/flac"))
        assertEquals("mobile_ogg", TranscodePreset.forStreaming(StreamingQuality.Mobile, "audio/flac"))
        assertEquals("default_ogg", TranscodePreset.forDownload(DownloadQuality.High, "audio/flac"))
        assertEquals("mobile_ogg", TranscodePreset.forDownload(DownloadQuality.Mobile, "audio/flac"))
    }
}
