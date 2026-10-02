package com.fakestripe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull

/** Trials on the Subscriptions API and on Checkout, ended by the simulated clock. */
class TrialTest {

    @Test
    fun `trial_period_days - trialing, a zero invoice, the reminder three days ahead and the first real invoice at trial end`() =
        LiveSim().use { s ->
            s.resetAt()
            val (customer, _) = s.customerWithCard()
            val price = s.monthlyPrice(2900)
            val created = s.actor(
                "POST", "/v1/subscriptions",
                form("customer" to customer, "items[0][price]" to price, "trial_period_days" to "7", "metadata[storeId]" to "store-9"),
            )
            assertEquals(200, created.status, created.text)
            val sub = created.json
            val trialEnd = T0_SECONDS + 7 * DAY_SECONDS
            assertEquals("trialing", sub.s("status"))
            assertEquals(T0_SECONDS, sub.l("trial_start"))
            assertEquals(trialEnd, sub.l("trial_end"))
            assertEquals(trialEnd, sub.l("current_period_end"))
            assertEquals(trialEnd, sub.l("billing_cycle_anchor"))
            val zero = s.invoice(sub.s("latest_invoice"))
            assertEquals(0, zero.l("total"))
            assertEquals("paid", zero.s("status"))
            assertTrue(zero.lines().single().s("description").startsWith("Trial period for SSF Basic"))
            assertEquals(listOf("invoice.paid", "customer.subscription.created"), s.eventTypes().takeLast(2))

            s.advance(4 * DAY_SECONDS - 1)
            assertTrue("customer.subscription.trial_will_end" !in s.eventTypes())
            s.advance(1)
            assertEquals("customer.subscription.trial_will_end", s.eventTypes().last())

            s.advance(3 * DAY_SECONDS)
            val active = s.subscription(sub.s("id"))
            assertEquals("active", active.s("status"))
            assertEquals(trialEnd, active.l("current_period_start"))
            assertEquals(trialEnd + 30 * DAY_SECONDS, active.l("current_period_end"))
            assertEquals("store-9", active["metadata"]!!.obj().s("storeId"))
            val first = s.invoice(active.s("latest_invoice"))
            assertEquals(2900, first.l("total"))
            assertEquals("subscription_cycle", first.s("billing_reason"))
            assertEquals("paid", first.s("status"))
            assertEquals(trialEnd, first.l("created"))
            assertEquals(
                listOf("charge.succeeded", "payment_intent.succeeded", "invoice.paid", "customer.subscription.updated"),
                s.eventTypes().takeLast(4),
            )
        }

    @Test
    fun `trial_end, now, a short trial and the validation Stripe applies`() = LiveSim().use { s ->
        s.resetAt()
        val (customer, _) = s.customerWithCard()
        val price = s.monthlyPrice(1000)
        fun create(vararg extra: Pair<String, String>) =
            s.actor("POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to price, *extra))

        val explicit = create("trial_end" to (T0_SECONDS + 10 * DAY_SECONDS).toString()).json
        assertEquals(T0_SECONDS + 10 * DAY_SECONDS, explicit.l("trial_end"))
        val none = create("trial_end" to "now").json
        assertEquals("active", none.s("status"))
        assertEquals(JsonNull, none["trial_end"])
        assertEquals("active", create("trial_period_days" to "0").json.s("status"))

        // A trial shorter than three days is reminded straight away.
        val short = create("trial_period_days" to "1").json
        assertEquals("trialing", short.s("status"))
        assertEquals("customer.subscription.trial_will_end", s.eventTypes().last())

        for (bad in listOf(
            arrayOf("trial_end" to T0_SECONDS.toString()),
            arrayOf("trial_end" to "soon"),
            arrayOf("trial_end" to (T0_SECONDS + 731 * DAY_SECONDS).toString()),
            arrayOf("trial_period_days" to "731"),
            arrayOf("trial_period_days" to "seven"),
            arrayOf("trial_period_days" to "3", "trial_end" to (T0_SECONDS + 5 * DAY_SECONDS).toString()),
        )) {
            val reply = create(*bad)
            assertEquals(400, reply.status, bad.toList().toString())
            assertEquals("invalid_request_error", reply.json["error"]!!.obj().s("type"))
        }
    }

    @Test
    fun `a trial ending on a declining card is past_due, and one set to cancel ends with the trial`() = LiveSim().use { s ->
        s.resetAt()
        val (customer, _) = s.customerWithCard("4000000000009995")
        val price = s.monthlyPrice(1000)
        val declined = s.actor(
            "POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to price, "trial_period_days" to "5"),
        ).str("id")
        val leaving = s.actor(
            "POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to price, "trial_period_days" to "5"),
        ).str("id")
        s.actor("POST", "/v1/subscriptions/$leaving", form("cancel_at_period_end" to "true"))
        s.advance(5 * DAY_SECONDS)
        assertEquals("past_due", s.subscription(declined).s("status"))
        assertEquals("open", s.invoice(s.subscription(declined).s("latest_invoice")).s("status"))
        assertEquals("canceled", s.subscription(leaving).s("status"))
        assertTrue(s.eventTypes().containsAll(listOf("invoice.payment_failed", "customer.subscription.deleted")))
    }

    @Test
    fun `checkout subscription_data trials and the admin renew route end a trial early`() = LiveSim().use { s ->
        s.resetAt()
        val price = s.monthlyPrice(4900, "SSF Pro")
        val customer = s.actor("POST", "/v1/customers", form("email" to "trial@example.com")).str("id")
        val session = s.actor(
            "POST", "/v1/checkout/sessions",
            form(
                "mode" to "subscription", "customer" to customer, "line_items[0][price]" to price,
                "success_url" to "https://cp.example/ok?session={CHECKOUT_SESSION_ID}",
                "subscription_data[trial_period_days]" to "14", "subscription_data[metadata][storeId]" to "store-1",
            ),
        ).also { assertEquals(200, it.status, it.text) }.json
        assertEquals(0, session.l("amount_total"))
        val page = s.actor("GET", "/checkout/${session.s("id")}", key = null).text
        assertTrue(page.contains("14-day free trial") && page.contains("Start trial"))
        val paid = s.actor("POST", "/checkout/${session.s("id")}/pay", form("card_number" to "4242424242424242"), key = null)
        assertEquals(303, paid.status)
        val done = s.actor("GET", "/v1/checkout/sessions/${session.s("id")}").json
        assertEquals("complete", done.s("status"))
        assertEquals("no_payment_required", done.s("payment_status"))
        val sub = s.subscription(done.s("subscription"))
        assertEquals("trialing", sub.s("status"))
        assertEquals(T0_SECONDS + 14 * DAY_SECONDS, sub.l("trial_end"))
        assertEquals("store-1", sub["metadata"]!!.obj().s("storeId"))

        val renewed = s.control("POST", "/v1/admin/subscriptions/${sub.s("id")}/renew")
        assertEquals("active", renewed.str("status"))
        assertEquals(4900, s.invoice(renewed.str("latest_invoice")).l("total"))

        val withEnd = s.actor(
            "POST", "/v1/checkout/sessions",
            form(
                "mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://cp.example/ok",
                "subscription_data[trial_end]" to (T0_SECONDS + 3 * DAY_SECONDS).toString(),
            ),
        )
        assertEquals(200, withEnd.status, withEnd.text)
        assertTrue(s.actor("GET", "/checkout/${withEnd.str("id")}", key = null).text.contains("Free trial until 8 Oct 2026"))
        for (bad in listOf(
            "subscription_data[trial_end]" to (T0_SECONDS + 47 * 3600).toString(),
            "subscription_data[trial_period_days]" to "0",
            "subscription_data[trial_period_days]" to "731",
        )) {
            val reply = s.actor(
                "POST", "/v1/checkout/sessions",
                form("mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://cp.example/ok", bad),
            )
            assertEquals(400, reply.status, bad.toString())
        }
    }
}
