package com.ondumusic.player.model

import com.ondumusic.player.api.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscodePresetTest {
    private val server = TranscodePresetList(
        defaultId = "balanced",
        presets = listOf(
            TranscodePreset(
                id = "balanced", name = "Balanced (160kbps)", codec = "opus", container = "ogg",
                mimetype = "audio/ogg", fileExtension = "opus", bitrate = 160,
            ),
            TranscodePreset(
                id = "small", name = "Small (64kbps)", codec = "opus", container = "ogg",
                mimetype = "audio/ogg", fileExtension = "opus", bitrate = 64,
            ),
        ),
    )

    @Test fun originalPlaysAsItIsWhenExoPlayerCanPlayIt() {
        assertNull(server.resolve(TranscodeQuality.Original, "audio/flac"))
        assertNull(server.resolve(TranscodeQuality.Original, "audio/ogg"))
    }

    /** AIFF never plays in ExoPlayer, so Original still has to be transcoded, and the server's default is the preset for that. */
    @Test fun unsupportedFormatsAreTranscodedEvenAtOriginal() {
        assertEquals("balanced", server.resolve(TranscodeQuality.Original, "audio/aiff")?.id)
    }

    @Test fun aChosenPresetIsUsedByItsId() {
        assertEquals("small", server.resolve(TranscodeQuality.Preset("small"), "audio/flac")?.id)
    }

    /**
     * The server answers an id it doesn't know with 400 rather than its default, so a
     * preset renamed out from under a stored choice has to fall back on this side.
     */
    @Test fun aPresetTheServerNoLongerHasFallsBackToItsDefault() {
        assertEquals("balanced", server.resolve(TranscodeQuality.Preset("mobile_ogg"), "audio/flac")?.id)
    }

    @Test fun theDefaultIsTheFirstPresetWhenTheDefaultIdIsNotListed() {
        val odd = TranscodePresetList(defaultId = "gone", presets = server.presets)
        assertEquals("balanced", odd.defaultPreset?.id)
        assertNull(TranscodePresetList(defaultId = "gone", presets = emptyList()).defaultPreset)
    }

    @Test fun theBuiltInListMatchesWhatOlderServersAccept() {
        assertEquals("default_ogg", TranscodePresetList.builtIn.defaultPreset?.id)
        assertEquals(listOf("default_ogg", "mobile_ogg"), TranscodePresetList.builtIn.presets.map { it.id })
    }

    @Test fun decodesTheServersShape() {
        val json = """
            {"id":"default_ogg","name":"Default (192kbps)","codec":"opus","container":"ogg",
             "mimetype":"audio/ogg","extension":"opus","bitrate":192}
        """
        val preset = ApiJson.decodeFromString(TranscodePreset.serializer(), json)
        assertEquals("default_ogg", preset.id)
        assertEquals("opus", preset.fileExtension)
        assertEquals(192, preset.bitrate)
    }

    @Test fun survivesARoundTripThroughJson() {
        val encoded = ApiJson.encodeToString(TranscodePresetList.serializer(), server)
        assertEquals(server, ApiJson.decodeFromString(TranscodePresetList.serializer(), encoded))
    }
}

class TranscodeQualityTest {
    @Test fun originalIsItsOwnWord() {
        assertEquals("original", TranscodeQuality.Original.raw)
        assertEquals(TranscodeQuality.Original, TranscodeQuality.fromRaw("original"))
    }

    @Test fun anythingElseIsAPresetId() {
        assertEquals("mobile_ogg", TranscodeQuality.Preset("mobile_ogg").raw)
        assertEquals(TranscodeQuality.Preset("mobile_ogg"), TranscodeQuality.fromRaw("mobile_ogg"))
    }
}
