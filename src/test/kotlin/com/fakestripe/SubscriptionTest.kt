package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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

class SubscriptionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    private suspend fun HttpResponse.obj(json: Json): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `subscribe, upgrade with proration, then cancel`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-sub", ".json"), 1L)) }
        val client = authed()

        // Customer with a saved card
        val customerId = client.post("/v1/customers") { setBody(form("email" to "sub@test.com")) }.obj(json)["id"]!!.jsonPrimitive.content
        val pmId = client.post("/v1/payment_methods") {
            setBody(form("type" to "card", "card[number]" to "4242424242424242", "card[exp_month]" to "12", "card[exp_year]" to "2034"))
        }.obj(json)["id"]!!.jsonPrimitive.content
        client.post("/v1/payment_methods/$pmId/attach") { setBody(form("customer" to customerId)) }

        // Basic $10/mo and Pro $300/yr
        val basic = client.post("/v1/products") { setBody(form("name" to "Basic")) }.obj(json)["id"]!!.jsonPrimitive.content
        val basicPrice = client.post("/v1/prices") {
            setBody(form("product" to basic, "currency" to "usd", "unit_amount" to "1000", "recurring[interval]" to "month"))
        }.obj(json)["id"]!!.jsonPrimitive.content
        val pro = client.post("/v1/products") { setBody(form("name" to "Pro")) }.obj(json)["id"]!!.jsonPrimitive.content
        val proYear = client.post("/v1/prices") {
            setBody(form("product" to pro, "currency" to "usd", "unit_amount" to "30000", "recurring[interval]" to "year"))
        }.obj(json)["id"]!!.jsonPrimitive.content

        // Subscribe to Basic monthly
        val sub = client.post("/v1/subscriptions") {
            setBody(form("customer" to customerId, "items[0][price]" to basicPrice, "default_payment_method" to pmId))
        }.obj(json)
        assertEquals("active", sub["status"]!!.jsonPrimitive.content)
        val subId = sub["id"]!!.jsonPrimitive.content
        val itemId = sub["items"]!!.jsonObject["data"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        val firstInvoiceId = sub["latest_invoice"]!!.jsonPrimitive.content

        // First invoice was charged in full
        val firstInvoice = client.get("/v1/invoices/$firstInvoiceId").obj(json)
        assertEquals("paid", firstInvoice["status"]!!.jsonPrimitive.content)
        assertEquals("1000", firstInvoice["total"]!!.jsonPrimitive.content)
        assertEquals("subscription_create", firstInvoice["billing_reason"]!!.jsonPrimitive.content)

        // Upgrade to Pro annual -> proration invoice: credit $10 unused, charge $300 => $290 net
        val upgraded = client.post("/v1/subscriptions/$subId") {
            setBody(form("items[0][id]" to itemId, "items[0][price]" to proYear))
        }.obj(json)
        val newItemPrice = upgraded["items"]!!.jsonObject["data"]!!.jsonArray[0].jsonObject["price"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(proYear, newItemPrice)
        val prorationInvoiceId = upgraded["latest_invoice"]!!.jsonPrimitive.content
        assertTrue(prorationInvoiceId != firstInvoiceId)

        val prorationInvoice = client.get("/v1/invoices/$prorationInvoiceId").obj(json)
        assertEquals("subscription_update", prorationInvoice["billing_reason"]!!.jsonPrimitive.content)
        assertEquals("29000", prorationInvoice["total"]!!.jsonPrimitive.content)
        assertEquals(2, prorationInvoice["lines"]!!.jsonObject["data"]!!.jsonArray.size)

        // Cancel
        val canceled = client.post("/v1/subscriptions/$subId") { setBody(form("cancel_at_period_end" to "true")) }.obj(json)
        assertEquals("true", canceled["cancel_at_period_end"]!!.jsonPrimitive.content)
    }
}
