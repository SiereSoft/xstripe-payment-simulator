package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class InvoiceLine(
    val id: String,
    val amount: Long,
    val currency: String,
    val description: String,
    val priceId: String?,
    val quantity: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val proration: Boolean = false,
    val subscription: String? = null,
    val subscriptionItem: String? = null,
) {
    fun toApiJson(priceJson: JsonObject?): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "line_item")
        put("amount", amount)
        put("currency", currency)
        put("description", description)
        put("price", priceJson ?: JsonNull)
        put("quantity", quantity)
        put("period", buildJsonObject { put("start", periodStart); put("end", periodEnd) })
        put("proration", proration)
        put("subscription", subscription)
        put("subscription_item", subscriptionItem)
        put("type", if (proration) "invoiceitem" else "subscription")
        put("livemode", false)
    }
}

/**
 * A generated bill. Subscriptions create invoices on sign-up, renewal, and (for
 * upgrades) proration. `total` is the sum of the line items.
 */
@Serializable
data class Invoice(
    val id: String,
    val created: Long,
    val number: String,
    val customer: String,
    var subscription: String?,
    var status: String, // draft | open | paid | void | uncollectible
    var subtotal: Long,
    var total: Long,
    var amountDue: Long,
    var amountPaid: Long,
    var amountRemaining: Long,
    val currency: String,
    val lines: MutableList<InvoiceLine>,
    var paid: Boolean,
    var billingReason: String,
    var paymentIntent: String? = null,
    var periodStart: Long,
    var periodEnd: Long,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(priceJsonFor: (String) -> JsonObject?): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "invoice")
        put("number", number)
        put("customer", customer)
        put("subscription", subscription)
        put("status", status)
        put("subtotal", subtotal)
        put("total", total)
        put("amount_due", amountDue)
        put("amount_paid", amountPaid)
        put("amount_remaining", amountRemaining)
        put("currency", currency)
        put("paid", paid)
        put("attempt_count", if (paid) 1 else 0)
        put("billing_reason", billingReason)
        put("collection_method", "charge_automatically")
        put("payment_intent", paymentIntent)
        put("period_start", periodStart)
        put("period_end", periodEnd)
        put("created", created)
        put("livemode", false)
        put("lines", buildJsonObject {
            put("object", "list")
            put("has_more", false)
            put("total_count", lines.size)
            put("url", "/v1/invoices/$id/lines")
            put("data", JsonArray(lines.map { it.toApiJson(it.priceId?.let(priceJsonFor)) }))
        })
        put("hosted_invoice_url", JsonNull)
        put("invoice_pdf", JsonNull)
        putMetadata(metadata)
    }
}
