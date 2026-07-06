package com.fakestripe.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stripe's `expand[]` lets a caller ask for a referenced id to be inlined as the
 * full object (e.g. `expand[]=customer` turns "cus_123" into the customer object).
 * We support single-level expansion of top-level id fields, which covers the
 * common SDK flows (customer, payment_method, latest_charge).
 */
object Expand {
    fun apply(json: JsonObject, expand: Set<String>, resolve: (String) -> JsonObject?): JsonObject {
        if (expand.isEmpty()) return json
        val out = json.toMutableMap()
        for (path in expand) {
            val field = path.substringBefore('.')
            val current = out[field]
            if (current is JsonPrimitive && current.isString) {
                resolve(current.content)?.let { out[field] = it }
            }
        }
        return JsonObject(out)
    }
}
