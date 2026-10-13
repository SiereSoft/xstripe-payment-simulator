package com.fakestripe.routes

import com.fakestripe.error.StripeException
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
    attributes.getOrNull(IdempotencyRecorderKey)?.let { recorder ->
        // Record once: a later error page for the same call must not overwrite the result.
        attributes.remove(IdempotencyRecorderKey)
        recorder(status.value, text)
    }
    respondText(text, ContentType.Application.Json, status)
}

/** Parse a form-encoded POST body into [StripeParams] (tolerating an empty body). */
suspend fun ApplicationCall.formParams(): StripeParams {
    // If the idempotency filter already consumed the body, parse the cached copy.
    val version = request.header(STRIPE_VERSION_HEADER)
    attributes.getOrNull(CachedBodyKey)?.let { return StripeParams.fromBody(it, version) }
    return StripeParams.from(runCatching { receiveParameters() }.getOrDefault(Parameters.Empty), version)
}

/** Parse query-string params (for GET list/retrieve). */
fun ApplicationCall.queryParams(): StripeParams =
    StripeParams.from(request.queryParameters, request.header(STRIPE_VERSION_HEADER))

/** The header a client names its Stripe API version with (and the version a response is in). */
const val STRIPE_VERSION_HEADER = "Stripe-Version"

/**
 * Serve one of the hosted browser pages (checkout / customer portal).
 *
 * The pages are static HTML with no scripts, so a strict CSP costs nothing and
 * turns any future markup-injection bug into a non-event. `no-store` keeps
 * payment-shaped pages out of shared caches and browser history restores.
 */
suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    // form-action allows http/https because the checkout success form and the
    // portal return form legitimately target a caller-supplied site. Scheme is
    // already restricted at session-create time; this is the second line.
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' http: https:; " +
            "base-uri 'none'; frame-ancestors 'none'",
    )
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("Cache-Control", "no-store")
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
    Expand.apply(json, params.expand) { resolveJson(it, params.basil) }

/**
 * Build a Stripe list response: newest-first, cursor pagination via
 * `starting_after` / `ending_before`, `limit` clamped to 1..100 (default 10), and Stripe's
 * `created` filter (`created=<ts>` or `created[gt|gte|lt|lte]=<ts>`).
 */
fun <T> DataStore.paginated(
    all: Collection<T>,
    url: String,
    params: StripeParams,
    id: (T) -> String,
    created: (T) -> Long,
    toJson: (T) -> JsonObject,
): JsonObject {
    val window0 = createdFilter(params)
    val sorted = all.filter { window0(created(it)) }
        .sortedWith(compareByDescending<T> { created(it) }.thenByDescending { id(it) })
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
    val data = page.map { Expand.apply(toJson(it), params.expand) { ref -> resolveJson(ref, params.basil) } }
    return StripeList.of(url, data, window.size > limit)
}

/** Stripe's `created` range filter on list endpoints; an unparsable bound is a 400. */
private fun createdFilter(params: StripeParams): (Long) -> Boolean {
    val exact = params.long("created")
    val bounds = params.subMap("created").mapValues { (op, raw) ->
        raw.toLongOrNull() ?: throw StripeException.invalidRequest(
            "Invalid integer: $raw", param = "created[$op]", code = "parameter_invalid_integer",
        )
    }
    bounds.keys.firstOrNull { it !in setOf("gt", "gte", "lt", "lte") }?.let {
        throw StripeException.invalidRequest("Received unknown parameter: created[$it]", param = "created[$it]")
    }
    return { at ->
        (exact == null || at == exact) &&
            bounds["gt"].let { it == null || at > it } && bounds["gte"].let { it == null || at >= it } &&
            bounds["lt"].let { it == null || at < it } && bounds["lte"].let { it == null || at <= it }
    }
}
