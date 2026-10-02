package com.fakestripe.control

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Constants and conventions of the Siere simulator control protocol (docs/CONTROL-PROTOCOL.md). */
object ControlProtocol {
    const val VERSION = 1
    const val VERSION_HEADER = "X-Siere-Control-Protocol"
    const val TOKEN_HEADER = "X-Siere-Control-Token"
    const val CORRELATION_HEADER = "X-Siere-Correlation-Id"
    const val TOKEN_ENV = "FAKE_STRIPE_CONTROL_TOKEN"
    const val MIN_TOKEN_LENGTH = 16
    const val MAX_CORRELATION_LENGTH = 128
    const val MAX_ADVANCE_SECONDS = 31_536_000L

    /** Fault and snapshot names. */
    val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Epoch milliseconds -> `2026-10-02T10:00:00.000Z`. */
    fun iso(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

    /** Parse an ISO-8601 instant with an offset (`Z` or `+02:00`); null when malformed. */
    fun parseIso(text: String): Long? = try {
        OffsetDateTime.parse(text.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /** A usable control token: configured, not blank, at least [MIN_TOKEN_LENGTH] characters. */
    fun usableToken(token: String?): String? =
        token?.takeIf { it.isNotBlank() && it.length >= MIN_TOKEN_LENGTH }
}

/**
 * A controller error rendered as `{"error": {"code", "message", ...}}`.
 *
 * [param] is an optional extension naming the offending field (kept from the simulator's
 * earlier admin errors so existing clients can still point at the input).
 */
class ControlException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val param: String? = null,
) : RuntimeException(message) {
    fun toJson(): JsonObject = controlError(code, message, param)

    companion object {
        fun invalid(message: String, param: String? = null) =
            ControlException(HttpStatusCode.BadRequest, "invalid_request", message, param)

        fun notFound(message: String) = ControlException(HttpStatusCode.NotFound, "not_found", message)

        fun conflict(code: String, message: String) = ControlException(HttpStatusCode.Conflict, code, message)
    }
}

fun controlError(code: String, message: String, param: String? = null): JsonObject = buildJsonObject {
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
        put("type", "admin_error")
        if (param != null) put("param", param)
    })
}

/** The protocol's generic code for an HTTP status. */
fun genericCode(status: HttpStatusCode): String = when (status.value) {
    400 -> "invalid_request"
    403 -> "forbidden"
    404 -> "not_found"
    409 -> "conflict"
    else -> "internal_error"
}
