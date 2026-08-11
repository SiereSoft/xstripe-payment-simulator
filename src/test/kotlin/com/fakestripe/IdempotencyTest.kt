package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
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
import kotlin.test.assertNotEquals

class IdempotencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun ApplicationTestBuilder.authed() = createClient {
        install(DefaultRequest) { headers.append(HttpHeaders.Authorization, "Bearer sk_test_123") }
    }

    @Test
    fun `same key replays the first response, different body conflicts`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-idem", ".json"), 1L)) }
        val client = authed()

        val first = client.post("/v1/customers") {
            header("Idempotency-Key", "key-abc")
            setBody(form("email" to "a@b.com"))
        }
        val firstId = json.parseToJsonElement(first.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Same key + same body -> replays the exact same object (no new customer created)
        val replay = client.post("/v1/customers") {
            header("Idempotency-Key", "key-abc")
            setBody(form("email" to "a@b.com"))
        }
        val replayId = json.parseToJsonElement(replay.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(firstId, replayId)
        assertEquals("true", replay.headers["Idempotent-Replayed"])

        // Same key + DIFFERENT body -> idempotency error
        val conflict = client.post("/v1/customers") {
            header("Idempotency-Key", "key-abc")
            setBody(form("email" to "different@b.com"))
        }
        assertEquals(HttpStatusCode.BadRequest, conflict.status)
        assertEquals(
            "idempotency_error",
            json.parseToJsonElement(conflict.bodyAsText()).jsonObject["error"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `without a key each POST creates a new object`() = testApplication {
        application { module(Simulator.boot(Files.createTempFile("fs-idem2", ".json"), 1L)) }
        val client = authed()
        val a = json.parseToJsonElement(
            client.post("/v1/customers") { setBody(form("email" to "a@b.com")) }.bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content
        val b = json.parseToJsonElement(
            client.post("/v1/customers") { setBody(form("email" to "a@b.com")) }.bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content
        assertNotEquals(a, b)
    }
}
