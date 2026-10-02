package com.fakestripe.control

import com.fakestripe.store.FaultInjectionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What a fault matches: an operation identifier, or an HTTP method plus a path pattern. */
@Serializable
data class FaultMatch(
    val operation: String? = null,
    val method: String? = null,
    val path: String? = null,
) {
    fun matches(requestOperation: String?, requestMethod: String, requestPath: String): Boolean =
        if (operation != null) {
            operation == requestOperation
        } else {
            method.equals(requestMethod, ignoreCase = true) && PathPattern.matches(path!!, requestPath)
        }

    fun toJson(): JsonObject = buildJsonObject {
        if (operation != null) {
            put("operation", operation)
        } else {
            put("method", method)
            put("path", path)
        }
    }
}

/**
 * A named fault (protocol section 5). Faults are part of the world: persisted with it, cleared by
 * reset and carried by snapshots. [skipped] counts the matching requests already let through
 * while [skip] was being honoured.
 */
@Serializable
data class Fault(
    val name: String,
    val match: FaultMatch,
    val kind: String,
    val status: Int = DEFAULT_STATUS,
    val body: JsonElement? = null,
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
    val skip: Int = 0,
    val count: Int = 1,
    var remaining: Int = count,
    var injected: Int = 0,
    var skipped: Int = 0,
    /** `controller` for faults installed through the API, `scenario` for ones a reset seeded. */
    val origin: String = ORIGIN_CONTROLLER,
) {
    /** The body sent for `error` and `commit_then_error`: the caller's, or a Stripe-shaped default. */
    fun responseBody(): JsonElement = body ?: buildJsonObject {
        put("error", buildJsonObject {
            put("type", "api_error")
            put("message", "An error occurred while processing your request. (injected fault $name)")
        })
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("match", match.toJson())
        put("kind", kind)
        put("status", status)
        put("body", body ?: JsonNull)
        put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
        put("delay_ms", delayMs)
        put("skip", skip)
        put("count", count)
        put("remaining", remaining)
        put("injected", injected)
        put("skipped", skipped)
        put("origin", origin)
    }

    /** The pre-protocol `fault_injection` view of a scenario-seeded response-loss fault. */
    fun toLegacy(): FaultInjectionState = FaultInjectionState(
        id = LEGACY_IDS[name] ?: name,
        operation = match.operation ?: "${match.method} ${match.path}",
        phase = if (kind == KIND_COMMIT_THEN_ERROR || kind == KIND_COMMIT_THEN_DROP) "after_commit" else "before_commit",
        responseStatus = status,
        responseCode = ((body as? JsonObject)?.get("error") as? JsonObject)?.get("code")
            ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: kind,
        remaining = remaining,
        injectedCount = injected,
    )

    companion object {
        const val KIND_ERROR = "error"
        const val KIND_COMMIT_THEN_ERROR = "commit_then_error"
        const val KIND_COMMIT_THEN_DROP = "commit_then_drop"
        const val KIND_DROP_BEFORE = "drop_before"
        const val KIND_LATENCY = "latency"
        val KINDS = setOf(KIND_ERROR, KIND_COMMIT_THEN_ERROR, KIND_COMMIT_THEN_DROP, KIND_DROP_BEFORE, KIND_LATENCY)

        const val DEFAULT_STATUS = 500
        const val MAX_DELAY_MS = 60_000L
        const val MAX_COUNT = 1000
        const val ORIGIN_CONTROLLER = "controller"
        const val ORIGIN_SCENARIO = "scenario"

        /** The scenario fault `duplicate_payments_refund_response_loss` installs. */
        const val REFUND_RESPONSE_LOSS = "refund-response-loss"
        private val LEGACY_IDS = mapOf(REFUND_RESPONSE_LOSS to "refund_response_loss_once")

        /** Headers the HTTP engine owns; a fault may not set them. */
        private val FORBIDDEN_HEADERS = setOf(
            "content-length", "content-type", "transfer-encoding", "connection", "keep-alive",
            "upgrade", "trailer", "te", "proxy-connection",
        )

        /** The scenario-seeded post-commit response loss on refund creation. */
        fun refundResponseLoss(): Fault = Fault(
            name = REFUND_RESPONSE_LOSS,
            match = FaultMatch(operation = "refund.create"),
            kind = KIND_COMMIT_THEN_ERROR,
            status = 503,
            body = buildJsonObject {
                put("error", buildJsonObject {
                    put("type", "api_error")
                    put("code", "injected_response_loss")
                    put("message", "The operation committed, but its success response was lost.")
                })
            },
            headers = mapOf("Stripe-Should-Retry" to "true"),
            count = 1,
            origin = ORIGIN_SCENARIO,
        )

        /** Validate a `POST /v1/admin/faults` body into a fault, or throw a 400. */
        fun parse(json: JsonObject): Fault {
            val name = json.string("name") ?: throw ControlException.invalid("`name` is required.", "name")
            if (!ControlProtocol.NAME_PATTERN.matches(name)) {
                throw ControlException.invalid("`name` must match [A-Za-z0-9._-]{1,64}.", "name")
            }
            val matchJson = json["match"] as? JsonObject
                ?: throw ControlException.invalid("`match` must be an object.", "match")
            val match = parseMatch(matchJson)
            val kind = json.string("kind") ?: throw ControlException.invalid("`kind` is required.", "kind")
            if (kind !in KINDS) {
                throw ControlException.invalid(
                    "Unsupported fault kind '$kind'. Supported kinds: ${KINDS.sorted().joinToString()}.",
                    "kind",
                )
            }
            val status = json.int("status") ?: DEFAULT_STATUS
            if (status !in 400..599) throw ControlException.invalid("`status` must be 400..599.", "status")
            val delayMs = json.long("delay_ms") ?: 0L
            if (delayMs !in 0..MAX_DELAY_MS) throw ControlException.invalid("`delay_ms` must be 0..60000.", "delay_ms")
            val skip = json.int("skip") ?: 0
            if (skip < 0) throw ControlException.invalid("`skip` must not be negative.", "skip")
            val count = json.int("count") ?: 1
            if (count !in 1..MAX_COUNT) throw ControlException.invalid("`count` must be 1..1000.", "count")
            val headers = when (val raw = json["headers"]) {
                null, JsonNull -> emptyMap()
                is JsonObject -> raw.mapValues { (key, value) ->
                    if (key.lowercase() in FORBIDDEN_HEADERS || key.isBlank() || key.any { it.isWhitespace() }) {
                        throw ControlException.invalid("Header '$key' cannot be injected.", "headers")
                    }
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw ControlException.invalid("Header values must be strings.", "headers")
                }
                else -> throw ControlException.invalid("`headers` must be an object of strings.", "headers")
            }
            val body = json["body"]?.takeUnless { it is JsonNull }
            return Fault(
                name = name,
                match = match,
                kind = kind,
                status = status,
                body = body,
                headers = headers,
                delayMs = delayMs,
                skip = skip,
                count = count,
                remaining = count,
            )
        }

        private fun parseMatch(json: JsonObject): FaultMatch {
            val operation = json.string("operation")
            val method = json.string("method")
            val path = json.string("path")
            if (operation != null) {
                if (method != null || path != null) {
                    throw ControlException.invalid("`match` takes either `operation` or `method` + `path`.", "match")
                }
                if (operation !in Operations.identifiers) {
                    throw ControlException.invalid("Unknown operation '$operation'. See docs/AGENT-GUIDE.md.", "match")
                }
                return FaultMatch(operation = operation)
            }
            if (method == null || path == null) {
                throw ControlException.invalid("`match` needs `operation`, or both `method` and `path`.", "match")
            }
            if (method.uppercase() !in setOf("GET", "POST", "DELETE", "PUT", "PATCH")) {
                throw ControlException.invalid("Unsupported method '$method'.", "match")
            }
            if (!PathPattern.isValid(path)) {
                throw ControlException.invalid("`path` must start with '/' and may end with '**' only.", "match")
            }
            return FaultMatch(method = method.uppercase(), path = path)
        }
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.intOrNull ?: throw ControlException.invalid("`$key` must be an integer.", key)
    else -> throw ControlException.invalid("`$key` must be an integer.", key)
}

internal fun JsonObject.long(key: String): Long? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.longOrNull ?: throw ControlException.invalid("`$key` must be an integer.", key)
    else -> throw ControlException.invalid("`$key` must be an integer.", key)
}

internal fun JsonObject.bool(key: String): Boolean? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> when (v.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> throw ControlException.invalid("`$key` must be a boolean.", key)
    }
    else -> throw ControlException.invalid("`$key` must be a boolean.", key)
}
