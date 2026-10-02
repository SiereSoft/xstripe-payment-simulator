package com.fakestripe.routes

import com.fakestripe.control.ControlException
import com.fakestripe.store.Simulator
import com.fakestripe.webhook.WebhookJob
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Webhook endpoint configuration and the persisted outbox (control protocol section 7):
 *
 *   - POST|GET /v1/admin/webhook          the endpoint (url, secret) — the secret is never echoed
 *   - GET  /v1/admin/webhooks?status=     the outbox
 *   - GET  /v1/admin/webhooks/{id}        one job, with its exact payload
 *   - POST /v1/admin/webhooks/flush       deliver due (or listed) jobs now
 *   - POST /v1/admin/webhooks/{id}/replay enqueue an exact duplicate
 */
fun Route.webhookAdminRoutes(sim: Simulator) {

    post("/v1/admin/webhook") {
        val input = call.controlInput()
        sim.configureWebhook(input.string("url"), input.string("secret"))
        call.respondStripe(webhookConfig(sim))
    }

    get("/v1/admin/webhook") {
        call.respondStripe(webhookConfig(sim))
    }

    get("/v1/admin/webhooks") {
        val status = call.queryParams().opt("status")
        if (status != null && status !in WebhookJob.STATUSES) {
            throw ControlException.invalid(
                "`status` must be one of ${WebhookJob.STATUSES.sorted().joinToString()}.",
                "status",
            )
        }
        call.respondStripe(buildJsonObject {
            val target = sim.webhookTarget()
            put("webhooks", JsonArray(sim.webhookJobs(status).map { it.toJson(target) }))
        })
    }

    get("/v1/admin/webhooks/{id}") {
        call.respondStripe(sim.webhookJob(call.parameters["id"]!!).toJson(sim.webhookTarget(), includePayload = true))
    }

    post("/v1/admin/webhooks/flush") {
        val input = call.controlInput()
        val ids = when (val raw = input.body["ids"]) {
            null, JsonNull -> null
            is JsonArray -> raw.map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    ?: throw ControlException.invalid("`ids` must be an array of job ids.", "ids")
            }
            else -> throw ControlException.invalid("`ids` must be an array of job ids.", "ids")
        }
        val concurrency = when (val raw = input.raw("concurrency")) {
            null -> 1
            is JsonPrimitive -> raw.intOrNull?.takeIf { it in 1..16 }
                ?: throw ControlException.invalid("`concurrency` must be 1..16.", "concurrency")
            else -> throw ControlException.invalid("`concurrency` must be 1..16.", "concurrency")
        }
        val results = sim.flushWebhooks(
            ids = ids,
            reverse = input.bool("reverse") ?: false,
            concurrency = concurrency,
            includeFuture = input.bool("include_future") ?: false,
        )
        val target = sim.webhookTarget()
        call.respondStripe(buildJsonObject { put("results", JsonArray(results.map { it.toJson(target) })) })
    }

    post("/v1/admin/webhooks/{id}/replay") {
        call.respondStripe(sim.replayWebhook(call.parameters["id"]!!).toJson(sim.webhookTarget()), HttpStatusCode.Created)
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
    put("url", sim.read { it.config.webhookUrl })
    put("secret_configured", sim.webhooks.secret.isNotBlank())
    put("delivery", sim.read { it.config.webhookDelivery })
}
