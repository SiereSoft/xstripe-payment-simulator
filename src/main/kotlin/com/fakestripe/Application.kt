package com.fakestripe

import com.fakestripe.error.StripeException
import com.fakestripe.routes.adminRoutes
import com.fakestripe.routes.chargeRoutes
import com.fakestripe.routes.customerRoutes
import com.fakestripe.routes.paymentIntentRoutes
import com.fakestripe.routes.paymentMethodRoutes
import com.fakestripe.routes.respondStripe
import com.fakestripe.store.Simulator
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.Base64
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.event.Level
import java.nio.file.Paths

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 12111
    val host = System.getenv("HOST") ?: "0.0.0.0"
    embeddedServer(Netty, port = port, host = host) { module() }.start(wait = true)
}

fun Application.module() {
    val dataPath = Paths.get(System.getenv("FAKE_STRIPE_DATA") ?: "data/state.json")
    val seed = System.getenv("FAKE_STRIPE_SEED")?.toLongOrNull() ?: 1L
    module(Simulator.boot(dataPath, seed))
}

/** Wires the whole API around a given [Simulator]. Tests inject their own. */
fun Application.module(sim: Simulator) {
    install(CallLogging) { level = Level.INFO }
    install(DefaultHeaders) {
        header("Stripe-Version", "2024-06-20")
        header("Server", "fake-stripe/0.1.0")
    }
    install(StatusPages) {
        // Any StripeException renders in Stripe's exact error shape + status.
        exception<StripeException> { call, cause -> call.respondStripe(cause.toJson(), cause.status) }
        // Anything unexpected becomes a Stripe api_error rather than an HTML stacktrace.
        exception<Throwable> { call, cause ->
            call.respondStripe(
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("type", "api_error")
                        put("message", cause.message ?: "Internal server error")
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
    // key, exactly like real Stripe. We accept ANY sk_... value — this is a fake — but
    // reject a missing key with Stripe's authentication_error. Admin/health stay open.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val needsAuth = path.startsWith("/v1/") && !path.startsWith("/v1/admin/")
        if (needsAuth && apiKeyFrom(call.request.header(HttpHeaders.Authorization)) == null) {
            val err = StripeException.authenticationError()
            call.respondStripe(err.toJson(), err.status)
            return@intercept finish()
        }
    }

    routing {
        adminRoutes(sim)
        customerRoutes(sim)
        paymentMethodRoutes(sim)
        paymentIntentRoutes(sim)
        chargeRoutes(sim)
        unknownUrlFallback()
    }
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
