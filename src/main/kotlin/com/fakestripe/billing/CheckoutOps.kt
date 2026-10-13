package com.fakestripe.billing

import com.fakestripe.cards.TestCards
import com.fakestripe.error.StripeException
import com.fakestripe.model.CheckoutLine
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.DataStore
import com.fakestripe.util.RedirectUrl
import com.fakestripe.util.StripeParams

/**
 * Checkout Session lifecycle: create one from an API call, then complete it from the
 * hosted page.
 *
 * The important rule lives in [complete]: `checkout.session.completed` is emitted only
 * once the customer and subscription actually exist, and it is emitted *before* the
 * browser is redirected to `success_url`. An integration that grants access on the
 * event (the correct design) and one that grants it on the redirect (the bug) behave
 * identically in a browser — so the simulator has to keep the event path real.
 */
object CheckoutOps {

    private const val DAY = 86_400L

    /** Stripe expires an unpaid Checkout Session after 24 hours. */
    private const val SESSION_TTL = 86_400L

    /** Checkout requires `subscription_data[trial_end]` to be at least 48 hours away. */
    private const val MIN_TRIAL_END_LEAD = 48 * 3_600L

    /** Stripe substitutes the session id into `success_url` wherever this appears. */
    private const val SESSION_ID_TEMPLATE = "{CHECKOUT_SESSION_ID}"

    /** Stripe's message when a 3-D Secure challenge fails. */
    const val AUTHENTICATION_FAILED =
        "We are unable to authenticate your payment method. Please choose a different payment method and try again."

    sealed interface PayResult {
        /** Payment taken (or the session was already complete): send the browser on. */
        data class Redirect(val url: String) : PayResult

        /** Nothing was granted; re-render the page with this message. */
        data class Failed(val message: String) : PayResult

        /** The card asks for 3-D Secure: show the challenge page. */
        data class Authenticate(val url: String) : PayResult
    }

    // --- create -------------------------------------------------------------

    /** One `line_items[n]`: an existing `price`, or `price_data` to create one inline. */
    private class LineSpec(
        val index: Int,
        val priceId: String?,
        val inline: InlinePrice?,
        val quantity: Long,
        val currency: String,
        val unitAmount: Long,
        val interval: String?,
        val intervalCount: Int,
    )

    /** `line_items[n][price_data]`: becomes a Price (and, with `product_data`, a Product) at Stripe. */
    private class InlinePrice(
        val productId: String?,
        val productName: String?,
        val productDescription: String?,
        val productMetadata: Map<String, String>,
        val taxBehavior: String?,
    )

    fun createSession(store: DataStore, params: StripeParams, baseUrl: String): CheckoutSession {
        val mode = params.opt("mode") ?: "payment"
        if (mode != "payment" && mode != "subscription") {
            throw StripeException.invalidRequest(
                "Only `payment` and `subscription` modes are simulated (got `$mode`).", param = "mode",
            )
        }
        val successUrl = RedirectUrl.validate(
            params.opt("success_url") ?: throw StripeException.missingParam("success_url"),
            param = "success_url",
        )

        val indices = params.indices("line_items")
        if (indices.isEmpty()) throw StripeException.missingParam("line_items[0][price]")
        val specs = indices.map { i -> lineSpec(store, params, mode, i) }

        if (specs.any { it.currency != specs.first().currency }) {
            throw StripeException.invalidRequest("All line items must use the same currency.", param = "line_items")
        }
        if (mode == "subscription") {
            val existing = specs.mapNotNull { it.priceId }.map { store.requirePrice(it) }
            if (existing.isNotEmpty()) BillingOps.requireCompatiblePrices(existing, "line_items")
            specs.forEach { spec ->
                if (spec.interval != specs.first().interval || spec.intervalCount != specs.first().intervalCount) {
                    throw StripeException.invalidRequest(
                        "All prices on a subscription must have the same `recurring.interval` and `recurring.interval_count`.",
                        param = "line_items[${spec.index}][price]",
                    )
                }
            }
        }

        val subscriptionData = params.subMap("subscription_data")
        val subscriptionMetadata = params.subMap("subscription_data[metadata]")
        if (mode != "subscription" && (subscriptionData.isNotEmpty() || subscriptionMetadata.isNotEmpty())) {
            throw StripeException.invalidRequest(
                "You can not pass `subscription_data` in `$mode` mode.",
                param = "subscription_data",
            )
        }
        val paymentIntentData = params.subMap("payment_intent_data")
        val paymentIntentMetadata = params.subMap("payment_intent_data[metadata]")
        if (mode != "payment" && (paymentIntentData.isNotEmpty() || paymentIntentMetadata.isNotEmpty())) {
            throw StripeException.invalidRequest(
                "You can not pass `payment_intent_data` in `$mode` mode.",
                param = "payment_intent_data",
            )
        }
        val paymentMethodTypes = params.list("payment_method_types").ifEmpty { listOf("card") }
        paymentMethodTypes.firstOrNull { it != "card" }?.let {
            throw StripeException.invalidRequest(
                "The payment method type `$it` is not simulated; only `card` is.",
                param = "payment_method_types",
            )
        }
        if (params.bool("automatic_tax[enabled]") == true) {
            throw StripeException.invalidRequest(
                "Automatic tax is not simulated; pass `automatic_tax[enabled]=false`.",
                param = "automatic_tax[enabled]",
            )
        }
        val trialDays = subscriptionData["trial_period_days"]
        val trialEndParam = subscriptionData["trial_end"]
        if (trialDays != null && (trialDays.toLongOrNull() ?: 0L) < 1) {
            throw StripeException.invalidRequest(
                "`subscription_data[trial_period_days]` must be at least 1.",
                param = "subscription_data[trial_period_days]",
            )
        }
        val trialEnd = BillingOps.parseTrial(
            store,
            trialEnd = trialEndParam,
            trialDays = trialDays,
            endParam = "subscription_data[trial_end]",
            daysParam = "subscription_data[trial_period_days]",
            minLeadSeconds = MIN_TRIAL_END_LEAD,
        )

        val customerId = params.opt("customer")?.also { store.requireCustomer(it) }
        val currency = params.opt("currency")?.lowercase() ?: specs.first().currency
        val hasTrial = trialEnd != null
        // With a trial nothing is due today, so the session totals are zero (Stripe's behaviour).
        val due = specs.sumOf { it.unitAmount * it.quantity }
        if (!hasTrial) Amounts.requireChargeable(due, currency, allowZero = mode == "subscription", param = "line_items")
        val total = if (hasTrial) 0L else due

        // Everything is valid: only now create the inline products and prices.
        val now = store.now()
        val lines = specs.map { spec -> CheckoutLine(spec.priceId ?: createInlinePrice(store, spec, now), spec.quantity) }
        val id = store.newId("cs")

        val session = CheckoutSession(
            id = id,
            created = now,
            mode = mode,
            lines = lines.toMutableList(),
            successUrl = successUrl,
            cancelUrl = RedirectUrl.validateOptional(params.opt("cancel_url"), param = "cancel_url"),
            url = "${baseUrl.trimEnd('/')}/checkout/$id",
            currency = currency,
            amountTotal = total,
            expiresAt = now + SESSION_TTL,
            clientReferenceId = params.opt("client_reference_id"),
            customer = customerId,
            customerEmail = params.opt("customer_email") ?: customerId?.let { store.customers[it]?.email },
            subscriptionMetadata = LinkedHashMap(subscriptionMetadata),
            trialPeriodDays = trialDays?.toInt(),
            trialEnd = if (trialEndParam != null) trialEnd else null,
            paymentIntentMetadata = LinkedHashMap(paymentIntentMetadata),
            paymentIntentDescription = paymentIntentData["description"],
            paymentMethodTypes = paymentMethodTypes,
            allowPromotionCodes = params.bool("allow_promotion_codes"),
        )
        session.metadata.putAll(params.subMap("metadata"))
        store.checkoutSessions[id] = session
        return session
    }

    /** Validate `line_items[i]` without creating anything. */
    private fun lineSpec(store: DataStore, params: StripeParams, mode: String, i: Int): LineSpec {
        val item = "line_items[$i]"
        val priceId = params.opt("$item[price]")
        val priceData = params.subMap("$item[price_data]")
        val productData = params.subMap("$item[price_data][product_data]")
        val productMetadata = params.subMap("$item[price_data][product_data][metadata]")
        val recurring = params.subMap("$item[price_data][recurring]")
        val hasPriceData = priceData.isNotEmpty() || productData.isNotEmpty() || recurring.isNotEmpty()
        val quantity = BillingOps.quantity(params.opt("$item[quantity]"), "$item[quantity]", min = 1) ?: 1L
        if (priceId != null && hasPriceData) {
            throw StripeException.invalidRequest(
                "You may only specify one of these parameters: price, price_data.",
                param = "$item[price]",
            )
        }
        if (priceId == null && !hasPriceData) {
            throw StripeException.invalidRequest(
                "You must specify either `price` or `price_data` in `$item`.",
                param = "$item[price]",
            )
        }
        if (priceId != null) {
            val price = store.requirePrice(priceId)
            checkRecurringForMode(mode, price.recurringInterval != null, "$item[price]")
            return LineSpec(
                i, priceId, null, quantity, price.currency, price.unitAmount ?: 0L,
                price.recurringInterval, price.recurringIntervalCount,
            )
        }

        val currency = priceData["currency"]?.lowercase() ?: throw StripeException.missingParam("$item[price_data][currency]")
        Amounts.requireSupportedCurrency(currency, "$item[price_data][currency]")
        val unitAmount = when {
            priceData["unit_amount"] != null && priceData["unit_amount_decimal"] != null -> throw StripeException.invalidRequest(
                "You may only specify one of these parameters: unit_amount, unit_amount_decimal.",
                param = "$item[price_data][unit_amount]",
            )
            priceData["unit_amount"] != null -> priceData["unit_amount"]!!.toLongOrNull()
                ?: throw StripeException.invalidRequest(
                    "Invalid integer: ${priceData["unit_amount"]}",
                    param = "$item[price_data][unit_amount]", code = "parameter_invalid_integer",
                )
            priceData["unit_amount_decimal"] != null -> priceData["unit_amount_decimal"]!!.toBigDecimalOrNull()
                ?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toLong()
                ?: throw StripeException.invalidRequest(
                    "Only whole-number `unit_amount_decimal` values are simulated.",
                    param = "$item[price_data][unit_amount_decimal]",
                )
            else -> throw StripeException.missingParam("$item[price_data][unit_amount]")
        }
        if (unitAmount < 0) {
            throw StripeException.invalidRequest(
                "This value must be greater than or equal to 0.", param = "$item[price_data][unit_amount]",
            )
        }
        Amounts.requireNotTooLarge(unitAmount, currency, "$item[price_data][unit_amount]")

        val productId = priceData["product"]?.also { store.requireProduct(it) }
        if (productId != null && (productData.isNotEmpty() || productMetadata.isNotEmpty())) {
            throw StripeException.invalidRequest(
                "You may only specify one of these parameters: product, product_data.",
                param = "$item[price_data][product]",
            )
        }
        if (productId == null && productData["name"].isNullOrBlank()) {
            throw StripeException.missingParam("$item[price_data][product_data][name]")
        }

        val interval = recurring["interval"]
        if (recurring.isNotEmpty() && interval == null) throw StripeException.missingParam("$item[price_data][recurring][interval]")
        if (interval != null && interval !in setOf("day", "week", "month", "year")) {
            throw StripeException.invalidRequest(
                "Invalid $item[price_data][recurring][interval]: must be one of day, month, week, or year",
                param = "$item[price_data][recurring][interval]",
            )
        }
        val intervalCount = recurring["interval_count"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 1 } ?: throw StripeException.invalidRequest(
                "Invalid $item[price_data][recurring][interval_count]: must be a positive integer.",
                param = "$item[price_data][recurring][interval_count]",
            )
        } ?: 1
        if (interval != null && intervalCount > maxIntervalCount(interval)) {
            throw StripeException.invalidRequest(
                "The maximum billing interval is one year (1 year, 12 months, 52 weeks or 365 days).",
                param = "$item[price_data][recurring][interval_count]",
            )
        }
        checkRecurringForMode(mode, interval != null, "$item[price_data]")
        return LineSpec(
            i, null,
            InlinePrice(productId, productData["name"], productData["description"], productMetadata, priceData["tax_behavior"]),
            quantity, currency, unitAmount, interval, intervalCount,
        )
    }

    private fun maxIntervalCount(interval: String): Int = when (interval) {
        "day" -> 365
        "week" -> 52
        "month" -> 12
        else -> 1
    }

    private fun checkRecurringForMode(mode: String, recurring: Boolean, param: String) {
        if (mode == "subscription" && !recurring) {
            throw StripeException.invalidRequest(
                "You specified `subscription` mode but passed a one-time price.",
                param = param,
            )
        }
        if (mode == "payment" && recurring) {
            throw StripeException.invalidRequest(
                "You specified `payment` mode but passed a recurring price.",
                param = param,
            )
        }
    }

    /**
     * Stripe turns `price_data` into a Price of its own (and `product_data` into a Product). An
     * inline price is not reusable, so it is created inactive.
     */
    private fun createInlinePrice(store: DataStore, spec: LineSpec, now: Long): String {
        val inline = spec.inline!!
        val productId = inline.productId ?: Product(
            id = store.newId("prod"),
            created = now,
            updated = now,
            name = inline.productName!!,
            description = inline.productDescription,
        ).also {
            it.metadata.putAll(inline.productMetadata)
            store.products[it.id] = it
        }.id
        val price = Price(
            id = store.newId("price"),
            created = now,
            product = productId,
            currency = spec.currency,
            unitAmount = spec.unitAmount,
            active = false,
            recurringInterval = spec.interval,
            recurringIntervalCount = spec.intervalCount,
            taxBehavior = inline.taxBehavior ?: "unspecified",
        )
        store.prices[price.id] = price
        return price.id
    }

    /**
     * How a session reads right now: one past its expiry shows as `expired` even before the
     * scheduler has expired it (and emitted `checkout.session.expired`). Never mutates.
     */
    fun expireIfStale(store: DataStore, session: CheckoutSession): CheckoutSession =
        if (session.status == "open" && store.now() > session.expiresAt) session.copy(status = "expired") else session

    // --- complete -----------------------------------------------------------

    /**
     * What the hosted page's Pay button does: take the card, and only if it actually
     * charges (or, for a trial, validates), create the customer + subscription and complete the
     * session. A card that asks for 3-D Secure is sent to the challenge page first.
     */
    fun pay(store: DataStore, session: CheckoutSession, cardInput: String): PayResult {
        when (expireIfStale(store, session).status) {
            // Pressing Pay twice (or a stale tab) must not bill twice.
            "complete" -> return PayResult.Redirect(resolveSuccessUrl(session))
            "expired" -> return PayResult.Failed("This checkout session has expired. Start a new one.")
        }

        val pm = resolveCard(store, cardInput)
            ?: return PayResult.Failed("Enter a card number — try 4242 4242 4242 4242.")

        when (val outcome = TestCards.forNumber(pm.number).outcome) {
            is TestCards.Outcome.Decline -> {
                recordFailedAttempt(store, session, pm)
                return PayResult.Failed(outcome.message)
            }
            is TestCards.Outcome.RequiresAction -> {
                session.pendingAuthentication = pm.id
                return PayResult.Authenticate("/checkout/${session.id}/authenticate")
            }
            is TestCards.Outcome.Succeed -> Unit
        }
        return complete(store, session, pm)
    }

    /**
     * The 3-D Secure challenge's answer. `complete` charges the card as authenticated (and, in
     * subscription mode, saves it as authenticated for later off-session renewals); `fail` grants
     * nothing and leaves the session open.
     */
    fun authenticate(store: DataStore, session: CheckoutSession, outcome: String): PayResult {
        when (expireIfStale(store, session).status) {
            "complete" -> return PayResult.Redirect(resolveSuccessUrl(session))
            "expired" -> return PayResult.Failed("This checkout session has expired. Start a new one.")
        }
        val pmId = session.pendingAuthentication
            ?: return PayResult.Failed("There is no payment waiting for authentication. Enter a card to pay.")
        session.pendingAuthentication = null
        val pm = store.requirePaymentMethod(pmId)
        if (outcome != "complete") return PayResult.Failed(AUTHENTICATION_FAILED)
        if (session.mode == "subscription") pm.authenticated = true
        store.authenticatedPaymentMethod = pm.id
        return complete(store, session, pm)
    }

    private fun complete(store: DataStore, session: CheckoutSession, pm: PaymentMethod): PayResult {
        // The card is good, so the customer is real from here on.
        val customer = session.customer?.let { store.requireCustomer(it) } ?: createCustomer(store, session)
        pm.customer = customer.id
        pm.billingEmail = pm.billingEmail ?: customer.email
        pm.billingName = pm.billingName ?: customer.name
        customer.defaultPaymentMethod = pm.id
        session.customer = customer.id

        when (session.mode) {
            "subscription" -> {
                // A trial starts when the subscription does, as at Stripe.
                val trialEnd = session.trialEnd
                    ?: session.trialPeriodDays?.let { store.now() + it * DAY }
                val sub = BillingOps.create(
                    store,
                    customer.id,
                    session.lines.map { BillingOps.ItemSpec(it.priceId, it.quantity) },
                    pm.id,
                    session.subscriptionMetadata,
                    trialEnd,
                    itemParam = "line_items",
                )
                session.subscription = sub.id
                session.invoice = sub.latestInvoice
                session.paymentIntent = sub.latestInvoice?.let { store.invoices[it]?.paymentIntent }
            }
            else -> {
                val pi = PaymentIntentMachine.create(
                    store,
                    StripeParams(
                        listOf(
                            "amount" to session.amountTotal.toString(),
                            "currency" to session.currency,
                            "customer" to customer.id,
                            "payment_method" to pm.id,
                            "confirm" to "true",
                            "description" to (session.paymentIntentDescription ?: "Checkout session ${session.id}"),
                        ) + session.paymentIntentMetadata.map { (k, v) -> "metadata[$k]" to v },
                    ),
                )
                session.paymentIntent = pi.id
            }
        }

        session.status = "complete"
        session.paymentStatus = if (session.amountTotal > 0) "paid" else "no_payment_required"
        // Emitted last, and only now: the customer and subscription both exist, and the
        // client_reference_id handed in at creation is echoed back so the integration
        // can find whoever started this.
        store.recordEvent("checkout.session.completed", session.toApiJson())
        return PayResult.Redirect(resolveSuccessUrl(session))
    }

    fun resolveSuccessUrl(session: CheckoutSession): String =
        session.successUrl.replace(SESSION_ID_TEMPLATE, session.id)

    // --- helpers ------------------------------------------------------------

    /** Accept a raw PAN typed into the page, or one of Stripe's shared `pm_card_*` tokens. */
    private fun resolveCard(store: DataStore, input: String): PaymentMethod? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("pm_")) return PaymentIntentMachine.resolvePaymentMethod(store, trimmed)
        val number = trimmed.filter { it.isDigit() }
        if (number.length < 12) return null
        return PaymentMethod(
            id = store.newId("pm"),
            created = store.now(),
            number = number,
            expMonth = 12,
            expYear = 2034,
        ).also { store.paymentMethods[it.id] = it }
    }

    /**
     * Run the charge that was going to fail anyway, so the declined attempt is
     * recorded (charge.failed / payment_intent.payment_failed) exactly as it would be
     * through the API — but stop before anything is granted. Nothing is left behind
     * but the failed attempt: no customer, no subscription, session still open.
     */
    private fun recordFailedAttempt(store: DataStore, session: CheckoutSession, pm: PaymentMethod) {
        if (session.amountTotal < 1) return
        runCatching {
            PaymentIntentMachine.create(
                store,
                StripeParams(
                    listOf(
                        "amount" to session.amountTotal.toString(),
                        "currency" to session.currency,
                        "payment_method" to pm.id,
                        "confirm" to "true",
                        "description" to "Checkout session ${session.id}",
                    ),
                ),
            )
        }
    }

    private fun createCustomer(store: DataStore, session: CheckoutSession): Customer {
        val customer = Customer(
            id = store.newId("cus"),
            created = store.now(),
            email = session.customerEmail,
        )
        store.customers[customer.id] = customer
        store.recordEvent("customer.created", customer.toApiJson())
        return customer
    }
}
