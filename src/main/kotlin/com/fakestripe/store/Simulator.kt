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

    fun reset(seed: Long) {
        synchronized(lock) {
            val nextRevision = store.revision + 1
            store = Seeder.build(seed).also { it.revision = nextRevision }
            Snapshot.save(store, dataPath)
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
        ): Simulator {
            val loaded = Snapshot.load(dataPath)
            val store = loaded ?: Seeder.build(defaultSeed)
            val sim = Simulator(store, dataPath, webhooks)
            if (loaded == null) Snapshot.save(store, dataPath)
            return sim
        }
    }
}
