package com.fakestripe

import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionItem
import com.fakestripe.store.ClockMode
import com.fakestripe.store.Simulator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Work that falls due on the simulated clock, settled in time order. */
class SchedulerTest {

    @Test
    fun `due work of every kind is settled in time order`() = LiveSim().use { s ->
        s.resetAt()
        val (customer, pm) = s.customerWithCard()
        val monthly = s.monthlyPrice(1000)
        val renewing = s.actor("POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to monthly)).str("id")
        val trialing = s.actor(
            "POST", "/v1/subscriptions",
            form("customer" to customer, "items[0][price]" to monthly, "default_payment_method" to pm, "trial_period_days" to "14"),
        ).str("id")
        val oneTime = s.actor(
            "POST", "/v1/prices", form("currency" to "eur", "unit_amount" to "500", "product_data[name]" to "Setup"),
        ).str("id")
        val session = s.actor(
            "POST", "/v1/checkout/sessions",
            form("mode" to "payment", "line_items[0][price]" to oneTime, "success_url" to "https://example.com/ok"),
        ).str("id")

        val moved = s.advance(31 * DAY_SECONDS)
        assertEquals(4, moved.json["settled"]!!.str().toInt())
        val work = s.control("GET", "/v1/admin/journal?kind=state").json["entries"]!!.arr().map { it.obj() }
        assertEquals(
            listOf("checkout.session.expire", "subscription.trial_will_end", "subscription.trial_end", "subscription.renew"),
            work.map { it.s("operation") },
        )
        assertEquals(
            listOf("2026-10-06T08:00:01.000Z", "2026-10-16T08:00:00.000Z", "2026-10-19T08:00:00.000Z", "2026-11-04T08:00:00.000Z"),
            work.map { it.s("at") },
        )
        assertEquals(session, work[0]["refs"]!!.obj().s("checkout_session"))
        assertEquals(trialing, work[1]["refs"]!!.obj().s("subscription"))
        assertEquals(renewing, work[3]["refs"]!!.obj().s("subscription"))
        assertEquals("expired", s.actor("GET", "/v1/checkout/sessions/$session").str("status"))
        val types = s.eventTypes()
        assertTrue(types.containsAll(listOf("checkout.session.expired", "customer.subscription.trial_will_end")))

        // Objects created by scheduled work carry the time the work fell due, not the time of the move.
        val renewal = s.invoice(s.subscription(renewing).s("latest_invoice"))
        assertEquals("subscription_cycle", renewal.s("billing_reason"))
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, renewal.l("created"))
        assertEquals(T0_SECONDS + 31 * DAY_SECONDS, s.control("GET", "/v1/admin/clock").json.l("current_time"))
    }

    @Test
    fun `a declined renewal is past_due until a later renewal collects, and period-end cancellation ends it`() = LiveSim().use { s ->
        s.resetAt()
        val (customer, pm) = s.customerWithCard()
        val price = s.monthlyPrice(1500)
        val sub = s.actor("POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to price)).str("id")
        assertEquals(pm, s.subscription(sub).s("default_payment_method"))
        val declining = s.actor("POST", "/v1/payment_methods", form("type" to "card", "card[number]" to "4000000000000002")).str("id")
        s.actor("POST", "/v1/payment_methods/$declining/attach", form("customer" to customer))
        s.actor("POST", "/v1/subscriptions/$sub", form("default_payment_method" to declining))

        s.advance(30 * DAY_SECONDS)
        assertEquals("past_due", s.subscription(sub).s("status"))
        val failed = s.invoice(s.subscription(sub).s("latest_invoice"))
        assertEquals("open", failed.s("status"))
        assertTrue("invoice.payment_failed" in s.eventTypes())

        s.actor("POST", "/v1/subscriptions/$sub", form("default_payment_method" to pm))
        s.advance(30 * DAY_SECONDS)
        assertEquals("active", s.subscription(sub).s("status"))

        s.actor("POST", "/v1/subscriptions/$sub", form("cancel_at_period_end" to "true"))
        s.advance(30 * DAY_SECONDS)
        val ended = s.subscription(sub)
        assertEquals("canceled", ended.s("status"))
        assertEquals(T0_SECONDS + 90 * DAY_SECONDS, ended.l("ended_at"))
        assertEquals("customer.subscription.deleted", s.eventTypes().last())
        val invoices = s.actor("GET", "/v1/invoices?subscription=$sub&limit=100").json["data"]!!.arr()
        assertEquals(3, invoices.size, "create, failed renewal, paid renewal; none after cancellation")
    }

    @Test
    fun `in free mode the background scheduler settles work as the wall clock reaches it`() {
        var wall = T0_SECONDS
        val sim = Simulator.boot(
            Files.createTempDirectory("fs-free-scheduler").resolve("state.json"),
            9L,
            defaultClockMode = ClockMode.FREE,
            wallTimeSeconds = { wall },
        )
        val subId = sim.write { store ->
            val customer = store.customers.values.first { it.defaultPaymentMethod != null }
            val price = store.prices.values.first { it.recurringInterval == "month" }
            com.fakestripe.billing.BillingOps.create(
                store, customer.id, listOf(com.fakestripe.billing.BillingOps.ItemSpec(price.id, 1)),
                customer.defaultPaymentMethod, emptyMap(), trialEnd = null, itemParam = "items",
            ).id
        }
        wall += 30 * DAY_SECONDS + 5
        sim.pumpOnce()
        val renewed = sim.read { it.subscriptions.getValue(subId) }
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, renewed.currentPeriodStart)
        assertEquals(2, sim.read { store -> store.invoices.values.count { it.subscription == subId } })
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, sim.read { store -> store.invoices.getValue(renewed.latestInvoice!!).created })
        sim.close()
    }

    @Test
    fun `broken scheduled work is logged and skipped instead of stopping the clock`() {
        val sim = Simulator.boot(Files.createTempDirectory("fs-broken-work").resolve("state.json"), 2L, defaultClockMode = ClockMode.MANUAL)
        sim.write { store ->
            val now = store.now()
            store.subscriptions["sub_broken"] = Subscription(
                id = "sub_broken", created = now, customer = store.customers.keys.first(), status = "active",
                items = mutableListOf(SubscriptionItem("si_broken", now, "price_missing", 1, "sub_broken")),
                currentPeriodStart = now, currentPeriodEnd = now + 60, currency = "usd",
            )
        }
        val moved = sim.moveClockTo(sim.read { it.nowMs() } + 3_600_000)
        assertEquals(0, moved.settled)
        assertEquals(sim.read { it.now() }, sim.read { it.subscriptions.getValue("sub_broken").currentPeriodStart } + 3600)
        sim.close()
    }
}
