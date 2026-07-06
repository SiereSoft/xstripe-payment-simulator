package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests exercising the real routing + state machine through Ktor's
 * in-memory test host. Each test gets its own temp-file-backed simulator, and a
 * client that carries an API key (auth is required on /v1 business endpoints).
 */
class SimulatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun newSim() = Simulator.boot(Files.createTempFile("fs-test", ".json"), 1L)

    /** A test client that always sends a (fake) API key. */
    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    @Test
    fun `create customer echoes fields and mints a cus_ id`() = testApplication {
        application { module(newSim()) }
        val client = authed()
        val res = client.post("/v1/customers") {
            setBody(form("email" to "jane@example.com", "name" to "Jane"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["id"]!!.jsonPrimitive.content.startsWith("cus_"))
        assertEquals("customer", body["object"]!!.jsonPrimitive.content)
        assertEquals("jane@example.com", body["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create and confirm with a good card succeeds`() = testApplication {
        application { module(newSim()) }
        val client = authed()
        val res = client.post("/v1/payment_intents") {
            setBody(form("amount" to "2000", "currency" to "usd", "payment_method" to "pm_card_visa", "confirm" to "true"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val pi = json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("succeeded", pi["status"]!!.jsonPrimitive.content)
        assertEquals("2000", pi["amount_received"]!!.jsonPrimitive.content)
        assertTrue(pi["latest_charge"]!!.jsonPrimitive.content.startsWith("ch_"))
    }

    @Test
    fun `declined card yields 402 card_error and requires_payment_method`() = testApplication {
        application { module(newSim()) }
        val client = authed()
        val res = client.post("/v1/payment_intents") {
            setBody(form("amount" to "500", "currency" to "usd", "payment_method" to "pm_card_chargeDeclined", "confirm" to "true"))
        }
        assertEquals(HttpStatusCode.PaymentRequired, res.status)
        val err = json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("card_error", err["type"]!!.jsonPrimitive.content)
        assertEquals("card_declined", err["code"]!!.jsonPrimitive.content)
        val pi = err["payment_intent"]!!.jsonObject
        assertEquals("requires_payment_method", pi["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `manual capture moves requires_capture to succeeded`() = testApplication {
        application { module(newSim()) }
        val client = authed()
        val created = client.post("/v1/payment_intents") {
            setBody(form("amount" to "1500", "currency" to "usd", "capture_method" to "manual", "payment_method" to "pm_card_visa", "confirm" to "true"))
        }
        val pi = json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals("requires_capture", pi["status"]!!.jsonPrimitive.content)
        val id = pi["id"]!!.jsonPrimitive.content

        val captured = client.post("/v1/payment_intents/$id/capture")
        val after = json.parseToJsonElement(captured.bodyAsText()).jsonObject
        assertEquals("succeeded", after["status"]!!.jsonPrimitive.content)
        assertEquals("1500", after["amount_received"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown object returns Stripe-shaped 404`() = testApplication {
        application { module(newSim()) }
        val client = authed()
        val res = client.get("/v1/customers/cus_nope")
        assertEquals(HttpStatusCode.NotFound, res.status)
        val err = json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("invalid_request_error", err["type"]!!.jsonPrimitive.content)
        assertEquals("resource_missing", err["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing API key is rejected with 401 authentication_error`() = testApplication {
        application { module(newSim()) }
        // Default client (no Authorization header) must be rejected.
        val res = client.post("/v1/customers") { setBody(form("email" to "x@y.com")) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        val err = json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("authentication_error", err["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `admin endpoints do not require a key`() = testApplication {
        application { module(newSim()) }
        val res = client.get("/v1/admin/health")
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `same seed produces identical world`() {
        fun ids(seed: Long): List<String> =
            Simulator.boot(Files.createTempFile("fs-seed", ".json"), seed)
                .read { store -> store.customers.keys.toList() }
        assertEquals(ids(7L), ids(7L))
        assertTrue(ids(7L) != ids(8L))
    }
}
