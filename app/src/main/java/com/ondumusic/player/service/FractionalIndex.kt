package com.ondumusic.player.service

/**
 * Fractional indexing keys, byte-for-byte compatible with the
 * [rocicorp/fractional-indexing](https://github.com/rocicorp/fractional-indexing)
 * JS package and its Python/Elixir ports. The server validates positions with the
 * Elixir port, so any divergence here is a rejected write.
 *
 * Keys are short, lexicographically-sortable strings used to maintain ordered lists
 * where items can be inserted between any two existing items without renumbering.
 *
 * A key is `<integer part><optional fractional part>`:
 *
 * - Integer part: a "head" character encodes its length. Lowercase `a..z` represents
 *   positive integers (`a` = 1 trailing digit, `b` = 2, …, `z` = 26). Uppercase `Z..A`
 *   represents the negative direction approaching zero (`Z` = 1 trailing digit,
 *   `Y` = 2, …, `A` = 26).
 * - Fractional part: extra digits for arbitrary precision; must not end in `0`.
 *
 * Default alphabet is base 62: `0-9A-Za-z`. Comparison is plain code-unit order, which
 * is what the JS reference uses.
 */
object FractionalIndex {
    const val BASE62_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val DEFAULT_MAX_LENGTH = 100

    class InvalidKeyException(message: String) : IllegalArgumentException(message)

    /**
     * A key strictly between [a] and [b]; null means unbounded on that side.
     *
     * @throws InvalidKeyException for a malformed key or `a >= b`. Keys from the network
     *   or storage can be malformed, so callers handling those should catch it.
     */
    fun between(a: String?, b: String?, digits: String = BASE62_DIGITS): String {
        if (a != null) validateOrderKey(a, digits)
        if (b != null) validateOrderKey(b, digits)
        if (a != null && b != null && a >= b) throw InvalidKeyException("$a >= $b")
        return generateKeyBetween(a, b, digits)
    }

    /** A key strictly greater than [prev]. */
    fun after(prev: String?): String = between(prev, null)

    /** A key strictly less than [next]. */
    fun before(next: String?): String = between(null, next)

    /**
     * Whether [key] is a structurally valid key in [digits]. Stricter than the reference
     * validator: every character must be in the alphabet and the key within [maxLength].
     */
    fun isValid(key: String, maxLength: Int = DEFAULT_MAX_LENGTH, digits: String = BASE62_DIGITS): Boolean {
        if (key.isEmpty() || key.length > maxLength) return false
        if (key.any { it !in digits }) return false
        return try {
            validateOrderKey(key, digits)
            true
        } catch (_: InvalidKeyException) {
            false
        }
    }

    /**
     * [n] distinct keys strictly between [a] and [b], in sorted order. Used for batch
     * inserts and for rebalancing (call with `null, null, n`).
     */
    fun nKeysBetween(a: String?, b: String?, n: Int, digits: String = BASE62_DIGITS): List<String> {
        if (n == 0) return emptyList()
        if (n == 1) return listOf(between(a, b, digits))
        if (b == null) {
            var c = between(a, null, digits)
            val result = mutableListOf(c)
            repeat(n - 1) {
                c = between(c, null, digits)
                result += c
            }
            return result
        }
        if (a == null) {
            var c = between(null, b, digits)
            val result = mutableListOf(c)
            repeat(n - 1) {
                c = between(null, c, digits)
                result += c
            }
            return result.asReversed()
        }
        val mid = n / 2
        val c = between(a, b, digits)
        return nKeysBetween(a, c, mid, digits) + c + nKeysBetween(c, b, n - mid - 1, digits)
    }

    private fun generateKeyBetween(a: String?, b: String?, digits: String): String {
        val zero = digits.first()
        val smallestInt = "A" + zero.toString().repeat(26)

        if (a == null && b == null) return "a$zero"

        if (a == null) {
            val ib = integerPart(b!!)
            val fb = b.substring(ib.length)
            if (ib == smallestInt) return ib + midpoint("", fb, digits)
            if (ib < b) return ib
            return decrementInteger(ib, digits) ?: throw InvalidKeyException("cannot decrement any more")
        }

        if (b == null) {
            val ia = integerPart(a)
            val fa = a.substring(ia.length)
            return incrementInteger(ia, digits) ?: (ia + midpoint(fa, null, digits))
        }

        val ia = integerPart(a)
        val fa = a.substring(ia.length)
        val ib = integerPart(b)
        val fb = b.substring(ib.length)
        if (ia == ib) return ia + midpoint(fa, fb, digits)
        val i = incrementInteger(ia, digits) ?: throw InvalidKeyException("cannot increment any more")
        if (i < b) return i
        return ia + midpoint(fa, null, digits)
    }

    /**
     * Both inputs are fractional parts (no integer prefix). [a] may be empty; [b] is null
     * or a non-empty string with `a < b`. Neither may end in the zero digit.
     */
    private fun midpoint(a: String, b: String?, digits: String): String {
        val zero = digits.first()
        if (b != null && a >= b) throw InvalidKeyException("$a >= $b")
        if (a.lastOrNull() == zero || b?.lastOrNull() == zero) throw InvalidKeyException("trailing zero")

        if (b != null) {
            // Treat a missing digit in `a` as zero while skipping the shared prefix.
            var n = 0
            while (n < b.length && (a.getOrNull(n) ?: zero) == b[n]) n++
            if (n > 0) {
                val aRest = if (a.length > n) a.substring(n) else ""
                return b.substring(0, n) + midpoint(aRest, b.substring(n), digits)
            }
        }

        val digitA = if (a.isEmpty()) 0 else digitIndex(digits, a[0])
        val digitB = if (b != null) digitIndex(digits, b[0]) else digits.length
        if (digitB - digitA > 1) {
            // Round half up, matching JS `Math.round` on the reference side.
            return digits[(digitA + digitB + 1) / 2].toString()
        }
        if (b != null && b.length > 1) return b.substring(0, 1)
        // b is null or a single digit: keep a's first digit and recurse past it.
        val aRest = if (a.length > 1) a.substring(1) else ""
        return digits[digitA] + midpoint(aRest, null, digits)
    }

    private fun digitIndex(digits: String, ch: Char): Int {
        val index = digits.indexOf(ch)
        if (index < 0) throw InvalidKeyException("invalid digit: $ch")
        return index
    }

    private fun validateOrderKey(key: String, digits: String) {
        if (key.isEmpty()) throw InvalidKeyException("invalid order key: $key")
        val zero = digits.first()
        if (key == "A" + zero.toString().repeat(26)) throw InvalidKeyException("invalid order key: $key")
        val i = integerPart(key)
        val f = key.substring(i.length)
        if (f.isNotEmpty() && f.last() == zero) throw InvalidKeyException("invalid order key: $key")
    }

    private fun integerPart(key: String): String {
        if (key.isEmpty()) throw InvalidKeyException("invalid order key: $key")
        val n = integerLength(key[0])
        if (n > key.length) throw InvalidKeyException("invalid order key: $key")
        return key.substring(0, n)
    }

    private fun integerLength(head: Char): Int = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw InvalidKeyException("invalid order key head: $head")
    }

    private fun validateInteger(i: String) {
        if (i.isEmpty() || integerLength(i[0]) != i.length) {
            throw InvalidKeyException("invalid integer part of order key: $i")
        }
    }

    /** The next integer part, or null past "z…z" (the caller then adds a fraction). */
    private fun incrementInteger(x: String, digits: String): String? {
        validateInteger(x)
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var i = digs.size - 1
        var carry = true
        while (carry && i >= 0) {
            val index = digitIndex(digits, digs[i]) + 1
            if (index == digits.length) {
                digs[i] = digits.first()
                i--
            } else {
                digs[i] = digits[index]
                carry = false
            }
        }
        if (!carry) return head + String(digs)
        if (head == 'Z') return "a" + digits.first()
        if (head == 'z') return null
        val newHead = head + 1
        // Moving into longer lowercase territory gains a digit; moving up through the
        // uppercase heads toward 'a' loses one.
        return if (newHead > 'a') newHead + String(digs) + digits.first()
        else newHead + String(digs, 0, digs.size - 1)
    }

    /** The previous integer part, or null below "A0…0". */
    private fun decrementInteger(x: String, digits: String): String? {
        validateInteger(x)
        val head = x[0]
        val digs = x.substring(1).toCharArray()
        var i = digs.size - 1
        var borrow = true
        while (borrow && i >= 0) {
            val index = digitIndex(digits, digs[i])
            if (index == 0) {
                digs[i] = digits.last()
                i--
            } else {
                digs[i] = digits[index - 1]
                borrow = false
            }
        }
        if (!borrow) return head + String(digs)
        if (head == 'a') return "Z" + digits.last()
        if (head == 'A') return null
        val newHead = head - 1
        return if (newHead < 'Z') newHead + String(digs) + digits.last()
        else newHead + String(digs, 0, digs.size - 1)
    }
}
