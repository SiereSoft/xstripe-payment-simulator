package com.fakestripe

import com.fakestripe.store.Simulator
import com.fakestripe.webhook.WebhookDispatcher
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WebhookTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    private fun hmac(secret: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a mutation delivers a correctly signed event to the webhook URL`() = testApplication {
        val secret = "whsec_test_abc123"
        val received = LinkedBlockingQueue<Pair<String, String>>() // body, Stripe-Signature

        val receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        receiver.createContext("/hook") { exchange ->
            val sig = exchange.requestHeaders.getFirst("Stripe-Signature") ?: ""
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            received.add(body to sig)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        receiver.start()
        val port = receiver.address.port

        try {
            val dispatcher = WebhookDispatcher("http://127.0.0.1:$port/hook", secret)
            application { module(Simulator.boot(Files.createTempFile("fs-hook", ".json"), 1L, dispatcher)) }
            val client = authed()

            client.post("/v1/customers") { setBody(form("email" to "hook@test.com")) }

            val delivery = received.poll(5, TimeUnit.SECONDS) ?: fail("no webhook was delivered")
            val (body, signatureHeader) = delivery

            // The payload is a real event of the right type
            val event = json.parseToJsonElement(body).jsonObject
            assertEquals("event", event["object"]!!.jsonPrimitive.content)
            assertEquals("customer.created", event["type"]!!.jsonPrimitive.content)

            // Verify the Stripe-Signature: t=..,v1=hmac(secret, "t.body")
            val parts = signatureHeader.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
            val timestamp = parts["t"]!!
            val expected = hmac(secret, "$timestamp.$body")
            assertEquals(expected, parts["v1"])
        } finally {
            receiver.stop(0)
        }
    }

    @Test
    fun `events are recorded and retrievable via the API`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-events", ".json"), 1L)) }
        val client = authed()

        client.post("/v1/payment_intents") {
            setBody(form("amount" to "2000", "currency" to "usd", "payment_method" to "pm_card_visa", "confirm" to "true"))
        }
        val events = json.parseToJsonElement(
            client.get("/v1/events?type=payment_intent.succeeded").bodyAsText(),
        ).jsonObject
        assertTrue(events["data"]!!.jsonArray.isNotEmpty())
    }
}
