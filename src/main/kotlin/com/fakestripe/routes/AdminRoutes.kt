package com.fakestripe.routes

import com.fakestripe.error.StripeException
import com.fakestripe.seed.Seeder
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
 *   - POST /v1/admin/reset?seed=N&scenario=ID persist a deterministic task world
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
    val query = call.queryParams()
    val form = call.formParams()
    val seed = query.long("seed") ?: form.long("seed") ?: sim.seed
    val scenario = (query.opt("scenario") ?: form.opt("scenario"))?.takeIf { it.isNotBlank() }
    if (scenario != null && scenario !in Seeder.supportedScenarios) {
        throw StripeException.invalidRequest(
            "Unsupported scenario '$scenario'. Supported scenarios: ${Seeder.supportedScenarios.sorted().joinToString()}.",
            param = "scenario",
        )
    }
    sim.reset(seed, scenario)
    call.respondStripe(sim.read { store ->
        buildJsonObject {
            put("object", "admin.reset")
            put("seed", seed)
            put("scenario", store.scenario?.id)
            put("state_revision", store.revision)
            put("task_context", store.scenario?.instructionContext ?: buildJsonObject { })
            put("customers", store.customers.size)
            put("payment_methods", store.paymentMethods.size)
            put("payment_intents", store.paymentIntents.size)
            put("charges", store.charges.size)
        }
    })
}
