package com.fakestripe.billing

import com.fakestripe.cards.TestCards
import com.fakestripe.error.StripeException
import com.fakestripe.model.CheckoutLine
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentMethod
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams

/**
 * Checkout Session lifecycle: create one from an API call, then complete it from the
 * hosted page.
 *
 * The important rule lives in [pay]: `checkout.session.completed` is emitted only
 * once the customer and subscription actually exist, and it is emitted *before* the
 * browser is redirected to `success_url`. An integration that grants access on the
 * event (the correct design) and one that grants it on the redirect (the bug) behave
 * identically in a browser — so the simulator has to keep the event path real.
 */
object CheckoutOps {

    /** Stripe expires an unpaid Checkout Session after 24 hours. */
    private const val SESSION_TTL = 86_400L

    /** Stripe substitutes the session id into `success_url` wherever this appears. */
    private const val SESSION_ID_TEMPLATE = "{CHECKOUT_SESSION_ID}"

    sealed interface PayResult {
        /** Payment taken (or the session was already complete): send the browser on. */
        data class Redirect(val url: String) : PayResult

        /** Nothing was granted; re-render the page with this message. */
        data class Failed(val message: String) : PayResult
    }

    // --- create -------------------------------------------------------------

    fun createSession(store: DataStore, params: StripeParams, baseUrl: String): CheckoutSession {
        val mode = params.opt("mode") ?: "payment"
        if (mode != "payment" && mode != "subscription") {
            throw StripeException.invalidRequest(
                "Only `payment` and `subscription` modes are simulated (got `$mode`).", param = "mode",
            )
        }
        val successUrl = params.opt("success_url") ?: throw StripeException.missingParam("success_url")

        val itemsData = params.indexedSubMaps("line_items")
        if (itemsData.isEmpty()) throw StripeException.missingParam("line_items[0][price]")

        val lines = itemsData.mapIndexed { i, item ->
            val priceId = item["price"] ?: throw StripeException.invalidRequest(
                "Only existing prices are simulated; pass `line_items[$i][price]`.",
                param = "line_items[$i][price]",
            )
            val price = store.requirePrice(priceId)
            val recurring = price.recurringInterval != null
            if (mode == "subscription" && !recurring) {
                throw StripeException.invalidRequest(
                    "You specified `subscription` mode but passed a one-time price.",
                    param = "line_items[$i][price]",
                )
            }
            if (mode == "payment" && recurring) {
                throw StripeException.invalidRequest(
                    "You specified `payment` mode but passed a recurring price.",
                    param = "line_items[$i][price]",
                )
            }
            CheckoutLine(priceId, item["quantity"]?.toLongOrNull() ?: 1L)
        }

        val customerId = params.opt("customer")?.also { store.requireCustomer(it) }
        val firstPrice = store.requirePrice(lines.first().priceId)
        val total = lines.sumOf { (store.prices[it.priceId]?.unitAmount ?: 0) * it.quantity }
        val now = store.now()
        val id = store.newId("cs")

        val session = CheckoutSession(
            id = id,
            created = now,
            mode = mode,
            lines = lines.toMutableList(),
            successUrl = successUrl,
            cancelUrl = params.opt("cancel_url"),
            url = "${baseUrl.trimEnd('/')}/checkout/$id",
            currency = params.opt("currency")?.lowercase() ?: firstPrice.currency,
            amountTotal = total,
            expiresAt = now + SESSION_TTL,
            clientReferenceId = params.opt("client_reference_id"),
            customer = customerId,
            customerEmail = params.opt("customer_email") ?: customerId?.let { store.customers[it]?.email },
        )
        session.metadata.putAll(params.subMap("metadata"))
        store.checkoutSessions[id] = session
        return session
    }

    /** Lazily age out a session past its TTL (Stripe expires them on a timer). */
    fun expireIfStale(store: DataStore, session: CheckoutSession): CheckoutSession {
        if (session.status == "open" && store.now() > session.expiresAt) session.status = "expired"
        return session
    }

    // --- complete -----------------------------------------------------------

    /**
     * What the hosted page's Pay button does: take the card, and only if it actually
     * charges, create the customer + subscription and complete the session.
     */
    fun pay(store: DataStore, session: CheckoutSession, cardInput: String): PayResult {
        expireIfStale(store, session)
        when (session.status) {
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
                recordFailedAttempt(store, session, pm)
                return PayResult.Failed(
                    "This card requires 3-D Secure authentication, which the simulated " +
                        "checkout page does not run. Use a card that succeeds or declines outright.",
                )
            }
            is TestCards.Outcome.Succeed -> Unit
        }

        // The card is good, so the customer is real from here on.
        val customer = session.customer?.let { store.requireCustomer(it) } ?: createCustomer(store, session)
        pm.customer = customer.id
        pm.billingEmail = pm.billingEmail ?: customer.email
        pm.billingName = pm.billingName ?: customer.name
        customer.defaultPaymentMethod = pm.id
        session.customer = customer.id

        when (session.mode) {
            "subscription" -> {
                val sub = BillingOps.createSubscription(store, subscriptionParams(session, customer.id, pm.id))
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
                            "description" to "Checkout session ${session.id}",
                        ),
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

    private fun subscriptionParams(session: CheckoutSession, customerId: String, pmId: String): StripeParams {
        val entries = mutableListOf(
            "customer" to customerId,
            "default_payment_method" to pmId,
        )
        session.lines.forEachIndexed { i, line ->
            entries += "items[$i][price]" to line.priceId
            entries += "items[$i][quantity]" to line.quantity.toString()
        }
        return StripeParams(entries)
    }
}
