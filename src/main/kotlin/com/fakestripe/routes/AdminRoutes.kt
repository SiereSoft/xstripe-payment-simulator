package com.fakestripe.routes

import com.fakestripe.BuildInfo
import com.fakestripe.control.ControlException
import com.fakestripe.control.ControlProtocol
import com.fakestripe.control.Fault
import com.fakestripe.control.JournalEntry
import com.fakestripe.error.StripeException
import com.fakestripe.seed.Seeder
import com.fakestripe.seedValue
import com.fakestripe.store.ClockMode
import com.fakestripe.store.DataStore
import com.fakestripe.store.SimConfig
import com.fakestripe.store.Simulator
import com.fakestripe.store.toControlPlaneJson
import com.fakestripe.util.StripeParams
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Service routes on both listeners:
 *   - GET /healthz liveness (no authentication)
 *   - GET / service info (actor only)
 */
fun Route.serviceRoutes(sim: Simulator, includeServiceInfo: Boolean) {
    get("/healthz") {
        call.respondStripe(buildJsonObject { put("status", "ok") })
    }

    if (includeServiceInfo) {
        get("/") {
            call.respondStripe(buildJsonObject {
                put("service", "fake-stripe")
                put("version", BuildInfo.VERSION)
                put("description", "A stateful Stripe payments-core simulator. See README.md.")
                put("seed", sim.seed)
            })
        }
    }
}

/**
 * The control protocol (docs/CONTROL-PROTOCOL.md) on the controller listener. The token check
 * happens before routing (see Application.kt), so every route here is already authenticated.
 *
 *   - GET /v1/admin/health, POST /v1/admin/reset, GET /v1/admin/state, GET|PATCH /v1/admin/config
 *   - GET|POST /v1/admin/clock, POST /v1/admin/clock/advance
 *   - GET|POST|DELETE /v1/admin/faults[/{name}]
 *   - GET /v1/admin/journal
 *   - GET|POST /v1/admin/snapshots, GET|DELETE /v1/admin/snapshots/{name}, POST .../{name}/restore
 */
fun Route.adminRoutes(sim: Simulator) {

    get("/v1/admin/health") {
        call.respondStripe(healthJson(sim))
    }

    get("/v1/admin/state") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondStripe(sim.read { store -> store.toControlPlaneJson() })
    }

    // Reset is POST-only: it destroys and rebuilds the world, so it must not be
    // reachable by anything that follows links or prefetches URLs.
    post("/v1/admin/reset") {
        handleReset(sim, call)
    }

    get("/v1/admin/config") {
        call.respondStripe(configJson(sim))
    }

    patch("/v1/admin/config") {
        val input = call.controlInput()
        input.string("webhook_delivery")?.let { mode ->
            if (mode != SimConfig.DELIVERY_AUTO && mode != SimConfig.DELIVERY_MANUAL) {
                throw ControlException.invalid("`webhook_delivery` must be `auto` or `manual`.", "webhook_delivery")
            }
            sim.setWebhookDelivery(mode)
        }
        // Like the signing secret, a process setting (FAKE_STRIPE_WEBHOOK_SIGN_WALL_CLOCK), not part of the world.
        input.string("webhook_signature_clock")?.let { clock ->
            if (clock != SIGNATURE_CLOCK_WALL && clock != SIGNATURE_CLOCK_SIMULATED) {
                throw ControlException.invalid("`webhook_signature_clock` must be `simulated` or `wall`.", "webhook_signature_clock")
            }
            sim.webhooks.signWithWallClock = clock == SIGNATURE_CLOCK_WALL
        }
        call.respondStripe(configJson(sim))
    }

    // --- clock ----------------------------------------------------------------------------

    get("/v1/admin/clock") {
        call.respondStripe(sim.read { store -> clockJson(store) })
    }

    post("/v1/admin/clock") {
        val input = call.controlInput()
        val text = input.string("now") ?: throw ControlException.invalid("`now` is required.", "now")
        val target = ControlProtocol.parseIso(text)
            ?: throw ControlException.invalid("`now` must be an ISO-8601 time such as 2026-10-02T10:00:00.000Z.", "now")
        val moved = sim.moveClockTo(target)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondStripe(clockMoveJson(sim, moved, advancedBy = null))
    }

    post("/v1/admin/clock/advance") {
        val input = call.controlInput()
        val raw = input.raw("seconds") ?: throw ControlException.invalid("Missing required param: seconds.", "seconds")
        val seconds = (raw as? JsonPrimitive)?.content?.trim()?.let { runCatching { BigDecimal(it) }.getOrNull() }
        if (seconds == null || seconds.signum() <= 0 || seconds > BigDecimal.valueOf(ControlProtocol.MAX_ADVANCE_SECONDS)) {
            throw ControlException.invalid(
                "The seconds parameter must be a number greater than 0 and at most ${ControlProtocol.MAX_ADVANCE_SECONDS}.",
                "seconds",
            )
        }
        val millis = seconds.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        if (millis <= 0) throw ControlException.invalid("The seconds parameter must be at least 0.001.", "seconds")
        val start = sim.read { store ->
            if (store.clock.mode != ClockMode.MANUAL) null else store.nowMs()
        }
        // In free mode moveClockTo answers 409 clock_not_manual; the target is irrelevant then.
        val moved = sim.moveClockTo((start ?: 0L) + millis)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val advancedBy: JsonPrimitive = seconds.stripTrailingZeros().let {
            if (it.scale() <= 0) JsonPrimitive(it.toLong()) else JsonPrimitive(it)
        }
        call.respondStripe(clockMoveJson(sim, moved, advancedBy))
    }

    // --- faults ---------------------------------------------------------------------------

    get("/v1/admin/faults") {
        call.respondStripe(buildJsonObject { put("faults", JsonArray(sim.faults().map { it.toJson() })) })
    }

    post("/v1/admin/faults") {
        val fault = Fault.parse(call.controlInput().body)
        call.respondStripe(sim.installFault(fault).toJson(), HttpStatusCode.Created)
    }

    delete("/v1/admin/faults/{name}") {
        sim.deleteFault(call.parameters["name"]!!)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/v1/admin/faults") {
        sim.deleteAllFaults()
        call.respond(HttpStatusCode.NoContent)
    }

    // --- journal --------------------------------------------------------------------------

    get("/v1/admin/journal") {
        val query = call.queryParams()
        val after = query.opt("after")?.let {
            it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ControlException.invalid("`after` must be a sequence number.", "after")
        } ?: 0L
        val limit = query.opt("limit")?.let {
            it.toIntOrNull()?.takeIf { v -> v in 1..1000 } ?: throw ControlException.invalid("`limit` must be 1..1000.", "limit")
        } ?: 200
        val kind = query.opt("kind")?.also {
            if (it !in JournalEntry.KINDS) {
                throw ControlException.invalid("`kind` must be one of ${JournalEntry.KINDS.sorted().joinToString()}.", "kind")
            }
        }
        val bodies = when (query.opt("bodies")) {
            null, "false" -> false
            "true" -> true
            else -> throw ControlException.invalid("`bodies` must be true or false.", "bodies")
        }
        val page = sim.journal.page(after, limit, kind)
        call.respondStripe(buildJsonObject {
            put("entries", JsonArray(page.entries.map { it.toJson(bodies) }))
            put("next", page.next)
            put("oldest", page.oldest)
        })
    }

    // --- snapshots ------------------------------------------------------------------------

    post("/v1/admin/snapshots") {
        val input = call.controlInput()
        val name = input.string("name") ?: throw ControlException.invalid("`name` is required.", "name")
        if (!ControlProtocol.NAME_PATTERN.matches(name)) {
            throw ControlException.invalid("Snapshot names must match [A-Za-z0-9._-]{1,64}.", "name")
        }
        val replace = input.bool("replace") ?: false
        call.respondStripe(sim.saveSnapshot(name, replace).toJson(), HttpStatusCode.Created)
    }

    get("/v1/admin/snapshots") {
        call.respondStripe(buildJsonObject { put("snapshots", JsonArray(sim.snapshots.list().map { it.toJson() })) })
    }

    get("/v1/admin/snapshots/{name}") {
        val name = call.parameters["name"]!!
        val meta = sim.snapshots.meta(name) ?: throw ControlException.notFound("No snapshot named '$name'.")
        call.respondStripe(meta.toJson())
    }

    delete("/v1/admin/snapshots/{name}") {
        val name = call.parameters["name"]!!
        if (!sim.snapshots.delete(name)) throw ControlException.notFound("No snapshot named '$name'.")
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/admin/snapshots/{name}/restore") {
        sim.restoreSnapshot(call.parameters["name"]!!)
        call.respondStripe(healthJson(sim))
    }
}

private val EPISODE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val RESET_FIELDS = listOf("seed", "scenario", "clock_mode", "episode_id", "now")

private suspend fun handleReset(sim: Simulator, call: ApplicationCall) {
    val input = call.controlInput()
    // 0.1.x callers passed parameters in the query string or a form body; they keep 0.1.x's
    // clock default (free without a scenario). The protocol's JSON body defaults to manual.
    val legacy = input.form || RESET_FIELDS.any { input.query.has(it) }
    val seedText = input.string("seed")?.trim()?.takeIf { it.isNotEmpty() } ?: sim.defaultSeedText
    if (seedText.length > 128) throw StripeException.invalidRequest("The seed must be at most 128 characters.", param = "seed")
    val scenario = input.string("scenario")?.takeIf { it.isNotBlank() }
    if (scenario != null && scenario !in Seeder.supportedScenarios) {
        throw StripeException.invalidRequest(
            "Unsupported scenario '$scenario'. Supported scenarios: ${Seeder.supportedScenarios.sorted().joinToString()}.",
            param = "scenario",
        )
    }
    val clockMode = input.string("clock_mode")?.takeIf { it.isNotBlank() }?.let { value ->
        ClockMode.parse(value) ?: throw StripeException.invalidRequest(
            "Unsupported clock mode '$value'. Supported modes: free, manual.",
            param = "clock_mode",
        )
    } ?: if (legacy) ClockMode.defaultFor(scenario) else ClockMode.MANUAL
    val episodeId = input.string("episode_id")
    if (episodeId != null && !EPISODE_ID_PATTERN.matches(episodeId)) {
        throw StripeException.invalidRequest(
            "The episode_id parameter must be 1-128 characters using letters, numbers, '.', '_', ':', or '-'.",
            param = "episode_id",
        )
    }
    val startMs = input.string("now")?.let { text ->
        if (clockMode != ClockMode.MANUAL) {
            throw ControlException.invalid("`now` sets a manual clock; it cannot be combined with clock_mode=free.", "now")
        }
        ControlProtocol.parseIso(text)
            ?: throw ControlException.invalid("`now` must be an ISO-8601 time such as 2026-10-02T10:00:00.000Z.", "now")
    }
    sim.reset(seedValue(seedText), scenario, clockMode, episodeId, seedText, startMs)
    val health = healthJson(sim)
    call.respondStripe(sim.read { store ->
        JsonObject(
            health + buildJsonObject {
                put("object", "admin.reset")
                put("scenario", store.scenario?.id)
                put("state_revision", store.revision)
                put("task_context", store.scenario?.instructionContext ?: buildJsonObject { })
            },
        )
    })
}

/** `GET /v1/admin/health`: identity and position, plus the 0.1.x world summary. */
internal fun healthJson(sim: Simulator): JsonObject = sim.read { store ->
    buildJsonObject {
        put("status", "ok")
        put("simulator", BuildInfo.SIMULATOR)
        put("version", BuildInfo.VERSION)
        put("protocol", ControlProtocol.VERSION)
        put("seed", store.seedText)
        put("episode_id", store.episodeId)
        put("clock", clockJson(store))
        put("revision", store.revision)
        put("journal_seq", sim.journal.lastSeq)
        put("customers", store.customers.size)
        put("payment_methods", store.paymentMethods.size)
        put("payment_intents", store.paymentIntents.size)
        put("charges", store.charges.size)
    }
}

/** `{"now", "mode"}` plus 0.1.x's `current_time` (Unix seconds). */
private fun clockJson(store: DataStore) = buildJsonObject {
    put("now", ControlProtocol.iso(store.nowMs()))
    put("mode", store.clock.mode.wireValue)
    put("current_time", store.clock.now())
}

private fun clockMoveJson(sim: Simulator, moved: Simulator.ClockMoveResult, advancedBy: JsonPrimitive?) = buildJsonObject {
    put("now", ControlProtocol.iso(moved.nowMs))
    put("mode", ClockMode.MANUAL.wireValue)
    put("settled", moved.settled)
    put("object", "admin.clock")
    put("current_time", Math.floorDiv(moved.nowMs, 1000L))
    if (advancedBy != null) put("advanced_by", advancedBy)
    put("state_revision", moved.revision)
    put("episode_id", sim.episodeId)
}

private fun configJson(sim: Simulator) = sim.read { store ->
    buildJsonObject {
        put("object", "admin.config")
        put("webhook_delivery", store.config.webhookDelivery)
        put("webhook_url", store.config.webhookUrl)
        put("webhook_secret_configured", sim.webhooks.secret.isNotBlank())
        put("clock_mode", store.clock.mode.wireValue)
        put("webhook_signature_clock", if (sim.webhooks.signWithWallClock) SIGNATURE_CLOCK_WALL else SIGNATURE_CLOCK_SIMULATED)
        put("account_id", sim.accountId)
    }
}

private const val SIGNATURE_CLOCK_WALL = "wall"
private const val SIGNATURE_CLOCK_SIMULATED = "simulated"

/**
 * A controller request's input: a JSON object body (the protocol), a form body or the query
 * string (0.1.x). Body fields win over query parameters.
 */
internal class ControlInput(val body: JsonObject, val query: StripeParams, val form: Boolean) {
    fun raw(name: String): JsonElement? =
        body[name]?.takeUnless { it is JsonNull } ?: query.opt(name)?.let { JsonPrimitive(it) }

    fun string(name: String): String? = when (val value = raw(name)) {
        null -> null
        is JsonPrimitive -> value.content
        else -> throw ControlException.invalid("`$name` must be a string.", name)
    }

    fun bool(name: String): Boolean? = when (val value = raw(name)) {
        null -> null
        is JsonPrimitive -> when (value.content) {
            "true" -> true
            "false" -> false
            else -> throw ControlException.invalid("`$name` must be true or false.", name)
        }
        else -> throw ControlException.invalid("`$name` must be true or false.", name)
    }
}

private val controlJson = Json { ignoreUnknownKeys = true }

internal suspend fun ApplicationCall.controlInput(): ControlInput {
    val text = runCatching { receiveText() }.getOrDefault("")
    val contentType = runCatching { request.contentType() }.getOrNull()
    val form = contentType?.match(ContentType.Application.FormUrlEncoded) == true
    val body = when {
        text.isBlank() -> JsonObject(emptyMap())
        form -> JsonObject(StripeParams.fromBody(text).let { params -> params.keys().associateWith { JsonPrimitive(params.opt(it)) } })
        else -> runCatching { controlJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ControlException.invalid("The request body must be a JSON object.")
    }
    return ControlInput(body, queryParams(), form)
}
