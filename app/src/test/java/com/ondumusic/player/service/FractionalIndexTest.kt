package com.ondumusic.player.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FractionalIndexTest {

    // Canonical test vectors from the rocicorp suite.
    @Test fun canonicalOkCases() {
        val cases = listOf(
            Triple(null, null, "a0"),
            Triple(null, "a0", "Zz"),
            Triple(null, "Zz", "Zy"),
            Triple("a0", null, "a1"),
            Triple("a1", null, "a2"),
            Triple("a0", "a1", "a0V"),
            Triple("a1", "a2", "a1V"),
            Triple("a0V", "a1", "a0l"),
            Triple("Zz", "a0", "ZzV"),
            Triple("Zz", "a1", "a0"),
            Triple(null, "Y00", "Xzzz"),
            Triple("bzz", null, "c000"),
            Triple("a0", "a0V", "a0G"),
            Triple("a0", "a0G", "a08"),
            Triple("b125", "b129", "b127"),
            Triple("a0", "a1V", "a1"),
            Triple("Zz", "a01", "a0"),
            Triple(null, "a0V", "a0"),
            Triple(null, "b999", "b99"),
            Triple(null, "A000000000000000000000000001", "A000000000000000000000000000V"),
            Triple("zzzzzzzzzzzzzzzzzzzzzzzzzzy", null, "zzzzzzzzzzzzzzzzzzzzzzzzzzz"),
            Triple("zzzzzzzzzzzzzzzzzzzzzzzzzzz", null, "zzzzzzzzzzzzzzzzzzzzzzzzzzzV"),
        )
        for ((a, b, expected) in cases) {
            assertEquals("between($a, $b)", expected, FractionalIndex.between(a, b))
        }
    }

    @Test fun canonicalErrorCases() {
        // Smallest-integer sentinel.
        assertInvalid(null, "A00000000000000000000000000")
        // Trailing zero.
        assertInvalid("a00", null)
        assertInvalid("a00", "a1")
        // Bad head character.
        assertInvalid("0", "1")
        // a >= b.
        assertInvalid("a1", "a0")
    }

    private fun assertInvalid(a: String?, b: String?) {
        assertThrows("between($a, $b)", FractionalIndex.InvalidKeyException::class.java) {
            FractionalIndex.between(a, b)
        }
    }

    @Test fun nKeysBase10() {
        val base10 = "0123456789"
        assertEquals(
            listOf("a0", "a1", "a2", "a3", "a4"),
            FractionalIndex.nKeysBetween(null, null, 5, base10),
        )
        assertEquals(
            listOf("a5", "a6", "a7", "a8", "a9", "b00", "b01", "b02", "b03", "b04"),
            FractionalIndex.nKeysBetween("a4", null, 10, base10),
        )
        assertEquals(
            listOf("Z5", "Z6", "Z7", "Z8", "Z9"),
            FractionalIndex.nKeysBetween(null, "a0", 5, base10),
        )
        assertEquals(
            listOf(
                "a01", "a02", "a03", "a035", "a04", "a05", "a06", "a07", "a08", "a09",
                "a1", "a11", "a12", "a13", "a14", "a15", "a16", "a17", "a18", "a19",
            ),
            FractionalIndex.nKeysBetween("a0", "a2", 20, base10),
        )
    }

    @Test fun nKeysZeroAndOne() {
        assertEquals(emptyList<String>(), FractionalIndex.nKeysBetween(null, null, 0))
        assertEquals(listOf("a0"), FractionalIndex.nKeysBetween(null, null, 1))
    }

    @Test fun validateAcceptsValid() {
        for (key in listOf("a0", "a0V", "Zz", "b00", "A000000000000000000000000001")) {
            assertTrue("expected $key to be valid", FractionalIndex.isValid(key))
        }
    }

    @Test fun validateRejectsMalformed() {
        for (key in listOf("", "zm", "foo", "a00", "A00000000000000000000000000", "a")) {
            assertFalse("expected $key to be invalid", FractionalIndex.isValid(key))
        }
    }

    @Test fun validateRejectsOutsideAlphabet() {
        for (key in listOf("a#?!@3124123", "a!", "a 1", "b1@")) {
            assertFalse("expected $key to be invalid (alphabet)", FractionalIndex.isValid(key))
        }
    }

    @Test fun validateEnforcesMaxLength() {
        val longKey = "a" + "V".repeat(200)
        assertFalse(FractionalIndex.isValid(longKey, maxLength = 50))
        assertFalse(FractionalIndex.isValid(longKey))
    }

    @Test fun randomInsertionsProduceValidSortedKeys() {
        val rng = XorShift64(42u)
        val keys = mutableListOf<String>()
        var prev: String? = null
        repeat(5) {
            val k = FractionalIndex.between(prev, null)
            keys += k
            prev = k
        }
        repeat(1000) {
            val n = keys.size
            val pos = (rng.next() % (n + 1).toULong()).toInt()
            val a = if (pos > 0) keys[pos - 1] else null
            val b = if (pos < n) keys[pos] else null
            keys.add(pos, FractionalIndex.between(a, b))
        }
        assertEquals("keys should remain sorted", keys.sorted(), keys)
        assertEquals("all keys should be unique", keys.size, keys.toSet().size)
        for (k in keys) assertTrue("invalid key produced: $k", FractionalIndex.isValid(k))
    }

    @Test fun convenienceApi() {
        assertEquals("a0", FractionalIndex.between(null, null))
        assertEquals("a0", FractionalIndex.after(null))
        assertEquals("a1", FractionalIndex.after("a0"))
        assertEquals("Zz", FractionalIndex.before("a0"))
    }
}

/** Tiny seedable generator so the insertion sequence is reproducible. */
private class XorShift64(seed: ULong) {
    private var state = if (seed == 0uL) 1uL else seed

    fun next(): ULong {
        state = state xor (state shl 13)
        state = state xor (state shr 7)
        state = state xor (state shl 17)
        return state
    }
}
