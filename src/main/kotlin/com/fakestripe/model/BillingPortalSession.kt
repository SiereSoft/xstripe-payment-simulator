package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Customer Portal session. Like [CheckoutSession] its point is [url] — a page the
 * customer's browser opens to manage their own billing. The simulated portal serves
 * the one action integrations actually depend on: cancelling (at period end) and
 * resuming a subscription, each emitting `customer.subscription.updated`.
 */
@Serializable
data class BillingPortalSession(
    val id: String,
    val created: Long,
    val customer: String,
    val returnUrl: String?,
    val url: String,
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "billing_portal.session")
        put("configuration", JsonNull)
        put("created", created)
        put("customer", customer)
        put("flow", JsonNull)
        put("livemode", false)
        put("locale", JsonNull)
        put("on_behalf_of", JsonNull)
        put("return_url", returnUrl)
        put("url", url)
    }
}
