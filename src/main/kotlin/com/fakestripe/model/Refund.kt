package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Refund returns money from a captured Charge. Refunds may be full or partial,
 * and a charge can have several as long as their total does not exceed the amount.
 */
@Serializable
data class Refund(
    val id: String,
    val created: Long,
    val amount: Long,
    val currency: String,
    val charge: String,
    val paymentIntent: String?,
    var status: String = "succeeded",
    var reason: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "refund")
        put("amount", amount)
        put("currency", currency)
        put("charge", charge)
        put("payment_intent", paymentIntent)
        put("created", created)
        put("status", status)
        put("reason", reason)
        put("receipt_number", JsonNull)
        put("balance_transaction", "txn_${id.substringAfter('_')}")
        put("destination_details", JsonNull)
        putMetadata(metadata)
    }
}
