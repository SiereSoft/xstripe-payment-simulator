package com.fakestripe.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Emit a `metadata` object; Stripe always includes it (possibly empty). */
fun JsonObjectBuilder.putMetadata(metadata: Map<String, String>) {
    put("metadata", buildJsonObject { for ((k, v) in metadata) put(k, v) })
}

/** Put a nullable nested object, writing explicit `null` (Stripe includes nulls). */
fun JsonObjectBuilder.putObjectOrNull(key: String, value: JsonObject?) {
    put(key, value ?: JsonNull)
}
