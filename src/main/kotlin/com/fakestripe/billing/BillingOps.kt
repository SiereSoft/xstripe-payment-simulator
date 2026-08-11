package com.fakestripe.billing

import com.fakestripe.error.StripeException
import com.fakestripe.model.Invoice
import com.fakestripe.model.InvoiceLine
import com.fakestripe.model.Price
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionItem
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Subscription + invoice business logic: creating a subscription bills a first
 * invoice; upgrading a subscription's price generates a proration invoice
 * (credit for unused time + charge for the remaining period); canceling ends it.
 *
 * Interval lengths are fixed constants (not calendar-exact) so proration math is
 * deterministic and a task checker can predict invoice totals.
 */
object BillingOps {
    private const val DAY = 86_400L
    private const val WEEK = 604_800L
    private const val MONTH = 2_592_000L // 30 days
    private const val YEAR = 31_536_000L // 365 days

    private fun intervalSeconds(interval: String, count: Int): Long {
        val base = when (interval) {
            "day" -> DAY
            "week" -> WEEK
            "month" -> MONTH
            "year" -> YEAR
            else -> MONTH
        }
        return base * count.coerceAtLeast(1)
    }

    // --- create -------------------------------------------------------------

    fun createSubscription(store: DataStore, params: StripeParams): Subscription {
        val customerId = params.require("customer")
        val customer = store.requireCustomer(customerId)
        val itemsData = params.indexedSubMaps("items")
        if (itemsData.isEmpty()) throw StripeException.missingParam("items[0][price]")

        val now = store.now()
        val subId = store.newId("sub")
        val items = mutableListOf<SubscriptionItem>()
        var currency = "usd"
        var periodSeconds = MONTH

        itemsData.forEachIndexed { i, item ->
            val priceId = item["price"] ?: throw StripeException.missingParam("items[$i][price]")
            val price = store.requirePrice(priceId)
            if (price.recurringInterval == null) {
                throw StripeException.invalidRequest("Price $priceId is not a recurring price.", param = "items[$i][price]")
            }
            val qty = item["quantity"]?.toLongOrNull() ?: 1L
            items.add(SubscriptionItem(store.newId("si"), now, priceId, qty, subId))
            if (i == 0) {
                currency = price.currency
                periodSeconds = intervalSeconds(price.recurringInterval, price.recurringIntervalCount)
            }
        }

        val sub = Subscription(
            id = subId,
            created = now,
            customer = customerId,
            status = "active",
            items = items,
            currentPeriodStart = now,
            currentPeriodEnd = now + periodSeconds,
            currency = currency,
            defaultPaymentMethod = params.opt("default_payment_method") ?: customer.defaultPaymentMethod,
        )
        store.subscriptions[subId] = sub

        val lines = items.map { si ->
            val price = store.requirePrice(si.priceId)
            InvoiceLine(
                id = store.newId("il"),
                amount = (price.unitAmount ?: 0) * si.quantity,
                currency = price.currency,
                description = lineDescription(store, price, si.quantity),
                priceId = price.id,
                quantity = si.quantity,
                periodStart = sub.currentPeriodStart,
                periodEnd = sub.currentPeriodEnd,
                subscription = subId,
                subscriptionItem = si.id,
            )
        }
        val invoice = createInvoice(store, customerId, subId, currency, lines, "subscription_create", sub.currentPeriodStart, sub.currentPeriodEnd)
        val paid = payInvoice(store, invoice, customerId, sub.defaultPaymentMethod)
        sub.latestInvoice = invoice.id
        // Stripe leaves a subscription whose *first* invoice never collected as
        // `incomplete` — it is not an active subscription until money moves.
        if (!paid) sub.status = "incomplete"
        store.recordEvent("customer.subscription.created", store.subscriptionJson(sub))
        return sub
    }

    // --- update / upgrade ---------------------------------------------------

    fun updateSubscription(store: DataStore, sub: Subscription, params: StripeParams): Subscription {
        params.bool("cancel_at_period_end")?.let { sub.cancelAtPeriodEnd = it }
        if (params.has("default_payment_method")) sub.defaultPaymentMethod = params.opt("default_payment_method")
        params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) sub.metadata.remove(k) else sub.metadata[k] = v }

        val itemsData = params.indexedSubMaps("items")
        if (itemsData.isEmpty()) {
            store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
            return sub
        }

        val prorationBehavior = params.opt("proration_behavior") ?: "create_prorations"
        val now = store.now()
        val fraction = fractionRemaining(sub, now)
        val prorationLines = mutableListOf<InvoiceLine>()
        var newPeriodEnd: Long? = null

        itemsData.forEach { item ->
            val target = item["id"]?.let { iid -> sub.items.find { it.id == iid } } ?: sub.items.firstOrNull() ?: return@forEach
            val newPriceId = item["price"]
            val newQty = item["quantity"]?.toLongOrNull()

            if (newPriceId != null && newPriceId != target.priceId) {
                val oldPrice = store.requirePrice(target.priceId)
                val newPrice = store.requirePrice(newPriceId)
                val qty = newQty ?: target.quantity
                if (prorationBehavior != "none") {
                    prorationLines.add(prorationLine(store, sub, target, oldPrice, target.quantity, now, credit = true, fraction))
                    prorationLines.add(prorationLine(store, sub, target, newPrice, qty, now, credit = false, fraction))
                }
                target.priceId = newPriceId
                if (newQty != null) target.quantity = newQty
                if (newPrice.recurringInterval != null && newPrice.recurringInterval != oldPrice.recurringInterval) {
                    newPeriodEnd = now + intervalSeconds(newPrice.recurringInterval, newPrice.recurringIntervalCount)
                }
            } else if (newQty != null) {
                target.quantity = newQty
            }
        }

        newPeriodEnd?.let { sub.currentPeriodStart = now; sub.currentPeriodEnd = it }

        if (prorationLines.isNotEmpty()) {
            val invoice = createInvoice(store, sub.customer, sub.id, sub.currency, prorationLines, "subscription_update", now, sub.currentPeriodEnd)
            payInvoice(store, invoice, sub.customer, sub.defaultPaymentMethod)
            sub.latestInvoice = invoice.id
        }
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return sub
    }

    fun cancelSubscription(store: DataStore, sub: Subscription, atPeriodEnd: Boolean): Subscription {
        if (atPeriodEnd) {
            sub.cancelAtPeriodEnd = true
        } else {
            val now = store.now()
            sub.status = "canceled"
            sub.canceledAt = now
            sub.endedAt = now
        }
        store.recordEvent(
            if (atPeriodEnd) "customer.subscription.updated" else "customer.subscription.deleted",
            store.subscriptionJson(sub),
        )
        return sub
    }

    /** Undo a pending `cancel_at_period_end` — the customer portal's "renew" button. */
    fun resumeSubscription(store: DataStore, sub: Subscription): Subscription {
        sub.cancelAtPeriodEnd = false
        sub.canceledAt = null
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return sub
    }

    /** Terminal states never bill again and can't be cancelled twice. */
    fun isTerminal(sub: Subscription): Boolean = sub.status == "canceled" || sub.status == "incomplete_expired"

    /**
     * Roll a subscription into its next billing period and try to collect.
     *
     * Real Stripe does this on a wall clock; the simulator does it on demand
     * (`POST /v1/admin/subscriptions/{id}/renew`) so a test can reach the renewal
     * paths — a second `invoice.paid`, or a declining card that yields
     * `invoice.payment_failed` and a `past_due` subscription — without waiting a month.
     */
    fun renewSubscription(store: DataStore, sub: Subscription): Subscription {
        if (isTerminal(sub)) {
            throw StripeException.invalidRequest(
                "Subscription ${sub.id} has a status of '${sub.status}' and cannot be renewed.",
                code = "subscription_status_invalid",
            )
        }
        // A subscription set to cancel at period end simply ends when that period does.
        if (sub.cancelAtPeriodEnd) return cancelSubscription(store, sub, atPeriodEnd = false)

        val firstPrice = store.requirePrice(sub.items.first().priceId)
        val period = intervalSeconds(firstPrice.recurringInterval ?: "month", firstPrice.recurringIntervalCount)
        sub.currentPeriodStart = sub.currentPeriodEnd
        sub.currentPeriodEnd = sub.currentPeriodStart + period

        val lines = sub.items.map { si ->
            val price = store.requirePrice(si.priceId)
            InvoiceLine(
                id = store.newId("il"),
                amount = (price.unitAmount ?: 0) * si.quantity,
                currency = price.currency,
                description = lineDescription(store, price, si.quantity),
                priceId = price.id,
                quantity = si.quantity,
                periodStart = sub.currentPeriodStart,
                periodEnd = sub.currentPeriodEnd,
                subscription = sub.id,
                subscriptionItem = si.id,
            )
        }
        val invoice = createInvoice(
            store, sub.customer, sub.id, sub.currency, lines, "subscription_cycle",
            sub.currentPeriodStart, sub.currentPeriodEnd,
        )
        val paid = payInvoice(store, invoice, sub.customer, sub.defaultPaymentMethod)
        sub.latestInvoice = invoice.id
        // Dunning: a failed renewal is past_due, not cancelled — Stripe retries for days.
        sub.status = if (paid) "active" else "past_due"
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return sub
    }

    // --- invoices -----------------------------------------------------------

    /**
     * Attempt to collect an open invoice with the given payment method.
     * Returns true if the money moved. A failed collection leaves the invoice `open`
     * and unpaid and emits `invoice.payment_failed` — the dunning signal integrations
     * use to warn a customer without cutting them off on the first decline.
     */
    fun payInvoice(store: DataStore, invoice: Invoice, customerId: String, pmId: String?): Boolean {
        if (invoice.total <= 0) {
            invoice.status = "paid"; invoice.paid = true; invoice.amountPaid = 0; invoice.amountRemaining = 0
            store.recordEvent("invoice.paid", store.invoiceJson(invoice))
            return true
        }
        if (pmId == null) {
            failInvoice(store, invoice)
            return false
        }
        val pi = runCatching {
            PaymentIntentMachine.create(
                store,
                StripeParams(
                    listOf(
                        "amount" to invoice.total.toString(),
                        "currency" to invoice.currency,
                        "customer" to customerId,
                        "payment_method" to pmId,
                        "confirm" to "true",
                    ),
                ),
            )
        }.getOrNull()

        if (pi != null && pi.status == "succeeded") {
            invoice.status = "paid"; invoice.paid = true
            invoice.amountPaid = invoice.total; invoice.amountRemaining = 0
            invoice.paymentIntent = pi.id
            store.recordEvent("invoice.paid", store.invoiceJson(invoice))
            invoice.subscription
                ?.let(store.subscriptions::get)
                ?.takeIf { it.status == "past_due" || it.status == "incomplete" }
                ?.let { subscription ->
                    subscription.status = "active"
                    store.recordEvent(
                        "customer.subscription.updated",
                        store.subscriptionJson(subscription),
                    )
                }
            return true
        }
        // The PaymentIntent exists even when the charge was declined; keep the link
        // so the failure is traceable from the invoice.
        invoice.paymentIntent = pi?.id ?: invoice.paymentIntent
        failInvoice(store, invoice)
        return false
    }

    private fun failInvoice(store: DataStore, invoice: Invoice) {
        invoice.status = "open"
        invoice.paid = false
        invoice.amountPaid = 0
        invoice.amountRemaining = invoice.total
        store.recordEvent("invoice.payment_failed", store.invoiceJson(invoice))
    }

    private fun createInvoice(
        store: DataStore,
        customerId: String,
        subId: String?,
        currency: String,
        lines: List<InvoiceLine>,
        reason: String,
        periodStart: Long,
        periodEnd: Long,
    ): Invoice {
        val subtotal = lines.sumOf { it.amount }
        val id = store.newId("in")
        val invoice = Invoice(
            id = id,
            created = store.now(),
            number = "INV-" + (store.invoices.size + 1).toString().padStart(4, '0'),
            customer = customerId,
            subscription = subId,
            status = "draft",
            subtotal = subtotal,
            total = subtotal,
            amountDue = subtotal,
            amountPaid = 0,
            amountRemaining = subtotal,
            currency = currency,
            lines = lines.toMutableList(),
            paid = false,
            billingReason = reason,
            periodStart = periodStart,
            periodEnd = periodEnd,
        )
        store.invoices[id] = invoice
        return invoice
    }

    // --- helpers ------------------------------------------------------------

    private fun fractionRemaining(sub: Subscription, now: Long): Double {
        val total = (sub.currentPeriodEnd - sub.currentPeriodStart).toDouble()
        if (total <= 0) return 0.0
        return ((sub.currentPeriodEnd - now).toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun prorationLine(
        store: DataStore,
        sub: Subscription,
        item: SubscriptionItem,
        price: Price,
        quantity: Long,
        now: Long,
        credit: Boolean,
        fraction: Double,
    ): InvoiceLine {
        val magnitude = ((price.unitAmount ?: 0).toDouble() * quantity * fraction).roundToLong()
        val amount = if (credit) -magnitude else magnitude
        val label = if (credit) "Unused time on ${productName(store, price)}" else "Remaining time on ${productName(store, price)}"
        return InvoiceLine(
            id = store.newId("il"),
            amount = amount,
            currency = price.currency,
            description = label,
            priceId = price.id,
            quantity = quantity,
            periodStart = now,
            periodEnd = sub.currentPeriodEnd,
            proration = true,
            subscription = sub.id,
            subscriptionItem = item.id,
        )
    }

    private fun lineDescription(store: DataStore, price: Price, qty: Long): String {
        val amount = (price.unitAmount ?: 0) / 100.0
        val interval = price.recurringInterval
        return "$qty × ${productName(store, price)} (at $${"%.2f".format(amount)}${if (interval != null) " / $interval" else ""})"
    }

    private fun productName(store: DataStore, price: Price): String =
        store.products[price.product]?.name ?: price.product
}
