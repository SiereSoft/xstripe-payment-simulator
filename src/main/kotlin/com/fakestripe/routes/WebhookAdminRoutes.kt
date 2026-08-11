package com.fakestripe.routes

import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Controller endpoints to point webhook delivery at a receiver and inspect the
 * current config. Handy for tasks/tests that verify signed event delivery.
 */
fun Route.webhookAdminRoutes(sim: Simulator, controlToken: String?) {

    post("/v1/admin/webhook") {
        if (!call.requireController(controlToken)) return@post
        val params = call.formParams()
        sim.configureWebhook(params.opt("url"), params.opt("secret"))
        call.respondStripe(webhookConfig(sim))
    }

    get("/v1/admin/webhook") {
        if (!call.requireController(controlToken)) return@get
        call.respondStripe(webhookConfig(sim))
    }
}

/**
 * The signing secret is deliberately not echoed. Everything else in the export
 * surface strips `*_secret` fields (see StateExport); this endpoint used to be
 * the one exception. Callers that need to verify a signature already hold the
 * secret they configured.
 */
private fun webhookConfig(sim: Simulator) = buildJsonObject {
    put("object", "admin.webhook")
    put("url", sim.webhooks.url)
    put("secret_configured", sim.webhooks.secret.isNotBlank())
}
