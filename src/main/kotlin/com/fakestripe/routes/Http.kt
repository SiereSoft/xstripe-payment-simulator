package com.fakestripe.routes

import com.fakestripe.model.Expand
import com.fakestripe.model.StripeList
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val stripeJson = Json { encodeDefaults = true; prettyPrint = false }

/** Set by the idempotency filter when a POST body was pre-read to fingerprint it. */
val CachedBodyKey = AttributeKey<String>("fakestripe.cachedBody")

/** Set by the idempotency filter; invoked with (status, body) so the response is cached. */
val IdempotencyRecorderKey = AttributeKey<(Int, String) -> Unit>("fakestripe.idempotencyRecorder")

/** Respond with a JSON body in Stripe's content type (recording it for idempotency if asked). */
suspend fun ApplicationCall.respondStripe(element: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    val text = stripeJson.encodeToString(JsonElement.serializer(), element)
    attributes.getOrNull(IdempotencyRecorderKey)?.invoke(status.value, text)
    respondText(text, ContentType.Application.Json, status)
}

/** Parse a form-encoded POST body into [StripeParams] (tolerating an empty body). */
suspend fun ApplicationCall.formParams(): StripeParams {
    // If the idempotency filter already consumed the body, parse the cached copy.
    attributes.getOrNull(CachedBodyKey)?.let { return StripeParams.fromBody(it) }
    return StripeParams.from(runCatching { receiveParameters() }.getOrDefault(Parameters.Empty))
}

/** Parse query-string params (for GET list/retrieve). */
fun ApplicationCall.queryParams(): StripeParams = StripeParams.from(request.queryParameters)

/** Serve one of the hosted browser pages (checkout / customer portal). */
suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(html, ContentType.Text.Html, status)
}

/** Redirect after a form POST — 303 so the browser re-issues it as a GET. */
suspend fun ApplicationCall.seeOther(url: String) {
    response.headers.append(HttpHeaders.Location, url)
    respond(HttpStatusCode.SeeOther)
}

/**
 * Absolute base URL for hosted pages, i.e. what a *browser* should be sent to.
 * Defaults to the host this request arrived on, which is right for localhost and
 * docker-compose alike; `FAKE_STRIPE_PUBLIC_URL` overrides it when the simulator is
 * reachable at a different address from outside its network.
 */
fun ApplicationCall.publicBaseUrl(): String {
    System.getenv("FAKE_STRIPE_PUBLIC_URL")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.trimEnd('/') }
    val host = request.header(HttpHeaders.Host)
        ?: "${request.origin.serverHost}:${request.origin.serverPort}"
    return "${request.origin.scheme}://$host"
}

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
