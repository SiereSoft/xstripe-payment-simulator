package com.fakestripe.webhook

import com.fakestripe.control.ControlProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One webhook delivery job in the persisted outbox: an event's exact signed bytes ([payload]) on
 * its way to the configured endpoint. A job is never dropped: it ends `delivered` or `exhausted`.
 *
 * Statuses: `pending` (waiting for its first attempt), `failed` (the last attempt failed and a
 * retry is scheduled at [nextAttemptAtMs]), `delivered` (a 2xx answer) and `exhausted` (the retry
 * window is used up).
 */
@Serializable
data class WebhookJob(
    val id: String,
    val eventId: String,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
    var status: String = PENDING,
    var attempts: Int = 0,
    var nextAttemptAtMs: Long? = createdAtMs,
    var lastAttemptAtMs: Long? = null,
    var lastResponseStatus: Int? = null,
    var lastError: String? = null,
    var deliveredAtMs: Long? = null,
    val replayOf: String? = null,
) {
    val open: Boolean get() = status == PENDING || status == FAILED

    /**
     * The protocol's job shape (`id`, `event_id`, `event_type`, `target`, `status`, `attempts`,
     * `next_attempt_at`, `last_status`) plus the simulator's extra detail. [target] is the
     * callback's path (never its query, which may carry a secret).
     */
    fun toJson(target: String?, includePayload: Boolean = false): JsonObject = buildJsonObject {
        put("id", id)
        put("event_id", eventId)
        put("event_type", eventType)
        put("target", target)
        put("status", status)
        put("attempts", attempts)
        put("next_attempt_at", nextAttemptAtMs?.let(ControlProtocol::iso))
        put("last_status", lastResponseStatus)
        put("created_at", ControlProtocol.iso(createdAtMs))
        put("last_attempt_at", lastAttemptAtMs?.let(ControlProtocol::iso))
        put("last_error", lastError)
        put("delivered_at", deliveredAtMs?.let(ControlProtocol::iso))
        put("replay_of", replayOf)
        put("payload_bytes", payload.toByteArray().size)
        if (includePayload) put("payload", payload)
    }

    companion object {
        const val PENDING = "pending"
        const val FAILED = "failed"
        const val DELIVERED = "delivered"
        const val EXHAUSTED = "exhausted"
        val STATUSES = setOf(PENDING, FAILED, DELIVERED, EXHAUSTED)
    }
}

/**
 * Stripe's documented live-mode behaviour: "In live mode, Stripe attempts to deliver a given
 * event to your webhook endpoint for up to 3 days with an exponential back off." Stripe does not
 * publish the intervals, so the simulator fixes them: after failed attempt `n` the next attempt is
 * `2^(n-1)` hours later (1 h, 2 h, 4 h, 8 h, 16 h, 32 h), measured from that attempt, and no
 * attempt is scheduled later than 72 hours after the event. Delivered on time, that is attempts
 * at +0 h, +1 h, +3 h, +7 h, +15 h, +31 h and +63 h, after which the job is `exhausted`.
 */
object RetrySchedule {
    const val HOUR_MS = 3_600_000L
    const val WINDOW_MS = 72 * HOUR_MS

    /** Delay before the attempt that follows failed attempt number [attempt] (1-based). */
    fun delayAfter(attempt: Int): Long = HOUR_MS shl (attempt - 1).coerceIn(0, 30)

    /** When to try again after failed attempt [attempt] made at [attemptAtMs], or null when exhausted. */
    fun nextAttempt(createdAtMs: Long, attempt: Int, attemptAtMs: Long): Long? {
        val next = attemptAtMs + delayAfter(attempt)
        return if (next > createdAtMs + WINDOW_MS) null else next
    }
}
