package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class StateExportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun newSim() = Simulator.boot(Files.createTempFile("fs-state", ".json"), 1L)

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    private suspend fun io.ktor.client.HttpClient.exportState(): JsonObject {
        val response = get("/v1/admin/state") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    @Test
    fun `state export requires the configured controller token`() = testApplication {
        application { module(newSim(), controlToken = "controller-test-token") }

        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/state").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/admin/state") {
                header("X-Siere-Control-Token", "wrong-token")
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/admin/state") {
                header("X-Siere-Control-Token", "controller-test-token")
            }.also {
                assertEquals("no-store", it.headers[HttpHeaders.CacheControl])
            }.status,
        )
    }

    @Test
    fun `state export is disabled when no controller token is configured`() = testApplication {
        application { module(newSim()) }
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/admin/state").status)
    }

    @Test
    fun `state export is complete redacted and read only`() = testApplication {
        application { module(newSim(), controlToken = "controller-test-token") }
        val api = authed()

        val before = api.exportState()
        val initialRevision = before["state_revision"]!!.jsonPrimitive.long
        val requiredCollections = setOf(
            "customers",
            "payment_methods",
            "payment_intents",
            "charges",
            "refunds",
            "products",
            "prices",
            "subscriptions",
            "invoices",
            "checkout_sessions",
            "billing_portal_sessions",
            "events",
            "idempotency_records",
        )
        requiredCollections.forEach { key ->
            assertNotNull(before[key], "Missing state collection: $key")
            before[key]!!.jsonArray
        }

        val rawCard = "4242424242424242"
        val rawIdempotencyKey = "private-idempotency-key"
        val paymentMethodResponse = api.post("/v1/payment_methods") {
            header("Idempotency-Key", rawIdempotencyKey)
            setBody(form("type" to "card", "card[number]" to rawCard))
        }
        assertEquals(HttpStatusCode.OK, paymentMethodResponse.status)
        val paymentMethodId = json.parseToJsonElement(paymentMethodResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val intentResponse = api.post("/v1/payment_intents") {
            header("Idempotency-Key", "intent-idempotency-key")
            setBody(
                form(
                    "amount" to "2000",
                    "currency" to "usd",
                    "payment_method" to paymentMethodId,
                    "confirm" to "true",
                ),
            )
        }
        val intent = json.parseToJsonElement(intentResponse.bodyAsText()).jsonObject
        val clientSecret = intent["client_secret"]!!.jsonPrimitive.content

        val after = api.exportState()
        val afterText = after.toString()
        val afterRevision = after["state_revision"]!!.jsonPrimitive.long
        assertTrue(afterRevision > initialRevision)
        assertFalse(afterText.contains(rawCard))
        assertFalse(afterText.contains(clientSecret))
        assertFalse(afterText.contains(rawIdempotencyKey))
        assertFalse(afterText.contains("intent-idempotency-key"))
        assertEquals(2, after["idempotency_records"]!!.jsonArray.size)
        assertTrue(
            after["payment_methods"]!!.jsonArray.all { "number" !in it.jsonObject },
        )
        assertTrue(
            after["payment_intents"]!!.jsonArray.all { "clientSecret" !in it.jsonObject },
        )

        val repeatedRead = api.exportState()
        assertEquals(afterRevision, repeatedRead["state_revision"]!!.jsonPrimitive.long)
        assertEquals(after, repeatedRead)
    }

    @Test
    fun `state revision survives restart and advances on reset`() {
        val path = Files.createTempFile("fs-state-restart", ".json")
        val simulator = Simulator.boot(path, 1L)
        simulator.write { store -> store.customers.values.first().description = "changed" }
        val writtenRevision = simulator.read { it.revision }

        val restarted = Simulator.boot(path, 99L)
        assertEquals(writtenRevision, restarted.read { it.revision })

        restarted.reset(2L)
        assertTrue(restarted.read { it.revision } > writtenRevision)
    }
}
