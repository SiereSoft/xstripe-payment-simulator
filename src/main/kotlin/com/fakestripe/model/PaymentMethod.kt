package com.fakestripe.model

import com.fakestripe.cards.TestCards
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A card PaymentMethod. We keep the raw [number] internally (it decides the
 * charge outcome) but never expose it — the API only returns brand + last4,
 * exactly like real Stripe.
 */
@Serializable
data class PaymentMethod(
    val id: String,
    val created: Long,
    val number: String,
    val expMonth: Int,
    val expYear: Int,
    var customer: String? = null,
    var billingName: String? = null,
    var billingEmail: String? = null,
    var billingPhone: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    val brand: String get() = TestCards.forNumber(number).brand
    val funding: String get() = TestCards.forNumber(number).funding
    val country: String get() = TestCards.forNumber(number).country
    val last4: String get() = TestCards.last4(number)

    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "payment_method")
        put("type", "card")
        put("created", created)
        put("customer", customer)
        put("livemode", false)
        put("billing_details", buildJsonObject {
            put("address", buildJsonObject {
                put("city", JsonNull); put("country", JsonNull); put("line1", JsonNull)
                put("line2", JsonNull); put("postal_code", JsonNull); put("state", JsonNull)
            })
            put("email", billingEmail)
            put("name", billingName)
            put("phone", billingPhone)
        })
        put("card", buildJsonObject {
            put("brand", brand)
            put("checks", buildJsonObject {
                put("address_line1_check", JsonNull)
                put("address_postal_code_check", JsonNull)
                put("cvc_check", "pass")
            })
            put("country", country)
            put("exp_month", expMonth)
            put("exp_year", expYear)
            put("fingerprint", fingerprint())
            put("funding", funding)
            put("generated_from", JsonNull)
            put("last4", last4)
            put("networks", buildJsonObject {
                put("available", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(brand)) })
                put("preferred", JsonNull)
            })
            put("three_d_secure_usage", buildJsonObject { put("supported", true) })
            put("wallet", JsonNull)
        })
        putMetadata(metadata)
    }

    /** Stable pseudo-fingerprint derived from the card number (Stripe groups dup cards). */
    private fun fingerprint(): String {
        val h = number.hashCode().toLong() and 0xFFFFFFFFL
        return h.toString(36).padStart(10, '0').take(16)
    }
}
