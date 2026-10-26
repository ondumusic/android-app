package com.ondumusic.player.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ogg.OggExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class OpusGaplessTest {
    /** 1.0137 s of tone from ffmpeg: final granule 48970 with a pre-skip of 312, so 950 samples of padding. */
    private val tone = File(javaClass.getResource("/opus/tone.ogg")!!.toURI())

    @Test
    fun paddingFillsTheLastFrame() {
        // A five-minute track: granule 14101698 ends 258 samples into its last frame.
        assertEquals(702, opusEndPadding(14_101_698L * 1_000_000 / 48_000))
        assertEquals(0, opusEndPadding(960L * 1_000_000 / 48_000 * 10))
        assertEquals(0, opusEndPadding(C.TIME_UNSET))
    }

    @Test
    fun theFormatCarriesThePaddingAndComesBeforeTheFirstSample() {
        val events = extract(knownLength = true)
        assertEquals(listOf("format 950", "sample"), events.take(2))
        assertEquals(1, events.count { it.startsWith("format") })
    }

    /** A stream still being sent with no length: the reader can't reach the end, so nothing is trimmed. */
    @Test
    fun withoutALengthTheFormatGoesUntouched() {
        val events = extract(knownLength = false)
        assertEquals(listOf("format 0", "sample"), events.take(2))
    }

    @Test
    fun onlyTheOggReaderIsWrapped() {
        val extractors = OpusGaplessExtractorsFactory().createExtractors()
        assertTrue(extractors.any { it is OpusPaddingExtractor && it.underlyingImplementation is OggExtractor })
        assertTrue(extractors.any { it is Mp3Extractor })
        assertTrue(extractors.none { it is OggExtractor })
    }

    private fun extract(knownLength: Boolean): List<String> {
        val events = mutableListOf<String>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = object : TrackOutput {
                override fun format(format: Format) { events += "format ${format.encoderPadding}" }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                    input.read(ByteArray(length), 0, length)
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    if (events.last() != "sample") events += "sample"
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val extractor = OpusPaddingExtractor(OggExtractor())
        extractor.init(output)
        var source = FileDataSource()
        val fileLength = source.open(DataSpec(Uri.fromFile(tone)))
        val length = if (knownLength) fileLength else C.LENGTH_UNSET.toLong()
        var input = DefaultExtractorInput(source, 0, length)
        val position = PositionHolder()
        while (true) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    source.close()
                    source = FileDataSource()
                    source.open(DataSpec.Builder().setUri(Uri.fromFile(tone)).setPosition(position.position).build())
                    input = DefaultExtractorInput(source, position.position, length)
                }
            }
        }
        source.close()
        return events
    }
}
