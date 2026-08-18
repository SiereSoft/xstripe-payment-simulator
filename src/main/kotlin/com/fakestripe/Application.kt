package com.fakestripe

import com.fakestripe.error.StripeException
import com.fakestripe.routes.adminRoutes
import com.fakestripe.routes.billingPortalRoutes
import com.fakestripe.routes.chargeRoutes
import com.fakestripe.routes.checkoutRoutes
import com.fakestripe.routes.customerRoutes
import com.fakestripe.routes.CachedBodyKey
import com.fakestripe.routes.IdempotencyRecorderKey
import com.fakestripe.routes.eventRoutes
import com.fakestripe.routes.invoiceRoutes
import com.fakestripe.routes.paymentIntentRoutes
import com.fakestripe.routes.paymentMethodRoutes
import com.fakestripe.routes.priceRoutes
import com.fakestripe.routes.productRoutes
import com.fakestripe.routes.refundRoutes
import com.fakestripe.routes.respondStripe
import com.fakestripe.routes.serviceRoutes
import com.fakestripe.routes.subscriptionAdminRoutes
import com.fakestripe.routes.subscriptionRoutes
import com.fakestripe.routes.webhookAdminRoutes
import com.fakestripe.store.ClockMode
import com.fakestripe.store.Simulator
import com.fakestripe.webhook.WebhookDispatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.event.Level

fun main() {
    val config = ServerConfig.from(System.getenv())
    val simulator = simulatorFromEnvironment()
    val controlToken = System.getenv("FAKE_STRIPE_CONTROL_TOKEN")
        ?.takeUnless { it.isBlank() }
    val controller = embeddedServer(Netty, port = config.controllerPort, host = config.controllerHost) {
        controllerModule(simulator, controlToken)
    }
    val actor = embeddedServer(Netty, port = config.actorPort, host = config.actorHost) {
        actorModule(simulator)
    }

    controller.start(wait = false)
    try {
        actor.start(wait = true)
    } finally {
        controller.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }
}

fun Application.module() {
    actorModule(simulatorFromEnvironment())
}

private fun simulatorFromEnvironment(): Simulator {
    val dataPath = Paths.get(System.getenv("FAKE_STRIPE_DATA") ?: "data/state.json")
    val seed = System.getenv("FAKE_STRIPE_SEED")?.toLongOrNull() ?: 1L
    val webhooks = WebhookDispatcher(
        url = System.getenv("FAKE_STRIPE_WEBHOOK_URL"),
        secret = System.getenv("FAKE_STRIPE_WEBHOOK_SECRET") ?: "whsec_test",
    )
    val clockModeValue = System.getenv("FAKE_STRIPE_CLOCK_MODE") ?: ClockMode.FREE.wireValue
    val clockMode = ClockMode.parse(clockModeValue)
        ?: error("FAKE_STRIPE_CLOCK_MODE must be 'free' or 'manual'.")
    return Simulator.boot(dataPath, seed, webhooks, clockMode)
}

data class ServerConfig(
    val actorHost: String,
    val actorPort: Int,
    val controllerHost: String,
    val controllerPort: Int,
) {
    init {
        require(actorPort in 1..65_535) { "PORT must be between 1 and 65535." }
        require(controllerPort in 1..65_535) { "CONTROL_PORT must be between 1 and 65535." }
        require(actorPort != controllerPort) { "PORT and CONTROL_PORT must be different." }
    }

    companion object {
        fun from(environment: Map<String, String>): ServerConfig = ServerConfig(
            // Loopback by default: the actor API accepts any API key, so binding
            // all interfaces would expose the whole simulated world to the local
            // network. Docker sets HOST=0.0.0.0 explicitly (see compose.yaml).
            actorHost = environment["HOST"] ?: "127.0.0.1",
            actorPort = environment["PORT"]?.toIntOrNull() ?: 12111,
            controllerHost = environment["CONTROL_HOST"] ?: "127.0.0.1",
            controllerPort = environment["CONTROL_PORT"]?.toIntOrNull() ?: 12112,
        )
    }
}

/** Wires only the Stripe-compatible surface exposed to the actor client. */
fun Application.actorModule(sim: Simulator) {
    configureApplication(sim, actorRoutes = true, controllerRoutes = false, controlToken = null)
}

/** Wires only Gym lifecycle and verifier endpoints on the controller listener. */
fun Application.controllerModule(sim: Simulator, controlToken: String?) {
    configureApplication(sim, actorRoutes = false, controllerRoutes = true, controlToken = controlToken)
}

/**
 * Combined in-memory application retained for behavior tests. Production startup
 * always uses [actorModule] and [controllerModule] on separate listeners.
 */
fun Application.module(sim: Simulator, controlToken: String? = null) {
    configureApplication(sim, actorRoutes = true, controllerRoutes = true, controlToken = controlToken)
}

private fun Application.configureApplication(
    sim: Simulator,
    actorRoutes: Boolean,
    controllerRoutes: Boolean,
    controlToken: String?,
) {
    install(CallLogging) {
        level = Level.INFO
        mdc("episode_id") { sim.episodeId }
    }
    install(DefaultHeaders) {
        header("Stripe-Version", "2024-06-20")
        header("Server", "fake-stripe/0.1.1")
    }
    install(StatusPages) {
        // Any StripeException renders in Stripe's exact error shape + status.
        exception<StripeException> { call, cause -> call.respondStripe(cause.toJson(), cause.status) }
        // Anything unexpected becomes a Stripe api_error rather than an HTML stacktrace.
        // The cause is logged server-side; the response carries a fixed message so
        // internal details (filesystem paths, library internals) never leak to callers.
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled exception for {} {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause,
            )
            call.respondStripe(
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("type", "api_error")
                        put("message", "An internal error occurred.")
                    })
                },
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Stripe-style Request-Id on every response.
    intercept(ApplicationCallPipeline.Setup) {
        call.response.headers.append("Request-Id", "req_" + kotlin.random.Random.nextLong().toString(36).trimStart('-'))
    }

    // Lightweight API-key auth: every /v1 business call must carry a Bearer (or Basic)
    // key, like real Stripe. We accept ANY sk_test_... value — this is a fake — but
    // reject a missing key with Stripe's authentication_error. Controller routes
    // use their own token check instead of a Stripe API key.
    intercept(ApplicationCallPipeline.Plugins) {
        if (actorRoutes && call.isBusinessPath()) {
            val key = apiKeyFrom(call.request.header(HttpHeaders.Authorization))
            if (key == null) {
                val err = StripeException.authenticationError()
                call.respondStripe(err.toJson(), err.status)
                return@intercept finish()
            }
            // A live-mode credential must never reach a simulator. Fail loudly so a
            // misconfigured client cannot silently believe it is talking to Stripe.
            if (LIVE_KEY_PREFIXES.any { key.startsWith(it) }) {
                val err = StripeException.liveKeyRejected()
                call.respondStripe(err.toJson(), err.status)
                return@intercept finish()
            }
        }
    }

    // Idempotency: a POST carrying `Idempotency-Key` replays its first response on
    // repeat (and errors if the same key is reused with a different body).
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.normalizedPath()
        val isBusinessPost = actorRoutes && call.request.httpMethod == HttpMethod.Post &&
            call.isBusinessPath()
        val key = call.request.header("Idempotency-Key")
        if (isBusinessPost && !key.isNullOrBlank()) {
            // Read the body once (so we can fingerprint it) and cache it for the handler.
            val bodyText = call.receiveText()
            call.attributes.put(CachedBodyKey, bodyText)
            val fingerprint = sha256("POST\n$path\n$bodyText")

            val existing = sim.idempotencyLookup(key)
            if (existing != null) {
                if (existing.fingerprint == fingerprint) {
                    call.response.headers.append("Idempotent-Replayed", "true")
                    call.respondText(existing.body, ContentType.Application.Json, HttpStatusCode.fromValue(existing.status))
                } else {
                    val err = StripeException.idempotencyError(key)
                    call.respondStripe(err.toJson(), err.status)
                }
                return@intercept finish()
            }
            // First time for this key: record whatever response the handler produces.
            call.attributes.put(IdempotencyRecorderKey) { status, respBody ->
                sim.recordIdempotency(key, fingerprint, status, respBody)
            }
        }
    }

    routing {
        serviceRoutes(sim, includeServiceInfo = actorRoutes)
        if (actorRoutes) {
            customerRoutes(sim)
            paymentMethodRoutes(sim)
            paymentIntentRoutes(sim)
            chargeRoutes(sim)
            refundRoutes(sim)
            productRoutes(sim)
            priceRoutes(sim)
            subscriptionRoutes(sim)
            invoiceRoutes(sim)
            checkoutRoutes(sim)
            billingPortalRoutes(sim)
            eventRoutes(sim)
        }
        if (controllerRoutes) {
            adminRoutes(sim, controlToken)
            subscriptionAdminRoutes(sim, controlToken)
            webhookAdminRoutes(sim, controlToken)
        }
        unknownUrlFallback()
    }
}

private fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

/** Live-mode credential prefixes. These must never be accepted by a simulator. */
private val LIVE_KEY_PREFIXES = listOf("sk_live_", "rk_live_", "pk_live_")

/**
 * The path as Ktor's router will see it.
 *
 * [io.ktor.server.request.path] returns the *raw* URI, but routing matches on
 * *decoded* segments. Comparing the raw form against `/v1/` let a caller reach a
 * business route as `/%76%31/customers` while a prefix check said "not /v1/, no
 * auth needed". Decoding here keeps this check aligned with the router.
 *
 * Decoding is best-effort: a malformed escape sequence falls back to the raw
 * path, which fails closed (auth still required).
 */
private fun ApplicationCall.normalizedPath(): String {
    val raw = request.path()
    return runCatching { raw.decodeURLPart() }.getOrDefault(raw)
}

/**
 * True for the Stripe-compatible business surface — everything under `/v1/`
 * except the controller-only `/v1/admin/` tree.
 *
 * Fails closed: if either the raw or the decoded path looks like `/v1/`, the
 * request is treated as a business call and must authenticate.
 */
private fun ApplicationCall.isBusinessPath(): Boolean {
    val decoded = normalizedPath()
    val looksBusiness = decoded.startsWith("/v1/") || request.path().startsWith("/v1/")
    return looksBusiness && !decoded.startsWith("/v1/admin/")
}

/**
 * Extract the API key from an Authorization header. Supports `Bearer sk_...`
 * (what the SDKs send) and `Basic base64(key:)` (what `curl -u sk_...:` sends).
 * Returns null when absent or blank.
 */
private fun apiKeyFrom(header: String?): String? {
    if (header == null) return null
    val bearer = "Bearer "
    val basic = "Basic "
    val key = when {
        header.startsWith(bearer, ignoreCase = true) -> header.substring(bearer.length).trim()
        header.startsWith(basic, ignoreCase = true) -> runCatching {
            String(Base64.getDecoder().decode(header.substring(basic.length).trim())).substringBefore(':')
        }.getOrNull()
        else -> null
    }
    return key?.takeIf { it.isNotBlank() }
}

/** Stripe-shaped 404 for any unrecognized URL (mirrors real Stripe). */
private fun Routing.unknownUrlFallback() {
    route("{...}") {
        handle {
            call.respondStripe(
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("type", "invalid_request_error")
                        put("message", "Unrecognized request URL (${call.request.httpMethod.value} ${call.request.path()}).")
                    })
                },
                HttpStatusCode.NotFound,
            )
        }
    }
}
