package com.fakestripe

import com.fakestripe.billing.BillingOps
import com.fakestripe.billing.ScheduleOps
import com.fakestripe.error.StripeException
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionSchedule
import com.fakestripe.store.ClockMode
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import com.fakestripe.store.SimulatorClockState
import com.fakestripe.store.Snapshot
import com.fakestripe.util.StripeParams
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionScheduleTest {
    private val month = 30L * 86400
    private val year = 365L * 86400
    private fun params(vararg fields: Pair<String, String>) = StripeParams(fields.toList())
    private fun world(): Pair<DataStore, Subscription> {
        val s = DataStore(7, clockState = SimulatorClockState(ClockMode.MANUAL, 1900000000L))
        s.customers["cus_schedule"] = Customer("cus_schedule", s.now(), defaultPaymentMethod = "pm_schedule")
        s.paymentMethods["pm_schedule"] = PaymentMethod("pm_schedule", s.now(), "4242424242424242", 12, 2034, "cus_schedule")
        s.products["prod_schedule"] = Product("prod_schedule", s.now(), s.now(), "Schedule example")
        s.prices["price_month"] = Price("price_month", s.now(), "prod_schedule", "eur", 3100, recurringInterval = "month")
        s.prices["price_year"] = Price("price_year", s.now(), "prod_schedule", "eur", 19000, recurringInterval = "year")
        val sub = BillingOps.create(s, "cus_schedule", listOf(BillingOps.ItemSpec("price_month", 1)), "pm_schedule", mapOf("owner" to "original"), null, "items")
        return s to sub
    }
    private fun phases(sub: Subscription, end: String = "release") = params(
        "end_behavior" to end, "proration_behavior" to "none",
        "phases[0][start_date]" to sub.currentPeriodStart.toString(),
        "phases[0][end_date]" to sub.currentPeriodEnd.toString(),
        "phases[0][items][0][price]" to "price_month", "phases[0][items][0][quantity]" to "1",
        "phases[1][start_date]" to sub.currentPeriodEnd.toString(),
        "phases[1][iterations]" to "1", "phases[1][items][0][price]" to "price_year",
        "phases[1][billing_cycle_anchor]" to "phase_start", "phases[1][proration_behavior]" to "none",
        "phases[1][metadata][change]" to "retained_change",
    )
    private fun scheduled(s: DataStore, sub: Subscription, end: String = "release"): SubscriptionSchedule =
        ScheduleOps.create(s, params("from_subscription" to sub.id)).also { ScheduleOps.update(s, it, phases(sub, end)) }
    private fun sim(s: DataStore) = Simulator(s, Files.createTempDirectory("fs-schedules").resolve("state.json"))

    @Test
    fun `monthly to yearly phase bills exactly once at its boundary and releases current terms`() {
        val (s, sub) = world()
        val until = sub.currentPeriodEnd
        val schedule = scheduled(s, sub)
        val itemId = sub.items.single().id
        sim(s).use { simulator ->
            simulator.moveClockTo((until - 1) * 1000)
            assertEquals("price_month", simulator.store.requireSubscription(sub.id).items.single().priceId)
            assertEquals(1, simulator.store.invoices.size)
            simulator.moveClockTo(until * 1000)
            val current = simulator.store.requireSubscription(sub.id)
            val invoice = simulator.store.requireInvoice(requireNotNull(current.latestInvoice))
            assertEquals("price_year", current.items.single().priceId)
            assertEquals(itemId, current.items.single().id)
            assertEquals(until, current.currentPeriodStart)
            assertEquals(until + year, current.currentPeriodEnd)
            assertEquals(until, current.billingCycleAnchor)
            assertEquals(19000L, invoice.amountPaid)
            assertEquals("retained_change", invoice.subscriptionMetadata["change"])
            assertEquals("original", invoice.subscriptionMetadata["owner"])
            assertEquals(2, simulator.store.invoices.size)
            assertEquals(2, simulator.store.charges.values.count { it.paid })
            simulator.write { ScheduleOps.release(it, it.requireSchedule(schedule.id), params()) }
            simulator.moveClockTo((until + year) * 1000)
            assertEquals(3, simulator.store.invoices.size)
            assertNull(simulator.store.requireSubscription(sub.id).schedule)
            assertEquals("price_year", simulator.store.requireSubscription(sub.id).items.single().priceId)
        }
    }

    @Test
    fun `release before renewal preserves original price while schedule cancellation stops billing`() {
        for (release in listOf(true, false)) {
            val (s, sub) = world()
            val until = sub.currentPeriodEnd
            val schedule = scheduled(s, sub)
            sim(s).use { simulator ->
                simulator.write {
                    if (release) ScheduleOps.release(it, schedule, params()) else ScheduleOps.cancel(it, schedule, params())
                }
                simulator.moveClockTo(until * 1000)
                val current = simulator.store.requireSubscription(sub.id)
                assertEquals(if (release) "active" else "canceled", current.status)
                assertEquals("price_month", current.items.single().priceId)
                assertEquals(if (release) 2 else 1, simulator.store.invoices.size)
                val saved = simulator.store.requireSchedule(schedule.id)
                assertEquals(if (release) "released" else "canceled", saved.status)
                if (release) { assertEquals(sub.id, saved.releasedSubscription); assertNull(saved.subscription) }
            }
        }
    }

    @Test
    fun `end behavior completes once then continues or stops the underlying subscription`() {
        for (end in listOf("release", "cancel")) {
            val (s, sub) = world()
            val until = sub.currentPeriodEnd
            val schedule = scheduled(s, sub, end)
            sim(s).use { simulator ->
                simulator.moveClockTo((until + year) * 1000)
                val saved = simulator.store.requireSchedule(schedule.id)
                assertEquals("completed", saved.status)
                assertEquals(until + year, saved.completedAt)
                assertEquals(if (end == "release") 3 else 2, simulator.store.invoices.size)
                assertNull(simulator.store.requireSubscription(sub.id).schedule)
                assertEquals(if (end == "release") "active" else "canceled", simulator.store.requireSubscription(sub.id).status)
            }
        }
    }

    @Test
    fun `failed changed renewal keeps one invoice allocation and paid retry cannot collect twice`() {
        val (s, sub) = world()
        val until = sub.currentPeriodEnd
        scheduled(s, sub)
        BillingOps.failNextRenewals(sub, 1, null)
        sim(s).use { simulator ->
            simulator.moveClockTo(until * 1000)
            val current = simulator.store.requireSubscription(sub.id)
            val invoice = simulator.store.requireInvoice(requireNotNull(current.latestInvoice))
            assertEquals("past_due", current.status)
            assertEquals("open", invoice.status)
            assertEquals(0L, invoice.amountPaid)
            assertTrue(BillingOps.payInvoice(simulator.store, invoice, current.customer, "pm_schedule"))
            assertEquals(19000L, invoice.amountPaid)
            assertEquals("active", current.status)
            assertEquals(1, invoice.payments.size)
            val captured = simulator.store.charges.values.count { it.paid }
            assertTrue(BillingOps.payInvoice(simulator.store, invoice, current.customer, "pm_schedule"))
            assertEquals(captured, simulator.store.charges.values.count { it.paid })
            assertEquals(1, invoice.payments.size)
        }
    }

    @Test
    fun `old world reads defaults and new schedule survives saved world restart`() {
        val (s, sub) = world()
        val old = Json.parseToJsonElement(Snapshot.encode(s)).jsonObject
        val legacy = JsonObject((old - "subscriptionSchedules") + ("subscriptions" to JsonArray(
            old.getValue("subscriptions").jsonArray.map { JsonObject(it.jsonObject - "schedule") },
        )))
        val decoded = Snapshot.decode(legacy.toString())
        assertTrue(decoded.subscriptionSchedules.isEmpty())
        assertEquals(1, decoded.invoices.size)
        val schedule = scheduled(s, sub)
        val path = Files.createTempDirectory("fs-schedule-restart").resolve("state.json")
        Snapshot.saveOrThrow(s, path)
        Simulator.boot(path, 999L).use { restarted ->
            assertEquals(schedule.id, restarted.store.requireSubscription(sub.id).schedule)
            assertEquals(schedule, restarted.store.requireSchedule(schedule.id))
            restarted.moveClockTo(sub.currentPeriodEnd * 1000)
            assertEquals("price_year", restarted.store.requireSubscription(sub.id).items.single().priceId)
            assertEquals(2, restarted.store.invoices.size)
        }
    }

    @Test
    fun `invalid future phases refuse before any metadata term or money mutation`() {
        val (s, sub) = world()
        val schedule = ScheduleOps.create(s, params("from_subscription" to sub.id))
        val before = Snapshot.encode(s)
        val invalid = listOf(
            params("metadata[bad]" to "must_not_persist", "phases[1][end_date]" to "1"),
            params("metadata[bad]" to "must_not_persist", "end_behavior" to "refund"),
            params("phases[0][items][0][price]" to "price_year"),
            params("phases[0][items][0][price]" to "price_month", "phases[0][items][0][quantity]" to "-1"),
            params("phases[0][items][0][price]" to "price_month", "phases[0][end_date]" to (sub.currentPeriodEnd + 1).toString()),
            params("phases[0][items][0][price]" to "price_month", "phases[0][start_date]" to (sub.currentPeriodStart + 1).toString()),
            params("phases[0][items][0][price]" to "price_month", "phases[0][duration][interval]" to "hour"),
        )
        invalid.forEach { input ->
            assertFailsWith<StripeException> { ScheduleOps.update(s, schedule, input) }
            assertEquals(before, Snapshot.encode(s))
        }
        assertFailsWith<StripeException> { BillingOps.updateSubscription(s, sub, params("cancel_at_period_end" to "true")) }
        assertEquals(before, Snapshot.encode(s))
        assertFailsWith<StripeException> { ScheduleOps.cancel(s, schedule, params("prorate" to "true")) }
        assertEquals(before, Snapshot.encode(s))
    }

    @Test
    fun `HTTP create update release lost replies replay original identities and decode with Stripe SDK`() = testApplication {
        val (s, sub) = world()
        val simulator = sim(s)
        application { module(simulator, controlToken = "schedule-test-controller") }
        suspend fun fault(name: String, operation: String) {
            assertEquals(HttpStatusCode.Created, client.post("/v1/admin/faults") {
                header("X-Siere-Control-Token", "schedule-test-controller")
                header("Content-Type", "application/json")
                setBody("""{"name":"$name","match":{"operation":"$operation"},"kind":"commit_then_error","status":503,"count":1}""")
            }.status)
        }
        suspend fun post(path: String, key: String, fields: StripeParams) = client.post(path) {
            header("Authorization", "Bearer sk_test_schedule")
            header("Stripe-Version", "2025-06-30.basil")
            header("Idempotency-Key", key)
            setBody(FormDataContent(parametersOf(*fields.keys().map { it to listOf(fields.require(it)) }.toTypedArray())))
        }
        fault("create", "subscription_schedule.create")
        val create = params("from_subscription" to sub.id)
        assertEquals(HttpStatusCode.ServiceUnavailable, post("/v1/subscription_schedules", "create", create).status)
        val replay = post("/v1/subscription_schedules", "create", create)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        val body = replay.bodyAsText()
        val sdk = com.stripe.model.StripeObject.deserializeStripeObject(body, com.stripe.model.SubscriptionSchedule::class.java, com.stripe.net.ApiResource.getGlobalResponseGetter())
        assertEquals("active", sdk.status)
        assertEquals(sub.id, sdk.subscription)
        val id = sdk.id
        assertEquals(1, simulator.store.subscriptionSchedules.size)
        assertEquals(1, simulator.store.invoices.size)
        fault("update", "subscription_schedule.update")
        assertEquals(HttpStatusCode.ServiceUnavailable, post("/v1/subscription_schedules/$id", "phases", phases(sub)).status)
        val updated = post("/v1/subscription_schedules/$id", "phases", phases(sub))
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(2, simulator.store.requireSchedule(id).phases.size)
        assertEquals(1, simulator.store.invoices.size)
        val list = client.get("/v1/subscription_schedules?customer=${sub.customer}") { header("Authorization", "Bearer sk_test_schedule") }
        assertEquals(1, Json.parseToJsonElement(list.bodyAsText()).jsonObject["data"]!!.jsonArray.size)
        fault("release", "subscription_schedule.release")
        assertEquals(HttpStatusCode.ServiceUnavailable, post("/v1/subscription_schedules/$id/release", "release", params()).status)
        val released = post("/v1/subscription_schedules/$id/release", "release", params())
        assertEquals(HttpStatusCode.OK, released.status)
        assertEquals("released", simulator.store.requireSchedule(id).status)
        assertNull(simulator.store.requireSubscription(sub.id).schedule)
        assertEquals(1, simulator.store.invoices.size)
    }
}
