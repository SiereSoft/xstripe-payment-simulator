package com.fakestripe.error

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stripe error types. The `type` field is the coarse category the SDKs switch on
 * to decide which exception class to raise (CardError, InvalidRequestError, ...).
 */
enum class StripeErrorType(val wire: String) {
    CARD_ERROR("card_error"),
    INVALID_REQUEST_ERROR("invalid_request_error"),
    IDEMPOTENCY_ERROR("idempotency_error"),
    API_ERROR("api_error"),
    AUTHENTICATION_ERROR("authentication_error"),
}

/**
 * An error rendered in Stripe's exact wire shape:
 *
 * ```
 * { "error": { "type": ..., "code": ..., "message": ..., "param": ..., "doc_url": ... } }
 * ```
 *
 * Thrown from anywhere in a handler; the StatusPages plugin renders it with the
 * right HTTP status.
 */
class StripeException(
    val status: HttpStatusCode,
    val type: StripeErrorType,
    override val message: String,
    val code: String? = null,
    val declineCode: String? = null,
    val param: String? = null,
    val docUrl: String? = null,
    /** For card errors raised during confirm, Stripe embeds the PaymentIntent. */
    val paymentIntent: JsonObject? = null,
    val paymentMethod: JsonObject? = null,
) : RuntimeException(message) {

    fun toJson(): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("type", type.wire)
            if (code != null) put("code", code)
            if (declineCode != null) put("decline_code", declineCode)
            put("message", message)
            if (param != null) put("param", param)
            if (docUrl != null) put("doc_url", docUrl)
            if (paymentIntent != null) put("payment_intent", paymentIntent)
            if (paymentMethod != null) put("payment_method", paymentMethod)
        })
    }

    companion object {
        /** No such object with that id (Stripe returns 404 + invalid_request_error). */
        fun resourceMissing(resource: String, id: String, param: String = "id") = StripeException(
            status = HttpStatusCode.NotFound,
            type = StripeErrorType.INVALID_REQUEST_ERROR,
            code = "resource_missing",
            message = "No such $resource: '$id'",
            param = param,
        )

        fun missingParam(param: String) = StripeException(
            status = HttpStatusCode.BadRequest,
            type = StripeErrorType.INVALID_REQUEST_ERROR,
            code = "parameter_missing",
            message = "Missing required param: $param.",
            param = param,
        )

        fun invalidRequest(message: String, param: String? = null, code: String? = null) = StripeException(
            status = HttpStatusCode.BadRequest,
            type = StripeErrorType.INVALID_REQUEST_ERROR,
            code = code,
            message = message,
            param = param,
        )

        /** No/blank API key (Stripe returns 401 + authentication_error). */
        fun authenticationError() = StripeException(
            status = HttpStatusCode.Unauthorized,
            type = StripeErrorType.AUTHENTICATION_ERROR,
            message = "You did not provide an API key. You need to provide your API key in the " +
                "Authorization header, using Bearer auth (e.g. 'Authorization: Bearer sk_test_...').",
        )

        /**
         * A live-mode key was sent to the simulator. Real Stripe would accept it;
         * we refuse loudly so a misconfigured client cannot believe it is talking
         * to Stripe — and so a live secret is never recorded in simulator state.
         */
        fun liveKeyRejected() = StripeException(
            status = HttpStatusCode.Unauthorized,
            type = StripeErrorType.AUTHENTICATION_ERROR,
            message = "A live-mode API key was supplied to a payment simulator. This server is not " +
                "Stripe and processes no real payments. Use a test-mode key (e.g. 'sk_test_123'). " +
                "Rotate the key you just sent if it was a real one.",
        )

        /** Same Idempotency-Key reused with a different request body. */
        fun idempotencyError(key: String) = StripeException(
            status = HttpStatusCode.BadRequest,
            type = StripeErrorType.IDEMPOTENCY_ERROR,
            message = "Keys for idempotent requests can only be used with the same parameters they " +
                "were first used with. Try using a key other than '$key' if you meant to execute a " +
                "different request.",
        )
    }
}
