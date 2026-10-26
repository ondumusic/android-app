package com.ondumusic.player.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ogg.OggExtractor
import kotlin.math.roundToLong

/**
 * Gapless Ogg Opus. The encoder pads a track's last packet with silence up to the 20 ms
 * frame and marks the real end in the final page's granule position. Media3's Ogg reader
 * reads that granule for the duration but never turns it into the end trim it applies for
 * MP3 and AAC, so every track would end in up to 20 ms of silence. The reader visits the
 * end of the file before its first sample; this holds the format back until then and sets
 * the padding from what it found.
 */
@UnstableApi
class OpusGaplessExtractorsFactory(private val inner: ExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = inner.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor = if (extractor is OggExtractor) OpusPaddingExtractor(extractor) else extractor
}

/**
 * Samples the decoder outputs past the real end. [durationUs] is the Ogg reader's: the
 * last granule position, pre-skip included, at Opus's fixed 48 kHz. Frames are taken to be
 * 20 ms, which is what the server encodes and every common encoder's default.
 */
fun opusEndPadding(durationUs: Long): Int {
    if (durationUs == C.TIME_UNSET || durationUs <= 0) return 0
    val granule = (durationUs * OPUS_RATE / 1_000_000.0).roundToLong()
    return ((OPUS_FRAME - granule % OPUS_FRAME) % OPUS_FRAME).toInt()
}

private const val OPUS_RATE = 48_000L
private const val OPUS_FRAME = 960L

@UnstableApi
internal class OpusPaddingExtractor(private val ogg: Extractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = ogg.sniff(input)
    override fun init(output: ExtractorOutput) = ogg.init(PaddingOutput(output))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = ogg.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = ogg.seek(position, timeUs)
    override fun release() = ogg.release()
    override fun getUnderlyingImplementation(): Extractor = ogg.underlyingImplementation
}

@UnstableApi
private class PaddingOutput(private val output: ExtractorOutput) : ExtractorOutput by output {
    private val tracks = mutableListOf<HeldFormatTrack>()

    override fun track(id: Int, type: Int): TrackOutput = HeldFormatTrack(output.track(id, type)).also { tracks += it }

    override fun seekMap(seekMap: SeekMap) {
        output.seekMap(seekMap)
        tracks.forEach { it.release(seekMap.durationUs) }
    }
}

@UnstableApi
private class HeldFormatTrack(private val track: TrackOutput) : TrackOutput by track {
    private var held: Format? = null
    private var durationUs: Long? = null

    override fun format(format: Format) {
        if (format.sampleMimeType != MimeTypes.AUDIO_OPUS) return track.format(format)
        val known = durationUs
        if (known == null) held = format else track.format(padded(format, known))
    }

    fun release(durationUs: Long) {
        this.durationUs = durationUs
        held?.let {
            held = null
            track.format(padded(it, durationUs))
        }
    }

    // A sample can't go out ahead of its format. Should one ever come before the seek map,
    // the format goes as read.
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        flush()
        return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
        flush()
        return track.sampleData(input, length, allowEndOfInput)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        flush()
        track.sampleData(data, length, sampleDataPart)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) {
        flush()
        track.sampleData(data, length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        flush()
        track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun flush() {
        held?.let {
            held = null
            track.format(it)
        }
    }

    private fun padded(format: Format, durationUs: Long): Format =
        format.buildUpon().setEncoderPadding(opusEndPadding(durationUs)).build()
}
