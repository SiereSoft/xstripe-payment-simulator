package com.fakestripe.billing

import com.fakestripe.error.StripeException
import com.fakestripe.hosted.date
import com.fakestripe.model.Invoice
import com.fakestripe.model.InvoiceLine
import com.fakestripe.model.InvoicePayment
import com.fakestripe.model.Price
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionItem
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonPrimitive

/**
 * Subscription + invoice business logic: creating a subscription bills a first invoice (a $0 one
 * when it starts with a trial); the simulated clock ends trials and renews periods (see
 * `store.Scheduler`); changing items prorates as Stripe does; canceling ends it.
 *
 * Interval lengths are fixed constants (not calendar-exact) so proration math is
 * deterministic and a task checker can predict invoice totals.
 */
object BillingOps {
    private const val DAY = 86_400L
    private const val WEEK = 604_800L
    private const val MONTH = 2_592_000L // 30 days
    private const val YEAR = 31_536_000L // 365 days

    /** Stripe caps trials at two years. */
    const val MAX_TRIAL_DAYS = 730

    private val PRORATION_BEHAVIORS = setOf("create_prorations", "none", "always_invoice")

    fun intervalSeconds(interval: String, count: Int): Long {
        val base = when (interval) {
            "day" -> DAY
            "week" -> WEEK
            "month" -> MONTH
            "year" -> YEAR
            else -> MONTH
        }
        return base * count.coerceAtLeast(1)
    }

    private fun periodOf(price: Price): Long =
        intervalSeconds(price.recurringInterval ?: "month", price.recurringIntervalCount)

    // --- create -------------------------------------------------------------

    /** A requested subscription item: a recurring price and a quantity. */
    data class ItemSpec(val priceId: String, val quantity: Long, val metadata: Map<String, String> = emptyMap())

    fun createSubscription(store: DataStore, params: StripeParams): Subscription {
        val customerId = params.require("customer")
        store.requireCustomer(customerId)
        val itemsData = params.indexedSubMaps("items")
        if (itemsData.isEmpty()) throw StripeException.missingParam("items[0][price]")
        val specs = itemsData.mapIndexed { i, item ->
            val priceId = item["price"] ?: throw StripeException.missingParam("items[$i][price]")
            ItemSpec(priceId, quantity(item["quantity"], "items[$i][quantity]", min = 0) ?: 1L)
        }
        val trialEnd = parseTrial(
            store,
            trialEnd = params.opt("trial_end"),
            trialDays = params.opt("trial_period_days"),
            endParam = "trial_end",
            daysParam = "trial_period_days",
            minLeadSeconds = 1,
        )
        return create(
            store,
            customerId,
            specs,
            params.opt("default_payment_method") ?: store.customers[customerId]?.defaultPaymentMethod,
            params.subMap("metadata"),
            trialEnd,
            itemParam = "items",
        )
    }

    /**
     * Resolve Stripe's two trial parameters into a trial end (Unix seconds), or null for no trial.
     * `trial_end=now` means no trial; the two parameters are mutually exclusive.
     */
    fun parseTrial(
        store: DataStore,
        trialEnd: String?,
        trialDays: String?,
        endParam: String,
        daysParam: String,
        minLeadSeconds: Long,
    ): Long? {
        if (trialEnd != null && trialDays != null) {
            throw StripeException.invalidRequest(
                "You may only specify one of these parameters: $endParam, $daysParam.",
                param = endParam,
            )
        }
        val now = store.now()
        if (trialDays != null) {
            val days = trialDays.toLongOrNull()
                ?: throw StripeException.invalidRequest("Invalid integer: $trialDays", param = daysParam, code = "parameter_invalid_integer")
            if (days < 0 || days > MAX_TRIAL_DAYS) {
                throw StripeException.invalidRequest(
                    "`$daysParam` must be a whole number of days from 0 to $MAX_TRIAL_DAYS.",
                    param = daysParam,
                )
            }
            return if (days == 0L) null else now + days * DAY
        }
        if (trialEnd == null || trialEnd == "now") return null
        val end = trialEnd.toLongOrNull()
            ?: throw StripeException.invalidRequest("Invalid timestamp: must be an integer Unix timestamp in the future.", param = endParam)
        if (end < now + minLeadSeconds) {
            val lead = if (minLeadSeconds > 1) " at least ${minLeadSeconds / 3600} hours" else ""
            throw StripeException.invalidRequest(
                "Invalid timestamp: must be an integer Unix timestamp$lead in the future.",
                param = endParam,
            )
        }
        if (end > now + MAX_TRIAL_DAYS * DAY) {
            throw StripeException.invalidRequest(
                "Invalid timestamp: can be no more than $MAX_TRIAL_DAYS days in the future.",
                param = endParam,
            )
        }
        return end
    }

    /** Parse an item quantity; null when absent. */
    fun quantity(raw: String?, param: String, min: Long): Long? {
        if (raw == null) return null
        val value = raw.toLongOrNull()
            ?: throw StripeException.invalidRequest("Invalid integer: $raw", param = param, code = "parameter_invalid_integer")
        if (value < min) {
            throw StripeException.invalidRequest("This value must be greater than or equal to $min.", param = param)
        }
        return value
    }

    /** Stripe requires every price on a subscription to share currency and billing interval. */
    fun requireCompatiblePrices(prices: List<Price>, param: String) {
        val first = prices.first()
        prices.forEachIndexed { i, price ->
            if (price.recurringInterval == null) {
                throw StripeException.invalidRequest("Price ${price.id} is not a recurring price.", param = "$param[$i][price]")
            }
            if (price.currency != first.currency) {
                throw StripeException.invalidRequest(
                    "All prices on a subscription must have the same currency.",
                    param = "$param[$i][price]",
                )
            }
            if (price.recurringInterval != first.recurringInterval ||
                price.recurringIntervalCount != first.recurringIntervalCount
            ) {
                throw StripeException.invalidRequest(
                    "All prices on a subscription must have the same `recurring.interval` and `recurring.interval_count`.",
                    param = "$param[$i][price]",
                )
            }
        }
    }

    /** Create a subscription (API or Checkout), bill its first invoice and emit `customer.subscription.created`. */
    fun create(
        store: DataStore,
        customerId: String,
        specs: List<ItemSpec>,
        defaultPaymentMethod: String?,
        metadata: Map<String, String>,
        trialEnd: Long?,
        itemParam: String,
    ): Subscription {
        val prices = specs.map { store.requirePrice(it.priceId) }
        requireCompatiblePrices(prices, itemParam)
        val now = store.now()
        val subId = store.newId("sub")
        val items = specs.map { spec ->
            SubscriptionItem(store.newId("si"), now, spec.priceId, spec.quantity, subId, LinkedHashMap(spec.metadata))
        }.toMutableList()
        val first = prices.first()
        val periodEnd = trialEnd ?: (now + periodOf(first))

        val sub = Subscription(
            id = subId,
            created = now,
            customer = customerId,
            status = if (trialEnd != null) "trialing" else "active",
            items = items,
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            currency = first.currency,
            defaultPaymentMethod = defaultPaymentMethod,
            metadata = LinkedHashMap(metadata),
            trialStart = trialEnd?.let { now },
            trialEnd = trialEnd,
            billingCycleAnchor = trialEnd ?: now,
        )
        store.subscriptions[subId] = sub

        val lines = items.map { si ->
            val price = store.requirePrice(si.priceId)
            if (trialEnd != null) {
                InvoiceLine(
                    id = store.newId("il"),
                    amount = 0,
                    currency = price.currency,
                    description = "Trial period for ${productName(store, price)}",
                    priceId = price.id,
                    quantity = si.quantity,
                    periodStart = now,
                    periodEnd = trialEnd,
                    subscription = subId,
                    subscriptionItem = si.id,
                )
            } else {
                cycleLine(store, sub, si, price)
            }
        }
        val invoice = createInvoice(store, customerId, subId, sub.currency, lines, "subscription_create", sub.currentPeriodStart, sub.currentPeriodEnd)
        val paid = payInvoice(store, invoice, customerId, sub.defaultPaymentMethod)
        sub.latestInvoice = invoice.id
        // Stripe leaves a subscription whose *first* invoice never collected as
        // `incomplete` — it is not an active subscription until money moves.
        if (!paid) sub.status = "incomplete"
        store.recordEvent("customer.subscription.created", store.subscriptionJson(sub))
        return sub
    }

    // --- update -------------------------------------------------------------

    /** One requested change to a subscription's items. */
    data class ItemChange(
        val item: SubscriptionItem?,
        val priceId: String?,
        val quantity: Long?,
        val deleted: Boolean,
        val metadata: Map<String, String>,
    )

    fun updateSubscription(store: DataStore, sub: Subscription, params: StripeParams): Subscription {
        val itemsData = params.indexedSubMaps("items")
        if (itemsData.isNotEmpty() && isTerminal(sub)) {
            throw StripeException.invalidRequest(
                "A canceled subscription can only update its cancellation_details and metadata.",
                code = "subscription_status_invalid",
            )
        }
        params.bool("cancel_at_period_end")?.let { sub.cancelAtPeriodEnd = it }
        if (params.has("default_payment_method")) sub.defaultPaymentMethod = params.opt("default_payment_method")?.ifEmpty { null }
        params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) sub.metadata.remove(k) else sub.metadata[k] = v }

        if (itemsData.isNotEmpty()) {
            val changes = itemsData.mapIndexed { i, item ->
                val existing = item["id"]?.let { iid ->
                    sub.items.find { it.id == iid } ?: throw StripeException.resourceMissing("subscription_item", iid, param = "items[$i][id]")
                }
                val deleted = item["deleted"] == "true"
                if (existing == null && item["price"] == null) throw StripeException.missingParam("items[$i][price]")
                if (deleted && existing == null) throw StripeException.missingParam("items[$i][id]")
                ItemChange(
                    item = existing,
                    priceId = item["price"],
                    quantity = quantity(item["quantity"], "items[$i][quantity]", min = 0),
                    deleted = deleted,
                    metadata = params.subMap("items[$i][metadata]"),
                )
            }
            applyItemChanges(store, sub, changes, prorationBehavior(params), itemParam = "items")
        }
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return sub
    }

    /** `POST /v1/subscription_items/{id}`: change one item's price, quantity or metadata. */
    fun updateSubscriptionItem(store: DataStore, itemId: String, params: StripeParams): SubscriptionItem {
        val sub = store.subscriptions.values.firstOrNull { s -> s.items.any { it.id == itemId } }
            ?: throw StripeException.resourceMissing("subscription_item", itemId)
        if (isTerminal(sub)) {
            throw StripeException.invalidRequest(
                "The subscription ${sub.id} has been canceled and its items cannot be updated.",
                code = "subscription_status_invalid",
            )
        }
        val item = sub.items.first { it.id == itemId }
        val change = ItemChange(
            item = item,
            priceId = params.opt("price"),
            quantity = quantity(params.opt("quantity"), "quantity", min = 0),
            deleted = false,
            metadata = params.subMap("metadata"),
        )
        applyItemChanges(store, sub, listOf(change), prorationBehavior(params), itemParam = "items")
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return item
    }

    private fun prorationBehavior(params: StripeParams): String {
        val value = params.opt("proration_behavior") ?: "create_prorations"
        if (value !in PRORATION_BEHAVIORS) {
            throw StripeException.invalidRequest(
                "Invalid proration_behavior: must be one of create_prorations, none, or always_invoice",
                param = "proration_behavior",
            )
        }
        return value
    }

    /**
     * Apply item changes the way Stripe prorates them.
     *
     * - Each change is prorated to the second: a credit for the unused time of the old
     *   price × quantity and a charge for the remaining time of the new one (an added item only
     *   charges, a deleted one only credits). With `create_prorations` (the default) those lines
     *   wait as pending invoice items for the next invoice; `always_invoice` invoices and collects
     *   them now; `none` creates none.
     * - When the billing interval changes the cycle restarts now and the proration lines are
     *   invoiced and collected immediately (the simulator prorates the new price over the
     *   remainder of the old period instead of charging a full new period; see COMPATIBILITY.md).
     * - During a trial nothing is prorated (the trial is free).
     */
    private fun applyItemChanges(
        store: DataStore,
        sub: Subscription,
        changes: List<ItemChange>,
        prorationBehavior: String,
        itemParam: String,
    ) {
        val now = store.now()
        val before = sub.items.map { Triple(it, it.priceId, it.quantity) }
        val oldPeriodKey = store.requirePrice(sub.items.first().priceId).let { it.recurringInterval to it.recurringIntervalCount }

        // Resolve the resulting items first so validation fails before anything changes.
        changes.forEach { change -> change.priceId?.let { store.requirePrice(it) } }
        val resulting = ArrayList<Pair<String, Long>>() // priceId to quantity, for validation
        sub.items.forEach { item ->
            val change = changes.lastOrNull { it.item?.id == item.id }
            if (change?.deleted == true) return@forEach
            resulting += (change?.priceId ?: item.priceId) to (change?.quantity ?: item.quantity)
        }
        changes.filter { it.item == null }.forEach { resulting += it.priceId!! to (it.quantity ?: 1L) }
        if (resulting.isEmpty()) {
            throw StripeException.invalidRequest("A subscription must have at least one active item.", param = itemParam)
        }
        val resultingPrices = resulting.map { store.requirePrice(it.first) }
        requireCompatiblePrices(resultingPrices, itemParam)
        if (resultingPrices.first().currency != sub.currency) {
            throw StripeException.invalidRequest("All prices on a subscription must have the same currency.", param = itemParam)
        }

        // Mutate the items.
        changes.forEach { change ->
            val item = change.item
            when {
                item == null -> sub.items += SubscriptionItem(
                    store.newId("si"), now, change.priceId!!, change.quantity ?: 1L, sub.id, LinkedHashMap(change.metadata),
                )
                change.deleted -> sub.items.remove(item)
                else -> {
                    change.priceId?.let { item.priceId = it }
                    change.quantity?.let { item.quantity = it }
                    change.metadata.forEach { (k, v) -> if (v.isEmpty()) item.metadata.remove(k) else item.metadata[k] = v }
                }
            }
        }

        if (sub.status == "trialing") return

        val newFirst = store.requirePrice(sub.items.first().priceId)
        val intervalChanged = (newFirst.recurringInterval to newFirst.recurringIntervalCount) != oldPeriodKey
        val fraction = fractionRemaining(sub, now)

        val lines = ArrayList<InvoiceLine>()
        if (prorationBehavior != "none") {
            before.forEach { (item, oldPriceId, oldQty) ->
                val stillThere = item in sub.items
                val changed = !stillThere || item.priceId != oldPriceId || item.quantity != oldQty
                if (!changed) return@forEach
                lines += prorationLine(store, sub, item, store.requirePrice(oldPriceId), oldQty, now, credit = true, fraction)
                if (stillThere) {
                    lines += prorationLine(store, sub, item, store.requirePrice(item.priceId), item.quantity, now, credit = false, fraction)
                }
            }
            sub.items.filter { added -> before.none { it.first === added } }.forEach { added ->
                lines += prorationLine(store, sub, added, store.requirePrice(added.priceId), added.quantity, now, credit = false, fraction)
            }
        }
        val billable = lines.filter { it.amount != 0L }

        if (intervalChanged) {
            // The billing cycle restarts now and the change is invoiced immediately.
            sub.currentPeriodStart = now
            sub.currentPeriodEnd = now + periodOf(newFirst)
            sub.billingCycleAnchor = now
            if (billable.isNotEmpty() || sub.pendingProrations.isNotEmpty()) invoiceNow(store, sub, billable, now)
            return
        }
        if (billable.isEmpty()) return
        when (prorationBehavior) {
            "always_invoice" -> invoiceNow(store, sub, billable, now)
            else -> sub.pendingProrations += billable
        }
    }

    /** Invoice [lines] plus any pending prorations right away and collect it. */
    private fun invoiceNow(store: DataStore, sub: Subscription, lines: List<InvoiceLine>, now: Long) {
        val all = lines + sub.pendingProrations
        sub.pendingProrations.clear()
        val invoice = createInvoice(store, sub.customer, sub.id, sub.currency, all, "subscription_update", now, sub.currentPeriodEnd)
        payInvoice(store, invoice, sub.customer, sub.defaultPaymentMethod)
        sub.latestInvoice = invoice.id
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
     * The simulated clock does this when it reaches `current_period_end`; the controller can also
     * force it (`POST /v1/admin/subscriptions/{id}/renew`) so a test reaches the renewal paths — a
     * second `invoice.paid`, or a declining card that yields `invoice.payment_failed` and a
     * `past_due` subscription — without moving time.
     */
    fun renewSubscription(store: DataStore, sub: Subscription): Subscription {
        if (isTerminal(sub)) {
            throw StripeException.invalidRequest(
                "Subscription ${sub.id} has a status of '${sub.status}' and cannot be renewed.",
                code = "subscription_status_invalid",
            )
        }
        if (sub.status == "trialing") return endTrial(store, sub)
        // A subscription set to cancel at period end simply ends when that period does.
        if (sub.cancelAtPeriodEnd) return cancelSubscription(store, sub, atPeriodEnd = false)

        val firstPrice = store.requirePrice(sub.items.first().priceId)
        sub.currentPeriodStart = sub.currentPeriodEnd
        sub.currentPeriodEnd = sub.currentPeriodStart + periodOf(firstPrice)
        billCycle(store, sub)
        return sub
    }

    /**
     * End a trial: the first paid period starts at `trial_end`, its invoice is created and collected
     * with the default payment method, and the subscription becomes `active` (or `past_due` when
     * the payment fails). A trial set to cancel at period end is canceled instead.
     */
    fun endTrial(store: DataStore, sub: Subscription): Subscription {
        if (sub.cancelAtPeriodEnd) return cancelSubscription(store, sub, atPeriodEnd = false)
        val firstPrice = store.requirePrice(sub.items.first().priceId)
        val start = sub.trialEnd ?: store.now()
        sub.currentPeriodStart = start
        sub.currentPeriodEnd = start + periodOf(firstPrice)
        sub.status = "active"
        billCycle(store, sub)
        return sub
    }

    /** Invoice the current period (plus pending prorations), collect it, update the status. */
    private fun billCycle(store: DataStore, sub: Subscription) {
        val lines = sub.items.map { cycleLine(store, sub, it, store.requirePrice(it.priceId)) } + sub.pendingProrations
        sub.pendingProrations.clear()
        val invoice = createInvoice(
            store, sub.customer, sub.id, sub.currency, lines, "subscription_cycle",
            sub.currentPeriodStart, sub.currentPeriodEnd,
        )
        val paid = payInvoice(store, invoice, sub.customer, forcedDeclineCard(store, sub) ?: sub.defaultPaymentMethod)
        sub.latestInvoice = invoice.id
        // Dunning: a failed renewal is past_due, not cancelled — Stripe retries for days.
        sub.status = if (paid) "active" else "past_due"
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
    }

    /** The card a renewal is charged to instead of the default one, see [failNextRenewals]. */
    const val DEFAULT_FAILING_CARD = "4000000000000002"

    /**
     * Controller-forced dunning (`POST /v1/admin/subscriptions/{id}/fail_next_renewal`): make the
     * next [count] renewals of [sub] charge a declining test card. 0 clears it.
     */
    fun failNextRenewals(sub: Subscription, count: Int, card: String?) {
        sub.failNextRenewals = count
        sub.failRenewalCard = if (count > 0) card else null
    }

    /** A one-off payment method with the declining card when a failure was forced for this renewal. */
    private fun forcedDeclineCard(store: DataStore, sub: Subscription): String? {
        if (sub.failNextRenewals <= 0) return null
        sub.failNextRenewals -= 1
        val number = sub.failRenewalCard ?: DEFAULT_FAILING_CARD
        if (sub.failNextRenewals == 0) sub.failRenewalCard = null
        val pm = com.fakestripe.model.PaymentMethod(
            id = store.newId("pm"),
            created = store.now(),
            number = number,
            expMonth = 12,
            expYear = 2034,
        )
        store.paymentMethods[pm.id] = pm
        return pm.id
    }

    // --- invoices -----------------------------------------------------------

    /**
     * Attempt to collect an open invoice with the given payment method.
     * Returns true if the money moved. A failed collection leaves the invoice `open`
     * and unpaid and emits `invoice.payment_failed` — the dunning signal integrations
     * use to warn a customer without cutting them off on the first decline.
     */
    fun payInvoice(store: DataStore, invoice: Invoice, customerId: String, pmId: String?): Boolean {
        val now = store.now()
        if (invoice.finalizedAt == null) invoice.finalizedAt = now
        if (invoice.total <= 0) {
            invoice.status = "paid"; invoice.paid = true; invoice.amountPaid = 0; invoice.amountRemaining = 0
            invoice.paidAt = now
            store.recordEvent("invoice.paid", store.invoiceJson(invoice))
            return true
        }
        if (pmId == null) {
            failInvoice(store, invoice)
            return false
        }
        val pi = try {
            PaymentIntentMachine.create(
                store,
                StripeParams(
                    listOf(
                        "amount" to invoice.total.toString(),
                        "currency" to invoice.currency,
                        "customer" to customerId,
                        "payment_method" to pmId,
                        "confirm" to "true",
                        // Invoices are collected without the customer present.
                        "off_session" to "true",
                    ),
                ),
            )
        } catch (e: StripeException) {
            // A decline still leaves its PaymentIntent behind; the error names it.
            (e.paymentIntent?.get("id") as? JsonPrimitive)?.content?.let(store.paymentIntents::get)
        } catch (e: Exception) {
            null
        }
        pi?.let { recordInvoicePayment(store, invoice, it, now) }

        if (pi != null && pi.status == "succeeded") {
            invoice.status = "paid"; invoice.paid = true
            invoice.amountPaid = invoice.total; invoice.amountRemaining = 0
            invoice.paymentIntent = pi.id
            invoice.paidAt = now
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

    /**
     * Basil's InvoicePayment: the invoice's default payment points at the PaymentIntent of the
     * latest attempt and turns `paid` with it.
     */
    private fun recordInvoicePayment(store: DataStore, invoice: Invoice, pi: com.fakestripe.model.PaymentIntent, now: Long) {
        val payment = invoice.payments.lastOrNull { it.isDefault && it.status == "open" }
            ?: InvoicePayment(
                id = store.newId("inpay"),
                created = now,
                invoice = invoice.id,
                currency = invoice.currency,
                amountRequested = invoice.total,
            ).also { invoice.payments += it }
        payment.paymentIntent = pi.id
        if (pi.status == "succeeded") {
            payment.status = "paid"
            payment.amountPaid = pi.amountReceived
            payment.paidAt = now
        }
    }

    /** `POST /v1/invoices/{id}/void`: a voided invoice asks for nothing and its open payments are canceled. */
    fun voidInvoice(store: DataStore, invoice: Invoice) {
        // 0.1.x behaviour, kept: any invoice can be voided (Stripe refuses paid ones).
        val now = store.now()
        invoice.status = "void"
        invoice.paid = false
        invoice.amountRemaining = 0
        invoice.voidedAt = now
        invoice.payments.filter { it.status == "open" }.forEach {
            it.status = "canceled"
            it.canceledAt = now
        }
        store.recordEvent("invoice.voided", store.invoiceJson(invoice))
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
        val subscriptionMetadata = subId?.let { store.subscriptions[it]?.metadata?.toMap() }.orEmpty()
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
            subscriptionMetadata = subscriptionMetadata,
        )
        store.invoices[id] = invoice
        return invoice
    }

    // --- helpers ------------------------------------------------------------

    private fun cycleLine(store: DataStore, sub: Subscription, si: SubscriptionItem, price: Price) = InvoiceLine(
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
        val what = "$quantity × ${productName(store, price)}"
        val label = if (credit) "Unused time on $what after ${date(now)}" else "Remaining time on $what after ${date(now)}"
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
