package com.fakestripe.store

import kotlinx.serialization.Serializable

/**
 * The cached outcome of a POST made with an `Idempotency-Key`. A repeat of the
 * same key replays [status] + [body] verbatim instead of re-executing; a repeat
 * with a *different* request body (different [fingerprint]) is an error.
 */
@Serializable
data class IdempotencyRecord(
    val fingerprint: String,
    val status: Int,
    val body: String,
)
