package com.fakestripe.store

import com.fakestripe.control.Fault
import com.fakestripe.control.FaultMatch
import com.fakestripe.model.BillingPortalSession
import com.fakestripe.model.Charge
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.Event
import com.fakestripe.model.Invoice
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Refund
import com.fakestripe.model.Subscription
import com.fakestripe.webhook.WebhookJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The whole persisted world. This is what makes the "in-memory + snapshot" persistence choice
 * survive a container restart: after every mutation the world is serialized to JSON, and on
 * startup it is loaded back. Named snapshots (`/v1/admin/snapshots`) carry the same document.
 *
 * [idCount] records how many IDs the generator has produced, so ID generation resumes at the same
 * point in the deterministic stream after a reload; [requestSeq] does the same for request ids.
 */
@Serializable
internal data class StoreSnapshot(
    val seed: Long,
    val idCount: Int,
    val revision: Long = 0,
    val clock: SimulatorClockState = SimulatorClockState(),
    val episodeId: String? = null,
    val scenario: ScenarioState? = null,
    /** 0.1.x scenario fault; migrated to [faults] on load, never written. */
    val faultInjection: FaultInjectionState? = null,
    val customers: List<Customer>,
    val paymentMethods: List<PaymentMethod>,
    val paymentIntents: List<PaymentIntent>,
    val charges: List<Charge>,
    // Defaults keep older snapshots (written before these objects existed) loadable.
    val refunds: List<Refund> = emptyList(),
    val products: List<Product> = emptyList(),
    val prices: List<Price> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val checkoutSessions: List<CheckoutSession> = emptyList(),
    val portalSessions: List<BillingPortalSession> = emptyList(),
    val events: List<Event> = emptyList(),
    val idempotency: Map<String, IdempotencyRecord> = emptyMap(),
    val seedText: String? = null,
    val requestSeq: Long = 0,
    val faults: List<Fault> = emptyList(),
    val outbox: List<WebhookJob> = emptyList(),
    val outboxSeq: Long = 0,
    val config: SimConfig = SimConfig(),
    /** The journal's sequence high-water mark, so numbering never restarts. */
    val journalSeq: Long = 0,
)

object Snapshot {
    private val log = LoggerFactory.getLogger(Snapshot::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Schema of the world document inside named snapshots; a different value is incompatible. */
    const val FORMAT = "fake-stripe.world/1"

    fun save(store: DataStore, path: Path, journalSeq: Long = 0) {
        try {
            saveOrThrow(store, path, journalSeq)
        } catch (e: Exception) {
            log.warn("Failed to write snapshot to {}: {}", path, e.message)
        }
    }

    /** Persist or throw; reset uses this so it never acknowledges an unsaved world. */
    fun saveOrThrow(store: DataStore, path: Path, journalSeq: Long = 0) {
        writeAtomically(path, encode(store, journalSeq))
    }

    internal fun writeAtomically(path: Path, text: String) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray())
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    internal fun toSnapshot(store: DataStore, journalSeq: Long = 0): StoreSnapshot = StoreSnapshot(
        seed = store.seed,
        seedText = store.seedText,
        idCount = store.ids.count,
        revision = store.revision,
        clock = store.clock.state.copy(),
        episodeId = store.episodeId,
        scenario = store.scenario,
        customers = store.customers.values.toList(),
        paymentMethods = store.paymentMethods.values.toList(),
        paymentIntents = store.paymentIntents.values.toList(),
        charges = store.charges.values.toList(),
        refunds = store.refunds.values.toList(),
        products = store.products.values.toList(),
        prices = store.prices.values.toList(),
        subscriptions = store.subscriptions.values.toList(),
        invoices = store.invoices.values.toList(),
        checkoutSessions = store.checkoutSessions.values.toList(),
        portalSessions = store.portalSessions.values.toList(),
        events = store.events.values.toList(),
        idempotency = store.idempotency.toMap(),
        requestSeq = store.requestSeq,
        faults = store.faults.toList(),
        outbox = store.outbox.values.toList(),
        outboxSeq = store.outboxSeq,
        config = store.config,
        journalSeq = journalSeq,
    )

    fun encode(store: DataStore, journalSeq: Long = 0): String =
        json.encodeToString(StoreSnapshot.serializer(), toSnapshot(store, journalSeq))

    internal fun encodeElement(store: DataStore, journalSeq: Long = 0): JsonElement =
        json.encodeToJsonElement(StoreSnapshot.serializer(), toSnapshot(store, journalSeq))

    /** Rebuild a world from [text]; throws when it is not a world document. */
    fun decode(text: String, wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS): DataStore =
        fromSnapshot(json.decodeFromString(StoreSnapshot.serializer(), text), wallTimeSeconds)

    internal fun decodeElement(
        element: JsonElement,
        wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS,
    ): DataStore = fromSnapshot(json.decodeFromJsonElement(StoreSnapshot.serializer(), element), wallTimeSeconds)

    /** The journal sequence high-water mark recorded in a state document, or 0. */
    fun journalSeqOf(text: String): Long =
        runCatching { json.decodeFromString(StoreSnapshot.serializer(), text).journalSeq }.getOrDefault(0)

    /** A deep, independent copy of [store] (copy-on-write for atomic multi-step changes). */
    fun copyOf(store: DataStore, wallTimeSeconds: () -> Long): DataStore =
        fromSnapshot(json.decodeFromJsonElement(StoreSnapshot.serializer(), encodeElement(store)), wallTimeSeconds)

    private fun fromSnapshot(snap: StoreSnapshot, wallTimeSeconds: () -> Long): DataStore =
        DataStore(
            snap.seed,
            idCount = snap.idCount,
            revision = snap.revision,
            clockState = snap.clock,
            wallTimeSeconds = wallTimeSeconds,
            episodeId = snap.episodeId,
            seedText = snap.seedText ?: snap.seed.toString(),
        ).apply {
            scenario = snap.scenario
            faults += snap.faults
            snap.faultInjection?.takeIf { snap.faults.isEmpty() }?.let { faults += migrateLegacyFault(it) }
            snap.customers.forEach { customers[it.id] = it }
            snap.paymentMethods.forEach { paymentMethods[it.id] = it }
            snap.paymentIntents.forEach { paymentIntents[it.id] = it }
            snap.charges.forEach { charges[it.id] = it }
            snap.refunds.forEach { refunds[it.id] = it }
            snap.products.forEach { products[it.id] = it }
            snap.prices.forEach { prices[it.id] = it }
            snap.subscriptions.forEach { subscriptions[it.id] = it }
            snap.invoices.forEach { invoices[it.id] = it }
            snap.checkoutSessions.forEach { checkoutSessions[it.id] = it }
            snap.portalSessions.forEach { portalSessions[it.id] = it }
            snap.events.forEach { events[it.id] = it }
            idempotency.putAll(snap.idempotency)
            requestSeq = snap.requestSeq
            snap.outbox.forEach { outbox[it.id] = it }
            outboxSeq = snap.outboxSeq
            config = snap.config
        }

    /** 0.1.x stored its one scenario fault in a bespoke shape; it becomes a named fault. */
    private fun migrateLegacyFault(legacy: FaultInjectionState): Fault {
        val template = Fault.refundResponseLoss()
        return template.copy(
            match = FaultMatch(operation = legacy.operation),
            status = legacy.responseStatus,
            body = buildJsonObject {
                put("error", buildJsonObject {
                    put("type", "api_error")
                    put("code", legacy.responseCode)
                    put("message", "The operation committed, but its success response was lost.")
                })
            },
            count = maxOf(1, legacy.remaining + legacy.injectedCount),
            remaining = legacy.remaining,
            injected = legacy.injectedCount,
        )
    }

    fun load(
        path: Path,
        wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS,
    ): DataStore? {
        if (!Files.exists(path)) return null
        return try {
            decode(String(Files.readAllBytes(path)), wallTimeSeconds)
        } catch (e: Exception) {
            log.warn("Failed to load snapshot from {}: {}. Starting fresh.", path, e.message)
            null
        }
    }
}
