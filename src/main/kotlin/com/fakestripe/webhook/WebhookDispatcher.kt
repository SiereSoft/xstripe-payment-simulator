package com.fakestripe.webhook

import com.fakestripe.model.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Delivers signed event payloads to a configurable webhook URL, like real Stripe.
 *
 * The `Stripe-Signature` header is `t=<timestamp>,v1=<hmac>` where the HMAC-SHA256
 * is taken over `"<timestamp>.<payload>"` with the endpoint secret — the exact
 * scheme `stripe.Webhook.constructEvent` verifies. Delivery is fire-and-forget on
 * a daemon thread, so it never blocks API responses.
 */
class WebhookDispatcher(url: String?, secret: String) {
    @Volatile var url: String? = url
    @Volatile var secret: String = secret

    private val log = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    private val json = Json { encodeDefaults = true }

    /**
     * Bounded queue: a slow or hanging receiver must not let pending deliveries
     * grow without limit. When the queue is full the oldest undelivered event is
     * dropped and logged — the simulator's own state is already durable, so
     * shedding delivery load is preferable to exhausting memory.
     */
    private val pool = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "webhook-dispatch").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    fun deliver(event: Event) {
        val target = url ?: return
        val payload = json.encodeToString(JsonElement.serializer(), event.toApiJson())
        val timestamp = event.created
        val signature = "t=$timestamp,v1=${sign("$timestamp.$payload")}"
        pool.submit {
            val previousEpisodeId = MDC.get("episode_id")
            try {
                if (event.episodeId == null) MDC.remove("episode_id")
                else MDC.put("episode_id", event.episodeId)
                post(target, payload, signature)
            } finally {
                if (previousEpisodeId == null) MDC.remove("episode_id")
                else MDC.put("episode_id", previousEpisodeId)
            }
        }
    }

    /** HMAC-SHA256 hex of the signed payload, keyed by the endpoint secret. */
    fun sign(signedPayload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(signedPayload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun post(target: String, payload: String, signature: String) {
        val safe = SafeTarget.parse(target)
        if (safe == null) {
            log.warn("Webhook delivery skipped: target is not an allowed http(s) destination")
            return
        }
        try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                // A receiver must not be able to bounce delivery somewhere else
                // (e.g. a cloud metadata endpoint) via a 3xx.
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Stripe-Signature", signature)
                setRequestProperty("User-Agent", "fake-stripe/0.1.2")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode // force the request to be sent
            conn.disconnect()
        } catch (e: Exception) {
            // Log origin only: the path and query string may carry a caller secret.
            log.warn("Webhook delivery to {} failed: {}", safe, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 1_024
    }
}

/**
 * A webhook destination that passed validation, reduced to `scheme://host:port`
 * so it is safe to log (the path and query may contain caller-chosen secrets).
 */
private class SafeTarget(private val origin: String) {
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
