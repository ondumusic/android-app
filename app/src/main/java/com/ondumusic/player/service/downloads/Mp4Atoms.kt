package com.ondumusic.player.service.downloads

import java.io.File
import java.io.RandomAccessFile

internal object Mp4Atoms {
    private val MOOV = "moov".toByteArray(Charsets.US_ASCII)
    private const val TAIL_SCAN_BYTES = 512L * 1024

    /**
     * Whether an MP4/M4A file has its `moov` atom. Without it the file can't be played,
     * and a transfer cut short can still look complete from its size alone.
     */
    fun hasMoovAtom(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val length = f.length()
            val header = ByteArray(8)
            var offset = 0L
            while (offset + 8 <= length) {
                f.seek(offset)
                f.readFully(header)
                val size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                    ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (type == "moov") return@use true
                if (size == 0L) {
                    // Size 0 means "extends to EOF", common for mdat without faststart: moov
                    // may still follow the actual data, so look through the tail of the file.
                    val scan = minOf(length, TAIL_SCAN_BYTES).toInt()
                    val tail = ByteArray(scan)
                    f.seek(length - scan)
                    f.readFully(tail)
                    return@use (0..scan - MOOV.size).any { i -> MOOV.indices.all { tail[i + it] == MOOV[it] } }
                }
                if (size < 8) return@use false
                offset += size
            }
            false
        }
    }.getOrDefault(false)
}
