package com.fakestripe.store

import com.fakestripe.control.ControlException
import com.fakestripe.control.ControlProtocol
import com.fakestripe.control.Fault
import com.fakestripe.control.Journal
import com.fakestripe.control.JournalEntry
import com.fakestripe.control.RequestIds
import com.fakestripe.control.RequestScope
import com.fakestripe.control.SnapshotStore
import com.fakestripe.seed.Seeder
import com.fakestripe.webhook.RetrySchedule
import com.fakestripe.webhook.WebhookDispatcher
import com.fakestripe.webhook.WebhookJob
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Owns the live [DataStore] and serializes all access through a single lock.
 *
 * - [read]  runs a block against the store without persisting.
 * - [write] runs a mutating block, settles work that fell due, queues the events it emitted for
 *   webhook delivery, and persists the world.
 * - [reset], [restoreSnapshot] replace the world; [moveClockTo] moves the manual clock and settles
 *   everything that falls due on the way, in time order.
 *
 * Holding one lock keeps deterministic ID generation and state transitions safe even though Netty
 * serves requests on many threads. Webhook attempts are network calls, so they happen outside
 * that lock; a second lock ([deliveryLock]) keeps clock moves, flushes and the background pump
 * from delivering concurrently.
 */
class Simulator(
    store: DataStore,
    private val dataPath: Path,
    val webhooks: WebhookDispatcher = WebhookDispatcher(null, "whsec_test"),
    private val wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS,
    /** The configured seed a reset without one falls back to. */
    val defaultSeedText: String = store.seedText,
    journalSeqFloor: Long = 0,
) : AutoCloseable {
    @Volatile
    var store: DataStore = store
        private set

    private val lock = Any()
    private val deliveryLock = ReentrantLock(true)
    private val log = LoggerFactory.getLogger(Simulator::class.java)

    val journal = Journal(Journal.pathFor(dataPath))
    val snapshots = SnapshotStore((dataPath.toAbsolutePath().parent ?: dataPath.toAbsolutePath()).resolve("snapshots"))

    /** Bumped by reset and restore: work in flight against an older world is discarded. */
    @Volatile
    private var generation = 0L
    private var persistedRequestSeq = store.requestSeq

    init {
        journal.ensureSeqAtLeast(journalSeqFloor)
        // An endpoint configured in the environment wins over the one a state file remembers.
        webhooks.url?.let { store.config = store.config.copy(webhookUrl = it) }
    }

    // --- access -----------------------------------------------------------------------------

    fun <T> read(block: (DataStore) -> T): T = synchronized(lock) { block(store) }

    fun <T> write(block: (DataStore) -> T): T = synchronized(lock) {
        val s = store
        try {
            settleWorldLocked(s, s.nowMs())
            s.eventOrigin = RequestScope.current.get()
            block(s)
        } finally {
            // Persist in `finally` so state produced *before* a card error is thrown (e.g. the
            // recorded declined charge) is still kept, matching Stripe.
            s.eventOrigin = null
            s.authenticatedPaymentMethod = null
            settleWorldLocked(s, s.nowMs())
            enqueueEventsLocked(s)
            s.revision += 1
            persist(s)
            signal()
        }
    }

    val seed: Long get() = synchronized(lock) { store.seed }
    val episodeId: String? get() = synchronized(lock) { store.episodeId }

    private fun persist(s: DataStore) {
        Snapshot.save(s, dataPath, journal.lastSeq)
        persistedRequestSeq = s.requestSeq
    }

    private fun persistOrThrow(s: DataStore) {
        Snapshot.saveOrThrow(s, dataPath, journal.lastSeq)
        persistedRequestSeq = s.requestSeq
    }

    // --- requests ---------------------------------------------------------------------------

    /** The next seeded request id; its position is part of the world. */
    fun nextRequestId(): String = synchronized(lock) {
        store.requestSeq += 1
        RequestIds.at(store.seed, store.requestSeq)
    }

    /** Journal one finished actor request and persist the request-id position if nothing else did. */
    fun recordRequest(entry: JournalEntry) {
        synchronized(lock) {
            journal.append(entry.copy(episodeId = store.episodeId, at = iso(store.nowMs())))
            if (store.requestSeq != persistedRequestSeq) persist(store)
        }
    }

    /** Idempotency: look up a prior response by key. */
    fun idempotencyLookup(key: String): IdempotencyRecord? = synchronized(lock) { store.idempotency[key] }

    /** Idempotency: remember the response for a key so repeats replay it. */
    fun recordIdempotency(key: String, fingerprint: String, status: Int, body: String, requestId: String? = null) {
        synchronized(lock) {
            store.idempotency[key] = IdempotencyRecord(fingerprint, status, body, requestId)
            store.revision += 1
            persist(store)
        }
    }

    // --- faults -----------------------------------------------------------------------------

    /**
     * The fault that applies to a request, consuming one use of it. Every matching fault still
     * honouring its `skip` counts the request toward it (and lets it pass); of the matching faults
     * past their `skip` with uses left, the first installed applies and the others are untouched.
     */
    fun takeFault(operation: String?, method: String, path: String, requestId: String?): Fault? =
        synchronized(lock) {
            var changed = false
            var applied: Fault? = null
            for (fault in store.faults) {
                if (fault.remaining <= 0 || !fault.match.matches(operation, method, path)) continue
                if (fault.skipped < fault.skip) {
                    fault.skipped += 1
                    changed = true
                    continue
                }
                if (applied == null) {
                    fault.remaining -= 1
                    fault.injected += 1
                    applied = fault.copy()
                    changed = true
                }
            }
            if (changed) {
                store.revision += 1
                persist(store)
            }
            applied?.also {
                journal.append(
                    entry(
                        JournalEntry.FAULT, operation = operation, method = method, path = path,
                        requestId = requestId, fault = it.name,
                        detail = buildJsonObject {
                            put("kind", it.kind)
                            put("remaining", it.remaining)
                        },
                    ),
                )
            }
        }

    fun faults(): List<Fault> = read { s -> s.faults.map { it.copy() } }

    fun installFault(fault: Fault): Fault = mutateControl("fault.install", buildJsonObject { put("fault", fault.name) }) { s ->
        if (s.faults.any { it.name == fault.name }) {
            throw ControlException.conflict("fault_exists", "A fault named '${fault.name}' is already installed.")
        }
        s.faults += fault
        fault.copy()
    }

    fun deleteFault(name: String): Unit = mutateControl("fault.delete", buildJsonObject { put("fault", name) }) { s ->
        if (!s.faults.removeIf { it.name == name }) throw ControlException.notFound("No fault named '$name'.")
    }

    fun deleteAllFaults(): Unit = mutateControl("fault.delete_all", null) { s -> s.faults.clear() }

    /**
     * An atomic controller change: applied to a copy, persisted, then swapped in, so a failed
     * write changes nothing. Journaled as a `control` entry.
     */
    private fun <T> mutateControl(operation: String, detail: JsonElement?, block: (DataStore) -> T): T =
        synchronized(lock) {
            val copy = Snapshot.copyOf(store, wallTimeSeconds)
            val result = block(copy)
            copy.revision = store.revision + 1
            persistOrThrow(copy)
            store = copy
            journal.append(entry(JournalEntry.CONTROL, operation = operation, detail = detail))
            signal()
            result
        }

    // --- configuration ----------------------------------------------------------------------

    /** Point webhook delivery at a URL (with optional secret) at runtime. */
    fun configureWebhook(url: String?, secret: String?) {
        if (secret != null) webhooks.secret = secret
        if (url != null) {
            mutateControl("config.webhook", buildJsonObject { put("url_configured", true) }) { s ->
                s.config = s.config.copy(webhookUrl = url)
            }
        }
    }

    fun setWebhookDelivery(mode: String): SimConfig =
        mutateControl("config.update", buildJsonObject { put("webhook_delivery", mode) }) { s ->
            s.config = s.config.copy(webhookDelivery = mode)
            s.config
        }

    // --- lifecycle --------------------------------------------------------------------------

    fun reset(
        seed: Long,
        scenario: String? = null,
        clockMode: ClockMode? = null,
        episodeId: String? = null,
        seedText: String = seed.toString(),
        startMs: Long? = null,
    ) {
        synchronized(lock) {
            val mode = clockMode ?: ClockMode.defaultFor(scenario)
            val candidate = Seeder.build(
                seed,
                scenario,
                mode,
                wallTimeSeconds,
                episodeId,
                seedText = seedText,
                startMs = startMs,
            ).also {
                it.revision = store.revision + 1
                it.config = store.config
            }
            Snapshot.saveOrThrow(candidate, dataPath, journal.lastSeq)
            persistedRequestSeq = candidate.requestSeq
            store = candidate
            generation += 1
            journal.clear()
            journal.append(
                entry(
                    JournalEntry.CONTROL, operation = "reset",
                    detail = buildJsonObject {
                        put("seed", seedText)
                        put("scenario", scenario)
                        put("clock_mode", mode.wireValue)
                    },
                ),
            )
            logEpisodeReset(seedText, scenario, episodeId)
        }
        signal()
    }

    private fun logEpisodeReset(seed: String, scenario: String?, episodeId: String?) {
        val previousEpisodeId = MDC.get("episode_id")
        try {
            MDC.put("episode_id", episodeId ?: "none")
            log.info("Reset simulator world: seed={}, scenario={}", seed, scenario ?: "none")
        } finally {
            if (previousEpisodeId == null) MDC.remove("episode_id")
            else MDC.put("episode_id", previousEpisodeId)
        }
    }

    fun saveSnapshot(name: String, replace: Boolean): SnapshotStore.Meta = synchronized(lock) {
        if (!replace && snapshots.exists(name)) {
            throw ControlException.conflict("snapshot_exists", "A snapshot named '$name' already exists.")
        }
        snapshots.save(
            name,
            Snapshot.encodeElement(store),
            createdAtMs = System.currentTimeMillis(),
            clockMs = store.nowMs(),
            revision = store.revision,
            episodeId = store.episodeId,
        )
    }

    fun restoreSnapshot(name: String) {
        synchronized(lock) {
            val world = try {
                snapshots.world(name)
            } catch (e: SnapshotStore.Incompatible) {
                throw ControlException.conflict("snapshot_incompatible", e.message ?: "Incompatible snapshot.")
            }
            val restored = try {
                Snapshot.decodeElement(world, wallTimeSeconds)
            } catch (e: Exception) {
                throw ControlException.conflict("snapshot_incompatible", "Snapshot '$name' cannot be read by this simulator.")
            }
            restored.revision = maxOf(store.revision, restored.revision) + 1
            Snapshot.saveOrThrow(restored, dataPath, journal.lastSeq)
            persistedRequestSeq = restored.requestSeq
            store = restored
            generation += 1
            journal.append(
                entry(JournalEntry.CONTROL, operation = "snapshot.restore", detail = buildJsonObject { put("snapshot", name) }),
            )
        }
        signal()
    }

    // --- clock ------------------------------------------------------------------------------

    data class ClockAdvanceResult(val currentTime: Long, val revision: Long)

    data class ClockMoveResult(val nowMs: Long, val settled: Int, val revision: Long)

    /** Advance manual episode time; return null when the clock is free-running. */
    fun advanceClock(seconds: Long): ClockAdvanceResult? {
        if (read { it.clock.mode } != ClockMode.MANUAL) return null
        val target = Math.addExact(read { it.nowMs() }, Math.multiplyExact(seconds, 1000L))
        val moved = moveClockTo(target)
        return ClockAdvanceResult(Math.floorDiv(moved.nowMs, 1000L), moved.revision)
    }

    /**
     * Move the manual clock to [targetMs], settling every item that falls due on the way in time
     * order. Between webhook attempts the move is applied in atomic, persisted segments (each one
     * built on a copy of the world and swapped in only once saved), so receivers can call the API
     * while they are being called. Without due webhooks the whole move is one atomic step.
     */
    fun moveClockTo(targetMs: Long): ClockMoveResult = deliveryLock.withLock {
        val gen = synchronized(lock) {
            if (store.clock.mode != ClockMode.MANUAL) {
                throw ControlException(
                    io.ktor.http.HttpStatusCode.Conflict,
                    "clock_not_manual",
                    "The simulator clock follows the wall clock (free mode); reset with clock_mode=manual to move it.",
                    param = "clock_mode",
                )
            }
            if (targetMs < store.nowMs()) {
                throw ControlException.conflict(
                    "clock_backwards",
                    "The clock cannot move back from ${iso(store.nowMs())} to ${iso(targetMs)}.",
                )
            }
            generation
        }
        val fromMs = read { it.nowMs() }
        var settled = 0
        while (true) {
            val due = synchronized(lock) {
                if (generation != gen) return@synchronized null
                val base = store
                val copy = Snapshot.copyOf(base, wallTimeSeconds)
                val auto = copy.config.webhookDelivery == SimConfig.DELIVERY_AUTO
                val failed = HashSet<String>()
                val entries = ArrayList<JournalEntry>()
                var webhook: Scheduler.Item.Webhook? = null
                while (true) {
                    val item = Scheduler.next(copy, targetMs, auto, failed) ?: break
                    copy.clock.setMs(maxOf(copy.clock.state.manualTimeMs!!, item.atMs))
                    if (item is Scheduler.Item.Webhook) {
                        webhook = item
                        break
                    }
                    applyWorldItem(copy, item, failed)?.let { entries += it }
                }
                if (webhook == null) copy.clock.setMs(targetMs)
                copy.revision = base.revision + 1
                persistOrThrow(copy)
                store = copy
                entries.forEach { journal.append(it.copy(episodeId = copy.episodeId)) }
                settled += entries.size
                webhook
            } ?: break
            if (deliverJob(due.jobId, gen) != null) settled += 1
        }
        synchronized(lock) {
            journal.append(
                entry(
                    JournalEntry.CLOCK, operation = "clock.move",
                    detail = buildJsonObject {
                        put("from", iso(fromMs))
                        put("to", iso(store.nowMs()))
                        put("settled", settled)
                    },
                ),
            )
            ClockMoveResult(store.nowMs(), settled, store.revision)
        }.also { signal() }
    }

    /** Settle due world items (not webhooks) up to [limitMs] inside the world lock. */
    private fun settleWorldLocked(s: DataStore, limitMs: Long): Int {
        val failed = HashSet<String>()
        var count = 0
        while (true) {
            val item = Scheduler.nextWorld(s, limitMs, failed) ?: break
            applyWorldItem(s, item, failed)?.let {
                journal.append(it.copy(episodeId = s.episodeId))
                count += 1
            }
        }
        return count
    }

    /** Apply one world item at its due time; returns its journal entry (unsequenced) or null on failure. */
    private fun applyWorldItem(s: DataStore, item: Scheduler.Item, failed: MutableSet<String>): JournalEntry? {
        val savedOrigin = s.eventOrigin
        s.eventOrigin = null
        s.clock.override = item.atMs
        return try {
            val applied = Scheduler.apply(s, item)
            enqueueEventsLocked(s)
            JournalEntry(
                at = iso(item.atMs), wall = iso(System.currentTimeMillis()), kind = JournalEntry.STATE,
                operation = applied.operation, refs = applied.refs,
            )
        } catch (e: Exception) {
            log.error("Scheduled work {} failed; skipping it for now", item.key, e)
            failed += item.key
            s.pendingEvents.clear()
            null
        } finally {
            s.clock.override = null
            s.eventOrigin = savedOrigin
        }
    }

    // --- webhooks ---------------------------------------------------------------------------

    /** Move freshly emitted events into the outbox (when an endpoint is configured). */
    private fun enqueueEventsLocked(s: DataStore) {
        if (s.pendingEvents.isEmpty()) return
        val url = s.config.webhookUrl
        if (url != null) {
            val at = s.nowMs()
            s.pendingEvents.forEach { event ->
                s.outboxSeq += 1
                val id = "whjob_" + s.outboxSeq.toString().padStart(6, '0')
                s.outbox[id] = WebhookJob(
                    id = id,
                    eventId = event.id,
                    eventType = event.type,
                    payload = event.toApiJson().toString(),
                    createdAtMs = at,
                )
            }
            Scheduler.pruneOutbox(s)
        }
        s.pendingEvents.clear()
    }

    /** One attempt for [jobId]: read under the lock, POST outside it, record under it. */
    private fun deliverJob(jobId: String, gen: Long): WebhookJob? {
        val prepared = synchronized(lock) {
            if (generation != gen) return null
            val job = store.outbox[jobId] ?: return null
            Triple(job.payload, store.config.webhookUrl, store.nowMs())
        }
        val (payload, target, attemptMs) = prepared
        val result = webhooks.attempt(target, payload, Math.floorDiv(attemptMs, 1000L))
        return synchronized(lock) {
            if (generation != gen) return@synchronized null
            val job = store.outbox[jobId] ?: return@synchronized null
            job.attempts += 1
            job.lastAttemptAtMs = attemptMs
            job.lastResponseStatus = result.status
            job.lastError = result.error
            if (result.delivered) {
                job.status = WebhookJob.DELIVERED
                job.deliveredAtMs = attemptMs
                job.nextAttemptAtMs = null
            } else {
                job.nextAttemptAtMs = RetrySchedule.nextAttempt(job.createdAtMs, job.attempts, attemptMs)
                job.status = if (job.nextAttemptAtMs == null) WebhookJob.EXHAUSTED else WebhookJob.FAILED
            }
            store.revision += 1
            persist(store)
            journal.append(
                entry(
                    JournalEntry.CALLBACK, operation = "webhook.deliver", method = "POST",
                    path = WebhookDispatcher.pathOf(target), status = result.status, outcome = job.status.let {
                        if (it == WebhookJob.DELIVERED) "delivered" else if (it == WebhookJob.EXHAUSTED) "exhausted" else "failed"
                    },
                    refs = mapOf("event" to job.eventId, "attempt" to job.attempts.toString(), "webhook" to job.id),
                    detail = buildJsonObject {
                        put("event_type", job.eventType)
                        put("error", result.error)
                    },
                    atMs = attemptMs,
                ),
            )
            job.copy()
        }
    }

    fun webhookJobs(status: String?): List<WebhookJob> = read { s ->
        s.outbox.values.filter { status == null || it.status == status }.map { it.copy() }
    }

    fun webhookJob(id: String): WebhookJob = read { s ->
        s.outbox[id]?.copy() ?: throw ControlException.notFound("No webhook job '$id'.")
    }

    /**
     * Deliver due jobs now, oldest first unless [reverse], up to [concurrency] at a time;
     * [includeFuture] also takes jobs whose next attempt is not yet due. Listed [ids] are attempted
     * whatever their state or due time (an unknown id is a 404 before anything is sent). Answers
     * each job as it stands after its attempt.
     */
    fun flushWebhooks(ids: List<String>?, reverse: Boolean, concurrency: Int, includeFuture: Boolean): List<WebhookJob> =
        deliveryLock.withLock {
            val gen: Long
            val selected: List<WebhookJob>
            synchronized(lock) {
                gen = generation
                val now = store.nowMs()
                val jobs = if (ids != null) {
                    ids.map { store.outbox[it] ?: throw ControlException.notFound("No webhook job '$it'.") }
                } else {
                    store.outbox.values.filter { it.open && (includeFuture || (it.nextAttemptAtMs ?: Long.MAX_VALUE) <= now) }
                        .sortedWith(compareBy({ it.nextAttemptAtMs ?: it.createdAtMs }, { it.id }))
                }
                selected = (if (reverse) jobs.reversed() else jobs).map { it.copy() }
            }
            val work = selected.map { job -> Callable { deliverJob(job.id, gen) ?: job } }
            if (concurrency <= 1 || work.size <= 1) {
                work.map { it.call() }
            } else {
                val pool = Executors.newFixedThreadPool(minOf(concurrency, work.size)) { r ->
                    Thread(r, "webhook-flush").apply { isDaemon = true }
                }
                try {
                    pool.invokeAll(work).map { it.get() }
                } finally {
                    pool.shutdownNow()
                }
            }
        }

    /** The callback path jobs are delivered to (for the protocol's `target`). */
    fun webhookTarget(): String? = read { WebhookDispatcher.pathOf(it.config.webhookUrl) }

    /** Enqueue an exact duplicate of a job (same event, same bytes); answers the new job. */
    fun replayWebhook(id: String): WebhookJob = mutateControl("webhook.replay", buildJsonObject { put("webhook", id) }) { s ->
        val original = s.outbox[id] ?: throw ControlException.notFound("No webhook job '$id'.")
        s.outboxSeq += 1
        val newId = "whjob_" + s.outboxSeq.toString().padStart(6, '0')
        val now = s.nowMs()
        val job = WebhookJob(
            id = newId,
            eventId = original.eventId,
            eventType = original.eventType,
            payload = original.payload,
            createdAtMs = now,
            replayOf = original.id,
        )
        s.outbox[newId] = job
        job.copy()
    }

    // --- background pump ----------------------------------------------------------------------

    private val pumpSignal = Object()
    private var signalled = false

    @Volatile
    private var closed = false
    private val pump = Thread(::pumpLoop, "fake-stripe-scheduler").apply { isDaemon = true }

    init {
        pump.start()
    }

    /** Wake the pump: something may have become due. */
    fun signal() {
        synchronized(pumpSignal) {
            signalled = true
            pumpSignal.notifyAll()
        }
    }

    /**
     * Free mode: settle world items as the wall clock reaches them. `auto` delivery: attempt every
     * webhook job whose time has come, in order. Runs on a daemon thread, ticking every second.
     */
    private fun pumpLoop() {
        while (!closed) {
            synchronized(pumpSignal) {
                if (!signalled) pumpSignal.wait(PUMP_TICK_MS)
                signalled = false
            }
            if (closed) break
            try {
                pumpOnce()
            } catch (e: Exception) {
                log.error("Background scheduler pass failed", e)
            }
        }
    }

    internal fun pumpOnce() = deliveryLock.withLock {
        val gen = synchronized(lock) {
            val s = store
            if (s.clock.mode == ClockMode.FREE && Scheduler.nextWorld(s, s.nowMs()) != null) {
                settleWorldLocked(s, s.nowMs())
                s.revision += 1
                persist(s)
            }
            generation
        }
        val tried = HashSet<String>()
        while (!closed) {
            val jobId = synchronized(lock) {
                if (generation != gen || store.config.webhookDelivery != SimConfig.DELIVERY_AUTO) null
                else Scheduler.nextWebhook(store, store.nowMs(), tried)?.jobId
            } ?: break
            tried += "webhook:$jobId"
            deliverJob(jobId, gen) ?: break
        }
    }

    override fun close() {
        closed = true
        signal()
        pump.join(TimeUnit.SECONDS.toMillis(5))
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun iso(ms: Long) = ControlProtocol.iso(ms)

    private fun entry(
        kind: String,
        operation: String? = null,
        method: String? = null,
        path: String? = null,
        status: Int? = null,
        outcome: String? = null,
        requestId: String? = null,
        refs: Map<String, String> = emptyMap(),
        fault: String? = null,
        detail: JsonElement? = null,
        atMs: Long? = null,
    ) = JournalEntry(
        episodeId = store.episodeId,
        at = iso(atMs ?: store.nowMs()),
        wall = iso(System.currentTimeMillis()),
        kind = kind,
        operation = operation,
        method = method,
        path = path,
        status = status,
        outcome = outcome,
        requestId = requestId,
        refs = refs,
        fault = fault,
        detail = detail,
    )

    companion object {
        const val PUMP_TICK_MS = 1_000L

        /**
         * Load the snapshot if present; otherwise build a fresh seeded world.
         * This is what gives "create a customer -> he's still there after restart".
         */
        fun boot(
            dataPath: Path,
            defaultSeed: Long,
            webhooks: WebhookDispatcher = WebhookDispatcher(null, "whsec_test"),
            defaultClockMode: ClockMode = ClockMode.FREE,
            wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS,
            defaultSeedText: String = defaultSeed.toString(),
        ): Simulator {
            val loaded = Snapshot.load(dataPath, wallTimeSeconds)
            val journalSeq = if (loaded != null) {
                Snapshot.journalSeqOf(String(Files.readAllBytes(dataPath)))
            } else {
                0L
            }
            val store = loaded ?: Seeder.build(
                defaultSeed,
                clockMode = defaultClockMode,
                wallTimeSeconds = wallTimeSeconds,
                seedText = defaultSeedText,
            )
            val sim = Simulator(store, dataPath, webhooks, wallTimeSeconds, defaultSeedText, journalSeq)
            if (loaded == null) sim.persist(store)
            return sim
        }
    }
}
