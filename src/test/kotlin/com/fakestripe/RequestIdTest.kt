package com.fakestripe

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonNull

/** Seeded request ids: returned in `Request-Id`, journaled, and stamped on the events a request causes. */
class RequestIdTest {

    @Test
    fun `events carry the request id and idempotency key of the API call that caused them`() = LiveSim().use { s ->
        s.resetAt()
        val created = s.actor("POST", "/v1/customers", form("email" to "req@example.com"), mapOf("Idempotency-Key" to "create-1"))
        val requestId = created.header("Request-Id")!!
        val event = s.actor("GET", "/v1/events?type=customer.created&limit=1").json["data"]!!.arr()[0].obj()
        assertEquals(requestId, event["request"]!!.obj().s("id"))
        assertEquals("create-1", event["request"]!!.obj().s("idempotency_key"))

        val replay = s.actor("POST", "/v1/customers", form("email" to "req@example.com"), mapOf("Idempotency-Key" to "create-1"))
        assertEquals("true", replay.header("Idempotent-Replayed"))
        assertEquals(requestId, replay.header("Original-Request"))
        assertNotEquals(requestId, replay.header("Request-Id"))
        assertEquals(1, s.actor("GET", "/v1/events?type=customer.created").json["data"]!!.arr().size, "a replay executes nothing")

        val plain = s.actor("POST", "/v1/customers", form("email" to "plain@example.com"))
        // Events created in the same simulated second list in id order, so pick ours by its object.
        val plainEvent = s.actor("GET", "/v1/events?type=customer.created").json["data"]!!.arr().map { it.obj() }
            .single { it["data"]!!.obj()["object"]!!.obj().s("id") == plain.str("id") }
        assertEquals(plain.header("Request-Id"), plainEvent["request"]!!.obj().s("id"))
        assertEquals(JsonNull, plainEvent["request"]!!.obj()["idempotency_key"])

        // Hosted-page actions and scheduled work are not API requests: their events carry no request.
        val price = s.monthlyPrice(1000)
        val session = s.actor("POST", "/v1/checkout/sessions", form("mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://x.example")).str("id")
        s.actor("POST", "/checkout/$session/pay", form("card_number" to "4242424242424242"), key = null)
        val completed = s.actor("GET", "/v1/events?type=checkout.session.completed").json["data"]!!.arr()[0].obj()
        assertEquals(JsonNull, completed["request"]!!.obj()["id"])
        s.advance(30 * DAY_SECONDS)
        val renewed = s.actor("GET", "/v1/events?type=customer.subscription.updated").json["data"]!!.arr()
        assertEquals(listOf<Any?>(JsonNull), renewed.map { it.obj()["request"]!!.obj()["id"] }.distinct())

        val journaled = s.control("GET", "/v1/admin/journal?kind=request&limit=1000").json["entries"]!!.arr().map { it.obj() }
        assertEquals(requestId, journaled.first { it.s("operation") == "customer.create" }.s("request_id"))
    }

    @Test
    fun `the request id position survives a restart`() {
        fun ids(restartAfter: Int?): List<String> {
            val dir = Files.createTempDirectory("fs-reqid")
            val out = mutableListOf<String>()
            var sim = LiveSim(dir)
            sim.resetAt()
            repeat(4) { i ->
                if (i == restartAfter) {
                    sim.close()
                    sim = LiveSim(dir)
                }
                out += sim.actor("GET", "/v1/customers").header("Request-Id")!!
            }
            sim.close()
            return out
        }
        val straight = ids(null)
        assertEquals(straight, ids(2))
        assertEquals(4, straight.toSet().size)
    }
}
