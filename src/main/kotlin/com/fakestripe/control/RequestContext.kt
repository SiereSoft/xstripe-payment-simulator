package com.fakestripe.control

import java.security.MessageDigest

/**
 * What the simulator knows about the actor request being served: its deterministic id, the
 * operation it performs and the caller's idempotency key. Events a request creates carry the id
 * and key in `event.request`, as Stripe's do.
 */
data class RequestContext(
    val requestId: String,
    val operation: String?,
    val idempotencyKey: String?,
    /** False for hosted-page actions: Stripe's own UI triggers those events, not an API call. */
    val apiRequest: Boolean,
)

/** The request served on the current thread (set through a coroutine thread-context element). */
object RequestScope {
    val current: ThreadLocal<RequestContext?> = ThreadLocal()
}

/**
 * Seeded request ids. Position `n` of seed `s` always yields the same `req_` id, and the position
 * is part of the persisted world, so a replayed episode answers with the same `Request-Id`s.
 */
object RequestIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val LENGTH = 14

    fun at(seed: Long, position: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("request:$seed:$position".toByteArray())
        val sb = StringBuilder("req_")
        for (i in 0 until LENGTH) sb.append(ALPHABET[(digest[i].toInt() and 0xFF) % ALPHABET.length])
        return sb.toString()
    }
}
