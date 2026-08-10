package com.fakestripe.store

import com.fakestripe.seed.Seeder
import com.fakestripe.webhook.WebhookDispatcher
import java.nio.file.Path

/**
 * Owns the live [DataStore] and serializes all access through a single lock.
 *
 * - [read]  runs a block against the store without persisting.
 * - [write] runs a mutating block, then snapshots to disk.
 * - [reset] rebuilds the world from a seed (the /v1/admin/reset endpoint).
 *
 * Holding one lock keeps deterministic ID generation and state transitions safe
 * even though Netty serves requests on many threads.
 */
class Simulator(
    @Volatile var store: DataStore,
    private val dataPath: Path,
    val webhooks: WebhookDispatcher = WebhookDispatcher(null, "whsec_test"),
    private val wallTimeSeconds: () -> Long = { SimulatorClock.systemTimeSeconds() },
) {
    private val lock = Any()

    fun <T> read(block: (DataStore) -> T): T = synchronized(lock) { block(store) }

    fun <T> write(block: (DataStore) -> T): T = synchronized(lock) {
        // Snapshot in `finally` so state produced *before* a card error is thrown
        // (e.g. the recorded declined charge) is still persisted, matching Stripe.
        try {
            block(store)
        } finally {
            store.revision += 1
            Snapshot.save(store, dataPath)
            drainEvents()
        }
    }

    /** Hand any events emitted during the write to the webhook dispatcher. */
    private fun drainEvents() {
        if (store.pendingEvents.isEmpty()) return
        val toDeliver = store.pendingEvents.toList()
        store.pendingEvents.clear()
        toDeliver.forEach { webhooks.deliver(it) }
    }

    /** Point webhook delivery at a URL (with optional secret) at runtime. */
    fun configureWebhook(url: String?, secret: String?) {
        if (url != null) webhooks.url = url
        if (secret != null) webhooks.secret = secret
    }

    fun reset(seed: Long, scenario: String? = null, clockMode: ClockMode? = null) {
        synchronized(lock) {
            val nextRevision = store.revision + 1
            val mode = clockMode ?: ClockMode.defaultFor(scenario)
            val candidate = Seeder.build(seed, scenario, mode, wallTimeSeconds).also {
                it.revision = nextRevision
            }
            Snapshot.saveOrThrow(candidate, dataPath)
            store = candidate
        }
    }

    data class ClockAdvanceResult(val currentTime: Long, val revision: Long)

    /** Advance manual episode time atomically; return null when the clock is free-running. */
    fun advanceClock(seconds: Long): ClockAdvanceResult? = synchronized(lock) {
        if (store.clock.mode != ClockMode.MANUAL) return@synchronized null
        val previousTime = store.clock.now()
        val previousRevision = store.revision
        val expiredSessionIds = mutableListOf<String>()
        return@synchronized try {
            val currentTime = store.clock.advance(seconds)
            store.checkoutSessions.values
                .filter { it.status == "open" && currentTime > it.expiresAt }
                .forEach {
                    expiredSessionIds += it.id
                    it.status = "expired"
                }
            store.revision += 1
            Snapshot.saveOrThrow(store, dataPath)
            ClockAdvanceResult(currentTime, store.revision)
        } catch (e: Exception) {
            store.clock.state.manualTime = previousTime
            store.revision = previousRevision
            expiredSessionIds.forEach { id -> store.checkoutSessions[id]?.status = "open" }
            throw e
        }
    }

    val seed: Long get() = synchronized(lock) { store.seed }

    /** Idempotency: look up a prior response by key. */
    fun idempotencyLookup(key: String): IdempotencyRecord? = synchronized(lock) { store.idempotency[key] }

    /** Idempotency: remember the response for a key so repeats replay it. */
    fun recordIdempotency(key: String, fingerprint: String, status: Int, body: String) {
        synchronized(lock) {
            store.idempotency[key] = IdempotencyRecord(fingerprint, status, body)
            store.revision += 1
            Snapshot.save(store, dataPath)
        }
    }

    companion object {
        /**
         * Load the snapshot if present; otherwise build a fresh seeded world.
         * This is what gives "create a customer -> he's still there after restart".
         */
        fun boot(
            dataPath: Path,
            defaultSeed: Long,
            webhooks: WebhookDispatcher = WebhookDispatcher(null, "whsec_test"),
            defaultClockMode: ClockMode = ClockMode.FREE,
            wallTimeSeconds: () -> Long = { SimulatorClock.systemTimeSeconds() },
        ): Simulator {
            val loaded = Snapshot.load(dataPath, wallTimeSeconds)
            val store = loaded ?: Seeder.build(
                defaultSeed,
                clockMode = defaultClockMode,
                wallTimeSeconds = wallTimeSeconds,
            )
            val sim = Simulator(store, dataPath, webhooks, wallTimeSeconds)
            if (loaded == null) Snapshot.save(store, dataPath)
            return sim
        }
    }
}
