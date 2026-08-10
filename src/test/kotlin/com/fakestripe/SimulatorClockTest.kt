package com.fakestripe

import com.fakestripe.seed.Seeder
import com.fakestripe.store.ClockMode
import com.fakestripe.store.Simulator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class SimulatorClockTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `scenario reset time is deterministic while free mode follows its time source`() {
        val first = Seeder.build(42L, Seeder.DUPLICATE_PAYMENTS)
        val repeated = Seeder.build(42L, Seeder.DUPLICATE_PAYMENTS)
        val otherSeed = Seeder.build(43L, Seeder.DUPLICATE_PAYMENTS)
        val otherScenario = Seeder.build(42L, Seeder.CANCEL_CANDIDATE)

        assertEquals(ClockMode.MANUAL, first.clock.mode)
        assertEquals(first.now(), repeated.now())
        assertNotEquals(first.now(), otherSeed.now())
        assertNotEquals(first.now(), otherScenario.now())

        var wallTime = 1_900_000_000L
        val free = Seeder.build(
            seed = 7L,
            clockMode = ClockMode.FREE,
            wallTimeSeconds = { wallTime },
        )
        assertEquals(ClockMode.FREE, free.clock.mode)
        assertNull(free.clock.state.manualTime)
        assertEquals(1_900_000_000L, free.now())
        wallTime += 15
        assertEquals(1_900_000_015L, free.now())
    }

    @Test
    fun `privileged advance persists manual time and timestamps new objects`() {
        val path = Files.createTempDirectory("fs-clock-route").resolve("state.json")
        val simulator = Simulator.boot(path, 1L)
        var advancedTime = 0L

        testApplication {
            application { module(simulator, controlToken = "controller-test-token") }

            val resetResponse = client.post(
                "/v1/admin/reset?seed=42&scenario=duplicate_payments",
            ) {
                header("X-Siere-Control-Token", "controller-test-token")
            }
            assertEquals(HttpStatusCode.OK, resetResponse.status)
            val reset = body(resetResponse.bodyAsText())
            val startTime = reset["clock"]!!.jsonObject["current_time"]!!.jsonPrimitive.long
            val resetRevision = reset["state_revision"]!!.jsonPrimitive.long
            assertEquals("manual", reset["clock"]!!.jsonObject["mode"]!!.jsonPrimitive.content)

            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/v1/admin/clock/advance?seconds=3600").status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/v1/admin/clock/advance?seconds=3600") {
                    header("X-Siere-Control-Token", "wrong-token")
                }.status,
            )

            val advanceResponse = client.post("/v1/admin/clock/advance?seconds=3600") {
                header("X-Siere-Control-Token", "controller-test-token")
            }
            assertEquals(HttpStatusCode.OK, advanceResponse.status)
            val advance = body(advanceResponse.bodyAsText())
            advancedTime = advance["current_time"]!!.jsonPrimitive.long
            assertEquals(startTime + 3600, advancedTime)
            assertEquals(resetRevision + 1, advance["state_revision"]!!.jsonPrimitive.long)

            val revisionBeforeOverflow = simulator.read { it.revision }
            val overflow = client.post("/v1/admin/clock/advance?seconds=${Long.MAX_VALUE}") {
                header("X-Siere-Control-Token", "controller-test-token")
            }
            assertEquals(HttpStatusCode.BadRequest, overflow.status)
            assertEquals("seconds", errorParam(overflow.bodyAsText()))
            assertEquals(advancedTime, simulator.read { it.now() })
            assertEquals(revisionBeforeOverflow, simulator.read { it.revision })

            val customerResponse = client.post("/v1/customers") {
                header(HttpHeaders.Authorization, "Bearer sk_test_123")
                setBody(form("email" to "clock@example.com", "name" to "Clock Test"))
            }
            assertEquals(HttpStatusCode.OK, customerResponse.status)
            assertEquals(advancedTime, body(customerResponse.bodyAsText())["created"]!!.jsonPrimitive.long)
            assertEquals(advancedTime, simulator.read { it.events.values.last().created })

            val priceId = simulator.read { it.prices.values.first().id }
            val sessionResponse = client.post("/v1/checkout/sessions") {
                header(HttpHeaders.Authorization, "Bearer sk_test_123")
                setBody(
                    form(
                        "mode" to "subscription",
                        "line_items[0][price]" to priceId,
                        "line_items[0][quantity]" to "1",
                        "success_url" to "https://example.com/success",
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, sessionResponse.status)
            val session = body(sessionResponse.bodyAsText())
            val sessionId = session["id"]!!.jsonPrimitive.content
            assertEquals(advancedTime, session["created"]!!.jsonPrimitive.long)

            val secondAdvance = client.post("/v1/admin/clock/advance?seconds=86401") {
                header("X-Siere-Control-Token", "controller-test-token")
            }
            assertEquals(HttpStatusCode.OK, secondAdvance.status)
            advancedTime += 86_401
            assertEquals(
                advancedTime,
                body(secondAdvance.bodyAsText())["current_time"]!!.jsonPrimitive.long,
            )

            val expired = client.get("/v1/checkout/sessions/$sessionId") {
                header(HttpHeaders.Authorization, "Bearer sk_test_123")
            }
            assertEquals(HttpStatusCode.OK, expired.status)
            assertEquals("expired", body(expired.bodyAsText())["status"]!!.jsonPrimitive.content)
        }

        val restarted = Simulator.boot(path, 999L)
        assertEquals(ClockMode.MANUAL, restarted.read { it.clock.mode })
        assertEquals(advancedTime, restarted.read { it.now() })
    }

    @Test
    fun `invalid mode and free clock advance fail without changing state`() = testApplication {
        val simulator = Simulator.boot(
            Files.createTempDirectory("fs-clock-invalid").resolve("state.json"),
            1L,
        )
        application { module(simulator, controlToken = "controller-test-token") }

        val seedBefore = simulator.seed
        val invalidMode = client.post("/v1/admin/reset?seed=99&clock_mode=warp") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidMode.status)
        assertEquals("clock_mode", errorParam(invalidMode.bodyAsText()))
        assertEquals(seedBefore, simulator.seed)

        val freeReset = client.post("/v1/admin/reset?seed=9&clock_mode=free") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.OK, freeReset.status)
        assertEquals("free", body(freeReset.bodyAsText())["clock"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val revisionBefore = simulator.read { it.revision }

        val advance = client.post("/v1/admin/clock/advance?seconds=60") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.BadRequest, advance.status)
        assertEquals("clock_mode", errorParam(advance.bodyAsText()))
        assertEquals(revisionBefore, simulator.read { it.revision })
    }

    @Test
    fun `failed clock persistence rolls back time and revision`() {
        val unwritableTarget = Files.createTempDirectory("fs-clock-failure")
        Files.writeString(unwritableTarget.resolve("keep"), "prevents directory replacement")
        val store = Seeder.build(11L, Seeder.UPGRADE_CANDIDATE)
        val simulator = Simulator(store, unwritableTarget)
        val timeBefore = store.now()
        val revisionBefore = store.revision

        assertFailsWith<Exception> { simulator.advanceClock(60) }
        assertEquals(timeBefore, store.now())
        assertEquals(revisionBefore, store.revision)
    }

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun body(text: String) = json.parseToJsonElement(text).jsonObject

    private fun errorParam(text: String): String =
        body(text)["error"]!!.jsonObject["param"]!!.jsonPrimitive.content
}
