package com.fakestripe.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stripe's list-response envelope: `{ object: "list", url, has_more, data: [...] }`. */
object StripeList {
    fun of(url: String, data: List<JsonObject>, hasMore: Boolean): JsonObject = buildJsonObject {
        put("object", "list")
        put("url", url)
        put("has_more", hasMore)
        put("data", JsonArray(data))
    }
}
