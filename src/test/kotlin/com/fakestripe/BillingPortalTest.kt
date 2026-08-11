package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingPortalTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val controlToken = "controller-test-token"

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        followRedirects = false
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    private suspend fun HttpResponse.obj(json: Json): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun events(client: HttpClient, type: String) =
        json.parseToJsonElement(client.get("/v1/events?type=$type&limit=100").bodyAsText())
            .jsonObject["data"]!!.jsonArray

    /** A customer on a $9/month plan, paid with the given card. */
    private suspend fun subscribe(client: HttpClient, card: String = "pm_card_visa"): Pair<String, String> {
        val customer = client.post("/v1/customers") { setBody(form("email" to "portal@example.com")) }
            .obj(json)["id"]!!.jsonPrimitive.content
        val pm = client.post("/v1/payment_methods/$card/attach") { setBody(form("customer" to customer)) }
            .obj(json)["id"]!!.jsonPrimitive.content
        client.post("/v1/customers/$customer") {
            setBody(form("invoice_settings[default_payment_method]" to pm))
        }
        val product = client.post("/v1/products") { setBody(form("name" to "Daily Digest")) }
            .obj(json)["id"]!!.jsonPrimitive.content
        val price = client.post("/v1/prices") {
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "900", "recurring[interval]" to "month"))
        }.obj(json)["id"]!!.jsonPrimitive.content
        val sub = client.post("/v1/subscriptions") {
            setBody(form("customer" to customer, "items[0][price]" to price))
        }.obj(json)["id"]!!.jsonPrimitive.content
        return customer to sub
    }

    @Test
    fun `portal cancels at period end and can undo it`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-portal", ".json"), 1L)) }
        val client = authed()
        val (customer, sub) = subscribe(client)

        val session = client.post("/v1/billing_portal/sessions") {
            setBody(form("customer" to customer, "return_url" to "https://example.com/account"))
        }.obj(json)
        val sessionId = session["id"]!!.jsonPrimitive.content
        val url = session["url"]!!.jsonPrimitive.content
        assertEquals("billing_portal.session", session["object"]!!.jsonPrimitive.content)
        assertTrue(url.endsWith("/billing_portal/$sessionId"), "hosted url should point at the simulator: $url")

        val portal = client.get("/billing_portal/$sessionId").bodyAsText()
        assertTrue(portal.contains("Daily Digest"), "portal should list the plan")
        assertTrue(portal.contains("Cancel plan"))

        // Cancel -> cancel_at_period_end, still active until the period runs out.
        val cancelled = client.post("/billing_portal/$sessionId/subscriptions/$sub/cancel")
        assertEquals(HttpStatusCode.SeeOther, cancelled.status)
        val afterCancel = client.get("/v1/subscriptions/$sub").obj(json)
        assertEquals("true", afterCancel["cancel_at_period_end"]!!.jsonPrimitive.content)
        assertEquals("active", afterCancel["status"]!!.jsonPrimitive.content)

        val updates = events(client, "customer.subscription.updated")
        assertTrue(updates.isNotEmpty())
        val latest = updates[0].jsonObject["data"]!!.jsonObject["object"]!!.jsonObject
        assertEquals("true", latest["cancel_at_period_end"]!!.jsonPrimitive.content)
        assertTrue(latest["current_period_end"]!!.jsonPrimitive.content.toLong() > 0)

        // Resume puts it back.
        client.post("/billing_portal/$sessionId/subscriptions/$sub/resume")
        assertEquals(
            "false",
            client.get("/v1/subscriptions/$sub").obj(json)["cancel_at_period_end"]!!.jsonPrimitive.content,
        )

        // A subscription belonging to someone else is not cancellable from this session.
        val (_, otherSub) = subscribe(client)
        client.post("/billing_portal/$sessionId/subscriptions/$otherSub/cancel")
        assertEquals(
            "false",
            client.get("/v1/subscriptions/$otherSub").obj(json)["cancel_at_period_end"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `deleting a customer immediately cancels their subscriptions`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-erase", ".json"), 1L)) }
        val client = authed()
        val (customer, sub) = subscribe(client)

        val deleted = client.delete("/v1/customers/$customer").obj(json)
        assertEquals("true", deleted["deleted"]!!.jsonPrimitive.content)

        val after = client.get("/v1/subscriptions/$sub").obj(json)
        assertEquals("canceled", after["status"]!!.jsonPrimitive.content)
        assertTrue(after["ended_at"]!!.jsonPrimitive.content.toLong() > 0)

        val deletedEvents = events(client, "customer.subscription.deleted")
        assertEquals(1, deletedEvents.size)
        assertEquals(
            sub,
            deletedEvents[0].jsonObject["data"]!!.jsonObject["object"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a renewal on a declining card goes past_due and emits invoice_payment_failed`() = testApplication {
        application {
            module(
                Simulator.boot(Files.createTempFile("fs-dunning", ".json"), 1L),
                controlToken = controlToken,
            )
        }
        val client = authed()
        val (customer, sub) = subscribe(client)

        // The card on file expires/declines before the renewal lands.
        val badPm = client.post("/v1/payment_methods/pm_card_chargeDeclined/attach") {
            setBody(form("customer" to customer))
        }.obj(json)["id"]!!.jsonPrimitive.content
        client.post("/v1/subscriptions/$sub") { setBody(form("default_payment_method" to badPm)) }

        val renewed = client.post("/v1/admin/subscriptions/$sub/renew") {
            header("X-Siere-Control-Token", controlToken)
        }.obj(json)
        assertEquals("past_due", renewed["status"]!!.jsonPrimitive.content)

        val failed = events(client, "invoice.payment_failed")
        assertEquals(1, failed.size)
        val invoice = failed[0].jsonObject["data"]!!.jsonObject["object"]!!.jsonObject
        assertEquals(customer, invoice["customer"]!!.jsonPrimitive.content)
        assertEquals("open", invoice["status"]!!.jsonPrimitive.content)
        assertEquals("false", invoice["paid"]!!.jsonPrimitive.content)
        assertEquals("subscription_cycle", invoice["billing_reason"]!!.jsonPrimitive.content)

        // Dunning, not termination: the subscription is still there.
        assertEquals("past_due", client.get("/v1/subscriptions/$sub").obj(json)["status"]!!.jsonPrimitive.content)

        // Selecting a working saved card and paying the open renewal restores service.
        val replacement = client.post("/v1/payment_methods/pm_card_visa/attach") {
            setBody(form("customer" to customer))
        }.obj(json)["id"]!!.jsonPrimitive.content
        client.post("/v1/subscriptions/$sub") {
            setBody(form("default_payment_method" to replacement))
        }
        val invoiceId = renewed["latest_invoice"]!!.jsonPrimitive.content
        val recoveredInvoice = client.post("/v1/invoices/$invoiceId/pay") {
            setBody(form("payment_method" to replacement))
        }.obj(json)

        assertEquals("paid", recoveredInvoice["status"]!!.jsonPrimitive.content)
        assertEquals("true", recoveredInvoice["paid"]!!.jsonPrimitive.content)
        assertEquals(
            "active",
            client.get("/v1/subscriptions/$sub").obj(json)["status"]!!.jsonPrimitive.content,
        )
        val restored = events(client, "customer.subscription.updated")
        assertTrue(
            restored.any {
                it.jsonObject["data"]!!.jsonObject["object"]!!
                    .jsonObject["status"]!!.jsonPrimitive.content == "active"
            },
        )
    }

    @Test
    fun `a renewal on a good card bills the next period`() = testApplication {
        application {
            module(
                Simulator.boot(Files.createTempFile("fs-renew", ".json"), 1L),
                controlToken = controlToken,
            )
        }
        val client = authed()
        val (_, sub) = subscribe(client)

        val before = client.get("/v1/subscriptions/$sub").obj(json)["current_period_end"]!!.jsonPrimitive.content.toLong()
        val renewed = client.post("/v1/admin/subscriptions/$sub/renew") {
            header("X-Siere-Control-Token", controlToken)
        }.obj(json)
        assertEquals("active", renewed["status"]!!.jsonPrimitive.content)
        assertTrue(renewed["current_period_end"]!!.jsonPrimitive.content.toLong() > before)

        val invoice = client.get("/v1/invoices/${renewed["latest_invoice"]!!.jsonPrimitive.content}").obj(json)
        assertEquals("paid", invoice["status"]!!.jsonPrimitive.content)
        assertEquals("subscription_cycle", invoice["billing_reason"]!!.jsonPrimitive.content)
    }
}
