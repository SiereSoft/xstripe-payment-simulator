package com.fakestripe.store

import com.fakestripe.model.BillingPortalSession
import com.fakestripe.model.Charge
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.Event
import com.fakestripe.model.Invoice
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Refund
import com.fakestripe.model.Subscription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-disk snapshot of the in-memory store. This is what makes the "in-memory +
 * snapshot" persistence choice survive a container restart: after every mutation
 * we serialize the world to JSON, and on startup we load it back.
 *
 * [idCount] records how many IDs the generator has produced, so ID generation
 * resumes at the same point in the deterministic stream after a reload.
 */
@Serializable
private data class StoreSnapshot(
    val seed: Long,
    val idCount: Int,
    val revision: Long = 0,
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
)

object Snapshot {
    private val log = LoggerFactory.getLogger(Snapshot::class.java)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun save(store: DataStore, path: Path) {
        try {
            val snap = StoreSnapshot(
                seed = store.seed,
                idCount = store.ids.count,
                revision = store.revision,
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
            )
            path.parent?.let { Files.createDirectories(it) }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.write(tmp, json.encodeToString(StoreSnapshot.serializer(), snap).toByteArray())
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            log.warn("Failed to write snapshot to {}: {}", path, e.message)
        }
    }

    fun load(path: Path): DataStore? {
        if (!Files.exists(path)) return null
        return try {
            val snap = json.decodeFromString(StoreSnapshot.serializer(), String(Files.readAllBytes(path)))
            DataStore(snap.seed, idCount = snap.idCount, revision = snap.revision).apply {
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
            }
        } catch (e: Exception) {
            log.warn("Failed to load snapshot from {}: {}. Starting fresh.", path, e.message)
            null
        }
    }
}
