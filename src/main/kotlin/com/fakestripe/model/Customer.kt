package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Stripe Customer. Almost everything else (payment methods, intents, invoices)
 * hangs off a customer, which is why it's the first object we model.
 *
 * The class is @Serializable so the whole store can be snapshotted to disk; the
 * public Stripe wire shape is produced by [toApiJson].
 */
@Serializable
data class Customer(
    val id: String,
    val created: Long,
    var email: String? = null,
    var name: String? = null,
    var description: String? = null,
    var phone: String? = null,
    var defaultPaymentMethod: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
    var deleted: Boolean = false,
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "customer")
        put("created", created)
        put("email", email)
        put("name", name)
        put("description", description)
        put("phone", phone)
        put("balance", 0)
        put("currency", JsonNull)
        put("default_source", JsonNull)
        put("delinquent", false)
        put("discount", JsonNull)
        put("invoice_prefix", invoicePrefix())
        put("invoice_settings", buildJsonObject {
            put("custom_fields", JsonNull)
            put("default_payment_method", defaultPaymentMethod)
            put("footer", JsonNull)
            put("rendering_options", JsonNull)
        })
        put("livemode", false)
        putMetadata(metadata)
        put("next_invoice_sequence", 1)
        put("address", JsonNull)
        put("shipping", JsonNull)
        put("tax_exempt", "none")
        put("test_clock", JsonNull)
    }

    fun toDeletedJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "customer")
        put("deleted", true)
    }

    private fun invoicePrefix(): String =
        id.substringAfter('_').take(8).uppercase().ifEmpty { "INV" }
}
