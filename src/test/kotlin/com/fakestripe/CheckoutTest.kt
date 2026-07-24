package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    /** Redirects are the thing under test here, so never follow them. */
    private fun ApplicationTestBuilder.authed() = createClient {
        followRedirects = false
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    private suspend fun HttpResponse.obj(json: Json): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun events(client: io.ktor.client.HttpClient, type: String) =
        json.parseToJsonElement(client.get("/v1/events?type=$type&limit=100").bodyAsText())
            .jsonObject["data"]!!.jsonArray

    @Test
    fun `checkout session pays, emits checkout_session_completed, and never bills twice`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-checkout", ".json"), 1L)) }
        val client = authed()

        val product = client.post("/v1/products") { setBody(form("name" to "Daily Digest")) }
            .obj(json)["id"]!!.jsonPrimitive.content
        val price = client.post("/v1/prices") {
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "900", "recurring[interval]" to "month"))
        }.obj(json)["id"]!!.jsonPrimitive.content

        val subscriberId = "8f14e45f-ceea-467a-9c1e-1c0d3a2b4e77"
        val session = client.post("/v1/checkout/sessions") {
            header("Idempotency-Key", "checkout:$subscriberId")
            setBody(
                form(
                    "mode" to "subscription",
                    "line_items[0][price]" to price,
                    "line_items[0][quantity]" to "1",
                    "customer_email" to "reader@example.com",
                    "client_reference_id" to subscriberId,
                    "success_url" to "https://example.com/account?checkout=done",
                    "cancel_url" to "https://example.com/upgrade?checkout=cancelled",
                ),
            )
        }.obj(json)

        val sessionId = session["id"]!!.jsonPrimitive.content
        val url = session["url"]!!.jsonPrimitive.content
        assertEquals("open", session["status"]!!.jsonPrimitive.content)
        assertEquals("unpaid", session["payment_status"]!!.jsonPrimitive.content)
        assertEquals("900", session["amount_total"]!!.jsonPrimitive.content)
        assertTrue(url.endsWith("/checkout/$sessionId"), "hosted url should point at the simulator: $url")

        // The hosted page renders the amount and the email.
        val pageBody = client.get("/checkout/$sessionId").bodyAsText()
        assertTrue(pageBody.contains("$9.00"), "page should show the amount")
        assertTrue(pageBody.contains("reader@example.com"), "page should show the email")

        // A declining card grants nothing: no completion event, session still payable.
        val declined = client.post("/checkout/$sessionId/pay") {
            setBody(form("card_number" to "4000000000009995"))
        }
        assertEquals(HttpStatusCode.SeeOther, declined.status)
        assertTrue(declined.headers[HttpHeaders.Location]!!.startsWith("/checkout/$sessionId?error="))
        assertTrue(events(client, "checkout.session.completed").isEmpty())
        assertEquals("open", client.get("/v1/checkout/sessions/$sessionId").obj(json)["status"]!!.jsonPrimitive.content)

        // A good card completes it and redirects to success_url.
        val paid = client.post("/checkout/$sessionId/pay") { setBody(form("card_number" to "4242424242424242")) }
        assertEquals(HttpStatusCode.SeeOther, paid.status)
        assertEquals("https://example.com/account?checkout=done", paid.headers[HttpHeaders.Location])

        val completed = events(client, "checkout.session.completed")
        assertEquals(1, completed.size)
        val payload = completed[0].jsonObject["data"]!!.jsonObject["object"]!!.jsonObject
        assertEquals(subscriberId, payload["client_reference_id"]!!.jsonPrimitive.content)
        assertEquals("complete", payload["status"]!!.jsonPrimitive.content)
        assertEquals("paid", payload["payment_status"]!!.jsonPrimitive.content)
        val customerId = payload["customer"]!!.jsonPrimitive.content
        val subscriptionId = payload["subscription"]!!.jsonPrimitive.content
        assertTrue(customerId.startsWith("cus_"))
        assertTrue(subscriptionId.startsWith("sub_"))

        // The subscription really exists and really got paid.
        val sub = client.get("/v1/subscriptions/$subscriptionId").obj(json)
        assertEquals("active", sub["status"]!!.jsonPrimitive.content)
        assertEquals(customerId, sub["customer"]!!.jsonPrimitive.content)
        val invoice = client.get("/v1/invoices/${sub["latest_invoice"]!!.jsonPrimitive.content}").obj(json)
        assertEquals("paid", invoice["status"]!!.jsonPrimitive.content)

        // Pressing Pay again on a completed session redirects but bills nothing more.
        val replay = client.post("/checkout/$sessionId/pay") { setBody(form("card_number" to "4242424242424242")) }
        assertEquals("https://example.com/account?checkout=done", replay.headers[HttpHeaders.Location])
        assertEquals(1, events(client, "checkout.session.completed").size)
        assertEquals(
            1,
            json.parseToJsonElement(client.get("/v1/subscriptions?customer=$customerId").bodyAsText())
                .jsonObject["data"]!!.jsonArray.size,
        )

        // A completed session no longer advertises a payable URL.
        val fetched = client.get("/v1/checkout/sessions/$sessionId").obj(json)
        assertNull(fetched["url"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `cancel redirects to cancel_url and creates nothing`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-checkout-cancel", ".json"), 1L)) }
        val client = authed()

        val product = client.post("/v1/products") { setBody(form("name" to "Plan")) }.obj(json)["id"]!!.jsonPrimitive.content
        val price = client.post("/v1/prices") {
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "500", "recurring[interval]" to "month"))
        }.obj(json)["id"]!!.jsonPrimitive.content
        val sessionId = client.post("/v1/checkout/sessions") {
            setBody(
                form(
                    "mode" to "subscription",
                    "line_items[0][price]" to price,
                    "success_url" to "https://example.com/done",
                    "cancel_url" to "https://example.com/cancelled",
                ),
            )
        }.obj(json)["id"]!!.jsonPrimitive.content

        val res = client.post("/checkout/$sessionId/cancel")
        assertEquals(HttpStatusCode.SeeOther, res.status)
        assertEquals("https://example.com/cancelled", res.headers[HttpHeaders.Location])
        assertTrue(events(client, "checkout.session.completed").isEmpty())
        assertTrue(
            json.parseToJsonElement(client.get("/v1/subscriptions").bodyAsText())
                .jsonObject["data"]!!.jsonArray.isEmpty(),
        )
    }

    @Test
    fun `repeating the idempotency key replays the same session`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-checkout-idem", ".json"), 1L)) }
        val client = authed()

        val product = client.post("/v1/products") { setBody(form("name" to "Plan")) }.obj(json)["id"]!!.jsonPrimitive.content
        val price = client.post("/v1/prices") {
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "500", "recurring[interval]" to "month"))
        }.obj(json)["id"]!!.jsonPrimitive.content
        val body = form(
            "mode" to "subscription",
            "line_items[0][price]" to price,
            "success_url" to "https://example.com/done",
        )

        val first = client.post("/v1/checkout/sessions") { header("Idempotency-Key", "checkout:abc"); setBody(body) }.obj(json)
        val second = client.post("/v1/checkout/sessions") { header("Idempotency-Key", "checkout:abc"); setBody(body) }.obj(json)
        assertEquals(first["id"], second["id"])
    }

    @Test
    fun `subscription mode rejects a one-time price`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-checkout-mode", ".json"), 1L)) }
        val client = authed()

        val product = client.post("/v1/products") { setBody(form("name" to "Mug")) }.obj(json)["id"]!!.jsonPrimitive.content
        val price = client.post("/v1/prices") {
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "1200"))
        }.obj(json)["id"]!!.jsonPrimitive.content

        val res = client.post("/v1/checkout/sessions") {
            setBody(form("mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://example.com/done"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("invalid_request_error", res.obj(json)["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
