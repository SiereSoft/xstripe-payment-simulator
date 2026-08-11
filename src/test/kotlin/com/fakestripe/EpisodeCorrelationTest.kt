package com.fakestripe

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fakestripe.store.Simulator
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class EpisodeCorrelationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val controlToken = "controller-test-token"

    @Test
    fun `reset correlates exported events while provider event shapes stay unchanged`() = testApplication {
        val simulator = newSim()
        application { module(simulator, controlToken) }

        val reset = client.post(
            "/v1/admin/reset?seed=42&scenario=duplicate_payments&episode_id=episode-2026-001",
        ) {
            header("X-Siere-Control-Token", controlToken)
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertEquals(
            "episode-2026-001",
            body(reset.bodyAsText())["episode_id"]!!.jsonPrimitive.content,
        )

        val created = client.post("/v1/customers") {
            header(HttpHeaders.Authorization, "Bearer sk_test_123")
            setBody(form("email" to "episode@example.com"))
        }
        assertEquals(HttpStatusCode.OK, created.status)

        val matching = client.get("/v1/events?episode_id=episode-2026-001") {
            header(HttpHeaders.Authorization, "Bearer sk_test_123")
        }
        val providerEvent = body(matching.bodyAsText())["data"]!!.jsonArray.single().jsonObject
        assertFalse("episode_id" in providerEvent)

        val otherEpisode = client.get("/v1/events?episode_id=episode-2026-002") {
            header(HttpHeaders.Authorization, "Bearer sk_test_123")
        }
        assertTrue(body(otherEpisode.bodyAsText())["data"]!!.jsonArray.isEmpty())

        val exported = client.get("/v1/admin/state") {
            header("X-Siere-Control-Token", controlToken)
        }
        val state = body(exported.bodyAsText())
        assertEquals("episode-2026-001", state["episode_id"]!!.jsonPrimitive.content)
        assertTrue(
            state["events"]!!.jsonArray.all {
                it.jsonObject["episode_id"]!!.jsonPrimitive.content == "episode-2026-001"
            },
        )
    }

    @Test
    fun `invalid episode id is rejected without resetting the world`() = testApplication {
        val simulator = newSim()
        application { module(simulator, controlToken) }
        val seedBefore = simulator.seed

        val response = client.post("/v1/admin/reset") {
            header("X-Siere-Control-Token", controlToken)
            setBody(form("seed" to "99", "episode_id" to "contains spaces"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "episode_id",
            body(response.bodyAsText())["error"]!!.jsonObject["param"]!!.jsonPrimitive.content,
        )
        assertEquals(seedBefore, simulator.seed)
        assertEquals(null, simulator.episodeId)
    }

    @Test
    fun `episode correlation survives restart and is attached to reset logs`() {
        val path = Files.createTempDirectory("fs-episode-restart").resolve("state.json")
        val simulator = Simulator.boot(path, 1L)
        val logger = LoggerFactory.getLogger(Simulator::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)

        try {
            simulator.reset(7L, episodeId = "episode-restart-007")
            simulator.write { store ->
                store.recordEvent("test.episode", buildJsonObject { put("object", "test") })
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val resetLog = appender.list.single {
            it.formattedMessage.startsWith("Reset simulator world:")
        }
        assertEquals("episode-restart-007", resetLog.mdcPropertyMap["episode_id"])

        val restarted = Simulator.boot(path, 999L)
        assertEquals("episode-restart-007", restarted.episodeId)
        assertEquals(
            setOf("episode-restart-007"),
            restarted.read { store -> store.events.values.map { it.episodeId }.toSet() },
        )
    }

    private fun newSim(): Simulator = Simulator.boot(
        Files.createTempDirectory("fs-episode-route").resolve("state.json"),
        1L,
    )

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(parametersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

    private fun body(text: String) = json.parseToJsonElement(text).jsonObject
}
