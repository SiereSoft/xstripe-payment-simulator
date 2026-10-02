package com.fakestripe

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val TEST_TOKEN = "controller-test-token"

val testJson = Json { ignoreUnknownKeys = true }

fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A response from a live listener: status, headers and the parsed JSON body (when it is JSON). */
class Reply(val status: Int, val headers: Map<String, List<String>>, val text: String) {
    val json: JsonObject by lazy { testJson.parseToJsonElement(text).jsonObject }
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    operator fun get(key: String): JsonElement? = json[key]
    fun str(key: String): String = json[key]!!.jsonPrimitive.content
    override fun toString(): String = "$status $text"
}

fun JsonElement.obj(): JsonObject = jsonObject
fun JsonElement.arr(): JsonArray = jsonArray
fun JsonElement.str(): String = jsonPrimitive.content
fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
fun JsonObject.l(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()

fun form(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

/**
 * The simulator started exactly as `main` starts it — both Netty listeners on free ports — with a
 * small HTTP client for each side.
 */
class LiveSim(
    val dataDir: Path = Files.createTempDirectory("fs-live"),
    extraEnv: Map<String, String> = emptyMap(),
    token: String? = TEST_TOKEN,
) : AutoCloseable {
    val actorPort = freePort()
    val controlPort = freePort()
    val env: Map<String, String> = buildMap {
        put("PORT", actorPort.toString())
        put("CONTROL_PORT", controlPort.toString())
        put("FAKE_STRIPE_DATA", dataDir.resolve("state.json").toString())
        if (token != null) put("FAKE_STRIPE_CONTROL_TOKEN", token)
        putAll(extraEnv)
    }
    val servers = startServers(env)
    val sim get() = servers.simulator
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun actor(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        key: String? = "sk_test_123",
    ): Reply {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$actorPort$path")).timeout(Duration.ofSeconds(30))
        if (key != null) builder.header("Authorization", "Bearer $key")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.header("Content-Type", "application/x-www-form-urlencoded")
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        return send(builder.build())
    }

    fun control(
        method: String,
        path: String,
        json: String? = null,
        token: String? = TEST_TOKEN,
        headers: Map<String, String> = emptyMap(),
    ): Reply {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$controlPort$path")).timeout(Duration.ofSeconds(60))
        if (token != null) builder.header("X-Siere-Control-Token", token)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (json != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (json != null) HttpRequest.BodyPublishers.ofString(json) else HttpRequest.BodyPublishers.noBody())
        return send(builder.build())
    }

    private fun send(request: HttpRequest): Reply {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.headers().map(), response.body())
    }

    override fun close() = servers.stop()
}

/** A local webhook endpoint that records deliveries and answers with programmable statuses. */
class Receiver(private val statuses: () -> Int = { 200 }) : AutoCloseable {
    data class Delivery(val body: String, val signature: String, val receivedAt: Long)

    val deliveries = CopyOnWriteArrayList<Delivery>()
    private val arrivals = LinkedBlockingQueue<Delivery>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            val delivery = Delivery(
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                exchange.requestHeaders.getFirst("Stripe-Signature") ?: "",
                System.currentTimeMillis(),
            )
            deliveries += delivery
            arrivals += delivery
            exchange.sendResponseHeaders(statuses(), -1)
            exchange.close()
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}/hook?key=private"

    fun await(seconds: Long = 5): Delivery? = arrivals.poll(seconds, TimeUnit.SECONDS)

    fun eventTypes(): List<String> = deliveries.map { testJson.parseToJsonElement(it.body).jsonObject.s("type") }

    override fun close() = server.stop(0)
}

fun hmacHex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun jsonString(value: String): String = JsonPrimitive(value).toString()

// --- flows shared by the billing tests -----------------------------------------------------

const val T0 = "2026-10-05T08:00:00.000Z"
const val T0_SECONDS = 1_791_187_200L
const val DAY_SECONDS = 86_400L

fun LiveSim.resetAt(now: String = T0, seed: String = "billing-1", extra: String = ""): Reply =
    control("POST", "/v1/admin/reset", """{"seed":"$seed","episode_id":"ep-1","now":"$now"$extra}""")
        .also { check(it.status == 200) { it.text } }

/** A customer whose default payment method is [card] (a `pm_card_*` token or a raw number). */
fun LiveSim.customerWithCard(card: String = "pm_card_visa", email: String = "owner@example.com"): Pair<String, String> {
    val customer = actor("POST", "/v1/customers", form("email" to email, "name" to "Owner", "metadata[accountId]" to "acc-1")).str("id")
    val pm = if (card.startsWith("pm_")) {
        actor("POST", "/v1/payment_methods/$card/attach", form("customer" to customer)).str("id")
    } else {
        val created = actor("POST", "/v1/payment_methods", form("type" to "card", "card[number]" to card)).str("id")
        actor("POST", "/v1/payment_methods/$created/attach", form("customer" to customer)).str("id")
    }
    actor("POST", "/v1/customers/$customer", form("invoice_settings[default_payment_method]" to pm))
    return customer to pm
}

fun LiveSim.monthlyPrice(amount: Long, name: String = "SSF Basic", currency: String = "eur", interval: String = "month"): String =
    actor(
        "POST", "/v1/prices",
        form(
            "currency" to currency, "unit_amount" to amount.toString(), "recurring[interval]" to interval,
            "product_data[name]" to name, "metadata[planId]" to name.lowercase(),
        ),
    ).also { check(it.status == 200) { it.text } }.str("id")

fun LiveSim.advance(seconds: Long): Reply =
    control("POST", "/v1/admin/clock/advance", """{"seconds":$seconds}""").also { check(it.status == 200) { it.text } }

fun LiveSim.state(): JsonObject = control("GET", "/v1/admin/state").json

/** Events in creation order as the state export holds them. */
fun LiveSim.eventTypes(): List<String> = state()["events"]!!.jsonArray.map { it.jsonObject.s("type") }

fun LiveSim.subscription(id: String): JsonObject = actor("GET", "/v1/subscriptions/$id").json

fun LiveSim.invoice(id: String): JsonObject = actor("GET", "/v1/invoices/$id").json

fun JsonObject.lines(): List<JsonObject> = this["lines"]!!.jsonObject["data"]!!.jsonArray.map { it.jsonObject }

fun JsonObject.items(): List<JsonObject> = this["items"]!!.jsonObject["data"]!!.jsonArray.map { it.jsonObject }
