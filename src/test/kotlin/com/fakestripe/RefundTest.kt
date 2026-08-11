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

class RefundTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    @Test
    fun `partial then full refund updates the charge, and over-refund is rejected`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-refund", ".json"), 1L)) }
        val client = authed()

        // Take a $20.00 payment
        val pi = json.parseToJsonElement(
            client.post("/v1/payment_intents") {
                setBody(form("amount" to "2000", "currency" to "usd", "payment_method" to "pm_card_visa", "confirm" to "true"))
            }.bodyAsText(),
        ).jsonObject
        val chargeId = pi["latest_charge"]!!.jsonPrimitive.content

        // Partial refund of $5.00
        val refund = json.parseToJsonElement(
            client.post("/v1/refunds") { setBody(form("charge" to chargeId, "amount" to "500")) }.bodyAsText(),
        ).jsonObject
        assertEquals("refund", refund["object"]!!.jsonPrimitive.content)
        assertEquals("500", refund["amount"]!!.jsonPrimitive.content)
        assertEquals("succeeded", refund["status"]!!.jsonPrimitive.content)

        // Charge now shows partial refund
        var charge = json.parseToJsonElement(client.get("/v1/charges/$chargeId").bodyAsText()).jsonObject
        assertEquals("500", charge["amount_refunded"]!!.jsonPrimitive.content)
        assertEquals("false", charge["refunded"]!!.jsonPrimitive.content)
        assertEquals(1, charge["refunds"]!!.jsonObject["data"]!!.let { (it as kotlinx.serialization.json.JsonArray).size })

        // Refund the remaining $15.00 (no amount => full remainder)
        client.post("/v1/refunds") { setBody(form("charge" to chargeId)) }
        charge = json.parseToJsonElement(client.get("/v1/charges/$chargeId").bodyAsText()).jsonObject
        assertEquals("2000", charge["amount_refunded"]!!.jsonPrimitive.content)
        assertEquals("true", charge["refunded"]!!.jsonPrimitive.content)

        // A further refund must fail
        val over = client.post("/v1/refunds") { setBody(form("charge" to chargeId)) }
        assertEquals(HttpStatusCode.BadRequest, over.status)
        val err = json.parseToJsonElement(over.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("invalid_request_error", err["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `refund by payment_intent targets its latest charge`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-refund2", ".json"), 1L)) }
        val client = authed()
        val pi = json.parseToJsonElement(
            client.post("/v1/payment_intents") {
                setBody(form("amount" to "1000", "currency" to "usd", "payment_method" to "pm_card_visa", "confirm" to "true"))
            }.bodyAsText(),
        ).jsonObject
        val piId = pi["id"]!!.jsonPrimitive.content

        val refund = json.parseToJsonElement(
            client.post("/v1/refunds") { setBody(form("payment_intent" to piId)) }.bodyAsText(),
        ).jsonObject
        assertEquals("1000", refund["amount"]!!.jsonPrimitive.content)
        assertEquals(piId, refund["payment_intent"]!!.jsonPrimitive.content)
    }
}
