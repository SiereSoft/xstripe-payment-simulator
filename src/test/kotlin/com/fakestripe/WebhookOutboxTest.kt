package com.fakestripe

import com.fakestripe.store.Simulator
import com.fakestripe.webhook.RetrySchedule
import com.fakestripe.webhook.WebhookDispatcher
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The persisted webhook outbox and Stripe's live-mode retry behaviour on the simulated clock. */
class WebhookOutboxTest {

    @Test
    fun `the documented schedule - seven attempts over 63 hours then exhausted`() {
        val hour = RetrySchedule.HOUR_MS
        assertEquals(listOf(1, 2, 4, 8, 16, 32).map { it * hour }, (1..6).map { RetrySchedule.delayAfter(it) })
        var at = 0L
        val attempts = mutableListOf(at)
        var n = 1
        while (true) {
            at = RetrySchedule.nextAttempt(0, n, at) ?: break
            attempts += at
            n += 1
        }
        assertEquals(listOf(0, 1, 3, 7, 15, 31, 63).map { it * hour }, attempts)
        assertNull(RetrySchedule.nextAttempt(0, 7, 63 * hour), "nothing is scheduled past 72 hours")
    }

    @Test
    fun `a failing endpoint gets every attempt in time order and the job ends exhausted`() = LiveSim().use { s ->
        Receiver { 503 }.use { receiver ->
            s.resetAt()
            s.control("POST", "/v1/admin/webhook", """{"url":"${receiver.url}","secret":"whsec_retry"}""")
            s.actor("POST", "/v1/customers", form("email" to "down@example.com"))
            receiver.await()
            // One long move: every retry falls due on the way and is attempted at its own time.
            val moved = s.advance(4 * DAY_SECONDS)
            assertEquals(6, moved.json["settled"]!!.str().toInt())
            val stamps = receiver.deliveries.map { it.signature.substringAfter("t=").substringBefore(",").toLong() - T0_SECONDS }
            assertEquals(listOf(0L, 1, 3, 7, 15, 31, 63).map { it * 3600 }, stamps)
            assertTrue(receiver.deliveries.all { it.body == receiver.deliveries[0].body })
            receiver.deliveries.forEach { d ->
                val t = d.signature.substringAfter("t=").substringBefore(",")
                assertEquals(hmacHex("whsec_retry", "$t.${d.body}"), d.signature.substringAfter("v1="))
            }
            val job = s.control("GET", "/v1/admin/webhooks?status=exhausted").json["webhooks"]!!.arr().single().obj()
            assertEquals(7, job.s("attempts").toInt())
            assertEquals(JsonNull, job["next_attempt_at"])
            assertEquals(503, job.s("last_status").toInt())
            val outcomes = s.control("GET", "/v1/admin/journal?kind=callback").json["entries"]!!.arr().map { it.obj().s("outcome") }
            assertEquals(List(6) { "failed" } + "exhausted", outcomes)
            assertEquals(7, receiver.deliveries.size, "no attempt after exhaustion")
            s.advance(10 * DAY_SECONDS)
            assertEquals(7, receiver.deliveries.size)
        }
    }

    @Test
    fun `more events than the old 1024 queue are all delivered, none dropped`() {
        val path = Files.createTempDirectory("fs-outbox").resolve("state.json")
        Receiver().use { receiver ->
            val sim = Simulator.boot(path, 3L, WebhookDispatcher(receiver.url, "whsec_bulk"))
            sim.setWebhookDelivery("manual")
            repeat(1100) { i -> sim.write { it.recordEvent("customer.updated", buildJsonObject { put("n", i) }) } }
            assertEquals(1100, sim.webhookJobs("pending").size)

            // The outbox is persisted: a restart still has every job.
            sim.close()
            val restarted = Simulator.boot(path, 3L, WebhookDispatcher(receiver.url, "whsec_bulk"))
            assertEquals(1100, restarted.webhookJobs("pending").size)
            val results = restarted.flushWebhooks(ids = null, reverse = false, concurrency = 16, includeFuture = false)
            assertEquals(1100, results.count { it.status == "delivered" })
            assertEquals(1100, receiver.deliveries.map { testJson.parseToJsonElement(it.body).obj().s("id") }.toSet().size)
            restarted.close()
        }
    }

    @Test
    fun `no endpoint means events are kept but nothing is queued, and refused targets fail visibly`() {
        val sim = Simulator.boot(Files.createTempDirectory("fs-noendpoint").resolve("state.json"), 4L)
        sim.write { it.recordEvent("customer.updated", buildJsonObject { }) }
        assertTrue(sim.webhookJobs(null).isEmpty())
        assertTrue(sim.read { it.events.isNotEmpty() })

        sim.setWebhookDelivery("manual")
        sim.configureWebhook("http://169.254.169.254/latest/meta-data", "whsec_x")
        sim.write { it.recordEvent("customer.updated", buildJsonObject { }) }
        val job = sim.flushWebhooks(null, reverse = false, concurrency = 1, includeFuture = false).single()
        assertEquals("failed", job.status)
        assertEquals("target_not_allowed", job.lastError)
        assertTrue(sim.webhookJob(job.id).nextAttemptAtMs != null)

        sim.configureWebhook("ftp://example.com/hook", null)
        assertEquals("target_not_allowed", sim.flushWebhooks(listOf(job.id), false, 1, false).single().lastError)
        sim.configureWebhook("http://127.0.0.1:1/unreachable", null)
        assertEquals("ConnectException", sim.flushWebhooks(listOf(job.id), false, 1, false).single().lastError)
        sim.close()
    }

    @Test
    fun `the dispatcher reports the endpoint's answer and never follows redirects`() {
        Receiver { 302 }.use { receiver ->
            val dispatcher = WebhookDispatcher(null, "whsec_d")
            val redirected = dispatcher.attempt(receiver.url, "{}", 1L)
            assertEquals(302, redirected.status)
            assertEquals(false, redirected.delivered)
            assertEquals("no_endpoint_configured", dispatcher.attempt(null, "{}", 1L).error)
            assertEquals("t=7,v1=${hmacHex("whsec_d", "7.{}")}", dispatcher.signatureHeader(7, "{}"))
            assertEquals("/hook", WebhookDispatcher.pathOf(receiver.url))
            assertNull(WebhookDispatcher.pathOf(null))
            assertNull(WebhookDispatcher.pathOf("::not a url"))
        }
    }
}
