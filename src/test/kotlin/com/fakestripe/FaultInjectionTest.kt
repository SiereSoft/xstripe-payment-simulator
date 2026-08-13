package com.fakestripe

import com.fakestripe.seed.Seeder
import com.fakestripe.store.Simulator
import io.ktor.client.plugins.DefaultRequest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FaultInjectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `post-commit response loss replays one committed refund by idempotency key`() =
        testApplication {
            val statePath = Files.createTempDirectory("fs-fault-recovery").resolve("state.json")
            val simulator = Simulator.boot(statePath, 1L)
            application { module(simulator, controlToken = "controller-test-token") }
            val actor = createClient {
                install(DefaultRequest) {
                    headers.append(HttpHeaders.Authorization, "Bearer sk_test_123")
                }
            }

            val reset = client.post(
                "/v1/admin/reset?seed=7&scenario=${Seeder.DUPLICATE_PAYMENTS_REFUND_RESPONSE_LOSS}",
            ) {
                header("X-Siere-Control-Token", "controller-test-token")
            }
            assertEquals(HttpStatusCode.OK, reset.status)
            val before = exportedState()
            val smallerChargeId = before["scenario"]!!.jsonObject["verifierContext"]!!
                .jsonObject["smaller_charge_id"]!!.jsonPrimitive.content
            val requestBody = FormDataContent(parametersOf("charge", smallerChargeId))

            val first = actor.post("/v1/refunds") {
                header("Idempotency-Key", "fault-recovery-key")
                setBody(requestBody)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, first.status)
            assertEquals("true", first.headers["Stripe-Should-Retry"])
            assertEquals(
                "injected_response_loss",
                json.parseToJsonElement(first.bodyAsText()).jsonObject["error"]!!
                    .jsonObject["code"]!!.jsonPrimitive.content,
            )

            val afterLoss = exportedState()
            assertEquals(1, afterLoss["refunds"]!!.jsonArray.size)
            assertEquals(1, afterLoss["idempotency_records"]!!.jsonArray.size)
            assertEquals(
                1,
                afterLoss["fault_injection"]!!.jsonObject["injectedCount"]!!
                    .jsonPrimitive.content.toInt(),
            )
            assertEquals(
                0,
                afterLoss["fault_injection"]!!.jsonObject["remaining"]!!
                    .jsonPrimitive.content.toInt(),
            )

            val replay = actor.post("/v1/refunds") {
                header("Idempotency-Key", "fault-recovery-key")
                setBody(FormDataContent(parametersOf("charge", smallerChargeId)))
            }
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotent-Replayed"])
            assertEquals(
                afterLoss,
                exportedState(),
                "the replay must not mutate provider or fault-audit state",
            )

            val restarted = Simulator.boot(statePath, 999L)
            assertEquals(1, restarted.read { it.refunds.size })
            assertEquals(1, restarted.read { it.faultInjection?.injectedCount })
            assertEquals(0, restarted.read { it.faultInjection?.remaining })
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.exportedState() =
        json.parseToJsonElement(
            client.get("/v1/admin/state") {
                header("X-Siere-Control-Token", "controller-test-token")
            }.bodyAsText(),
        ).jsonObject
}
