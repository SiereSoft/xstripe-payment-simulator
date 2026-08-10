package com.fakestripe.routes

import com.fakestripe.store.Simulator
import com.fakestripe.store.toControlPlaneJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Non-Stripe administrative endpoints used to drive reproducible training:
 *   - GET /healthz liveness
 *   - GET /v1/admin/health world summary
 *   - GET /v1/admin/state complete redacted state for privileged verifiers
 *   - POST /v1/admin/reset?seed=N wipe and re-seed the world deterministically
 */
fun Route.adminRoutes(sim: Simulator, controlToken: String?) {

    get("/healthz") {
        call.respondStripe(buildJsonObject { put("status", "ok") })
    }

    get("/") {
        call.respondStripe(buildJsonObject {
            put("service", "fake-stripe")
            put("version", "0.1.0")
            put("description", "A stateful Stripe payments-core simulator. See README.md.")
            put("seed", sim.seed)
        })
    }

    get("/v1/admin/health") {
        call.respondStripe(sim.read { store ->
            buildJsonObject {
                put("status", "ok")
                put("seed", store.seed)
                put("customers", store.customers.size)
                put("payment_methods", store.paymentMethods.size)
                put("payment_intents", store.paymentIntents.size)
                put("charges", store.charges.size)
            }
        })
    }

    get("/v1/admin/state") {
        if (controlToken == null) {
            call.respondStripe(
                adminError("State export is disabled. Set FAKE_STRIPE_CONTROL_TOKEN to enable it."),
                HttpStatusCode.NotFound,
            )
            return@get
        }
        val supplied = call.request.header(CONTROL_TOKEN_HEADER)
        if (supplied == null || !secureEquals(controlToken, supplied)) {
            call.respondStripe(
                adminError("A valid controller token is required."),
                HttpStatusCode.Forbidden,
            )
            return@get
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondStripe(sim.read { store -> store.toControlPlaneJson() })
    }

    post("/v1/admin/reset") { handleReset(sim, call) }
    get("/v1/admin/reset") { handleReset(sim, call) }
}

private const val CONTROL_TOKEN_HEADER = "X-Siere-Control-Token"

private fun secureEquals(expected: String, supplied: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())

private fun adminError(message: String) = buildJsonObject {
    put("error", buildJsonObject {
        put("type", "admin_error")
        put("message", message)
    })
}

private suspend fun handleReset(sim: Simulator, call: ApplicationCall) {
    val seed = call.queryParams().long("seed") ?: call.formParams().long("seed") ?: sim.seed
    sim.reset(seed)
    call.respondStripe(sim.read { store ->
        buildJsonObject {
            put("object", "admin.reset")
            put("seed", seed)
            put("customers", store.customers.size)
            put("payment_methods", store.paymentMethods.size)
            put("payment_intents", store.paymentIntents.size)
            put("charges", store.charges.size)
        }
    })
}
