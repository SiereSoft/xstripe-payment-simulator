package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
    /**
     * [period] is the item's current period, rendered only in basil and later versions (which
     * moved `current_period_start` / `current_period_end` from the subscription onto its items).
     */
    fun toApiJson(priceJson: JsonObject?, period: Pair<Long, Long>? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "subscription_item")
        put("created", created)
        if (period != null) {
            put("current_period_start", period.first)
            put("current_period_end", period.second)
        }
        put("price", priceJson ?: JsonNull)
        put("quantity", quantity)
        put("subscription", subscription)
        putMetadata(metadata)
    }
}

/**
 * A recurring billing agreement. Items point at recurring Prices; the current period advances by
 * the price interval when the simulated clock reaches [currentPeriodEnd]. A subscription with a
 * trial is `trialing` until [trialEnd]; its first real invoice is created then.
 *
 * [pendingProrations] are the proration lines Stripe calls pending invoice items: created by a
 * mid-period change with `proration_behavior=create_prorations` and billed on the next invoice.
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
    var trialStart: Long? = null,
    var trialEnd: Long? = null,
    /** `customer.subscription.trial_will_end` was emitted for the current trial. */
    var trialWillEndSent: Boolean = false,
    var billingCycleAnchor: Long? = null,
    val pendingProrations: MutableList<InvoiceLine> = mutableListOf(),
    /**
     * Controller-forced dunning: the next this-many renewals are charged to a declining test card
     * ([failRenewalCard]) instead of the default payment method. Not part of Stripe's object.
     */
    var failNextRenewals: Int = 0,
    var failRenewalCard: String? = null,
    var schedule: String? = null,
) {
    /** Stripe sets `cancel_at` to the period end once `cancel_at_period_end` is requested. */
    val cancelAt: Long? get() = if (cancelAtPeriodEnd) currentPeriodEnd else null

    /** The current period as basil renders it on each item. */
    val period: Pair<Long, Long> get() = currentPeriodStart to currentPeriodEnd

    /**
     * [basil]: answer in the 2025-03-31.basil (and later) shape — the current period lives on the
     * items, not on the subscription.
     */
    fun toApiJson(priceJsonFor: (String) -> JsonObject?, basil: Boolean = false): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "subscription")
        put("customer", customer)
        put("schedule", schedule)
        put("status", status)
        put("currency", currency)
        put("items", buildJsonObject {
            put("object", "list")
            put("has_more", false)
            put("total_count", items.size)
            put("url", "/v1/subscription_items?subscription=$id")
            put("data", JsonArray(items.map { it.toApiJson(priceJsonFor(it.priceId), if (basil) period else null) }))
        })
        if (!basil) {
            put("current_period_start", currentPeriodStart)
            put("current_period_end", currentPeriodEnd)
        }
        put("created", created)
        put("start_date", created)
        put("billing_cycle_anchor", billingCycleAnchor ?: created)
        put("cancel_at", cancelAt)
        put("cancel_at_period_end", cancelAtPeriodEnd)
        put("canceled_at", canceledAt)
        put("ended_at", endedAt)
        put("trial_start", trialStart)
        put("trial_end", trialEnd)
        put("collection_method", "charge_automatically")
        put("default_payment_method", defaultPaymentMethod)
        put("latest_invoice", latestInvoice)
        put("livemode", false)
        putMetadata(metadata)
    }
}
