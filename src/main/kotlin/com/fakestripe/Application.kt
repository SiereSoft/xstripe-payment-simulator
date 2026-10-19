package com.fakestripe

import com.fakestripe.control.ControlException
import com.fakestripe.control.ControlProtocol
import com.fakestripe.control.Fault
import com.fakestripe.control.JournalEntry
import com.fakestripe.control.Operations
import com.fakestripe.control.Redaction
import com.fakestripe.control.RequestContext
import com.fakestripe.control.RequestScope
import com.fakestripe.control.controlError
import com.fakestripe.control.genericCode
import com.fakestripe.error.StripeException
import com.fakestripe.routes.CachedBodyKey
import com.fakestripe.routes.IdempotencyRecorderKey
import com.fakestripe.routes.STRIPE_VERSION_HEADER
import com.fakestripe.routes.accountRoutes
import com.fakestripe.routes.adminRoutes
import com.fakestripe.routes.billingPortalRoutes
import com.fakestripe.routes.chargeRoutes
import com.fakestripe.routes.checkoutAdminRoutes
import com.fakestripe.routes.checkoutRoutes
import com.fakestripe.routes.customerRoutes
import com.fakestripe.routes.eventRoutes
import com.fakestripe.routes.invoicePaymentRoutes
import com.fakestripe.routes.invoiceRoutes
import com.fakestripe.routes.paymentIntentRoutes
import com.fakestripe.routes.paymentMethodRoutes
import com.fakestripe.routes.priceRoutes
import com.fakestripe.routes.productRoutes
import com.fakestripe.routes.refundRoutes
import com.fakestripe.routes.respondStripe
import com.fakestripe.routes.serviceRoutes
import com.fakestripe.routes.subscriptionAdminRoutes
import com.fakestripe.routes.subscriptionItemRoutes
import com.fakestripe.routes.scheduleRoutes
import com.fakestripe.routes.subscriptionRoutes
import com.fakestripe.routes.webhookAdminRoutes
import com.fakestripe.store.ClockMode
import com.fakestripe.store.Simulator
import com.fakestripe.util.ApiVersion
import com.fakestripe.webhook.WebhookDispatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingApplicationCall
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.io.IOException
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val startupLog = LoggerFactory.getLogger("com.fakestripe.Startup")

/** The two listeners of a running simulator; [controller] is null when no usable token is configured. */
class RunningServers(
    val simulator: Simulator,
    val actor: ApplicationEngine,
    val controller: ApplicationEngine?,
) {
    private val stopped = CountDownLatch(1)

    /** Block the calling thread until [stop] is called. */
    fun awaitStop() = stopped.await()

    fun stop() {
        controller?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        actor.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        simulator.close()
        stopped.countDown()
    }
}

/**
 * Start the actor listener and — only when `FAKE_STRIPE_CONTROL_TOKEN` holds a usable token (at
 * least 16 characters) — the controller listener. Without one the controller port is never opened
 * and the log says why.
 */
fun startServers(environment: Map<String, String>): RunningServers {
    val config = ServerConfig.from(environment)
    val simulator = simulatorFromEnvironment(environment)
    val rawToken = environment[ControlProtocol.TOKEN_ENV]
    val token = ControlProtocol.usableToken(rawToken)
    val controller = if (token == null) {
        startupLog.warn(
            if (rawToken.isNullOrBlank()) {
                "Controller disabled: ${ControlProtocol.TOKEN_ENV} is not set, so port ${config.controllerPort} is not opened."
            } else {
                "Controller disabled: ${ControlProtocol.TOKEN_ENV} must be at least ${ControlProtocol.MIN_TOKEN_LENGTH} " +
                    "characters, so port ${config.controllerPort} is not opened."
            },
        )
        null
    } else {
        embeddedServer(Netty, port = config.controllerPort, host = config.controllerHost) {
            controllerModule(simulator, token)
        }.start(wait = false)
    }
    val actor = embeddedServer(Netty, port = config.actorPort, host = config.actorHost) {
        actorModule(simulator)
    }.start(wait = false)
    return RunningServers(simulator, actor, controller)
}

fun Application.module() {
    actorModule(simulatorFromEnvironment(System.getenv()))
}

internal fun simulatorFromEnvironment(environment: Map<String, String>): Simulator {
    val dataPath = Paths.get(environment["FAKE_STRIPE_DATA"] ?: "data/state.json")
    val seedText = environment["FAKE_STRIPE_SEED"]?.trim()?.takeIf { it.isNotEmpty() } ?: "1"
    val webhooks = WebhookDispatcher(
        url = environment["FAKE_STRIPE_WEBHOOK_URL"]?.takeIf { it.isNotBlank() },
        secret = environment["FAKE_STRIPE_WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() } ?: "whsec_test",
        signWithWallClock = flag(environment, WALL_CLOCK_SIGNING_ENV),
    )
    val clockModeValue = environment["FAKE_STRIPE_CLOCK_MODE"] ?: ClockMode.FREE.wireValue
    val clockMode = ClockMode.parse(clockModeValue)
        ?: throw IllegalArgumentException("FAKE_STRIPE_CLOCK_MODE must be 'free' or 'manual'.")
    return Simulator.boot(
        dataPath, seedValue(seedText), webhooks, clockMode,
        defaultSeedText = seedText, accountId = accountIdFrom(environment),
    )
}

internal const val WALL_CLOCK_SIGNING_ENV = "FAKE_STRIPE_WEBHOOK_SIGN_WALL_CLOCK"
internal const val ACCOUNT_ID_ENV = "FAKE_STRIPE_ACCOUNT_ID"

/** `1`/`true`/`yes`/`on` or `0`/`false`/`no`/`off` (empty: off); anything else stops start-up. */
internal fun flag(environment: Map<String, String>, name: String): Boolean =
    when (environment[name]?.trim()?.lowercase()) {
        null, "", "0", "false", "no", "off" -> false
        "1", "true", "yes", "on" -> true
        else -> throw IllegalArgumentException("$name must be 1 or 0.")
    }

/** The account `GET /v1/account` answers: `acct_` plus letters, digits or underscores. */
internal fun accountIdFrom(environment: Map<String, String>): String {
    val value = environment[ACCOUNT_ID_ENV]?.trim()?.takeIf { it.isNotEmpty() } ?: return Simulator.DEFAULT_ACCOUNT_ID
    require(Regex("^acct_[A-Za-z0-9_]{1,123}$").matches(value)) {
        "$ACCOUNT_ID_ENV must look like a Stripe account id: acct_ followed by letters, digits or underscores."
    }
    return value
}

/**
 * A seed as the controller may give it: a number is used as is; any other text is hashed
 * (64-bit FNV-1a over UTF-8) so `"lunch-rush-1"` always builds the same world.
 */
fun seedValue(text: String): Long = text.toLongOrNull() ?: run {
    var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
    text.toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    hash
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

/** Wires only the control protocol on the controller listener. */
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

/** Raised where a dropped connection is asked for on an engine without a socket (in-memory tests). */
class ConnectionDroppedException : IOException("connection dropped by an injected fault")

/** What the journal needs to know about one actor request while it is served. */
internal class RequestTrace(
    val requestId: String,
    val operation: String?,
    val method: String,
    val path: String,
    val idempotencyKey: String?,
    val correlationId: String?,
) {
    var outcome: String = OUTCOME_ANSWERED
    var fault: String? = null
    var afterCommit: Fault? = null
    var faultApplied = false
    var dropped = false
    var committedStatus: Int? = null
    var replayed = false
    var responseText: String? = null
    var responseType: String? = null

    companion object {
        const val OUTCOME_ANSWERED = "answered"
        const val OUTCOME_ERROR_INJECTED = "error_injected"
        const val OUTCOME_DROPPED = "dropped"
        const val OUTCOME_REJECTED = "rejected"
    }
}

internal val RequestTraceKey = AttributeKey<RequestTrace>("fakestripe.requestTrace")

private fun Application.configureApplication(
    sim: Simulator,
    actorRoutes: Boolean,
    controllerRoutes: Boolean,
    controlToken: String?,
) {
    val token = ControlProtocol.usableToken(controlToken)
    val serveController = controllerRoutes && token != null
    if (controllerRoutes && token == null) {
        log.warn("Control plane disabled: no usable ${ControlProtocol.TOKEN_ENV} (at least 16 characters).")
    }
    fun ApplicationCall.isControllerCall(): Boolean =
        controllerRoutes && (!actorRoutes || normalizedPath().startsWith("/v1/admin/") || normalizedPath() == "/v1/admin")

    install(CallLogging) {
        level = Level.INFO
        mdc("episode_id") { sim.episodeId }
    }
    install(DefaultHeaders) {
        header("Server", BuildInfo.PRODUCT)
    }
    // Stripe answers in the version the request asked for (`Stripe-Version`), else the account default.
    intercept(ApplicationCallPipeline.Setup) {
        val requested = if (actorRoutes && !call.isControllerCall()) call.request.header(STRIPE_VERSION_HEADER) else null
        call.response.headers.append(STRIPE_VERSION_HEADER, ApiVersion.effective(requested))
    }
    install(StatusPages) {
        exception<ControlException> { call, cause -> call.respondStripe(cause.toJson(), cause.status) }
        // Any StripeException renders in Stripe's exact error shape + status (or, on the
        // controller, in the control protocol's shape).
        exception<StripeException> { call, cause ->
            if (call.isControllerCall()) {
                call.respondStripe(controlError(genericCode(cause.status), cause.message, cause.param), cause.status)
            } else {
                call.respondStripe(cause.toJson(), cause.status)
            }
        }
        // Anything unexpected becomes an api_error rather than an HTML stacktrace.
        // The cause is logged server-side; the response carries a fixed message so
        // internal details (filesystem paths, library internals) never leak to callers.
        exception<Throwable> { call, cause ->
            if (cause is ConnectionDroppedException) return@exception
            call.application.log.error(
                "Unhandled exception for {} {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause,
            )
            val body = if (call.isControllerCall()) {
                controlError("internal_error", "An internal error occurred.")
            } else {
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("type", "api_error")
                        put("message", "An internal error occurred.")
                    })
                }
            }
            call.respondStripe(body, HttpStatusCode.InternalServerError)
        }
    }

    // Controller: every response carries the protocol version; every /v1/admin/ route needs the token.
    if (controllerRoutes) {
        intercept(ApplicationCallPipeline.Setup) {
            if (call.isControllerCall()) {
                call.response.headers.append(ControlProtocol.VERSION_HEADER, ControlProtocol.VERSION.toString())
            }
        }
        if (serveController) {
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.normalizedPath()
                if (!(path.startsWith("/v1/admin/") || path == "/v1/admin")) return@intercept
                val supplied = call.request.header(ControlProtocol.TOKEN_HEADER)
                if (supplied == null || !secureEquals(token!!, supplied)) {
                    call.respondStripe(
                        controlError("forbidden", "A valid controller token is required."),
                        HttpStatusCode.Forbidden,
                    )
                    return@intercept finish()
                }
            }
        }
    }

    if (actorRoutes) installActorPipeline(sim, controllerRoutes)

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
            scheduleRoutes(sim)
            subscriptionItemRoutes(sim)
            invoiceRoutes(sim)
            invoicePaymentRoutes(sim)
            accountRoutes(sim)
            checkoutRoutes(sim)
            billingPortalRoutes(sim)
            eventRoutes(sim)
        }
        if (serveController) {
            adminRoutes(sim)
            subscriptionAdminRoutes(sim)
            checkoutAdminRoutes(sim)
            webhookAdminRoutes(sim)
        }
        unknownUrlFallback(controllerOnly = controllerRoutes && !actorRoutes, controllerPrefix = serveController)
    }
}

/**
 * The actor's request pipeline: a seeded `Request-Id`, the journal entry, named faults, API-key
 * auth and idempotency, in that order.
 */
private fun Application.installActorPipeline(sim: Simulator, controllerRoutes: Boolean) {
    // Journal every actor request once its outcome is known, under a seeded request id.
    intercept(ApplicationCallPipeline.Setup) {
        val path = call.normalizedPath()
        if (path == "/healthz" || controllerRoutes && (path.startsWith("/v1/admin/") || path == "/v1/admin")) {
            return@intercept
        }
        val method = call.request.httpMethod.value
        val requestId = sim.nextRequestId()
        call.response.headers.append("Request-Id", requestId)
        val trace = RequestTrace(
            requestId = requestId,
            operation = Operations.resolve(method, path),
            method = method,
            path = path,
            idempotencyKey = call.request.header("Idempotency-Key")?.takeIf { it.isNotBlank() },
            correlationId = call.request.header(ControlProtocol.CORRELATION_HEADER)
                ?.take(ControlProtocol.MAX_CORRELATION_LENGTH)?.takeIf { it.isNotEmpty() },
        )
        call.attributes.put(RequestTraceKey, trace)
        val bodyText = if (method != "GET" && method != "HEAD") {
            runCatching { call.receiveText() }.getOrDefault("").also { call.attributes.put(CachedBodyKey, it) }
        } else {
            null
        }
        val context = RequestContext(requestId, trace.operation, trace.idempotencyKey, apiRequest = path.startsWith("/v1/"))
        try {
            withContext(RequestScope.current.asContextElement(context)) { proceed() }
        } finally {
            sim.recordRequest(journalEntry(call, trace, bodyText))
        }
    }

    // Named faults (control protocol section 5). They apply before the API-key check: a fault
    // models the network or the provider failing, whether or not the request would authenticate.
    intercept(ApplicationCallPipeline.Plugins) {
        val trace = call.attributes.getOrNull(RequestTraceKey) ?: return@intercept
        val fault = sim.takeFault(trace.operation, trace.method, trace.path, trace.requestId) ?: return@intercept
        trace.fault = fault.name
        when (fault.kind) {
            Fault.KIND_LATENCY -> delay(fault.delayMs)
            Fault.KIND_ERROR -> {
                trace.outcome = RequestTrace.OUTCOME_ERROR_INJECTED
                fault.headers.forEach { (k, v) -> call.response.headers.append(k, v) }
                call.respondText(fault.responseBody().toString(), ContentType.Application.Json, HttpStatusCode.fromValue(fault.status))
                return@intercept finish()
            }
            Fault.KIND_DROP_BEFORE -> {
                trace.outcome = RequestTrace.OUTCOME_DROPPED
                trace.dropped = true
                call.dropConnection()
                return@intercept finish()
            }
            else -> {
                trace.afterCommit = fault
                trace.outcome = if (fault.kind == Fault.KIND_COMMIT_THEN_DROP) {
                    RequestTrace.OUTCOME_DROPPED
                } else {
                    RequestTrace.OUTCOME_ERROR_INJECTED
                }
            }
        }
    }

    // Lightweight API-key auth: every /v1 business call must carry a Bearer (or Basic)
    // key, like real Stripe. We accept ANY sk_test_... value — this is a fake — but
    // reject a missing key with Stripe's authentication_error.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.isBusinessPath()) {
            val key = apiKeyFrom(call.request.header(HttpHeaders.Authorization))
            val error = when {
                key == null -> StripeException.authenticationError()
                // A live-mode credential must never reach a simulator. Fail loudly so a
                // misconfigured client cannot silently believe it is talking to Stripe.
                LIVE_KEY_PREFIXES.any { key.startsWith(it) } -> StripeException.liveKeyRejected()
                else -> null
            }
            if (error != null) {
                call.attributes.getOrNull(RequestTraceKey)?.outcome = RequestTrace.OUTCOME_REJECTED
                call.respondStripe(error.toJson(), error.status)
                return@intercept finish()
            }
        }
    }

    // Idempotency: a POST carrying `Idempotency-Key` replays its first response on
    // repeat (and errors if the same key is reused with a different body).
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.normalizedPath()
        val isBusinessPost = call.request.httpMethod == HttpMethod.Post && call.isBusinessPath()
        val key = call.request.header("Idempotency-Key")
        if (isBusinessPost && !key.isNullOrBlank()) {
            val bodyText = call.attributes.getOrNull(CachedBodyKey)
                ?: call.receiveText().also { call.attributes.put(CachedBodyKey, it) }
            val fingerprint = sha256("POST\n$path\n$bodyText")
            val trace = call.attributes.getOrNull(RequestTraceKey)

            val existing = sim.idempotencyLookup(key)
            if (existing != null) {
                if (existing.fingerprint == fingerprint) {
                    trace?.replayed = true
                    call.response.headers.append("Idempotent-Replayed", "true")
                    existing.requestId?.let { call.response.headers.append("Original-Request", it) }
                    call.respondText(existing.body, ContentType.Application.Json, HttpStatusCode.fromValue(existing.status))
                } else {
                    if (trace != null && trace.afterCommit == null && trace.fault == null) {
                        trace.outcome = RequestTrace.OUTCOME_REJECTED
                    }
                    val err = StripeException.idempotencyError(key)
                    call.respondStripe(err.toJson(), err.status)
                }
                return@intercept finish()
            }
            // First time for this key: record whatever response the handler produces.
            val requestId = trace?.requestId
            call.attributes.put(IdempotencyRecorderKey) { status, respBody ->
                sim.recordIdempotency(key, fingerprint, status, respBody, requestId)
            }
        }
    }

    // Post-commit faults replace (or withhold) the response the handler produced; every actor
    // response body is captured for the journal on its way out.
    sendPipeline.intercept(ApplicationSendPipeline.Before) { message ->
        val trace = call.attributes.getOrNull(RequestTraceKey) ?: return@intercept
        if (trace.dropped) return@intercept finish()
        val fault = trace.afterCommit
        if (fault != null && !trace.faultApplied) {
            trace.faultApplied = true
            trace.committedStatus = when (message) {
                is HttpStatusCode -> message.value
                is OutgoingContent -> message.status?.value ?: call.response.status()?.value
                else -> call.response.status()?.value
            } ?: HttpStatusCode.OK.value
            if (fault.kind == Fault.KIND_COMMIT_THEN_DROP) {
                trace.dropped = true
                call.dropConnection()
                return@intercept finish()
            }
            fault.headers.forEach { (k, v) -> call.response.headers.append(k, v) }
            val text = fault.responseBody().toString()
            trace.responseText = text
            trace.responseType = ContentType.Application.Json.toString()
            proceedWith(TextContent(text, ContentType.Application.Json, HttpStatusCode.fromValue(fault.status)))
            return@intercept
        }
        if (message is TextContent) {
            trace.responseText = message.text
            trace.responseType = message.contentType.toString()
        }
    }
}

/** Close the client's connection without writing a response byte. */
private suspend fun ApplicationCall.dropConnection() {
    var engineCall: ApplicationCall = this
    while (engineCall is RoutingApplicationCall) engineCall = engineCall.engineCall
    val netty = engineCall as? NettyApplicationCall ?: throw ConnectionDroppedException()
    val channel = netty.context.channel()
    suspendCancellableCoroutine { continuation ->
        channel.close().addListener { continuation.resumeWith(Result.success(Unit)) }
    }
}

private fun journalEntry(call: ApplicationCall, trace: RequestTrace, requestBody: String?): JournalEntry {
    val status = if (trace.dropped) null else call.response.status()?.value
    val responseBody = if (trace.dropped) null else trace.responseText
    val detail = buildJsonObject {
        trace.committedStatus?.let { put("committed_status", it) }
        if (trace.replayed) put("idempotent_replayed", true)
    }
    return JournalEntry(
        at = "",
        wall = ControlProtocol.iso(System.currentTimeMillis()),
        kind = JournalEntry.REQUEST,
        operation = trace.operation,
        method = trace.method,
        path = trace.path,
        status = status,
        outcome = trace.outcome,
        requestId = trace.requestId,
        correlationId = trace.correlationId,
        idempotencyKey = trace.idempotencyKey,
        refs = refsOf(trace.path, responseBody),
        fault = trace.fault,
        detail = detail.takeIf { it.isNotEmpty() },
        requestBody = Redaction.body(requestBody, runCatching { call.request.contentType().toString() }.getOrNull()),
        responseBody = Redaction.body(responseBody, trace.responseType),
    )
}

private val ID_PREFIXES = mapOf(
    "cus" to "customer", "pm" to "payment_method", "pi" to "payment_intent", "ch" to "charge",
    "re" to "refund", "prod" to "product", "price" to "price", "sub" to "subscription",
    "si" to "subscription_item", "in" to "invoice", "cs" to "checkout_session",
    "bps" to "billing_portal_session", "evt" to "event",
)

private val REF_FIELDS = mapOf(
    "customer" to "customer", "payment_intent" to "payment_intent", "charge" to "charge",
    "latest_charge" to "charge", "subscription" to "subscription", "invoice" to "invoice",
    "latest_invoice" to "invoice", "payment_method" to "payment_method", "product" to "product",
)

private val refsJson = Json { ignoreUnknownKeys = true }

/** Object ids a request touched: ids in its path, the object it answered with, and that object's links. */
private fun refsOf(path: String, responseText: String?): Map<String, String> {
    val refs = LinkedHashMap<String, String>()
    path.split('/').forEach { segment ->
        val prefix = segment.substringBefore('_', "")
        ID_PREFIXES[prefix]?.let { type -> if (segment.length > prefix.length + 1) refs.putIfAbsent(type, segment) }
    }
    val body = responseText?.let { runCatching { refsJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    if (body != null) {
        val id = body.text("id")
        val type = id?.substringBefore('_', "")?.let(ID_PREFIXES::get)
        if (id != null && type != null) refs[type] = id
        REF_FIELDS.forEach { (field, type) ->
            body.text(field)?.takeIf { it.contains('_') }?.let { refs.putIfAbsent(type, it) }
        }
    }
    return refs
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Constant-time token comparison.
 *
 * [MessageDigest.isEqual] short-circuits on a length mismatch, which leaks the
 * expected token's length. Comparing fixed-width SHA-256 digests removes that.
 */
private fun secureEquals(expected: String, supplied: String): Boolean {
    val a = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray())
    val b = MessageDigest.getInstance("SHA-256").digest(supplied.toByteArray())
    return MessageDigest.isEqual(a, b)
}

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

/** A 404 for any unrecognized URL: Stripe's shape on the actor, the protocol's on the controller. */
private fun Routing.unknownUrlFallback(controllerOnly: Boolean, controllerPrefix: Boolean) {
    route("{...}") {
        handle {
            val path = call.request.path()
            val message = "Unrecognized request URL (${call.request.httpMethod.value} $path)."
            val body: JsonElement = if (controllerOnly || controllerPrefix && path.startsWith("/v1/admin")) {
                controlError("not_found", message)
            } else {
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("type", "invalid_request_error")
                        put("message", message)
                    })
                }
            }
            call.respondStripe(body, HttpStatusCode.NotFound)
        }
    }
}
