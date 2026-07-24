package com.fakestripe.store

import com.fakestripe.error.StripeException
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
import com.fakestripe.util.IdGenerator
import kotlinx.serialization.json.JsonObject

/**
 * The whole simulated world, held in memory. Insertion order is preserved so
 * listing is stable; a single [DataStore] instance is guarded by one lock in
 * [Simulator], which keeps ID generation and mutations deterministic.
 */
class DataStore(val seed: Long, idCount: Int = 0) {
    val ids = IdGenerator(seed, idCount)

    val customers = LinkedHashMap<String, Customer>()
    val paymentMethods = LinkedHashMap<String, PaymentMethod>()
    val paymentIntents = LinkedHashMap<String, PaymentIntent>()
    val charges = LinkedHashMap<String, Charge>()
    val refunds = LinkedHashMap<String, Refund>()
    val products = LinkedHashMap<String, Product>()
    val prices = LinkedHashMap<String, Price>()
    val subscriptions = LinkedHashMap<String, Subscription>()
    val invoices = LinkedHashMap<String, Invoice>()
    val checkoutSessions = LinkedHashMap<String, CheckoutSession>()
    val portalSessions = LinkedHashMap<String, BillingPortalSession>()
    val events = LinkedHashMap<String, Event>()
    val idempotency = LinkedHashMap<String, IdempotencyRecord>()

    /** Events emitted during the current write, awaiting webhook delivery (not persisted). */
    val pendingEvents = ArrayList<Event>()

    /** Record an event: retrievable via the API, persisted, and queued for webhook delivery. */
    fun recordEvent(type: String, dataObject: JsonObject): Event {
        val event = Event(newId("evt"), now(), type, dataObject)
        events[event.id] = event
        pendingEvents.add(event)
        return event
    }

    fun newId(prefix: String): String = ids.next(prefix)

    /** Stripe client secrets look like `pi_123_secret_abc`; keep them deterministic. */
    fun newClientSecret(objectId: String): String =
        objectId + "_secret_" + ids.next("s").substringAfter('_')

    /** Wall-clock seconds for runtime-created objects (timestamps are ignored by checkers). */
    fun now(): Long = System.currentTimeMillis() / 1000

    fun requireCustomer(id: String): Customer =
        customers[id]?.takeIf { !it.deleted } ?: throw StripeException.resourceMissing("customer", id)

    fun requirePaymentMethod(id: String): PaymentMethod =
        paymentMethods[id] ?: throw StripeException.resourceMissing("payment_method", id)

    fun requirePaymentIntent(id: String): PaymentIntent =
        paymentIntents[id] ?: throw StripeException.resourceMissing("payment_intent", id, param = "intent")

    fun requireCharge(id: String): Charge =
        charges[id] ?: throw StripeException.resourceMissing("charge", id)

    fun requireRefund(id: String): Refund =
        refunds[id] ?: throw StripeException.resourceMissing("refund", id)

    fun requireProduct(id: String): Product =
        products[id] ?: throw StripeException.resourceMissing("product", id)

    fun requirePrice(id: String): Price =
        prices[id] ?: throw StripeException.resourceMissing("price", id)

    fun requireSubscription(id: String): Subscription =
        subscriptions[id] ?: throw StripeException.resourceMissing("subscription", id)

    fun requireInvoice(id: String): Invoice =
        invoices[id] ?: throw StripeException.resourceMissing("invoice", id)

    fun requireEvent(id: String): Event =
        events[id] ?: throw StripeException.resourceMissing("event", id)

    fun requireCheckoutSession(id: String): CheckoutSession =
        checkoutSessions[id] ?: throw StripeException.resourceMissing("checkout.session", id)

    fun requirePortalSession(id: String): BillingPortalSession =
        portalSessions[id] ?: throw StripeException.resourceMissing("billing_portal.session", id)

    fun subscriptionJson(sub: Subscription): JsonObject =
        sub.toApiJson { prices[it]?.toApiJson() }

    fun invoiceJson(invoice: Invoice): JsonObject =
        invoice.toApiJson { prices[it]?.toApiJson() }

    fun refundsForCharge(chargeId: String): List<Refund> =
        refunds.values.filter { it.charge == chargeId }.sortedByDescending { it.created }

    /** Render a charge with its refunds inlined (as Stripe embeds `charge.refunds`). */
    fun chargeJson(charge: Charge): JsonObject =
        charge.toApiJson(refundsForCharge(charge.id).map { it.toApiJson() })

    /** Resolve any id to its API JSON — used to satisfy `expand[]`. */
    fun resolveJson(id: String): JsonObject? = when {
        id.startsWith("cus_") -> customers[id]?.toApiJson()
        id.startsWith("pm_") -> paymentMethods[id]?.toApiJson()
        id.startsWith("pi_") -> paymentIntents[id]?.toApiJson()
        id.startsWith("ch_") -> charges[id]?.let { chargeJson(it) }
        id.startsWith("re_") -> refunds[id]?.toApiJson()
        id.startsWith("prod_") -> products[id]?.toApiJson()
        id.startsWith("price_") -> prices[id]?.toApiJson()
        id.startsWith("sub_") -> subscriptions[id]?.let { subscriptionJson(it) }
        id.startsWith("in_") -> invoices[id]?.let { invoiceJson(it) }
        id.startsWith("cs_") -> checkoutSessions[id]?.toApiJson()
        id.startsWith("bps_") -> portalSessions[id]?.toApiJson()
        id.startsWith("evt_") -> events[id]?.toApiJson()
        else -> null
    }
}
