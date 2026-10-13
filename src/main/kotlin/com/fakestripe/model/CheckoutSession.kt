package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CheckoutLine(val priceId: String, val quantity: Long)

/**
 * A Checkout Session — the hosted payment page real Stripe redirects a browser to.
 *
 * The API half of this object is ordinary JSON, but the half that matters is [url]:
 * an integration's next step is a *browser* navigating there, so the simulator serves
 * its own page at that address (see routes/CheckoutRoutes). Pressing Pay there is what
 * creates the customer + subscription and emits `checkout.session.completed` — never
 * the redirect back to [successUrl], because an integration that upgrades on the
 * return trip instead of on the event is exactly the bug this shape exists to catch.
 */
@Serializable
data class CheckoutSession(
    val id: String,
    val created: Long,
    val mode: String, // payment | subscription
    val lines: MutableList<CheckoutLine>,
    val successUrl: String,
    val cancelUrl: String?,
    val url: String,
    val currency: String,
    val amountTotal: Long,
    val expiresAt: Long,
    var status: String = "open", // open | complete | expired
    var paymentStatus: String = "unpaid", // unpaid | paid | no_payment_required
    var clientReferenceId: String? = null,
    var customer: String? = null,
    var customerEmail: String? = null,
    var subscription: String? = null,
    var paymentIntent: String? = null,
    var invoice: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
    /** `subscription_data[metadata]`: copied onto the subscription the session creates. */
    val subscriptionMetadata: MutableMap<String, String> = LinkedHashMap(),
    /** `subscription_data[trial_period_days]` / `subscription_data[trial_end]`. */
    val trialPeriodDays: Int? = null,
    val trialEnd: Long? = null,
    /** A card waiting for the hosted 3-D Secure challenge (payment method id). */
    var pendingAuthentication: String? = null,
    /** `payment_intent_data[metadata]` / `[description]`: copied onto the payment-mode PaymentIntent. */
    val paymentIntentMetadata: Map<String, String> = emptyMap(),
    val paymentIntentDescription: String? = null,
    val paymentMethodTypes: List<String> = listOf("card"),
    val allowPromotionCodes: Boolean? = null,
) {
    val hasTrial: Boolean get() = trialPeriodDays != null || trialEnd != null

    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "checkout.session")
        put("allow_promotion_codes", allowPromotionCodes)
        put("amount_subtotal", amountTotal)
        put("automatic_tax", buildJsonObject {
            put("enabled", false)
            put("liability", JsonNull)
            put("status", JsonNull)
        })
        put("amount_total", amountTotal)
        put("cancel_url", cancelUrl)
        put("client_reference_id", clientReferenceId)
        put("created", created)
        put("currency", currency)
        put("customer", customer)
        put("customer_email", customerEmail)
        put("customer_details", buildJsonObject {
            put("address", JsonNull)
            put("email", customerEmail)
            put("name", JsonNull)
            put("phone", JsonNull)
            put("tax_exempt", "none")
            put("tax_ids", JsonArray(emptyList()))
        })
        put("expires_at", expiresAt)
        put("invoice", invoice)
        put("livemode", false)
        put("locale", JsonNull)
        put("mode", mode)
        put("payment_intent", paymentIntent)
        put("payment_method_types", JsonArray(paymentMethodTypes.map { JsonPrimitive(it) }))
        put("payment_status", paymentStatus)
        put("status", status)
        put("subscription", subscription)
        put("success_url", successUrl)
        // Stripe drops the hosted URL once the session is no longer payable.
        put("url", if (status == "open") JsonPrimitive(url) else JsonNull)
        put("total_details", buildJsonObject {
            put("amount_discount", 0)
            put("amount_shipping", 0)
            put("amount_tax", 0)
        })
        putMetadata(metadata)
    }
}
