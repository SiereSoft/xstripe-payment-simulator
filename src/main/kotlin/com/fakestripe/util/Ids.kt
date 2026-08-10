package com.fakestripe.util

import java.util.Random

/**
 * Deterministic ID generator.
 *
 * Real Stripe object IDs look like `cus_NffrFeUfNV2Hib` — a short type prefix,
 * an underscore, then a base62 random tail. We reproduce that shape, but drive
 * the randomness from a seeded [Random] so a given seed + call sequence always
 * yields the same IDs. That is what makes a seeded training world reproducible.
 *
 * [count] tracks how many IDs have been produced so the generator's position in
 * the stream can be restored after loading a snapshot (see Snapshot).
 */
class IdGenerator(private val seed: Long, initialCount: Int = 0) {
    private val rng = Random(seed)
    var count: Int = initialCount
        private set

    init {
        // Fast-forward the stream to the saved position.
        repeat(initialCount) { drawTail() }
    }

    fun next(prefix: String): String {
        val id = prefix + "_" + drawTail()
        count++
        return id
    }

    private fun drawTail(): String {
        val sb = StringBuilder(TAIL_LENGTH)
        repeat(TAIL_LENGTH) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    companion object {
        private const val TAIL_LENGTH = 24
        private const val ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
