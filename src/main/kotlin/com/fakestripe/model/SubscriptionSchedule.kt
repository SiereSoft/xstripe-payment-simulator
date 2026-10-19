package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ScheduleItem(val price: String, val quantity: Long = 1)

@Serializable
data class SchedulePhase(
    val startDate: Long,
    val endDate: Long,
    val items: List<ScheduleItem>,
    val currency: String,
    val metadata: Map<String, String> = emptyMap(),
    val prorationBehavior: String = "create_prorations",
    val billingCycleAnchor: String = "automatic",
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("start_date", startDate)
        put("end_date", endDate)
        put("currency", currency)
        put("items", JsonArray(items.map { item -> buildJsonObject {
            put("price", item.price)
            put("plan", item.price)
            put("quantity", item.quantity)
            put("metadata", buildJsonObject { })
            put("tax_rates", JsonArray(emptyList()))
        } }))
        put("metadata", buildJsonObject { metadata.forEach { (k, v) -> put(k, v) } })
        put("proration_behavior", prorationBehavior)
        put("billing_cycle_anchor", billingCycleAnchor)
        put("collection_method", JsonNull)
        put("default_payment_method", JsonNull)
        put("trial_end", JsonNull)
        put("add_invoice_items", JsonArray(emptyList()))
        put("discounts", JsonNull)
    }
}

/** Retained schedule identity and phase cursor; the existing world snapshot owns persistence. */
@Serializable
data class SubscriptionSchedule(
    val id: String,
    val created: Long,
    val customer: String,
    var subscription: String?,
    var phases: List<SchedulePhase>,
    var currentPhase: Int = 0,
    var endBehavior: String = "release",
    var status: String = "active",
    val metadata: MutableMap<String, String> = LinkedHashMap(),
    var releasedSubscription: String? = null,
    var releasedAt: Long? = null,
    var completedAt: Long? = null,
    var canceledAt: Long? = null,
    val defaultPaymentMethod: String? = null,
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "subscription_schedule")
        put("created", created)
        put("customer", customer)
        put("subscription", subscription)
        put("status", status)
        put("end_behavior", endBehavior)
        put("livemode", false)
        put("application", JsonNull)
        put("test_clock", JsonNull)
        put("released_subscription", releasedSubscription)
        put("released_at", releasedAt)
        put("completed_at", completedAt)
        put("canceled_at", canceledAt)
        put("metadata", buildJsonObject { metadata.forEach { (k, v) -> put(k, v) } })
        put("phases", JsonArray(phases.map { it.toApiJson() }))
        put("current_phase", if (status == "active") buildJsonObject {
            put("start_date", phases[currentPhase].startDate)
            put("end_date", phases[currentPhase].endDate)
        } else JsonNull)
        put("default_settings", buildJsonObject {
            put("collection_method", "charge_automatically")
            put("billing_cycle_anchor", "automatic")
            put("default_payment_method", defaultPaymentMethod)
            put("automatic_tax", buildJsonObject { put("enabled", false) })
        })
    }
}
