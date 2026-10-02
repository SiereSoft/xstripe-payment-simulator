package com.fakestripe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The SSF Control Plane's licence purchase, end to end: customer, a plan price made with
 * `product_data`, a Checkout Session with a plan and an add-on, the hosted page, the signed
 * webhooks the Control Plane consumes, and the billing portal.
 */
class CheckoutFidelityTest {

    private fun LiveSim.session(vararg params: Pair<String, String>): Reply =
        actor("POST", "/v1/checkout/sessions", form(*params))

    @Test
    fun `control plane purchase with a plan and an add-on`() = LiveSim().use { s ->
        Receiver().use { receiver ->
            s.resetAt()
            s.control("POST", "/v1/admin/webhook", """{"url":"${receiver.url}","secret":"whsec_cp"}""")
            val customer = s.actor(
                "POST", "/v1/customers",
                form("email" to "owner@shop.example", "name" to "Shop", "metadata[accountId]" to "acc-7"),
            ).str("id")
            val plan = s.monthlyPrice(2900, "SSF Basic")
            val addon = s.monthlyPrice(500, "Extra till")
            val planPrice = s.actor("GET", "/v1/prices/$plan").json
            assertEquals("recurring", planPrice.s("type"))
            assertEquals("ssf basic", planPrice["metadata"]!!.obj().s("planId"))
            assertEquals("SSF Basic", s.actor("GET", "/v1/products/${planPrice.s("product")}").str("name"))

            val created = s.session(
                "mode" to "subscription", "customer" to customer,
                "line_items[0][price]" to plan, "line_items[0][quantity]" to "1",
                "line_items[1][price]" to addon, "line_items[1][quantity]" to "3",
                "success_url" to "https://cp.example/billing/done?session={CHECKOUT_SESSION_ID}",
                "cancel_url" to "https://cp.example/billing/cancel",
                "client_reference_id" to "store-42", "metadata[storeId]" to "store-42",
                "subscription_data[metadata][storeId]" to "store-42",
            )
            assertEquals(200, created.status, created.text)
            val session = created.json
            assertEquals(2900L + 3 * 500, session.l("amount_total"))
            val page = s.actor("GET", "/checkout/${session.s("id")}", key = null).text
            assertTrue(page.contains("SSF Basic × 1") && page.contains("Extra till × 3"))
            for (card in listOf("4242424242424242", "4000000000000002", "4000002500003155")) assertTrue(page.contains(card))

            val paid = s.actor("POST", "/checkout/${session.s("id")}/pay", form("card_number" to "4242 4242 4242 4242"), key = null)
            assertEquals(303, paid.status)
            assertEquals("https://cp.example/billing/done?session=${session.s("id")}", paid.header("Location"))

            val expanded = s.actor(
                "GET", "/v1/checkout/sessions/${session.s("id")}?expand[]=subscription&expand[]=customer",
            ).json
            val subscription = expanded["subscription"]!!.obj()
            assertEquals("acc-7", expanded["customer"]!!.obj()["metadata"]!!.obj().s("accountId"))
            assertEquals("store-42", subscription["metadata"]!!.obj().s("storeId"))
            assertEquals(listOf(1L, 3L), subscription.items().map { it.l("quantity") })
            assertEquals(listOf(plan, addon), subscription.items().map { it["price"]!!.obj().s("id") })
            assertEquals("active", subscription.s("status"))
            assertEquals(4400, s.invoice(subscription.s("latest_invoice")).l("total"))

            val portal = s.actor("POST", "/v1/billing_portal/sessions", form("customer" to customer, "return_url" to "https://cp.example/account"))
            assertTrue(portal.str("url").endsWith("/billing_portal/${portal.str("id")}"))

            // What the Control Plane consumes, verified the way it verifies Stripe's signatures.
            val deadline = System.currentTimeMillis() + 5_000
            while (!receiver.eventTypes().contains("checkout.session.completed") && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val events = receiver.deliveries.associate { delivery ->
                val t = delivery.signature.substringAfter("t=").substringBefore(",")
                assertEquals(hmacHex("whsec_cp", "$t.${delivery.body}"), delivery.signature.substringAfter("v1="))
                val event = testJson.parseToJsonElement(delivery.body).obj()
                event.s("type") to event["data"]!!.obj()["object"]!!.obj()
            }
            val completed = events.getValue("checkout.session.completed")
            assertEquals("store-42", completed.s("client_reference_id"))
            assertEquals("store-42", completed["metadata"]!!.obj().s("storeId"))
            assertEquals(subscription.s("id"), completed.s("subscription"))
            assertEquals("store-42", events.getValue("customer.subscription.created")["metadata"]!!.obj().s("storeId"))
            assertEquals(subscription.s("id"), events.getValue("invoice.paid").s("subscription"))
            val order = receiver.eventTypes()
            assertTrue(order.indexOf("customer.subscription.created") < order.indexOf("checkout.session.completed"))
        }
    }

    @Test
    fun `authentication-required cards go through the simulated 3-D Secure page`() = LiveSim().use { s ->
        s.resetAt()
        val price = s.monthlyPrice(1000)
        val session = s.session(
            "mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://cp.example/ok",
            "customer_email" to "sca@example.com",
        ).str("id")

        val challenge = s.actor("POST", "/checkout/$session/pay", form("card_number" to "4000002500003155"), key = null)
        assertEquals(303, challenge.status)
        assertEquals("/checkout/$session/authenticate", challenge.header("Location"))
        val page = s.actor("GET", "/checkout/$session/authenticate", key = null).text
        assertTrue(page.contains("Complete authentication") && page.contains("Fail authentication") && page.contains("3155"))

        val failed = s.actor("POST", "/checkout/$session/authenticate", form("outcome" to "fail"), key = null)
        assertTrue(failed.header("Location")!!.startsWith("/checkout/$session?error="))
        assertEquals("open", s.actor("GET", "/v1/checkout/sessions/$session").str("status"))
        assertTrue(s.actor("GET", "/checkout/$session/authenticate", key = null).text.contains("Nothing to authenticate"))
        val nothing = s.actor("POST", "/checkout/$session/authenticate", form("outcome" to "complete"), key = null)
        assertTrue(nothing.header("Location")!!.contains("error="))

        s.actor("POST", "/checkout/$session/pay", form("card_number" to "4000002500003155"), key = null)
        val done = s.actor("POST", "/checkout/$session/authenticate", form("outcome" to "complete"), key = null)
        assertEquals("https://cp.example/ok", done.header("Location"))
        val completed = s.actor("GET", "/v1/checkout/sessions/$session").json
        assertEquals("complete", completed.s("status"))
        val sub = completed.s("subscription")
        assertEquals("active", s.subscription(sub).s("status"))
        assertEquals("https://cp.example/ok", s.actor("POST", "/checkout/$session/authenticate", form("outcome" to "complete"), key = null).header("Location"))

        // A card set up with authentication renews off-session without another challenge.
        s.advance(30 * DAY_SECONDS)
        assertEquals("active", s.subscription(sub).s("status"))
        assertEquals("paid", s.invoice(s.subscription(sub).s("latest_invoice")).s("status"))

        // A card that always asks for authentication cannot renew off-session.
        val always = s.session("mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://cp.example/ok").str("id")
        s.actor("POST", "/checkout/$always/pay", form("card_number" to "4000000000003220"), key = null)
        s.actor("POST", "/checkout/$always/authenticate", form("outcome" to "complete"), key = null)
        val alwaysSub = s.actor("GET", "/v1/checkout/sessions/$always").json.s("subscription")
        assertEquals("active", s.subscription(alwaysSub).s("status"))
        s.advance(30 * DAY_SECONDS)
        assertEquals("past_due", s.subscription(alwaysSub).s("status"))

        // Payment mode: the authenticated payment succeeds.
        val oneTime = s.actor("POST", "/v1/prices", form("currency" to "eur", "unit_amount" to "700", "product_data[name]" to "Setup")).str("id")
        val pay = s.session("mode" to "payment", "line_items[0][price]" to oneTime, "success_url" to "https://cp.example/ok").str("id")
        s.actor("POST", "/checkout/$pay/pay", form("card_number" to "4000000000003220"), key = null)
        s.actor("POST", "/checkout/$pay/authenticate", form("outcome" to "complete"), key = null)
        val pi = s.actor("GET", "/v1/checkout/sessions/$pay").json.s("payment_intent")
        assertEquals("succeeded", s.actor("GET", "/v1/payment_intents/$pi").str("status"))

        assertEquals(404, s.actor("GET", "/checkout/cs_missing/authenticate", key = null).status)
        assertEquals(404, s.actor("POST", "/checkout/cs_missing/authenticate", form("outcome" to "complete"), key = null).status)
    }

    @Test
    fun `declines, expiry and the validation Checkout applies`() = LiveSim().use { s ->
        s.resetAt()
        val price = s.monthlyPrice(1000)
        val yearly = s.monthlyPrice(10000, "Yearly", interval = "year")
        val usd = s.monthlyPrice(1000, "Dollar plan", currency = "usd")
        val session = s.session("mode" to "subscription", "line_items[0][price]" to price, "success_url" to "https://cp.example/ok").str("id")
        val declined = s.actor("POST", "/checkout/$session/pay", form("card_number" to "4000000000000002"), key = null)
        assertTrue(declined.header("Location")!!.contains("error="))
        assertTrue(s.actor("GET", declined.header("Location")!!, key = null).text.contains("Your card was declined."))
        assertEquals("open", s.actor("GET", "/v1/checkout/sessions/$session").str("status"))

        val expired = s.actor("POST", "/v1/checkout/sessions/$session/expire")
        assertEquals("expired", expired.str("status"))
        assertEquals("checkout.session.expired", s.eventTypes().last())
        assertEquals("expired", s.actor("POST", "/v1/checkout/sessions/$session/expire").str("status"))
        assertTrue(s.actor("POST", "/checkout/$session/pay", form("card_number" to "4242424242424242"), key = null).header("Location")!!.contains("expired"))
        assertTrue(s.actor("POST", "/checkout/$session/authenticate", form("outcome" to "complete"), key = null).header("Location")!!.contains("expired"))

        for (bad in listOf(
            arrayOf("line_items[0][price]" to price, "line_items[0][quantity]" to "0"),
            arrayOf("line_items[0][price]" to price, "line_items[1][price]" to yearly),
            arrayOf("line_items[0][price]" to price, "line_items[1][price]" to usd),
        )) {
            val reply = s.session("mode" to "subscription", "success_url" to "https://cp.example/ok", *bad)
            assertEquals(400, reply.status, bad.toList().toString())
        }
        val oneTime = s.actor("POST", "/v1/prices", form("currency" to "eur", "unit_amount" to "700", "product_data[name]" to "Setup")).str("id")
        val oneTimeUsd = s.actor("POST", "/v1/prices", form("currency" to "usd", "unit_amount" to "700", "product_data[name]" to "Setup")).str("id")
        assertEquals(400, s.session("mode" to "payment", "line_items[0][price]" to oneTime, "success_url" to "https://x.example", "subscription_data[metadata][a]" to "b").status)
        assertEquals(400, s.session("mode" to "payment", "line_items[0][price]" to oneTime, "line_items[1][price]" to oneTimeUsd, "success_url" to "https://x.example").status)
    }

    @Test
    fun `prices create their product inline with product_data`() = LiveSim().use { s ->
        s.resetAt()
        val created = s.actor(
            "POST", "/v1/prices",
            form("currency" to "EUR", "unit_amount" to "2900", "recurring[interval]" to "month", "product_data[name]" to "Inline", "product_data[metadata][tier]" to "basic"),
        )
        assertEquals(200, created.status, created.text)
        assertEquals("eur", created.str("currency"))
        val product = s.actor("GET", "/v1/products/${created.str("product")}").json
        assertEquals("Inline", product.s("name"))
        assertEquals("basic", product["metadata"]!!.obj().s("tier"))
        val existing = product.s("id")
        val replies = listOf(
            form("currency" to "eur", "unit_amount" to "1", "product" to existing, "product_data[name]" to "Both"),
            form("currency" to "eur", "unit_amount" to "1"),
            form("currency" to "eur", "unit_amount" to "1", "product_data[active]" to "true"),
            form("currency" to "eur", "unit_amount" to "1", "product" to existing, "recurring[interval]" to "fortnight"),
        ).map { s.actor("POST", "/v1/prices", it) }
        assertEquals(listOf(400, 400, 400, 400), replies.map { it.status })
        assertEquals("product", replies[1].json["error"]!!.obj().s("param"))
        assertEquals("product_data[name]", replies[2].json["error"]!!.obj().s("param"))
        val inactive = s.actor("POST", "/v1/prices", form("currency" to "eur", "unit_amount" to "1", "product_data[name]" to "Off", "product_data[active]" to "false"))
        assertFalse(s.actor("GET", "/v1/products/${inactive.str("product")}").json.s("active").toBoolean())
    }

    private fun JsonObject.items(): List<JsonObject> = this["items"]!!.jsonObject["data"]!!.arr().map { it.obj() }
}
