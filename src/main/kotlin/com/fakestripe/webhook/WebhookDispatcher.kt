package com.fakestripe.webhook

import com.fakestripe.model.Event
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "webhook-dispatch").apply { isDaemon = true }
    }

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
        try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Stripe-Signature", signature)
                setRequestProperty("User-Agent", "fake-stripe/0.1.0")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode // force the request to be sent
            conn.disconnect()
        } catch (e: Exception) {
            log.warn("Webhook delivery to {} failed: {}", target, e.message)
        }
    }
}
