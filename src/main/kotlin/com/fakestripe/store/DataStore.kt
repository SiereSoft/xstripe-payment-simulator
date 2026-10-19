package com.fakestripe.store

import com.fakestripe.error.StripeException
import com.fakestripe.model.BillingPortalSession
import com.fakestripe.model.Charge
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.Event
import com.fakestripe.model.Invoice
import com.fakestripe.model.InvoicePayment
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Refund
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionSchedule
import com.fakestripe.control.Fault
import com.fakestripe.control.RequestContext
import com.fakestripe.util.IdGenerator
import com.fakestripe.webhook.WebhookJob
import kotlinx.serialization.json.JsonObject

/**
 * The whole simulated world, held in memory. Insertion order is preserved so
 * listing is stable; a single [DataStore] instance is guarded by one lock in
 * [Simulator], which keeps ID generation and mutations deterministic.
 */
class DataStore(
    val seed: Long,
    idCount: Int = 0,
    var revision: Long = 0,
    clockState: SimulatorClockState = SimulatorClockState(),
    wallTimeSeconds: () -> Long = SimulatorClock.SYSTEM_SECONDS,
    val episodeId: String? = null,
    /** The seed as the controller gave it (`"lunch-rush-1"`); numeric seeds read the same. */
    val seedText: String = seed.toString(),
) {
    val ids = IdGenerator(seed, idCount)
    val clock = SimulatorClock(clockState, wallTimeSeconds)
    var scenario: ScenarioState? = null

    /** Named faults in installation order (control protocol section 5). */
    val faults = ArrayList<Fault>()

    /** Position of the seeded request-id stream: how many actor requests this world has served. */
    var requestSeq: Long = 0

    /** Webhook outbox in creation order, and the counter that names its jobs. */
    val outbox = LinkedHashMap<String, WebhookJob>()
    var outboxSeq: Long = 0

    /** Controller switches that belong to the world (carried by snapshots, kept across reset). */
    var config = SimConfig()

    /** Pre-protocol view of a scenario-seeded response-loss fault (`fault_injection` in the state export). */
    val faultInjection: FaultInjectionState?
        get() = faults.firstOrNull { it.origin == Fault.ORIGIN_SCENARIO }?.toLegacy()

    val customers = LinkedHashMap<String, Customer>()
    val paymentMethods = LinkedHashMap<String, PaymentMethod>()
    val paymentIntents = LinkedHashMap<String, PaymentIntent>()
    val charges = LinkedHashMap<String, Charge>()
    val refunds = LinkedHashMap<String, Refund>()
    val products = LinkedHashMap<String, Product>()
    val prices = LinkedHashMap<String, Price>()
    val subscriptionSchedules = LinkedHashMap<String, SubscriptionSchedule>()
    val subscriptions = LinkedHashMap<String, Subscription>()
    val invoices = LinkedHashMap<String, Invoice>()
    val checkoutSessions = LinkedHashMap<String, CheckoutSession>()
    val portalSessions = LinkedHashMap<String, BillingPortalSession>()
    val events = LinkedHashMap<String, Event>()
    val idempotency = LinkedHashMap<String, IdempotencyRecord>()

    /** Events emitted during the current write, awaiting the outbox (not persisted). */
    val pendingEvents = ArrayList<Event>()

    /** The API request whose events are being recorded (null for automatic events). Transient. */
    var eventOrigin: RequestContext? = null

    /** A card whose 3-D Secure challenge the customer just passed on the hosted page. Transient. */
    var authenticatedPaymentMethod: String? = null

    /** Record an event: retrievable via the API, persisted, and queued for webhook delivery. */
    fun recordEvent(type: String, dataObject: JsonObject): Event {
        val origin = eventOrigin?.takeIf { it.apiRequest }
        val event = Event(
            newId("evt"), now(), type, dataObject, episodeId,
            requestId = origin?.requestId,
            idempotencyKey = origin?.idempotencyKey,
        )
        events[event.id] = event
        pendingEvents.add(event)
        return event
    }

    fun newId(prefix: String): String = ids.next(prefix)

    /** Stripe client secrets look like `pi_123_secret_abc`; keep them deterministic. */
    fun newClientSecret(objectId: String): String =
        objectId + "_secret_" + ids.next("s").substringAfter('_')

    /** All runtime-created object timestamps flow through the simulator clock. */
    fun now(): Long = clock.now()

    /** The simulated time in epoch milliseconds. */
    fun nowMs(): Long = clock.nowMs()

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

    fun requireSchedule(id: String): SubscriptionSchedule =
        subscriptionSchedules[id] ?: throw StripeException.resourceMissing("subscription_schedule", id)

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

    /** A subscription as the API answers it; [basil] for 2025-03-31.basil and later versions. */
    fun subscriptionJson(sub: Subscription, basil: Boolean = false): JsonObject =
        sub.toApiJson({ prices[it]?.toApiJson() }, basil)

    /** An invoice as the API answers it; [includePayments] is basil's `expand[]=payments`. */
    fun invoiceJson(invoice: Invoice, basil: Boolean = false, includePayments: Boolean = false): JsonObject =
        invoice.toApiJson({ prices[it]?.toApiJson() }, basil, includePayments)

    /** Every InvoicePayment, oldest invoice first. */
    fun invoicePayments(): Sequence<InvoicePayment> = invoices.values.asSequence().flatMap { it.payments.asSequence() }

    fun refundsForCharge(chargeId: String): List<Refund> =
        refunds.values.filter { it.charge == chargeId }.sortedByDescending { it.created }

    /** Render a charge with its refunds inlined (as Stripe embeds `charge.refunds`). */
    fun chargeJson(charge: Charge): JsonObject =
        charge.toApiJson(refundsForCharge(charge.id).map { it.toApiJson() })

    /** Resolve any id to its API JSON — used to satisfy `expand[]` ([basil]: the request's version). */
    fun resolveJson(id: String, basil: Boolean = false): JsonObject? = when {
        id.startsWith("cus_") -> customers[id]?.toApiJson()
        id.startsWith("pm_") -> paymentMethods[id]?.toApiJson()
        id.startsWith("pi_") -> paymentIntents[id]?.toApiJson(basil)
        id.startsWith("ch_") -> charges[id]?.let { chargeJson(it) }
        id.startsWith("re_") -> refunds[id]?.toApiJson()
        id.startsWith("prod_") -> products[id]?.toApiJson()
        id.startsWith("price_") -> prices[id]?.toApiJson()
        id.startsWith("sub_sched_") -> subscriptionSchedules[id]?.toApiJson()
        id.startsWith("sub_") -> subscriptions[id]?.let { subscriptionJson(it, basil) }
        id.startsWith("in_") -> invoices[id]?.let { invoiceJson(it, basil) }
        id.startsWith("cs_") -> checkoutSessions[id]?.toApiJson()
        id.startsWith("bps_") -> portalSessions[id]?.toApiJson()
        id.startsWith("evt_") -> events[id]?.toApiJson()
        else -> null
    }
}

/** World-level controller configuration (`GET`/`PATCH /v1/admin/config`). */
@kotlinx.serialization.Serializable
data class SimConfig(
    /** `auto` delivers due webhooks in the background; `manual` only on flush. */
    val webhookDelivery: String = DELIVERY_AUTO,
    /** The webhook endpoint; the signing secret is never part of the world. */
    val webhookUrl: String? = null,
) {
    companion object {
        const val DELIVERY_AUTO = "auto"
        const val DELIVERY_MANUAL = "manual"
    }
}
