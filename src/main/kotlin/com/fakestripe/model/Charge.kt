package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Charge is the record of a single attempt to move money. One is created every
 * time a PaymentIntent is confirmed — whether it succeeds or is declined.
 */
@Serializable
data class Charge(
    val id: String,
    val created: Long,
    val amount: Long,
    val currency: String,
    val paymentIntent: String,
    val paymentMethod: String?,
    val customer: String?,
    var status: String,               // succeeded | pending | failed
    var paid: Boolean,
    var captured: Boolean,
    var amountCaptured: Long,
    var amountRefunded: Long = 0,
    var refunded: Boolean = false,
    val description: String? = null,
    // snapshot of the card used, so the charge renders without the PM present
    val cardBrand: String,
    val cardLast4: String,
    val cardExpMonth: Int,
    val cardExpYear: Int,
    val cardFunding: String,
    val cardCountry: String,
    // decline info (null on success)
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val outcome: JsonObject,
    val billingName: String? = null,
    val billingEmail: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(refunds: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "charge")
        put("amount", amount)
        put("amount_captured", amountCaptured)
        put("amount_refunded", amountRefunded)
        put("currency", currency)
        put("customer", customer)
        put("payment_intent", paymentIntent)
        put("payment_method", paymentMethod)
        put("status", status)
        put("paid", paid)
        put("captured", captured)
        put("refunded", refunded)
        put("disputed", false)
        put("created", created)
        put("livemode", false)
        put("description", description)
        put("failure_code", failureCode)
        put("failure_message", failureMessage)
        put("receipt_url", if (status == "succeeded") "https://pay.stripe.com/receipts/$id" else null)
        put("balance_transaction", if (status == "succeeded" && captured) "txn_${id.substringAfter('_')}" else null)
        put("calculated_statement_descriptor", "FAKE STRIPE")
        put("outcome", outcome)
        put("billing_details", buildJsonObject {
            put("address", JsonNull)
            put("email", billingEmail)
            put("name", billingName)
            put("phone", JsonNull)
        })
        put("payment_method_details", buildJsonObject {
            put("type", "card")
            put("card", buildJsonObject {
                put("brand", cardBrand)
                put("country", cardCountry)
                put("exp_month", cardExpMonth)
                put("exp_year", cardExpYear)
                put("funding", cardFunding)
                put("last4", cardLast4)
                put("network", cardBrand)
                put("mandate", JsonNull)
                put("wallet", JsonNull)
            })
        })
        put("refunds", buildJsonObject {
            put("object", "list")
            put("has_more", false)
            put("total_count", refunds.size)
            put("url", "/v1/charges/$id/refunds")
            put("data", JsonArray(refunds))
        })
        putMetadata(metadata)
    }

    companion object {
        fun approvedOutcome(): JsonObject = buildJsonObject {
            put("network_status", "approved_by_network")
            put("reason", JsonNull)
            put("risk_level", "normal")
            put("risk_score", 5)
            put("seller_message", "Payment complete.")
            put("type", "authorized")
        }

        fun declinedOutcome(declineReason: String): JsonObject = buildJsonObject {
            put("network_status", "declined_by_network")
            put("reason", declineReason)
            put("risk_level", "normal")
            put("risk_score", 5)
            put("seller_message", "The bank did not return any further details with this decline.")
            put("type", "issuer_declined")
        }
    }
}
