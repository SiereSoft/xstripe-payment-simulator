package com.fakestripe

import com.fakestripe.store.Simulator
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductPriceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    @Test
    fun `create product and recurring price, then list`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-catalog", ".json"), 1L)) }
        val client = authed()

        val product = json.parseToJsonElement(
            client.post("/v1/products") { setBody(form("name" to "Gold Plan")) }.bodyAsText(),
        ).jsonObject
        val prodId = product["id"]!!.jsonPrimitive.content
        assertTrue(prodId.startsWith("prod_"))

        val price = json.parseToJsonElement(
            client.post("/v1/prices") {
                setBody(form("product" to prodId, "currency" to "usd", "unit_amount" to "2500", "recurring[interval]" to "month"))
            }.bodyAsText(),
        ).jsonObject
        assertTrue(price["id"]!!.jsonPrimitive.content.startsWith("price_"))
        assertEquals("recurring", price["type"]!!.jsonPrimitive.content)
        assertEquals("month", price["recurring"]!!.jsonObject["interval"]!!.jsonPrimitive.content)

        // Seeded catalog (Basic/Pro) plus the one we just made are all listed
        val list = json.parseToJsonElement(client.get("/v1/products?limit=100").bodyAsText()).jsonObject
        assertTrue(list["data"]!!.jsonArray.size >= 3)
    }
}
