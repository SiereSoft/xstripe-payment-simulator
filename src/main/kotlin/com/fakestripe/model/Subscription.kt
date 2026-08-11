package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SubscriptionItem(
    val id: String,
    val created: Long,
    var priceId: String,
    var quantity: Long,
    val subscription: String,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(priceJson: JsonObject?): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "subscription_item")
        put("created", created)
        put("price", priceJson ?: JsonNull)
        put("quantity", quantity)
        put("subscription", subscription)
        putMetadata(metadata)
    }
}

/**
 * A recurring billing agreement. Items point at recurring Prices; the current
 * period advances by the price interval. Upgrades change an item's price and
 * generate a proration invoice.
 */
@Serializable
data class Subscription(
    val id: String,
    val created: Long,
    val customer: String,
    var status: String,
    val items: MutableList<SubscriptionItem>,
    var currentPeriodStart: Long,
    var currentPeriodEnd: Long,
    val currency: String,
    var cancelAtPeriodEnd: Boolean = false,
    var canceledAt: Long? = null,
    var endedAt: Long? = null,
    var defaultPaymentMethod: String? = null,
    var latestInvoice: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(priceJsonFor: (String) -> JsonObject?): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "subscription")
        put("customer", customer)
        put("status", status)
        put("currency", currency)
        put("items", buildJsonObject {
            put("object", "list")
            put("has_more", false)
            put("total_count", items.size)
            put("url", "/v1/subscription_items?subscription=$id")
            put("data", kotlinx.serialization.json.JsonArray(items.map { it.toApiJson(priceJsonFor(it.priceId)) }))
        })
        put("current_period_start", currentPeriodStart)
        put("current_period_end", currentPeriodEnd)
        put("created", created)
        put("start_date", created)
        put("billing_cycle_anchor", created)
        put("cancel_at_period_end", cancelAtPeriodEnd)
        put("canceled_at", canceledAt)
        put("ended_at", endedAt)
        put("collection_method", "charge_automatically")
        put("default_payment_method", defaultPaymentMethod)
        put("latest_invoice", latestInvoice)
        put("livemode", false)
        putMetadata(metadata)
    }
}
