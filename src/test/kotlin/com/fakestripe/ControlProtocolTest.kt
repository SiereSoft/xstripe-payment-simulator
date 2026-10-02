package com.fakestripe

import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Section 9 of docs/CONTROL-PROTOCOL.md, exercised against the real Netty listeners started the
 * way `main` starts them, on free ports.
 */
class ControlProtocolTest {

    private val start = "2026-10-05T08:00:00.000Z"

    private fun LiveSim.reset(body: String = """{"seed":"proto-1","episode_id":"run-0007","now":"$start"}"""): Reply =
        control("POST", "/v1/admin/reset", body).also { assertEquals(200, it.status, it.text) }

    private fun LiveSim.world(): JsonObject {
        val state = control("GET", "/v1/admin/state").json.toMutableMap()
        state.remove("revision")
        state.remove("state_revision")
        return JsonObject(state)
    }

    private fun LiveSim.customerCount(): Int = control("GET", "/v1/admin/state").json["customers"]!!.arr().size

    // --- 1. listeners and authentication ---------------------------------------------------

    @Test
    fun `controller requires the token, carries the protocol header and the actor hides it`() = LiveSim().use { s ->
        val missing = s.control("GET", "/v1/admin/health", token = null)
        assertEquals(403, missing.status)
        assertEquals("forbidden", missing.json["error"]!!.obj().s("code"))
        assertEquals("1", missing.header("X-Siere-Control-Protocol"))

        val wrong = s.control("GET", "/v1/admin/health", token = "not-the-controller-token")
        assertEquals(403, wrong.status)
        assertFalse(wrong.text.contains(TEST_TOKEN), "messages never echo secrets")

        val ok = s.control("GET", "/v1/admin/health")
        assertEquals(200, ok.status)
        assertEquals("1", ok.header("X-Siere-Control-Protocol"))

        val healthz = s.control("GET", "/healthz", token = null)
        assertEquals(200, healthz.status)
        assertEquals("ok", healthz.str("status"))
        assertEquals("1", healthz.header("X-Siere-Control-Protocol"))

        val unknown = s.control("GET", "/v1/admin/nope")
        assertEquals(404, unknown.status)
        assertEquals("not_found", unknown.json["error"]!!.obj().s("code"))
        assertEquals(403, s.control("GET", "/v1/admin/nope", token = null).status)
        assertEquals(404, s.control("GET", "/v1/customers").status, "the controller does not serve the actor API")

        // The actor answers 404 for anything under /v1/admin/, token or not.
        assertEquals(404, s.actor("GET", "/v1/admin/health", headers = mapOf("X-Siere-Control-Token" to TEST_TOKEN)).status)
        assertEquals(404, s.actor("POST", "/v1/admin/reset", "", headers = mapOf("X-Siere-Control-Token" to TEST_TOKEN)).status)
        assertNull(s.actor("GET", "/healthz").header("X-Siere-Control-Protocol"))
    }

    @Test
    fun `without a usable token the controller port is never opened`() {
        LiveSim(token = null).use { s ->
            assertNull(s.servers.controller)
            assertFailsWith<ConnectException> { Socket("127.0.0.1", s.controlPort).close() }
            assertEquals(200, s.actor("GET", "/healthz").status)
        }
        LiveSim(token = "too-short").use { s ->
            assertNull(s.servers.controller, "tokens shorter than 16 characters are refused")
            assertFailsWith<IOException> { s.control("GET", "/v1/admin/health") }
        }
    }

    // --- 3. health and lifecycle -----------------------------------------------------------

    @Test
    fun `health identifies the simulator and its position`() = LiveSim().use { s ->
        s.reset()
        val health = s.control("GET", "/v1/admin/health")
        assertEquals("ok", health.str("status"))
        assertEquals("stripe", health.str("simulator"))
        assertEquals(BuildInfo.VERSION, health.str("version"))
        assertEquals(1, health.json["protocol"]!!.jsonPrimitive.content.toInt())
        assertEquals("proto-1", health.str("seed"))
        assertEquals("run-0007", health.str("episode_id"))
        assertEquals(start, health.json["clock"]!!.obj().s("now"))
        assertEquals("manual", health.json["clock"]!!.obj().s("mode"))
        assertTrue(health.json["revision"]!!.jsonPrimitive.long > 0)
        assertTrue(health.json["journal_seq"]!!.jsonPrimitive.long >= 1)
        assertEquals("""{"status":"ok"}""", s.actor("GET", "/healthz", key = null).text)
        assertTrue(s.control("GET", "/v1/admin/state").json.containsKey("revision"))
    }

    @Test
    fun `two resets with one seed give identical worlds, ids and request ids`() = LiveSim().use { s ->
        fun episode(): Triple<JsonObject, String, String> {
            val reset = s.reset()
            assertEquals(1, reset.json["protocol"]!!.jsonPrimitive.content.toInt())
            assertEquals("proto-1", reset.str("seed"))
            val customer = s.actor("POST", "/v1/customers", form("email" to "same@example.com"))
            assertEquals(200, customer.status)
            return Triple(s.world(), customer.str("id"), customer.header("Request-Id")!!)
        }
        val (first, firstId, firstRequest) = episode()
        val (second, secondId, secondRequest) = episode()
        assertEquals(firstId, secondId)
        assertEquals(firstRequest, secondRequest)
        assertTrue(firstRequest.matches(Regex("req_[A-Za-z0-9]{14}")))
        assertEquals(first, second)

        // Another seed is another world; a numeric seed reads the same as before 0.2.0.
        val other = s.control("POST", "/v1/admin/reset", """{"seed":"proto-2"}""")
        assertNotEquals(first["customers"], s.world()["customers"])
        assertEquals("manual", other.json["clock"]!!.obj().s("mode"), "a protocol reset defaults to a manual clock")
        val legacy = s.control("POST", "/v1/admin/reset?seed=5")
        assertEquals("free", legacy.json["clock"]!!.obj().s("mode"), "0.1.x query resets keep their default")
        assertEquals("5", legacy.str("seed"))
        val bad = s.control("POST", "/v1/admin/reset", """{"clock_mode":"free","now":"$start"}""")
        assertEquals(400, bad.status)
        assertEquals("invalid_request", bad.json["error"]!!.obj().s("code"))
        assertEquals(400, s.control("POST", "/v1/admin/reset", """{"now":"yesterday"}""").status)
        assertEquals(400, s.control("POST", "/v1/admin/reset", "[1]").status)
    }

    // --- 4. clock --------------------------------------------------------------------------

    @Test
    fun `absolute and relative clock moves and their 409s`() = LiveSim().use { s ->
        s.reset()
        val clock = s.control("GET", "/v1/admin/clock")
        assertEquals(start, clock.str("now"))
        assertEquals("manual", clock.str("mode"))

        val backwards = s.control("POST", "/v1/admin/clock", """{"now":"2026-10-05T07:59:59.999Z"}""")
        assertEquals(409, backwards.status)
        assertEquals("clock_backwards", backwards.json["error"]!!.obj().s("code"))

        val same = s.control("POST", "/v1/admin/clock", """{"now":"$start"}""")
        assertEquals(200, same.status)
        assertEquals(0, same.json["settled"]!!.jsonPrimitive.content.toInt())

        val absolute = s.control("POST", "/v1/admin/clock", """{"now":"2026-10-05T10:00:00.250+02:00"}""")
        assertEquals("2026-10-05T08:00:00.250Z", absolute.str("now"))
        assertEquals("manual", absolute.str("mode"))

        val fractional = s.control("POST", "/v1/admin/clock/advance", """{"seconds":1.75}""")
        assertEquals("2026-10-05T08:00:02.000Z", fractional.str("now"))
        val legacy = s.control("POST", "/v1/admin/clock/advance?seconds=60")
        assertEquals("2026-10-05T08:01:02.000Z", legacy.str("now"))
        assertEquals(60, legacy.json["advanced_by"]!!.jsonPrimitive.content.toInt())

        for (bad in listOf("""{"seconds":0}""", """{"seconds":-5}""", """{"seconds":31536001}""", """{"seconds":"soon"}""", "{}")) {
            val reply = s.control("POST", "/v1/admin/clock/advance", bad)
            assertEquals(400, reply.status, bad)
            assertEquals("seconds", reply.json["error"]!!.obj().s("param"))
        }
        assertEquals(400, s.control("POST", "/v1/admin/clock", """{"now":"later"}""").status)
        assertEquals(400, s.control("POST", "/v1/admin/clock", "{}").status)

        s.control("POST", "/v1/admin/reset", """{"clock_mode":"free"}""")
        for ((path, body) in listOf("/v1/admin/clock" to """{"now":"2030-01-01T00:00:00.000Z"}""", "/v1/admin/clock/advance" to """{"seconds":1}""")) {
            val reply = s.control("POST", path, body)
            assertEquals(409, reply.status)
            assertEquals("clock_not_manual", reply.json["error"]!!.obj().s("code"))
        }
        assertEquals("free", s.control("GET", "/v1/admin/clock").str("mode"))
    }

    @Test
    fun `one long clock move ends in the same world as many short ones`() = LiveSim().use { s ->
        fun episode(steps: Int): JsonObject {
            s.reset()
            val customer = s.actor("POST", "/v1/customers", form("email" to "steps@example.com")).str("id")
            s.actor("POST", "/v1/payment_methods/pm_card_visa/attach", form("customer" to customer))
            val price = s.actor(
                "POST", "/v1/prices",
                form("currency" to "eur", "unit_amount" to "2900", "recurring[interval]" to "month", "product_data[name]" to "SSF Basic"),
            ).str("id")
            val pm = s.actor("GET", "/v1/customers/$customer/payment_methods").json["data"]!!.arr()[0].obj().s("id")
            val sub = s.actor(
                "POST", "/v1/subscriptions",
                form("customer" to customer, "items[0][price]" to price, "default_payment_method" to pm, "trial_period_days" to "5"),
            )
            assertEquals("trialing", sub.str("status"))
            val total = 70L * 86_400
            var settled = 0
            repeat(steps) {
                settled += s.control("POST", "/v1/admin/clock/advance", """{"seconds":${total / steps}}""")
                    .json["settled"]!!.jsonPrimitive.content.toInt()
            }
            // trial_will_end, trial end, then two renewals.
            assertEquals(4, settled)
            return s.world()
        }
        val long = episode(1)
        val short = episode(70)
        assertEquals(long, short)
        val types = long["events"]!!.arr().map { it.obj().s("type") }
        assertTrue(types.indexOf("customer.subscription.trial_will_end") < types.lastIndexOf("invoice.paid"))
        assertEquals(4, long["invoices"]!!.arr().size, "a $0 trial invoice, the first real one and two renewals")
    }

    // --- 5. faults -------------------------------------------------------------------------

    private fun LiveSim.install(json: String): Reply = control("POST", "/v1/admin/faults", json)

    /** Send a request on a raw socket and report what came back before the connection ended. */
    private fun rawPost(port: Int, path: String, body: String): Int {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val request = "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer sk_test_123\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body"
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            return try {
                socket.getInputStream().readBytes().size
            } catch (_: IOException) {
                0 // reset by peer: also no response
            }
        }
    }

    @Test
    fun `every fault kind including a real dropped connection`() = LiveSim().use { s ->
        s.reset()
        val customers = s.customerCount()

        // error: answered without executing.
        val error = s.install(
            """{"name":"create-down","match":{"operation":"customer.create"},"kind":"error","status":503,
               "body":{"error":{"message":"injected"}},"headers":{"Retry-After":"2"}}""",
        )
        assertEquals(201, error.status, error.text)
        assertEquals(1, error.json["remaining"]!!.jsonPrimitive.content.toInt())
        val refused = s.actor("POST", "/v1/customers", form("email" to "x@example.com"))
        assertEquals(503, refused.status)
        assertEquals("2", refused.header("Retry-After"))
        assertEquals("""{"error":{"message":"injected"}}""", refused.text)
        assertEquals(customers, s.customerCount())
        val duplicate = s.install("""{"name":"create-down","match":{"operation":"customer.list"},"kind":"error"}""")
        assertEquals(409, duplicate.status)
        assertEquals("fault_exists", duplicate.json["error"]!!.obj().s("code"))

        // commit_then_error: executed and committed; the same idempotency key finds the result.
        s.install("""{"name":"lost-reply","match":{"operation":"customer.create"},"kind":"commit_then_error","status":502}""")
        val lost = s.actor("POST", "/v1/customers", form("email" to "lost@example.com"), mapOf("Idempotency-Key" to "k-1"))
        assertEquals(502, lost.status)
        assertEquals(customers + 1, s.customerCount())
        val retried = s.actor("POST", "/v1/customers", form("email" to "lost@example.com"), mapOf("Idempotency-Key" to "k-1"))
        assertEquals(200, retried.status)
        assertEquals("true", retried.header("Idempotent-Replayed"))
        assertEquals(customers + 1, s.customerCount())

        // commit_then_drop: executed, then the connection closes without a single response byte.
        s.install("""{"name":"drop-after","match":{"method":"POST","path":"/v1/customers"},"kind":"commit_then_drop"}""")
        assertEquals(0, rawPost(s.actorPort, "/v1/customers", form("email" to "dropped@example.com")))
        assertEquals(customers + 2, s.customerCount())

        // drop_before: the connection closes and nothing is executed.
        s.install("""{"name":"drop-before","match":{"method":"POST","path":"/v1/**"},"kind":"drop_before"}""")
        assertEquals(0, rawPost(s.actorPort, "/v1/customers", form("email" to "never@example.com")))
        assertEquals(customers + 2, s.customerCount())

        // latency: real time, then handled normally.
        s.install("""{"name":"slow","match":{"operation":"customer.list"},"kind":"latency","delay_ms":400}""")
        val began = System.nanoTime()
        assertEquals(200, s.actor("GET", "/v1/customers").status)
        assertTrue((System.nanoTime() - began) / 1_000_000 >= 400)

        val faults = s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().map { it.obj() }
        assertEquals(listOf("create-down", "lost-reply", "drop-after", "drop-before", "slow"), faults.map { it.s("name") })
        assertTrue(faults.all { it.s("remaining") == "0" && it.s("injected") == "1" }, "spent faults stay listed")

        val requests = s.control("GET", "/v1/admin/journal?kind=request&limit=1000").json["entries"]!!.arr().map { it.obj() }
        val byFault = requests.filter { it["fault"] !is JsonNull }.associateBy { it.s("fault") }
        assertEquals("error_injected", byFault.getValue("create-down").s("outcome"))
        assertEquals("error_injected", byFault.getValue("lost-reply").s("outcome"))
        assertEquals("dropped", byFault.getValue("drop-after").s("outcome"))
        assertEquals(JsonNull, byFault.getValue("drop-after")["status"])
        assertEquals("dropped", byFault.getValue("drop-before").s("outcome"))
        assertEquals("answered", byFault.getValue("slow").s("outcome"))
        val faultEntries = s.control("GET", "/v1/admin/journal?kind=fault").json["entries"]!!.arr()
        assertEquals(5, faultEntries.size)

        assertEquals(404, s.control("DELETE", "/v1/admin/faults/unknown").status)
        assertEquals(204, s.control("DELETE", "/v1/admin/faults/slow").status)
        assertEquals(204, s.control("DELETE", "/v1/admin/faults").status)
        assertTrue(s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().isEmpty())
    }

    @Test
    fun `skip, count and installation order`() = LiveSim().use { s ->
        s.reset()
        s.install("""{"name":"later","match":{"method":"GET","path":"/v1/customers/*"},"kind":"error","status":500,"skip":1,"count":2}""")
        val statuses = (1..4).map { s.actor("GET", "/v1/customers/cus_missing").status }
        assertEquals(listOf(404, 500, 500, 404), statuses)
        val fault = s.control("GET", "/v1/admin/faults").json["faults"]!!.arr()[0].obj()
        assertEquals(0, fault.s("remaining").toInt())
        assertEquals(2, fault.s("injected").toInt())
        assertEquals(1, fault.s("skip").toInt())

        s.install("""{"name":"first","match":{"operation":"customer.list"},"kind":"error","status":501}""")
        s.install("""{"name":"second","match":{"operation":"customer.list"},"kind":"error","status":502}""")
        assertEquals(listOf(501, 502, 200), (1..3).map { s.actor("GET", "/v1/customers").status })

        // Every matching fault counts the request toward its own skip; only the first eligible one applies.
        s.control("DELETE", "/v1/admin/faults")
        s.install("""{"name":"patient","match":{"operation":"customer.list"},"kind":"error","status":503,"skip":2}""")
        s.install("""{"name":"eager","match":{"operation":"customer.list"},"kind":"error","status":504,"count":2}""")
        assertEquals(listOf(504, 504, 503, 200), (1..4).map { s.actor("GET", "/v1/customers").status })
        val counted = s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().map { it.obj() }
        assertEquals(listOf(2, 0), counted.map { it.s("skipped").toInt() })

        // Faults apply before (and regardless of) the API-key check.
        s.install("""{"name":"no-key","match":{"operation":"customer.list"},"kind":"error","status":429}""")
        assertEquals(429, s.actor("GET", "/v1/customers", key = null).status)
        assertEquals(401, s.actor("GET", "/v1/customers", key = null).status)

        for (bad in listOf(
            """{"match":{"operation":"customer.list"},"kind":"error"}""",
            """{"name":"has space","match":{"operation":"customer.list"},"kind":"error"}""",
            """{"name":"x","match":{"operation":"customer.teleport"},"kind":"error"}""",
            """{"name":"x","match":{"operation":"customer.list","method":"GET","path":"/"},"kind":"error"}""",
            """{"name":"x","match":{"method":"GET"},"kind":"error"}""",
            """{"name":"x","match":{"method":"GET","path":"/v1/**/x"},"kind":"error"}""",
            """{"name":"x","match":{"method":"BREW","path":"/v1"},"kind":"error"}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"explode"}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"error","status":302}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"latency","delay_ms":60001}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"error","count":0}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"error","skip":-1}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"error","headers":{"Content-Length":"1"}}""",
            """{"name":"x","match":{"operation":"customer.list"},"kind":"error","headers":{"X":1}}""",
            """{"name":"x","match":"customer.list","kind":"error"}""",
        )) {
            val reply = s.install(bad)
            assertEquals(400, reply.status, bad)
            assertEquals("invalid_request", reply.json["error"]!!.obj().s("code"))
        }
    }

    @Test
    fun `faults are part of the persisted world and reset clears them`() {
        val dir = Files.createTempDirectory("fs-fault-restart")
        LiveSim(dir).use { s ->
            s.reset()
            s.install("""{"name":"sticky","match":{"operation":"customer.list"},"kind":"error","status":503,"count":3}""")
            assertEquals(503, s.actor("GET", "/v1/customers").status)
        }
        LiveSim(dir).use { s ->
            val fault = s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().single().obj()
            assertEquals("sticky", fault.s("name"))
            assertEquals(2, fault.s("remaining").toInt())
            assertEquals(1, fault.s("injected").toInt())
            assertEquals(503, s.actor("GET", "/v1/customers").status)
            s.reset()
            assertTrue(s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().isEmpty())
        }
    }

    // --- 6. journal ------------------------------------------------------------------------

    @Test
    fun `journal pages, keeps numbering across reset, redacts and correlates`() = LiveSim().use { s ->
        s.reset()
        val card = "4242424242424242"
        val pm = s.actor(
            "POST", "/v1/payment_methods",
            form("type" to "card", "card[number]" to card, "card[cvc]" to "123"),
            mapOf("X-Siere-Correlation-Id" to "step-14", "Idempotency-Key" to "pm-1"),
        )
        s.actor("GET", "/v1/customers", headers = mapOf("X-Siere-Correlation-Id" to "c".repeat(200)))
        assertEquals(200, pm.status)
        s.actor("GET", "/v1/customers")
        s.actor("GET", "/v1/customers?limit=1")

        val all = s.control("GET", "/v1/admin/journal?bodies=true&limit=1000")
        assertFalse(all.text.contains(card), "card numbers never reach the journal")
        val entries = all.json["entries"]!!.arr().map { it.obj() }
        val created = entries.single { it.s("kind") == "request" && it.s("operation") == "payment_method.create" }
        assertEquals("step-14", created.s("correlation_id"))
        assertTrue(entries.any { it["correlation_id"] !is JsonNull && it.s("correlation_id") == "c".repeat(128) }, "cut to 128 characters")
        assertEquals("pm-1", created.s("idempotency_key"))
        assertEquals(pm.header("Request-Id"), created.s("request_id"))
        assertEquals(pm.str("id"), created["refs"]!!.obj().s("payment_method"))
        assertEquals("[redacted]", created["request_body"]!!.obj().s("card[number]"))
        assertEquals("[redacted]", created["request_body"]!!.obj().s("card[cvc]"))
        assertEquals("pm_", created["response_body"]!!.obj().s("id").take(3))
        assertEquals(start, created.s("at"))
        assertEquals("run-0007", created.s("episode_id"))
        assertFalse(s.control("GET", "/v1/admin/journal").json["entries"]!!.arr()[0].obj().containsKey("request_body"))

        val seqs = entries.map { it.l("seq") }
        assertEquals(seqs.indices.map { seqs.first() + it }, seqs, "one apart, in order")
        val page = s.control("GET", "/v1/admin/journal?limit=2").json
        assertEquals(seqs.take(2), page["entries"]!!.arr().map { it.obj().l("seq") })
        assertEquals(seqs[1], page.l("next"), "next is the last seq returned")
        assertEquals(seqs.first(), page.l("oldest"))
        val rest = s.control("GET", "/v1/admin/journal?after=${page.l("next")}").json
        assertEquals(seqs.drop(2), rest["entries"]!!.arr().map { it.obj().l("seq") })
        assertEquals(seqs.last(), rest.l("next"))
        val empty = s.control("GET", "/v1/admin/journal?after=${rest.l("next")}").json
        assertTrue(empty["entries"]!!.arr().isEmpty(), "paging ends with an empty page")
        assertEquals(rest.l("next"), empty.l("next"), "an empty page hands back the caller's own after")
        assertTrue(
            s.control("GET", "/v1/admin/journal?kind=request").json["entries"]!!.arr().all { it.obj().s("kind") == "request" },
        )
        for (bad in listOf("limit=0", "limit=1001", "after=-1", "kind=gossip", "bodies=maybe")) {
            assertEquals(400, s.control("GET", "/v1/admin/journal?$bad").status, bad)
        }

        val lastBefore = s.control("GET", "/v1/admin/health").json.l("journal_seq")
        s.reset()
        val after = s.control("GET", "/v1/admin/journal").json
        val first = after["entries"]!!.arr()[0].obj()
        assertEquals("control", first.s("kind"))
        assertEquals("reset", first.s("operation"))
        assertEquals(lastBefore + 1, first.l("seq"), "reset empties the journal without renumbering")
        assertEquals(first.l("seq"), after.l("oldest"))
    }

    // --- 7. callbacks ----------------------------------------------------------------------

    @Test
    fun `webhooks flush in order, in reverse, in parallel and replay the exact bytes`() = LiveSim().use { s ->
        Receiver().use { receiver ->
            s.reset()
            assertEquals("manual", s.control("PATCH", "/v1/admin/config", """{"webhook_delivery":"manual","unknown":1}""").str("webhook_delivery"))
            s.control("POST", "/v1/admin/webhook", """{"url":"${receiver.url}","secret":"whsec_protocol"}""")
            val first = s.actor("POST", "/v1/customers", form("email" to "one@example.com")).str("id")
            val second = s.actor("POST", "/v1/customers", form("email" to "two@example.com")).str("id")
            Thread.sleep(300)
            assertTrue(receiver.deliveries.isEmpty(), "manual delivery waits for a flush")
            val pending = s.control("GET", "/v1/admin/webhooks?status=pending").json["webhooks"]!!.arr()
            assertEquals(2, pending.size)

            val flushed = s.control("POST", "/v1/admin/webhooks/flush", """{"reverse":true}""").json["results"]!!.arr().map { it.obj() }
            assertEquals(listOf("delivered", "delivered"), flushed.map { it.s("status") })
            assertEquals(pending.map { it.obj().s("id") }.reversed(), flushed.map { it.s("id") })
            assertEquals("/hook", flushed[0].s("target"))
            assertEquals(200, flushed[0].s("last_status").toInt())
            val delivered = receiver.deliveries.map { testJson.parseToJsonElement(it.body).obj()["data"]!!.obj()["object"]!!.obj().s("id") }
            assertEquals(listOf(second, first), delivered)
            receiver.deliveries.forEach { delivery ->
                val parts = delivery.signature.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
                assertEquals(1_791_187_200L, parts.getValue("t").toLong(), "signed at the simulated time of the attempt")
                assertEquals(hmacHex("whsec_protocol", "${parts["t"]}.${delivery.body}"), parts["v1"])
            }
            assertTrue(s.control("GET", "/v1/admin/webhooks?status=delivered").json["webhooks"]!!.arr().size == 2)

            val original = pending[0].obj()
            val replay = s.control("POST", "/v1/admin/webhooks/${original.s("id")}/replay")
            assertEquals(201, replay.status)
            assertEquals(original.s("event_id"), replay.str("event_id"))
            assertEquals(original.s("id"), replay.str("replay_of"))
            s.control("POST", "/v1/admin/webhooks/flush", """{"ids":["${replay.str("id")}"]}""")
            val bytes = receiver.deliveries.map { it.body }
            assertEquals(3, bytes.size)
            assertTrue(bytes.count { it == bytes.last() } == 2, "a replay sends the same signed bytes")
            assertEquals(original.s("event_id"), testJson.parseToJsonElement(bytes.last()).obj().s("id"))

            repeat(5) { s.actor("POST", "/v1/customers", form("email" to "bulk$it@example.com")) }
            val parallel = s.control("POST", "/v1/admin/webhooks/flush", """{"concurrency":4}""").json["results"]!!.arr()
            assertEquals(5, parallel.count { it.obj().s("status") == "delivered" })
            // A listed job is sent again even though it was delivered.
            val again = s.control("POST", "/v1/admin/webhooks/flush", """{"ids":["${original.s("id")}"]}""").json
            assertEquals(2, again["results"]!!.arr()[0].obj().s("attempts").toInt())

            assertEquals("auto", s.control("PATCH", "/v1/admin/config", """{"webhook_delivery":"auto"}""").str("webhook_delivery"))
            val before = receiver.deliveries.size
            s.actor("POST", "/v1/customers", form("email" to "auto@example.com"))
            val deadline = System.currentTimeMillis() + 5_000
            while (receiver.deliveries.size == before && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(before + 1, receiver.deliveries.size, "auto delivery needs no flush")

            val callbacks = s.control("GET", "/v1/admin/journal?kind=callback&limit=1000").json["entries"]!!.arr().map { it.obj() }
            assertEquals(receiver.deliveries.size, callbacks.size)
            assertTrue(callbacks.all { it.s("outcome") == "delivered" && it.s("status") == "200" && it.s("path") == "/hook" })
            assertFalse(s.control("GET", "/v1/admin/journal?kind=callback&bodies=true").text.contains("key=private"))

            for (bad in listOf("""{"concurrency":17}""", """{"ids":"x"}""", """{"ids":[1]}""", """{"reverse":"yes"}""")) {
                assertEquals(400, s.control("POST", "/v1/admin/webhooks/flush", bad).status, bad)
            }
            assertEquals(404, s.control("POST", "/v1/admin/webhooks/flush", """{"ids":["whjob_999999"]}""").status)
            assertEquals(400, s.control("GET", "/v1/admin/webhooks?status=lost").status)
            assertEquals(404, s.control("POST", "/v1/admin/webhooks/whjob_999999/replay").status)
            assertEquals(400, s.control("PATCH", "/v1/admin/config", """{"webhook_delivery":"sometimes"}""").status)
        }
    }

    @Test
    fun `a failing endpoint is retried on the simulated clock and never dropped`() = LiveSim().use { s ->
        var answer = 500
        Receiver { answer }.use { receiver ->
            s.reset()
            s.control("POST", "/v1/admin/webhook", """{"url":"${receiver.url}"}""")
            s.actor("POST", "/v1/customers", form("email" to "retry@example.com"))
            assertTrue(receiver.await() != null)
            val job = waitForJob(s) { it.s("status") == "failed" }
            assertEquals(1, job.s("attempts").toInt())
            assertEquals("2026-10-05T09:00:00.000Z", job.s("next_attempt_at"))
            assertEquals(500, job.s("last_status").toInt())

            assertEquals(0, s.control("POST", "/v1/admin/webhooks/flush", "{}").json["results"]!!.arr().size, "not due yet")
            s.control("POST", "/v1/admin/clock/advance", """{"seconds":3599}""")
            assertEquals(1, receiver.deliveries.size)
            answer = 200
            val moved = s.control("POST", "/v1/admin/clock/advance", """{"seconds":1}""")
            assertEquals(1, moved.json["settled"]!!.jsonPrimitive.content.toInt(), "the retry is settled by the clock move")
            assertEquals(2, receiver.deliveries.size)
            val signed = receiver.deliveries.last().signature.substringAfter("t=").substringBefore(",").toLong()
            assertEquals(1_791_190_800L, signed)
            assertEquals(receiver.deliveries[0].body, receiver.deliveries[1].body)
            val done = waitForJob(s) { it.s("status") == "delivered" }
            assertEquals(2, done.s("attempts").toInt())
            val attempts = s.control("GET", "/v1/admin/journal?kind=callback").json["entries"]!!.arr().map { it.obj() }
            assertEquals(listOf("failed", "delivered"), attempts.map { it.s("outcome") })
            assertEquals(listOf("1", "2"), attempts.map { it["refs"]!!.obj().s("attempt") })
        }
    }

    private fun waitForJob(s: LiveSim, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val jobs = s.control("GET", "/v1/admin/webhooks").json["webhooks"]!!.arr().map { it.obj() }
            jobs.firstOrNull(predicate)?.let { return it }
            check(System.currentTimeMillis() < deadline) { "no job matched: $jobs" }
            Thread.sleep(20)
        }
    }

    // --- 8. snapshots ----------------------------------------------------------------------

    @Test
    fun `snapshots save, restore after changes, survive restart and refuse incompatible files`() {
        val dir = Files.createTempDirectory("fs-snapshots")
        val createdAfterSnapshot: String
        LiveSim(dir).use { s ->
            s.reset()
            s.install("""{"name":"kept","match":{"operation":"customer.list"},"kind":"error","count":5}""")
            s.control("PATCH", "/v1/admin/config", """{"webhook_delivery":"manual"}""")
            val saved = s.control("POST", "/v1/admin/snapshots", """{"name":"base"}""")
            assertEquals(201, saved.status, saved.text)
            assertEquals("base", saved.str("name"))
            assertEquals(start, saved.str("clock"))
            assertEquals("run-0007", saved.str("episode_id"))
            assertEquals("fake-stripe.world/1", saved.str("format"))
            assertTrue(saved.json.l("bytes") > 0)
            assertTrue(saved.str("created_at").endsWith("Z"))
            val taken = s.control("POST", "/v1/admin/snapshots", """{"name":"base"}""")
            assertEquals(409, taken.status)
            assertEquals("snapshot_exists", taken.json["error"]!!.obj().s("code"))
            assertEquals(201, s.control("POST", "/v1/admin/snapshots", """{"name":"base","replace":true}""").status)
            assertEquals(400, s.control("POST", "/v1/admin/snapshots", """{"name":"no/slash"}""").status)
            assertEquals(400, s.control("POST", "/v1/admin/snapshots", "{}").status)

            createdAfterSnapshot = s.actor("POST", "/v1/customers", form("email" to "later@example.com")).str("id")
            s.control("POST", "/v1/admin/clock/advance", """{"seconds":3600}""")
            s.control("DELETE", "/v1/admin/faults")
            val revisionBefore = s.control("GET", "/v1/admin/health").json.l("revision")
            val journalBefore = s.control("GET", "/v1/admin/health").json.l("journal_seq")

            val restored = s.control("POST", "/v1/admin/snapshots/base/restore")
            assertEquals(200, restored.status, restored.text)
            assertEquals(start, restored.json["clock"]!!.obj().s("now"))
            assertTrue(restored.json.l("revision") > revisionBefore)
            assertEquals(1, s.control("GET", "/v1/admin/faults").json["faults"]!!.arr().size)
            assertEquals("manual", s.control("GET", "/v1/admin/config").str("webhook_delivery"))
            assertTrue(s.world()["customers"]!!.arr().none { it.obj().s("id") == createdAfterSnapshot })
            val journal = s.control("GET", "/v1/admin/journal?limit=1000").json
            val last = journal["entries"]!!.arr().last().obj()
            assertEquals("snapshot.restore", last.s("operation"))
            assertEquals(journalBefore + 1, last.l("seq"), "restore appends to the journal instead of replacing it")
            // The identifier position is part of the snapshot: the same request yields the same id.
            assertEquals(createdAfterSnapshot, s.actor("POST", "/v1/customers", form("email" to "later@example.com")).str("id"))

            Files.writeString(dir.resolve("snapshots/foreign.json"), """{"format":"other-simulator/9","world":{}}""")
            Files.writeString(dir.resolve("snapshots/broken.json"), """not json""")
            Files.writeString(dir.resolve("snapshots/empty.json"), """{"format":"fake-stripe.world/1","world":{"seed":"x"}}""")
            for (name in listOf("foreign", "broken", "empty")) {
                val revision = s.control("GET", "/v1/admin/health").json.l("revision")
                val refused = s.control("POST", "/v1/admin/snapshots/$name/restore")
                assertEquals(409, refused.status, name)
                assertEquals("snapshot_incompatible", refused.json["error"]!!.obj().s("code"))
                assertEquals(revision, s.control("GET", "/v1/admin/health").json.l("revision"), "nothing changed")
            }
            assertEquals(404, s.control("POST", "/v1/admin/snapshots/missing/restore").status)
        }
        LiveSim(dir).use { s ->
            val names = s.control("GET", "/v1/admin/snapshots").json["snapshots"]!!.arr().map { it.obj().s("name") }
            assertEquals(listOf("base", "broken", "empty", "foreign"), names)
            assertEquals("base", s.control("GET", "/v1/admin/snapshots/base").str("name"))
            assertEquals(404, s.control("GET", "/v1/admin/snapshots/missing").status)
            s.reset(body = """{"seed":"another"}""")
            assertEquals(200, s.control("POST", "/v1/admin/snapshots/base/restore").status, "snapshots survive restart and reset")
            assertEquals("proto-1", s.control("GET", "/v1/admin/health").str("seed"))
            assertEquals(204, s.control("DELETE", "/v1/admin/snapshots/base").status)
            assertEquals(404, s.control("DELETE", "/v1/admin/snapshots/base").status)
        }
    }

    @Test
    fun `state carries the revision and the controller error shape`() = LiveSim().use { s ->
        s.reset()
        val state = s.control("GET", "/v1/admin/state")
        assertEquals("no-store", state.header("Cache-Control"))
        assertEquals(state.json.l("revision"), state.json.l("state_revision"))
        val bad = s.control("POST", "/v1/admin/reset", """{"scenario":"no_such"}""")
        assertEquals(400, bad.status)
        assertEquals("scenario", bad.json["error"]!!.obj().s("param"))
        assertEquals(404, s.control("POST", "/v1/admin/subscriptions/sub_missing/renew").status)
        assertTrue(s.control("GET", "/v1/admin/config").json.containsKey("webhook_delivery"))
        assertEquals(JsonNull, s.control("GET", "/v1/admin/webhook").json["url"])
    }
}
