package com.fakestripe.statemachine

import com.fakestripe.cards.TestCards
import com.fakestripe.error.StripeErrorType
import com.fakestripe.error.StripeException
import com.fakestripe.model.Charge
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentIntent.Status
import com.fakestripe.model.PaymentMethod
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * All PaymentIntent state transitions. Keeping them in one place makes the state
 * machine auditable — reviewers can read the entire lifecycle here rather than
 * hunting through route handlers.
 */
object PaymentIntentMachine {

    private val CONFIRMABLE = setOf(
        Status.REQUIRES_PAYMENT_METHOD, Status.REQUIRES_CONFIRMATION, Status.REQUIRES_ACTION,
    )
    private val CANCELABLE = setOf(
        Status.REQUIRES_PAYMENT_METHOD, Status.REQUIRES_CONFIRMATION,
        Status.REQUIRES_ACTION, Status.PROCESSING, Status.REQUIRES_CAPTURE,
    )

    // --- create -------------------------------------------------------------

    fun create(store: DataStore, params: StripeParams): PaymentIntent {
        val amount = params.long("amount") ?: throw StripeException.missingParam("amount")
        if (amount < 1) {
            throw StripeException.invalidRequest(
                "Invalid positive integer", param = "amount", code = "parameter_invalid_integer",
            )
        }
        val currency = params.require("currency").lowercase()
        val customerId = params.opt("customer")?.also { store.requireCustomer(it) }
        val types = params.list("payment_method_types").ifEmpty { listOf("card") }

        val id = store.newId("pi")
        val pi = PaymentIntent(
            id = id,
            created = store.now(),
            amount = amount,
            currency = currency,
            clientSecret = store.newClientSecret(id),
            status = Status.REQUIRES_PAYMENT_METHOD,
            paymentMethodTypes = types,
            customer = customerId,
            captureMethod = params.opt("capture_method") ?: "automatic",
            confirmationMethod = params.opt("confirmation_method") ?: "automatic",
            description = params.opt("description"),
            receiptEmail = params.opt("receipt_email"),
            setupFutureUsage = params.opt("setup_future_usage"),
        )
        pi.metadata.putAll(params.subMap("metadata"))
        store.paymentIntents[id] = pi

        params.opt("payment_method")?.let { pmParam ->
            val pm = resolvePaymentMethod(store, pmParam)
            pi.paymentMethod = pm.id
            pi.status = Status.REQUIRES_CONFIRMATION
        }

        if (params.bool("confirm") == true) {
            confirm(store, pi, params.opt("payment_method"), params)
        }
        return pi
    }

    // --- confirm ------------------------------------------------------------

    fun confirm(store: DataStore, pi: PaymentIntent, pmParam: String?, params: StripeParams): PaymentIntent {
        if (pi.status !in CONFIRMABLE) {
            throw StripeException.invalidRequest(
                "This PaymentIntent's status of '${pi.status}' does not allow it to be confirmed.",
                code = "payment_intent_unexpected_state",
            )
        }
        val pmId = pmParam ?: pi.paymentMethod ?: throw StripeException.invalidRequest(
            "You cannot confirm this PaymentIntent because it's missing a payment method.",
            param = "payment_method",
        )
        val pm = resolvePaymentMethod(store, pmId)
        pi.paymentMethod = pm.id
        pi.lastPaymentError = null
        pi.nextAction = null

        when (val outcome = TestCards.forNumber(pm.number).outcome) {
            is TestCards.Outcome.Succeed -> settleSuccess(store, pi, pm)
            is TestCards.Outcome.RequiresAction -> {
                pi.status = Status.REQUIRES_ACTION
                pi.nextAction = buildJsonObject {
                    put("type", "use_stripe_sdk")
                    put("use_stripe_sdk", buildJsonObject {
                        put("type", "three_d_secure_redirect")
                        put("stripe_js", JsonNull)
                    })
                }
            }
            is TestCards.Outcome.Decline -> settleDecline(store, pi, pm, outcome)
        }
        return pi
    }

    // --- capture (manual capture flow) --------------------------------------

    fun capture(store: DataStore, pi: PaymentIntent, params: StripeParams): PaymentIntent {
        if (pi.status != Status.REQUIRES_CAPTURE) {
            throw StripeException.invalidRequest(
                "This PaymentIntent could not be captured because it has a status of '${pi.status}'.",
                code = "payment_intent_unexpected_state",
            )
        }
        val captured = (params.long("amount_to_capture") ?: pi.amount).coerceIn(0, pi.amount)
        pi.latestCharge?.let { store.charges[it] }?.let { charge ->
            charge.captured = true
            charge.paid = true
            charge.amountCaptured = captured
            if (captured < charge.amount) {
                charge.amountRefunded = charge.amount - captured
                charge.refunded = false
            }
        }
        pi.amountReceived = captured
        pi.amountCapturable = 0
        pi.status = Status.SUCCEEDED
        return pi
    }

    // --- cancel -------------------------------------------------------------

    fun cancel(store: DataStore, pi: PaymentIntent, params: StripeParams): PaymentIntent {
        if (pi.status !in CANCELABLE) {
            throw StripeException.invalidRequest(
                "You cannot cancel this PaymentIntent because it has a status of '${pi.status}'.",
                code = "payment_intent_unexpected_state",
            )
        }
        pi.status = Status.CANCELED
        pi.canceledAt = store.now()
        pi.cancellationReason = params.opt("cancellation_reason") ?: "abandoned"
        pi.nextAction = null
        pi.amountCapturable = 0
        pi.latestCharge?.let { store.charges[it] }?.let { charge ->
            if (!charge.captured) {
                charge.status = "failed"
                charge.paid = false
            }
        }
        return pi
    }

    // --- outcomes -----------------------------------------------------------

    private fun settleSuccess(store: DataStore, pi: PaymentIntent, pm: PaymentMethod) {
        val manual = pi.captureMethod == "manual"
        val charge = buildCharge(
            store, pi, pm,
            status = "succeeded",
            captured = !manual,
            amountCaptured = if (manual) 0 else pi.amount,
            outcome = Charge.approvedOutcome(),
        )
        store.charges[charge.id] = charge
        pi.latestCharge = charge.id
        if (manual) {
            pi.status = Status.REQUIRES_CAPTURE
            pi.amountCapturable = pi.amount
            pi.amountReceived = 0
        } else {
            pi.status = Status.SUCCEEDED
            pi.amountCapturable = 0
            pi.amountReceived = pi.amount
        }
    }

    private fun settleDecline(
        store: DataStore,
        pi: PaymentIntent,
        pm: PaymentMethod,
        outcome: TestCards.Outcome.Decline,
    ) {
        val charge = buildCharge(
            store, pi, pm,
            status = "failed",
            captured = false,
            amountCaptured = 0,
            outcome = Charge.declinedOutcome(outcome.declineCode ?: outcome.code),
            failureCode = outcome.code,
            failureMessage = outcome.message,
        )
        store.charges[charge.id] = charge
        pi.latestCharge = charge.id
        pi.status = Status.REQUIRES_PAYMENT_METHOD
        pi.amountReceived = 0
        pi.amountCapturable = 0

        val docUrl = "https://stripe.com/docs/error-codes/${outcome.code}"
        pi.lastPaymentError = buildJsonObject {
            put("type", "card_error")
            put("code", outcome.code)
            if (outcome.declineCode != null) put("decline_code", outcome.declineCode)
            put("message", outcome.message)
            put("charge", charge.id)
            put("doc_url", docUrl)
            put("payment_method", pm.toApiJson())
        }
        // Real Stripe raises a 402 card_error whose `payment_intent` is the updated PI.
        throw StripeException(
            status = HttpStatusCode.PaymentRequired,
            type = StripeErrorType.CARD_ERROR,
            message = outcome.message,
            code = outcome.code,
            declineCode = outcome.declineCode,
            docUrl = docUrl,
            paymentIntent = pi.toApiJson(),
            paymentMethod = pm.toApiJson(),
        )
    }

    private fun buildCharge(
        store: DataStore,
        pi: PaymentIntent,
        pm: PaymentMethod,
        status: String,
        captured: Boolean,
        amountCaptured: Long,
        outcome: kotlinx.serialization.json.JsonObject,
        failureCode: String? = null,
        failureMessage: String? = null,
    ): Charge {
        val card = TestCards.forNumber(pm.number)
        return Charge(
            id = store.newId("ch"),
            created = store.now(),
            amount = pi.amount,
            currency = pi.currency,
            paymentIntent = pi.id,
            paymentMethod = pm.id,
            customer = pi.customer,
            status = status,
            paid = status == "succeeded",
            captured = captured,
            amountCaptured = amountCaptured,
            cardBrand = card.brand,
            cardLast4 = TestCards.last4(pm.number),
            cardExpMonth = pm.expMonth,
            cardExpYear = pm.expYear,
            cardFunding = card.funding,
            cardCountry = card.country,
            failureCode = failureCode,
            failureMessage = failureMessage,
            outcome = outcome,
            billingName = pm.billingName,
            billingEmail = pm.billingEmail,
            description = pi.description,
        )
    }

    /**
     * Resolve a payment_method reference: an existing PM id, or one of Stripe's
     * shared test tokens (pm_card_visa, ...). Tokens are materialized into a real
     * PaymentMethod, mirroring how Stripe turns a token into a pm_ object.
     */
    fun resolvePaymentMethod(store: DataStore, id: String): PaymentMethod {
        store.paymentMethods[id]?.let { return it }
        if (TestCards.isToken(id)) {
            val number = TestCards.numberForToken(id)!!
            val pm = PaymentMethod(
                id = store.newId("pm"),
                created = store.now(),
                number = number,
                expMonth = 12,
                expYear = 2034,
            )
            store.paymentMethods[pm.id] = pm
            return pm
        }
        throw StripeException.resourceMissing("payment_method", id, param = "payment_method")
    }
}
