package com.ondumusic.player.service.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4AtomsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun atom(type: String, payload: Int, size: Int = 8 + payload): ByteArray =
        byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.US_ASCII) + ByteArray(payload)

    private fun file(vararg atoms: ByteArray) = folder.newFile().apply { writeBytes(atoms.reduce(ByteArray::plus)) }

    @Test
    fun findsMoovAfterOtherAtoms() {
        assertTrue(Mp4Atoms.hasMoovAtom(file(atom("ftyp", 8), atom("mdat", 2000), atom("moov", 16))))
    }

    @Test
    fun aFileCutShortHasNoMoov() {
        assertFalse(Mp4Atoms.hasMoovAtom(file(atom("ftyp", 8), atom("mdat", 2000))))
    }

    /** Size 0 runs to the end of the file, so moov can only be found by scanning the tail. */
    @Test
    fun findsMoovAfterAnOpenEndedMdat() {
        assertTrue(Mp4Atoms.hasMoovAtom(file(atom("ftyp", 8), atom("mdat", 2000, size = 0), atom("moov", 16))))
    }

    @Test
    fun aCorruptSizeEndsTheSearch() {
        assertFalse(Mp4Atoms.hasMoovAtom(file(atom("ftyp", 8), atom("free", 0, size = 4), atom("moov", 16))))
    }

    @Test
    fun notAnMp4AtAll() {
        assertFalse(Mp4Atoms.hasMoovAtom(folder.newFile().apply { writeText("fLaC") }))
    }
}
