package com.fakestripe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quantity and item changes prorated as Stripe prorates them. */
class ProrationTest {

    /** A Basic subscription (10.00/month) ten days into its 30-day period, with a 5.00 add-on price. */
    private fun LiveSim.tenDaysIn(): Triple<String, String, String> {
        resetAt()
        val (customer, _) = customerWithCard()
        val basic = monthlyPrice(1000, "Basic")
        val addon = monthlyPrice(500, "Add-on")
        val sub = actor("POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to basic)).str("id")
        advance(10 * DAY_SECONDS)
        return Triple(sub, subscription(sub).items().single().s("id"), addon)
    }

    @Test
    fun `create_prorations waits for the next invoice`() = LiveSim().use { s ->
        val (sub, item, _) = s.tenDaysIn()
        val before = s.subscription(sub).s("latest_invoice")
        val updated = s.actor("POST", "/v1/subscription_items/$item", form("quantity" to "3", "metadata[seat]" to "front"))
        assertEquals(200, updated.status, updated.text)
        assertEquals(3, updated.json.l("quantity"))
        assertEquals("subscription_item", updated.str("object"))
        assertEquals("front", updated.json["metadata"]!!.obj().s("seat"))
        assertEquals(before, s.subscription(sub).s("latest_invoice"), "nothing is invoiced yet")
        assertEquals("customer.subscription.updated", s.eventTypes().last())

        s.advance(20 * DAY_SECONDS)
        val renewal = s.invoice(s.subscription(sub).s("latest_invoice"))
        assertEquals("subscription_cycle", renewal.s("billing_reason"))
        val prorations = renewal.lines().filter { it.s("proration") == "true" }
        assertEquals(listOf(-667L, 2000L), prorations.map { it.l("amount") })
        assertTrue(prorations[0].s("description").startsWith("Unused time on 1 × Basic"))
        assertTrue(prorations[1].s("description").startsWith("Remaining time on 3 × Basic"))
        assertEquals(3000L - 667 + 2000, renewal.l("total"))
        assertEquals("paid", renewal.s("status"))
    }

    @Test
    fun `always_invoice bills now and none bills nothing`() = LiveSim().use { s ->
        val (sub, item, _) = s.tenDaysIn()
        val now = s.actor("POST", "/v1/subscription_items/$item", form("quantity" to "3", "proration_behavior" to "always_invoice"))
        assertEquals(200, now.status, now.text)
        val invoice = s.invoice(s.subscription(sub).s("latest_invoice"))
        assertEquals("subscription_update", invoice.s("billing_reason"))
        assertEquals(1333, invoice.l("total"))
        assertEquals("paid", invoice.s("status"))

        s.actor("POST", "/v1/subscription_items/$item", form("quantity" to "1", "proration_behavior" to "none"))
        s.advance(20 * DAY_SECONDS)
        val renewal = s.invoice(s.subscription(sub).s("latest_invoice"))
        assertTrue(renewal.lines().none { it.s("proration") == "true" })
        assertEquals(1000, renewal.l("total"))
    }

    @Test
    fun `items on a subscription update add, change and remove items`() = LiveSim().use { s ->
        val (sub, item, addon) = s.tenDaysIn()
        val added = s.actor("POST", "/v1/subscriptions/$sub", form("items[0][price]" to addon, "items[0][quantity]" to "2", "items[0][metadata][kind]" to "addon"))
        assertEquals(200, added.status, added.text)
        val items = added.json.items()
        assertEquals(2, items.size)
        val addonItem = items.last()
        assertEquals("addon", addonItem["metadata"]!!.obj().s("kind"))

        val listed = s.actor("GET", "/v1/subscription_items?subscription=$sub").json["data"]!!.arr()
        assertEquals(2, listed.size)
        assertEquals(addon, s.actor("GET", "/v1/subscription_items/${addonItem.s("id")}").json["price"]!!.obj().s("id"))

        val changed = s.actor(
            "POST", "/v1/subscriptions/$sub",
            form("items[0][id]" to item, "items[0][quantity]" to "2", "items[1][id]" to addonItem.s("id"), "items[1][deleted]" to "true",
                "proration_behavior" to "always_invoice"),
        )
        assertEquals(200, changed.status, changed.text)
        assertEquals(1, changed.json.items().size)
        val invoice = s.invoice(changed.json.s("latest_invoice"))
        // Pending: +667 for the add-on (2 × 5.00 × 20/30); now: -667 / +1333 for Basic, -667 for the removed add-on.
        assertEquals(listOf(-667L, 1333L, -667L, 667L), invoice.lines().map { it.l("amount") })
        assertEquals(666, invoice.l("total"))

        val errors = listOf(
            s.actor("POST", "/v1/subscriptions/$sub", form("items[0][id]" to "si_missing", "items[0][quantity]" to "1")),
            s.actor("POST", "/v1/subscriptions/$sub", form("items[0][quantity]" to "1")),
            s.actor("POST", "/v1/subscriptions/$sub", form("items[0][id]" to item, "items[0][deleted]" to "true")),
            s.actor("POST", "/v1/subscriptions/$sub", form("items[0][id]" to item, "items[0][quantity]" to "-1")),
            s.actor("POST", "/v1/subscriptions/$sub", form("items[0][id]" to item, "items[0][quantity]" to "2", "proration_behavior" to "sometimes")),
            s.actor("POST", "/v1/subscription_items/si_missing", form("quantity" to "2")),
            s.actor("GET", "/v1/subscription_items/si_missing"),
            s.actor("GET", "/v1/subscription_items"),
        )
        assertEquals(listOf(404, 400, 400, 400, 400, 404, 404, 400), errors.map { it.status })
    }

    @Test
    fun `trials are not prorated and canceled subscriptions refuse item changes`() = LiveSim().use { s ->
        s.resetAt()
        val (customer, _) = s.customerWithCard()
        val basic = s.monthlyPrice(1000, "Basic")
        val trial = s.actor("POST", "/v1/subscriptions", form("customer" to customer, "items[0][price]" to basic, "trial_period_days" to "10")).json
        val item = trial.items().single().s("id")
        s.advance(DAY_SECONDS)
        s.actor("POST", "/v1/subscription_items/$item", form("quantity" to "4"))
        s.advance(9 * DAY_SECONDS)
        val first = s.invoice(s.subscription(trial.s("id")).s("latest_invoice"))
        assertEquals(4000, first.l("total"))
        assertTrue(first.lines().none { it.s("proration") == "true" })

        s.actor("DELETE", "/v1/subscriptions/${trial.s("id")}")
        assertEquals(400, s.actor("POST", "/v1/subscription_items/$item", form("quantity" to "1")).status)
        assertEquals(400, s.actor("POST", "/v1/subscriptions/${trial.s("id")}", form("items[0][id]" to item, "items[0][quantity]" to "1")).status)
        assertEquals(200, s.actor("POST", "/v1/subscriptions/${trial.s("id")}", form("metadata[note]" to "kept")).status)
    }
}
