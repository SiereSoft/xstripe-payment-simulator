package com.fakestripe.webhook

import com.fakestripe.BuildInfo
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

/**
 * The webhook endpoint's transport and signing secret.
 *
 * The `Stripe-Signature` header is `t=<timestamp>,v1=<hmac>` where the HMAC-SHA256 is taken over
 * `"<timestamp>.<payload>"` with the endpoint secret — the exact scheme
 * `stripe.Webhook.constructEvent` verifies. Like Stripe, every attempt is signed with the time of
 * that attempt. Which events go out, when, and how often they are retried is decided by the
 * persisted outbox (see [WebhookJob], [RetrySchedule]); this class only performs one attempt.
 *
 * [url] is the endpoint configured from the environment at start-up; the world's configuration
 * holds the live endpoint. The secret is never persisted or echoed.
 */
class WebhookDispatcher(url: String?, secret: String) {
    @Volatile var url: String? = url
    @Volatile var secret: String = secret

    private val log = LoggerFactory.getLogger(WebhookDispatcher::class.java)

    data class AttemptResult(val status: Int?, val error: String?) {
        val delivered: Boolean get() = status != null && status in 200..299
    }

    /** HMAC-SHA256 hex of the signed payload, keyed by the endpoint secret. */
    fun sign(signedPayload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(signedPayload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun signatureHeader(timestampSeconds: Long, payload: String): String =
        "t=$timestampSeconds,v1=${sign("$timestampSeconds.$payload")}"

    /** One delivery attempt. Any answer outside 2xx — redirects included — is a failure, as at Stripe. */
    fun attempt(target: String?, payload: String, timestampSeconds: Long): AttemptResult {
        if (target == null) return AttemptResult(null, "no_endpoint_configured")
        val safe = SafeTarget.parse(target)
        if (safe == null) {
            log.warn("Webhook delivery refused: target is not an allowed http(s) destination")
            return AttemptResult(null, "target_not_allowed")
        }
        return try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                // A receiver must not be able to bounce delivery somewhere else
                // (e.g. a cloud metadata endpoint) via a 3xx.
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Stripe-Signature", signatureHeader(timestampSeconds, payload))
                setRequestProperty("User-Agent", BuildInfo.PRODUCT)
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            runCatching { (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            conn.disconnect()
            AttemptResult(status, if (status in 200..299) null else "http_$status")
        } catch (e: Exception) {
            // Log origin only: the path and query string may carry a caller secret.
            log.warn("Webhook delivery to {} failed: {}", safe, e.javaClass.simpleName)
            AttemptResult(null, e.javaClass.simpleName)
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 3_000

        /** The path of a target URL for the journal (never its query, which may hold secrets). */
        fun pathOf(target: String?): String? =
            target?.let { runCatching { URI(it).rawPath?.ifEmpty { "/" } }.getOrNull() }
    }
}

/**
 * A webhook destination that passed validation, reduced to `scheme://host:port`
 * so it is safe to log (the path and query may contain caller-chosen secrets).
 */
internal class SafeTarget(private val origin: String) {
    override fun toString(): String = origin

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        /**
         * Returns a loggable origin when [target] is an acceptable delivery
         * destination, or null when it must be refused.
         *
         * Loopback is explicitly allowed — a local receiver is the normal case for
         * this simulator. What is refused is everything that only makes sense as an
         * SSRF pivot: non-http schemes, embedded credentials, and link-local,
         * multicast, or wildcard addresses (notably 169.254.169.254).
         */
        fun parse(target: String): SafeTarget? {
            val uri = runCatching { URI(target) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in ALLOWED_SCHEMES) return null
            if (uri.userInfo != null) return null
            val host = uri.host ?: return null

            val resolved = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (resolved != null &&
                (resolved.isLinkLocalAddress || resolved.isMulticastAddress || resolved.isAnyLocalAddress)
            ) {
                return null
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return SafeTarget("$scheme://$host$port")
        }
    }
}
