package com.fakestripe.routes

import com.fakestripe.error.StripeException
import com.fakestripe.seed.Seeder
import com.fakestripe.store.ClockMode
import com.fakestripe.store.DataStore
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
 *   - POST /v1/admin/clock/advance?seconds=N advance privileged manual time
 */
fun Route.serviceRoutes(sim: Simulator, includeServiceInfo: Boolean) {
    get("/healthz") {
        call.respondStripe(buildJsonObject { put("status", "ok") })
    }

    if (includeServiceInfo) {
        get("/") {
            call.respondStripe(buildJsonObject {
                put("service", "fake-stripe")
                put("version", "0.1.2")
                put("description", "A stateful Stripe payments-core simulator. See README.md.")
                put("seed", sim.seed)
            })
        }
    }
}

fun Route.adminRoutes(sim: Simulator, controlToken: String?) {

    get("/v1/admin/health") {
        if (!call.requireController(controlToken)) return@get
        call.respondStripe(sim.read { store ->
            buildJsonObject {
                put("status", "ok")
                put("episode_id", store.episodeId)
                put("seed", store.seed)
                put("customers", store.customers.size)
                put("payment_methods", store.paymentMethods.size)
                put("payment_intents", store.paymentIntents.size)
                put("charges", store.charges.size)
                put("clock", clockJson(store))
            }
        })
    }

    get("/v1/admin/state") {
        if (!call.requireController(controlToken)) return@get
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondStripe(sim.read { store -> store.toControlPlaneJson() })
    }

    post("/v1/admin/clock/advance") {
        if (!call.requireController(controlToken)) return@post
        val query = call.queryParams()
        val form = call.formParams()
        val rawSeconds = query.opt("seconds") ?: form.opt("seconds")
            ?: throw StripeException.missingParam("seconds")
        val seconds = rawSeconds.toLongOrNull()
        if (seconds == null || seconds <= 0) {
            throw StripeException.invalidRequest(
                "The seconds parameter must be a positive integer.",
                param = "seconds",
            )
        }
        val advanced = try {
            sim.advanceClock(seconds)
        } catch (_: ArithmeticException) {
            throw StripeException.invalidRequest(
                "Advancing by $seconds seconds would exceed the supported clock range.",
                param = "seconds",
            )
        } ?: throw StripeException.invalidRequest(
            "The simulator clock must be in manual mode before it can be advanced.",
            param = "clock_mode",
        )
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondStripe(buildJsonObject {
            put("object", "admin.clock")
            put("mode", ClockMode.MANUAL.wireValue)
            put("current_time", advanced.currentTime)
            put("advanced_by", seconds)
            put("state_revision", advanced.revision)
            put("episode_id", sim.episodeId)
        })
    }

    // Reset is POST-only: it destroys and rebuilds the world, so it must not be
    // reachable by anything that follows links or prefetches URLs.
    post("/v1/admin/reset") {
        if (!call.requireController(controlToken)) return@post
        handleReset(sim, call)
    }
}

private const val CONTROL_TOKEN_HEADER = "X-Siere-Control-Token"
private val EPISODE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

internal suspend fun ApplicationCall.requireController(controlToken: String?): Boolean {
    if (controlToken.isNullOrBlank()) {
        respondStripe(
            adminError("The control plane is disabled. Set FAKE_STRIPE_CONTROL_TOKEN to enable it."),
            HttpStatusCode.NotFound,
        )
        return false
    }
    val supplied = request.header(CONTROL_TOKEN_HEADER)
    if (supplied == null || !secureEquals(controlToken, supplied)) {
        respondStripe(
            adminError("A valid controller token is required."),
            HttpStatusCode.Forbidden,
        )
        return false
    }
    return true
}

/**
 * Constant-time token comparison.
 *
 * [MessageDigest.isEqual] short-circuits on a length mismatch, which leaks the
 * expected token's length. Comparing fixed-width SHA-256 digests removes that.
 */
private fun secureEquals(expected: String, supplied: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val a = digest.digest(expected.toByteArray())
    val b = MessageDigest.getInstance("SHA-256").digest(supplied.toByteArray())
    return MessageDigest.isEqual(a, b)
}

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
    val clockModeValue = (query.opt("clock_mode") ?: form.opt("clock_mode"))?.takeIf {
        it.isNotBlank()
    }
    val clockMode = clockModeValue?.let { value ->
        ClockMode.parse(value) ?: throw StripeException.invalidRequest(
            "Unsupported clock mode '$value'. Supported modes: free, manual.",
            param = "clock_mode",
        )
    } ?: ClockMode.defaultFor(scenario)
    val episodeId = query.opt("episode_id") ?: form.opt("episode_id")
    if (episodeId != null && !EPISODE_ID_PATTERN.matches(episodeId)) {
        throw StripeException.invalidRequest(
            "The episode_id parameter must be 1-128 characters using letters, numbers, '.', '_', ':', or '-'.",
            param = "episode_id",
        )
    }
    sim.reset(seed, scenario, clockMode, episodeId)
    call.respondStripe(sim.read { store ->
        buildJsonObject {
            put("object", "admin.reset")
            put("seed", seed)
            put("episode_id", store.episodeId)
            put("scenario", store.scenario?.id)
            put("state_revision", store.revision)
            put("clock", clockJson(store))
            put("task_context", store.scenario?.instructionContext ?: buildJsonObject { })
            put("customers", store.customers.size)
            put("payment_methods", store.paymentMethods.size)
            put("payment_intents", store.paymentIntents.size)
            put("charges", store.charges.size)
        }
    })
}

private fun clockJson(store: DataStore) = buildJsonObject {
    put("mode", store.clock.mode.wireValue)
    put("current_time", store.clock.now())
}
