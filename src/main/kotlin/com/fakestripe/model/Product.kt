package com.fakestripe.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A Product — the thing being sold. Prices attach to it; subscriptions bill it. */
@Serializable
data class Product(
    val id: String,
    val created: Long,
    var updated: Long,
    var name: String,
    var description: String? = null,
    var active: Boolean = true,
    var defaultPrice: String? = null,
    val metadata: MutableMap<String, String> = LinkedHashMap(),
) {
    fun toApiJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "product")
        put("active", active)
        put("name", name)
        put("description", description)
        put("created", created)
        put("updated", updated)
        put("default_price", defaultPrice)
        put("images", buildJsonArray { })
        put("livemode", false)
        put("type", "service")
        put("url", JsonNull)
        put("shippable", JsonNull)
        put("tax_code", JsonNull)
        put("package_dimensions", JsonNull)
        putMetadata(metadata)
    }

    fun toDeletedJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "product")
        put("deleted", true)
    }
}
