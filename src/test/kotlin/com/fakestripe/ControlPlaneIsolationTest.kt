package com.fakestripe

import com.fakestripe.store.Simulator
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ControlPlaneIsolationTest {

    private val controlToken = "controller-test-token"

    @Test
    fun `both listeners default to loopback and binding wider is explicit`() {
        val config = ServerConfig.from(emptyMap())

        // The actor API accepts any test-mode key, so a default of 0.0.0.0 would
        // hand the whole simulated world to anyone on the same network. Exposing
        // it has to be a deliberate act (Docker sets HOST explicitly).
        assertEquals("127.0.0.1", config.actorHost)
        assertEquals(12111, config.actorPort)
        assertEquals("127.0.0.1", config.controllerHost)
        assertEquals(12112, config.controllerPort)

        assertEquals("0.0.0.0", ServerConfig.from(mapOf("HOST" to "0.0.0.0")).actorHost)

        assertFailsWith<IllegalArgumentException> {
            ServerConfig("0.0.0.0", 12111, "127.0.0.1", 12111)
        }
    }

    @Test
    fun `actor can use provider API but cannot route control calls`() = testApplication {
        val simulator = newSim()
        application { actorModule(simulator) }

        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/customers") {
                header(HttpHeaders.Authorization, "Bearer sk_test_actor")
                setBody(FormDataContent(parametersOf("email" to listOf("actor@example.com"))))
            }.status,
        )

        val seedBefore = simulator.seed
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/v1/admin/reset?seed=99") {
                header("X-Siere-Control-Token", controlToken)
            }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/admin/state") {
                header("X-Siere-Control-Token", controlToken)
            }.status,
        )
        assertEquals(seedBefore, simulator.seed)
    }

    @Test
    fun `controller rejects actor API and requires its own credential`() = testApplication {
        val simulator = newSim()
        application { controllerModule(simulator, controlToken) }

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/customers").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/admin/reset?seed=99").status)
        assertEquals(1L, simulator.seed)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/admin/reset?seed=99") {
                header("X-Siere-Control-Token", controlToken)
            }.status,
        )
        assertEquals(99L, simulator.seed)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/admin/state") {
                header("X-Siere-Control-Token", controlToken)
            }.status,
        )
    }

    private fun newSim(): Simulator = Simulator.boot(
        Files.createTempDirectory("fs-control-isolation").resolve("state.json"),
        1L,
    )
}
