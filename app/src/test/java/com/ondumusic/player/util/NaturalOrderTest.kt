package com.ondumusic.player.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class NaturalOrderTest {
    private lateinit var savedLocale: Locale

    @Before fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test fun numbersCompareByValue() {
        assertTrue("Track 2".compareNatural("Track 10") < 0)
        assertTrue("Track 10".compareNatural("Track 2") > 0)
        assertTrue("a2b".compareNatural("a10b") < 0)
    }

    @Test fun numbersLongerThanALongCompareByValue() {
        assertTrue("x 99999999999999999999".compareNatural("x 100000000000000000000") < 0)
    }

    @Test fun caseIsIgnoredBeforeTheTiebreak() {
        assertTrue("apple".compareNatural("Banana") < 0)
        assertTrue("Apple".compareNatural("banana") < 0)
    }

    // Accents only separate letters that are otherwise equal, so "Émile" still sorts
    // among the E's rather than after Z.
    @Test fun accentsFileWithTheirBaseLetter() {
        assertTrue("Émile".compareNatural("Ziggy") < 0)
        assertTrue("Émile".compareNatural("Eve") < 0)
        assertTrue("e".compareNatural("é") < 0)
    }

    @Test fun distinctStringsNeverCompareEqual() {
        val pairs = listOf("abc" to "ABC", "Track 02" to "Track 2", "e" to "é", "a" to "A")
        for ((a, b) in pairs) {
            val forward = a.compareNatural(b)
            assertTrue("$a vs $b", forward != 0)
            assertEquals("$a vs $b is antisymmetric", -Integer.signum(forward), Integer.signum(b.compareNatural(a)))
        }
        assertEquals(0, "same".compareNatural("same"))
    }

    // The order Foundation's `localizedStandardCompare` gives for the same input.
    @Test fun matchesFoundationsOrder() {
        val expected = listOf(
            "a2b", "a10b", "ab", "abc", "ABC", "apple", "Émile", "Eve", "évian",
            "track 2", "Track 2", "Track 02", "Track 10", "Ziggy",
        )
        assertEquals(expected, expected.reversed().sortedWith(NaturalOrder))
        assertEquals(expected, expected.shuffled(kotlin.random.Random(1)).sortedWith(NaturalOrder))
    }

    @Test fun containsIgnoresCaseAndAccents() {
        assertTrue("Café del Mar".containsNatural("CAFE"))
        assertTrue("Sigur Rós".containsNatural("ros"))
        assertTrue("Beyonce".containsNatural("Beyoncé"))
        assertFalse("Café".containsNatural("tea"))
    }

    @Test fun anEmptyQueryMatchesNothing() {
        assertFalse("anything".containsNatural(""))
    }
}
