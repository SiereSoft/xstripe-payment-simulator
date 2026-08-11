package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An Event records something that happened (a payment succeeded, a subscription
 * was created, ...). Events are retrievable via the API and are the payloads the
 * simulator signs and POSTs to a configured webhook URL — just like real Stripe.
 */
@Serializable
data class Event(
    val id: String,
    val created: Long,
    val type: String,
    val dataObject: JsonObject,
    @SerialName("episode_id") val episodeId: String? = null,
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "event")
        put("api_version", "2024-06-20")
        put("created", created)
        put("type", type)
        put("livemode", false)
        put("pending_webhooks", 0)
        put("request", buildJsonObject {
            put("id", JsonNull)
            put("idempotency_key", JsonNull)
        })
        put("data", buildJsonObject { put("object", dataObject) })
    }
}
