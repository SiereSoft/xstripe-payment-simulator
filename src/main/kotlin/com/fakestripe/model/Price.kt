package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Price attaches an amount + currency (and optional recurring interval) to a
 * Product. Recurring prices drive subscriptions; one-time prices drive invoices.
 */
@Serializable
data class Price(
    val id: String,
    val created: Long,
    val product: String,
    val currency: String,
    val unitAmount: Long?,
    var active: Boolean = true,
    var nickname: String? = null,
    val recurringInterval: String? = null, // day | week | month | year
    val recurringIntervalCount: Int = 1,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    val type: String get() = if (recurringInterval != null) "recurring" else "one_time"

    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "price")
        put("active", active)
        put("product", product)
        put("currency", currency)
        put("unit_amount", unitAmount)
        put("unit_amount_decimal", unitAmount?.toString())
        put("type", type)
        put("recurring", if (recurringInterval != null) {
            buildJsonObject {
                put("interval", recurringInterval)
                put("interval_count", recurringIntervalCount)
                put("usage_type", "licensed")
                put("aggregate_usage", JsonNull)
                put("meter", JsonNull)
                put("trial_period_days", JsonNull)
            }
        } else {
            JsonNull
        })
        put("billing_scheme", "per_unit")
        put("created", created)
        put("livemode", false)
        put("nickname", nickname)
        put("tax_behavior", "unspecified")
        put("tiers_mode", JsonNull)
        put("lookup_key", JsonNull)
        putMetadata(metadata)
    }
}
