package com.ondumusic.player.api

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AcceptLanguageTest {
    private fun header(vararg tags: String) = ApiClient.acceptLanguage(tags.map(Locale::forLanguageTag))

    @Test
    fun languagesGoInOrderWithFallingWeights() =
        assertEquals("nl-NL, en-GB;q=0.9, de;q=0.8", header("nl-NL", "en-GB", "de"))

    /** Android 14's regional preferences ride along as extensions a server wouldn't match. */
    @Test
    fun regionalPreferencesAreLeftOut() =
        assertEquals("nl-NL, en-US;q=0.9", header("nl-NL-u-fw-mon-mu-celsius", "en-US-u-mu-fahrenhe", "nl-NL"))

    @Test
    fun noLanguagesMeansEnglish() = assertEquals("en", header())
}
