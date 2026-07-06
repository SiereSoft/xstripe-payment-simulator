package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The PaymentIntent — the heart of Stripe and the hard, valuable part of this
 * simulator. Its [status] moves through a strict state machine:
 *
 *   requires_payment_method -> requires_confirmation -> requires_action
 *        -> processing -> succeeded
 *                       -> requires_capture -> succeeded   (manual capture)
 *   ...any non-terminal state -> canceled
 *   a decline sends it back to requires_payment_method
 *
 * The transitions live in [com.fakestripe.statemachine.PaymentIntentMachine];
 * this class holds state and renders the wire shape.
 */
@Serializable
data class PaymentIntent(
    val id: String,
    val created: Long,
    val amount: Long,
    val currency: String,
    val clientSecret: String,
    var status: String,
    var paymentMethod: String? = null,
    val paymentMethodTypes: List<String> = listOf("card"),
    var customer: String? = null,
    val captureMethod: String = "automatic",
    val confirmationMethod: String = "automatic",
    var amountReceived: Long = 0,
    var amountCapturable: Long = 0,
    var latestCharge: String? = null,
    var description: String? = null,
    var receiptEmail: String? = null,
    var setupFutureUsage: String? = null,
    var canceledAt: Long? = null,
    var cancellationReason: String? = null,
    var nextAction: JsonObject? = null,
    var lastPaymentError: JsonObject? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    object Status {
        const val REQUIRES_PAYMENT_METHOD = "requires_payment_method"
        const val REQUIRES_CONFIRMATION = "requires_confirmation"
        const val REQUIRES_ACTION = "requires_action"
        const val PROCESSING = "processing"
        const val REQUIRES_CAPTURE = "requires_capture"
        const val SUCCEEDED = "succeeded"
        const val CANCELED = "canceled"
    }

    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "payment_intent")
        put("amount", amount)
        put("amount_capturable", amountCapturable)
        put("amount_received", amountReceived)
        put("currency", currency)
        put("status", status)
        put("client_secret", clientSecret)
        put("customer", customer)
        put("payment_method", paymentMethod)
        put("payment_method_types", buildJsonArray {
            paymentMethodTypes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        })
        put("capture_method", captureMethod)
        put("confirmation_method", confirmationMethod)
        put("created", created)
        put("livemode", false)
        put("description", description)
        put("receipt_email", receiptEmail)
        put("setup_future_usage", setupFutureUsage)
        put("latest_charge", latestCharge)
        put("canceled_at", canceledAt)
        put("cancellation_reason", cancellationReason)
        putObjectOrNull("next_action", nextAction)
        putObjectOrNull("last_payment_error", lastPaymentError)
        put("application", JsonNull)
        put("automatic_payment_methods", JsonNull)
        put("invoice", JsonNull)
        put("on_behalf_of", JsonNull)
        put("review", JsonNull)
        put("shipping", JsonNull)
        put("statement_descriptor", JsonNull)
        put("statement_descriptor_suffix", JsonNull)
        put("transfer_data", JsonNull)
        put("transfer_group", JsonNull)
        putMetadata(metadata)
    }
}
