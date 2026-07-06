package com.fakestripe.routes

import com.fakestripe.model.Expand
import com.fakestripe.model.StripeList
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val stripeJson = Json { encodeDefaults = true; prettyPrint = false }

/** Respond with a JSON body in Stripe's content type. */
suspend fun ApplicationCall.respondStripe(element: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(stripeJson.encodeToString(JsonElement.serializer(), element), ContentType.Application.Json, status)
}

/** Parse a form-encoded POST body into [StripeParams] (tolerating an empty body). */
suspend fun ApplicationCall.formParams(): StripeParams =
    StripeParams.from(runCatching { receiveParameters() }.getOrDefault(Parameters.Empty))

/** Parse query-string params (for GET list/retrieve). */
fun ApplicationCall.queryParams(): StripeParams = StripeParams.from(request.queryParameters)

/** Apply `expand[]` to a single object. */
fun DataStore.expand(json: JsonObject, params: StripeParams): JsonObject =
    Expand.apply(json, params.expand, ::resolveJson)

/**
 * Build a Stripe list response: newest-first, cursor pagination via
 * `starting_after` / `ending_before`, `limit` clamped to 1..100 (default 10).
 */
fun <T> DataStore.paginated(
    all: Collection<T>,
    url: String,
    params: StripeParams,
    id: (T) -> String,
    created: (T) -> Long,
    toJson: (T) -> JsonObject,
): JsonObject {
    val sorted = all.sortedWith(compareByDescending<T> { created(it) }.thenByDescending { id(it) })
    val limit = (params.long("limit")?.toInt() ?: 10).coerceIn(1, 100)
    val startingAfter = params.opt("starting_after")
    val endingBefore = params.opt("ending_before")

    val window = when {
        startingAfter != null -> {
            val idx = sorted.indexOfFirst { id(it) == startingAfter }
            if (idx >= 0) sorted.drop(idx + 1) else sorted
        }
        endingBefore != null -> {
            val idx = sorted.indexOfFirst { id(it) == endingBefore }
            if (idx >= 0) sorted.take(idx) else sorted
        }
        else -> sorted
    }
    val page = window.take(limit)
    val data = page.map { Expand.apply(toJson(it), params.expand, ::resolveJson) }
    return StripeList.of(url, data, window.size > limit)
}
