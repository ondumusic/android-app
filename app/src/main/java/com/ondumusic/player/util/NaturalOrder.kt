package com.ondumusic.player.util

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The ordering Foundation's `localizedStandardCompare` gives the iOS app, which is what
 * people expect from a file browser: case doesn't matter, accents do (but only once the
 * letters themselves are equal, so "Émile" still files among the E's), and numbers
 * compare by value, so "Track 2" comes before "Track 10".
 *
 * Distinct strings never compare equal. A sort is free to put equal elements in any
 * order, and "abc"/"ABC" swapping places between two loads of the same list reads as a
 * glitch, so ties fall through to case, then to fewer leading zeros, then to code points
 * — the same way Foundation breaks them.
 */
fun String.compareNatural(other: String): Int {
    if (this == other) return 0
    val width = maxOf(longestNumber(this), longestNumber(other))
    val lhs = padNumbers(this, width)
    val rhs = padNumbers(other, width)
    val collators = Collators.current()
    collators.secondary.compare(lhs, rhs).let { if (it != 0) return it }
    collators.tertiary.compare(lhs, rhs).let { if (it != 0) return it }
    (length - other.length).let { if (it != 0) return it }
    return compareTo(other)
}

/**
 * Foundation's `localizedStandardContains`: case- and accent-insensitive, so "cafe" finds
 * "Café" — search is typed in a hurry, often without the accents.
 *
 * An empty query matches nothing, as it does in Foundation; callers that want "no query
 * means everything" say so themselves.
 */
fun String.containsNatural(query: String): Boolean {
    if (query.isEmpty()) return false
    return foldForSearch(this).contains(foldForSearch(query))
}

/** [compareNatural] as a [Comparator], for `sortedWith` and `compareBy` chains. */
val NaturalOrder: Comparator<String> = Comparator { a, b -> a.compareNatural(b) }

private val combiningMarks = Regex("\\p{Mn}+")

private fun foldForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)

/**
 * Numbers are left-padded to a common width so the collator, which only knows
 * characters, compares them by value. Leading zeros are dropped first so "02" and "2"
 * pad to the same thing and are separated by the tiebreak rather than the collation.
 */
private fun padNumbers(s: String, width: Int): String {
    if (width == 0) return s
    val out = StringBuilder(s.length + width)
    var i = 0
    while (i < s.length) {
        if (!Character.isDigit(s[i])) {
            out.append(s[i++])
            continue
        }
        val start = out.length
        var significant = false
        while (i < s.length && Character.isDigit(s[i])) {
            val digit = Character.digit(s[i++], 10)
            if (digit != 0) significant = true
            // Other scripts' digits become ASCII so the padding zeros collate with them.
            if (significant) out.append('0' + digit)
        }
        val digits = out.length - start
        repeat(width - digits) { out.insert(start, '0') }
    }
    return out.toString()
}

private fun longestNumber(s: String): Int {
    var longest = 0
    var i = 0
    while (i < s.length) {
        if (!Character.isDigit(s[i])) {
            i++
            continue
        }
        while (i < s.length && Character.digit(s[i], 10) == 0) i++
        var digits = 0
        while (i < s.length && Character.isDigit(s[i])) {
            digits++
            i++
        }
        longest = maxOf(longest, digits)
    }
    return longest
}

/**
 * Collators are expensive to create, so they're kept — per thread, because Android's
 * are backed by ICU ones that aren't safe to share — and rebuilt only when the default
 * locale changes, which it can while the process lives on (the user switches language).
 */
private class Collators(val locale: Locale) {
    val secondary: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val tertiary: Collator = Collator.getInstance(locale).apply { strength = Collator.TERTIARY }

    companion object {
        private val cached = ThreadLocal<Collators>()

        fun current(): Collators {
            val locale = Locale.getDefault()
            cached.get()?.takeIf { it.locale == locale }?.let { return it }
            return Collators(locale).also { cached.set(it) }
        }
    }
}
