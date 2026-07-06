package com.fakestripe.routes

import com.fakestripe.store.Simulator
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Non-Stripe administrative endpoints used to drive reproducible training:
 *   - GET /healthz liveness
 *   - GET /v1/admin/health world summary
 *   - POST /v1/admin/reset?seed=N wipe and re-seed the world deterministically
 */
fun Route.adminRoutes(sim: Simulator) {

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

    post("/v1/admin/reset") { handleReset(sim, call) }
    get("/v1/admin/reset") { handleReset(sim, call) }
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
