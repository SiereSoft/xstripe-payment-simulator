package com.fakestripe.store

import com.fakestripe.model.BillingPortalSession
import com.fakestripe.model.Charge
import com.fakestripe.model.CheckoutSession
import com.fakestripe.model.Customer
import com.fakestripe.model.Event
import com.fakestripe.model.Invoice
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Refund
import com.fakestripe.model.Subscription
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val exportJson = Json { encodeDefaults = true }
private val camelBoundary = Regex("([a-z0-9])([A-Z])")

/**
 * A complete, stable control-plane view of the simulated world.
 *
 * This intentionally uses the persisted model rather than public list routes so
 * a verifier can compare deleted flags, internal relationships, and idempotency
 * outcomes in one atomic read. Values that grant authority or reveal raw card
 * data are removed before the JSON leaves the process.
 */
fun DataStore.toControlPlaneJson(): JsonObject = buildJsonObject {
    put("object", "admin.state")
    put("schema_version", 1)
    put("state_revision", revision)
    put("seed", seed)
    put("id_sequence", ids.count)
    put(
        "scenario",
        scenario?.let { exportJson.encodeToJsonElement(ScenarioState.serializer(), it).redacted() }
            ?: JsonNull,
    )
    put("customers", encodeCollection(customers.values, Customer.serializer()))
    put(
        "payment_methods",
        encodeCollection(paymentMethods.values, PaymentMethod.serializer(), setOf("number")),
    )
    put("payment_intents", encodeCollection(paymentIntents.values, PaymentIntent.serializer()))
    put("charges", encodeCollection(charges.values, Charge.serializer()))
    put("refunds", encodeCollection(refunds.values, Refund.serializer()))
    put("products", encodeCollection(products.values, Product.serializer()))
    put("prices", encodeCollection(prices.values, Price.serializer()))
    put("subscriptions", encodeCollection(subscriptions.values, Subscription.serializer()))
    put("invoices", encodeCollection(invoices.values, Invoice.serializer()))
    put(
        "checkout_sessions",
        encodeCollection(checkoutSessions.values, CheckoutSession.serializer()),
    )
    put(
        "billing_portal_sessions",
        encodeCollection(portalSessions.values, BillingPortalSession.serializer()),
    )
    put("events", encodeCollection(events.values, Event.serializer()))
    put("idempotency_records", idempotencyRecords())
}

private fun <T> encodeCollection(
    values: Collection<T>,
    serializer: KSerializer<T>,
    extraSensitiveKeys: Set<String> = emptySet(),
): JsonArray = JsonArray(
    values.map { value ->
        exportJson.encodeToJsonElement(serializer, value).redacted(extraSensitiveKeys)
    },
)

private fun DataStore.idempotencyRecords(): JsonArray = JsonArray(
    idempotency.map { (key, record) ->
        buildJsonObject {
            put("key_sha256", sha256(key))
            put("request_fingerprint", record.fingerprint)
            put("status", record.status)
            val response = runCatching { exportJson.parseToJsonElement(record.body) }.getOrNull()
            if (response != null) {
                put("response", response.redacted())
            } else {
                put("response_sha256", sha256(record.body))
            }
        }
    },
)

private fun JsonElement.redacted(extraSensitiveKeys: Set<String> = emptySet()): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(
            entries.mapNotNull { (key, value) ->
                if (key.isSensitive(extraSensitiveKeys)) null
                else key to value.redacted(extraSensitiveKeys)
            }.toMap(),
        )
        is JsonArray -> JsonArray(map { it.redacted(extraSensitiveKeys) })
        else -> this
    }

private fun String.isSensitive(extraSensitiveKeys: Set<String>): Boolean {
    val normalized = replace(camelBoundary, "$1_$2").lowercase()
    val normalizedExtras = extraSensitiveKeys.map { it.lowercase() }.toSet()
    return normalized in normalizedExtras ||
        normalized in setOf("secret", "password", "authorization", "api_key") ||
        normalized.endsWith("_secret") ||
        normalized.endsWith("_password") ||
        normalized.endsWith("_token")
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
