package com.fakestripe

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

/**
 * Regressions for security fixes. Each test here corresponds to a bug that was
 * live at some point; they exist so it cannot come back quietly.
 */
class SecurityRegressionTest {

    private fun newSim(): Simulator =
        Simulator.boot(Files.createTempDirectory("fs-sec").resolve("state.json"), 7L)

    private val key = "Bearer sk_test_123"

    private fun form(vararg pairs: Pair<String, String>) =
        FormDataContent(io.ktor.http.Parameters.build { pairs.forEach { (k, v) -> append(k, v) } })

    /**
     * Auth was decided from the *raw* request path while Ktor routes on the
     * *decoded* one, so `/%76%31/customers` reached a business route while the
     * prefix check saw something that did not start with `/v1/`.
     */
    @Test
    fun `percent-encoded path cannot bypass API key auth`() = testApplication {
        application { actorModule(newSim()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/customers").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/%76%31/customers").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/%63ustomers").status)

        // ...and a real key still works through the encoded form.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/%76%31/customers") { header(HttpHeaders.Authorization, key) }.status,
        )
    }

    /** A live-mode secret must never be accepted by a simulator. */
    @Test
    fun `live mode keys are rejected`() = testApplication {
        application { actorModule(newSim()) }

        listOf("sk_live_abc123", "rk_live_abc123").forEach { live ->
            val response = client.get("/v1/customers") {
                header(HttpHeaders.Authorization, "Bearer $live")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "expected $live to be refused")
            assertTrue(response.bodyAsText().contains("live-mode"), "error should explain why")
        }

        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/customers") { header(HttpHeaders.Authorization, key) }.status,
        )
    }

    /**
     * `success_url` / `cancel_url` land in a `Location:` header and a
     * `<form action>`, so a `javascript:` scheme meant script execution on the
     * checkout origin.
     */
    @Test
    fun `checkout rejects non-http redirect schemes`() = testApplication {
        application { actorModule(newSim()) }

        val product = client.post("/v1/products") {
            header(HttpHeaders.Authorization, key)
            setBody(form("name" to "Regression Widget"))
        }.bodyAsText().substringAfter("\"id\":\"").substringBefore('"')

        val price = client.post("/v1/prices") {
            header(HttpHeaders.Authorization, key)
            setBody(form("product" to product, "currency" to "usd", "unit_amount" to "500"))
        }.bodyAsText().substringAfter("\"id\":\"").substringBefore('"')

        suspend fun createWith(success: String, cancel: String): HttpStatusCode =
            client.post("/v1/checkout/sessions") {
                header(HttpHeaders.Authorization, key)
                setBody(
                    form(
                        "mode" to "payment",
                        "success_url" to success,
                        "cancel_url" to cancel,
                        "line_items[0][price]" to price,
                        "line_items[0][quantity]" to "1",
                    ),
                )
            }.status

        val ok = "https://ok.example/done"
        assertEquals(HttpStatusCode.BadRequest, createWith("javascript:alert(1)", ok))
        assertEquals(HttpStatusCode.BadRequest, createWith("data:text/html,<script>x</script>", ok))
        assertEquals(HttpStatusCode.BadRequest, createWith(ok, "javascript:alert(1)"))
        assertEquals(HttpStatusCode.OK, createWith(ok, "https://ok.example/back"))
    }

    /** The controller webhook config used to echo the signing secret verbatim. */
    @Test
    fun `webhook config never returns the signing secret`() = testApplication {
        val token = "controller-test-token"
        application { module(newSim(), controlToken = token) }

        client.post("/v1/admin/webhook") {
            header("X-Siere-Control-Token", token)
            setBody(
                FormDataContent(
                    parametersOf(
                        "url" to listOf("http://127.0.0.1:9/hook"),
                        "secret" to listOf("whsec_super_secret_value"),
                    ),
                ),
            )
        }

        val body = client.get("/v1/admin/webhook") {
            header("X-Siere-Control-Token", token)
        }.bodyAsText()

        assertFalse(body.contains("whsec_super_secret_value"), "secret leaked in $body")
        assertTrue(body.contains("secret_configured"))
    }

    /** Reset destroys and rebuilds the world, so it must not be a GET. */
    @Test
    fun `admin reset is not reachable by GET`() = testApplication {
        val token = "controller-test-token"
        application { module(newSim(), controlToken = token) }

        val response = client.get("/v1/admin/reset?seed=5") {
            header("X-Siere-Control-Token", token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /** A present-but-empty environment value must disable, never authorize, the controller. */
    @Test
    fun `blank control token disables the control plane`() = testApplication {
        application { module(newSim(), controlToken = "  ") }

        val noHeader = client.get("/v1/admin/state")
        val blankHeader = client.get("/v1/admin/state") {
            header("X-Siere-Control-Token", "  ")
        }

        assertEquals(HttpStatusCode.NotFound, noHeader.status)
        assertEquals(HttpStatusCode.NotFound, blankHeader.status)
    }

    /** Unexpected failures must not echo internal detail back to the caller. */
    @Test
    fun `scenario errors do not leak internal messages`() = testApplication {
        val token = "controller-test-token"
        application { module(newSim(), controlToken = token) }

        val body = client.post("/v1/admin/reset?seed=1&scenario=no_such_scenario") {
            header("X-Siere-Control-Token", token)
        }.bodyAsText()

        assertFalse(body.contains("com.fakestripe"), "stack/class detail leaked in $body")
        assertFalse(body.contains("/sessions/"), "filesystem path leaked in $body")
    }
}
