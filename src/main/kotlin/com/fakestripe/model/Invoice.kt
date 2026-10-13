package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /**
     * [basil]: the 2025-03-31.basil shape — the price moves to `pricing.price_details`, the
     * subscription item and proration flag to `parent`; `price`, `type`, `proration` and
     * `subscription_item` are gone. A proration is a pending invoice item at Stripe, so its parent is
     * `invoice_item_details`.
     */
    fun toApiJson(priceJson: JsonObject?, basil: Boolean = false, invoiceId: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "line_item")
        put("amount", amount)
        put("currency", currency)
        put("description", description)
        if (!basil) put("price", priceJson ?: JsonNull)
        put("quantity", quantity)
        put("period", buildJsonObject { put("start", periodStart); put("end", periodEnd) })
        if (basil) {
            put("discount_amounts", JsonArray(emptyList()))
            put("discountable", true)
            put("discounts", JsonArray(emptyList()))
            put("invoice", invoiceId)
            putMetadata(emptyMap())
            put("parent", parentJson())
            put("pretax_credit_amounts", JsonArray(emptyList()))
            put("pricing", if (priceId == null) JsonNull else buildJsonObject {
                put("price_details", buildJsonObject {
                    put("price", priceId)
                    put("product", (priceJson?.get("product") as? JsonPrimitive)?.contentOrNull)
                })
                put("type", "price_details")
                put("unit_amount_decimal", (priceJson?.get("unit_amount_decimal") as? JsonPrimitive)?.contentOrNull)
            })
            put("subscription", subscription)
            put("taxes", JsonArray(emptyList()))
        } else {
            put("proration", proration)
            put("subscription", subscription)
            put("subscription_item", subscriptionItem)
            put("type", if (proration) "invoiceitem" else "subscription")
        }
        put("livemode", false)
    }

    private fun parentJson(): JsonElement {
        if (subscription == null) return JsonNull
        val prorationDetails = buildJsonObject { put("credited_items", JsonNull) }
        return if (proration) {
            buildJsonObject {
                put("invoice_item_details", buildJsonObject {
                    put("invoice_item", "ii_" + id.substringAfter('_'))
                    put("proration", true)
                    put("proration_details", prorationDetails)
                    put("subscription", subscription)
                })
                put("subscription_item_details", JsonNull)
                put("type", "invoice_item_details")
            }
        } else {
            buildJsonObject {
                put("invoice_item_details", JsonNull)
                put("subscription_item_details", buildJsonObject {
                    put("invoice_item", JsonNull)
                    put("proration", false)
                    put("proration_details", prorationDetails)
                    put("subscription", subscription)
                    put("subscription_item", subscriptionItem)
                })
                put("type", "subscription_item_details")
            }
        }
    }
}

/**
 * Basil's InvoicePayment: how a payment funds an invoice. Every invoice that asks for money gets
 * one default payment, pointing at the PaymentIntent of its latest collection attempt: `open`
 * while unpaid, `paid` once the PaymentIntent succeeds (with `amount_paid` and
 * `status_transitions.paid_at`), `canceled` when the invoice is voided.
 */
@Serializable
data class InvoicePayment(
    val id: String,
    val created: Long,
    val invoice: String,
    val currency: String,
    val amountRequested: Long,
    var status: String = "open", // open | paid | canceled
    var paymentIntent: String? = null,
    var amountPaid: Long? = null,
    var paidAt: Long? = null,
    var canceledAt: Long? = null,
    val isDefault: Boolean = true,
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "invoice_payment")
        put("amount_paid", amountPaid)
        put("amount_requested", amountRequested)
        put("created", created)
        put("currency", currency)
        put("invoice", invoice)
        put("is_default", isDefault)
        put("livemode", false)
        put("payment", buildJsonObject {
            put("payment_intent", paymentIntent)
            put("type", "payment_intent")
        })
        put("status", status)
        put("status_transitions", buildJsonObject {
            put("canceled_at", canceledAt)
            put("paid_at", paidAt)
        })
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
    /** The subscription's metadata when the invoice was created (basil `parent.subscription_details.metadata`). */
    val subscriptionMetadata: Map<String, String> = emptyMap(),
    var finalizedAt: Long? = null,
    var paidAt: Long? = null,
    var voidedAt: Long? = null,
    /** Basil InvoicePayments (one default payment per invoice that asks for money). */
    val payments: MutableList<InvoicePayment> = mutableListOf(),
) {
    /**
     * [basil]: the 2025-03-31.basil shape — `parent.subscription_details` instead of `subscription`,
     * no `payment_intent` / `paid` (the `payments` list replaces them; [includePayments] renders it,
     * as `expand[]=payments` asks).
     */
    fun toApiJson(
        priceJsonFor: (String) -> JsonObject?,
        basil: Boolean = false,
        includePayments: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "invoice")
        put("number", number)
        put("customer", customer)
        if (basil) {
            put("parent", subscription?.let { sub ->
                buildJsonObject {
                    put("quote_details", JsonNull)
                    put("subscription_details", buildJsonObject {
                        putMetadata(subscriptionMetadata)
                        put("subscription", sub)
                    })
                    put("type", "subscription_details")
                }
            } ?: JsonNull)
        } else {
            put("subscription", subscription)
        }
        put("status", status)
        put("subtotal", subtotal)
        put("total", total)
        put("amount_due", amountDue)
        put("amount_paid", amountPaid)
        put("amount_remaining", amountRemaining)
        put("amount_overpaid", 0)
        put("currency", currency)
        if (!basil) put("paid", paid)
        put("attempt_count", if (paid) 1 else 0)
        put("attempted", paid || paymentIntent != null)
        put("billing_reason", billingReason)
        put("collection_method", "charge_automatically")
        if (!basil) put("payment_intent", paymentIntent)
        put("post_payment_credit_notes_amount", 0)
        put("pre_payment_credit_notes_amount", 0)
        put("status_transitions", buildJsonObject {
            put("finalized_at", finalizedAt)
            put("marked_uncollectible_at", JsonNull)
            put("paid_at", paidAt)
            put("voided_at", voidedAt)
        })
        put("period_start", periodStart)
        put("period_end", periodEnd)
        put("created", created)
        put("livemode", false)
        put("lines", buildJsonObject {
            put("object", "list")
            put("has_more", false)
            put("total_count", lines.size)
            put("url", "/v1/invoices/$id/lines")
            put("data", JsonArray(lines.map { it.toApiJson(it.priceId?.let(priceJsonFor), basil, id) }))
        })
        if (basil && includePayments) {
            put("payments", buildJsonObject {
                put("object", "list")
                put("data", JsonArray(payments.map { it.toApiJson() }))
                put("has_more", false)
                put("total_count", payments.size)
                put("url", "/v1/invoice_payments?invoice=$id")
            })
        }
        put("hosted_invoice_url", JsonNull)
        put("invoice_pdf", JsonNull)
        putMetadata(metadata)
    }
}
