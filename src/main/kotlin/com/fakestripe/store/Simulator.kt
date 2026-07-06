package com.fakestripe.store

import com.fakestripe.seed.Seeder
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
) {
    private val lock = Any()

    fun <T> read(block: (DataStore) -> T): T = synchronized(lock) { block(store) }

    fun <T> write(block: (DataStore) -> T): T = synchronized(lock) {
        // Snapshot in `finally` so state produced *before* a card error is thrown
        // (e.g. the recorded declined charge) is still persisted, matching Stripe.
        try {
            block(store)
        } finally {
            Snapshot.save(store, dataPath)
        }
    }

    fun reset(seed: Long) {
        synchronized(lock) {
            store = Seeder.build(seed)
            Snapshot.save(store, dataPath)
        }
    }

    val seed: Long get() = synchronized(lock) { store.seed }

    companion object {
        /**
         * Load the snapshot if present; otherwise build a fresh seeded world.
         * This is what gives "create a customer -> he's still there after restart".
         */
        fun boot(dataPath: Path, defaultSeed: Long): Simulator {
            val loaded = Snapshot.load(dataPath)
            val store = loaded ?: Seeder.build(defaultSeed)
            val sim = Simulator(store, dataPath)
            if (loaded == null) Snapshot.save(store, dataPath)
            return sim
        }
    }
}
