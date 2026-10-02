package com.fakestripe

import com.fakestripe.store.Simulator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull

/** The rest of the actor surface, end to end over HTTP, including its error answers. */
class ApiSurfaceTest {

    @Test
    fun `payment methods and payment intents`() = LiveSim().use { s ->
        s.resetAt()
        val customer = s.actor("POST", "/v1/customers", form("email" to "pm@example.com")).str("id")
        val pm = s.actor(
            "POST", "/v1/payment_methods",
            form("type" to "card", "card[number]" to "5555555555554444", "billing_details[name]" to "Ann", "metadata[k]" to "v"),
        ).json
        assertEquals("mastercard", pm["card"]!!.obj().s("brand"))
        assertEquals(400, s.actor("POST", "/v1/payment_methods", form("type" to "sepa_debit")).status)
        assertEquals(400, s.actor("POST", "/v1/payment_methods", form("type" to "card")).status)
        val updated = s.actor("POST", "/v1/payment_methods/${pm.s("id")}", form("billing_details[email]" to "ann@example.com", "billing_details[phone]" to "+1", "billing_details[name]" to "Ann B", "metadata[k]" to "")).json
        assertEquals("ann@example.com", updated["billing_details"]!!.obj().s("email"))
        assertTrue(updated["metadata"]!!.obj().isEmpty())
        s.actor("POST", "/v1/payment_methods/${pm.s("id")}/attach", form("customer" to customer))
        s.actor("POST", "/v1/customers/$customer", form("invoice_settings[default_payment_method]" to pm.s("id")))
        assertEquals(customer, s.actor("GET", "/v1/payment_methods/${pm.s("id")}").str("customer"))
        assertEquals(1, s.actor("GET", "/v1/payment_methods?customer=$customer").json["data"]!!.arr().size)
        s.actor("POST", "/v1/payment_methods/${pm.s("id")}/detach")
        assertEquals(JsonNull, s.actor("GET", "/v1/customers/$customer").json["invoice_settings"]!!.obj()["default_payment_method"])

        val manual = s.actor("POST", "/v1/payment_intents", form("amount" to "1500", "currency" to "usd", "capture_method" to "manual", "customer" to customer)).json
        assertEquals("requires_payment_method", manual.s("status"))
        val withPm = s.actor("POST", "/v1/payment_intents/${manual.s("id")}", form("payment_method" to "pm_card_visa", "description" to "order 1", "receipt_email" to "r@example.com", "metadata[o]" to "1")).json
        assertEquals("requires_confirmation", withPm.s("status"))
        assertEquals("requires_capture", s.actor("POST", "/v1/payment_intents/${manual.s("id")}/confirm", "").str("status"))
        assertEquals(400, s.actor("POST", "/v1/payment_intents/${manual.s("id")}/confirm", "").status)
        val captured = s.actor("POST", "/v1/payment_intents/${manual.s("id")}/capture", form("amount_to_capture" to "1000")).json
        assertEquals("succeeded", captured.s("status"))
        assertEquals(1000, captured.l("amount_received"))
        assertEquals(400, s.actor("POST", "/v1/payment_intents/${manual.s("id")}/capture", "").status)
        assertEquals(400, s.actor("POST", "/v1/payment_intents/${manual.s("id")}/cancel", "").status)
        val toCancel = s.actor("POST", "/v1/payment_intents", form("amount" to "300", "currency" to "usd")).str("id")
        assertEquals("canceled", s.actor("POST", "/v1/payment_intents/$toCancel/cancel", form("cancellation_reason" to "duplicate")).str("status"))
        assertEquals(1, s.actor("GET", "/v1/payment_intents?customer=$customer").json["data"]!!.arr().size)
        assertEquals("order 1", s.actor("GET", "/v1/payment_intents/${manual.s("id")}").str("description"))
        val sca = s.actor("POST", "/v1/payment_intents", form("amount" to "300", "currency" to "usd", "payment_method" to "pm_card_authenticationRequired", "confirm" to "true"))
        assertEquals("requires_action", sca.str("status"))
        assertEquals(400, s.actor("POST", "/v1/payment_intents", form("amount" to "0", "currency" to "usd")).status)
        assertEquals(400, s.actor("POST", "/v1/payment_intents", form("currency" to "usd")).status)
        assertEquals(400, s.actor("POST", "/v1/payment_intents/$toCancel/confirm", "").status)
        assertEquals(404, s.actor("GET", "/v1/payment_intents/pi_missing").status)
        assertEquals(404, s.actor("POST", "/v1/payment_intents", form("amount" to "300", "currency" to "usd", "payment_method" to "pm_missing")).status)
    }

    @Test
    fun `charges, refunds, products, prices, invoices, events and customers`() = LiveSim().use { s ->
        s.resetAt()
        val customer = s.actor("POST", "/v1/customers", form("email" to "money@example.com")).str("id")
        val pi = s.actor("POST", "/v1/payment_intents", form("amount" to "2000", "currency" to "usd", "customer" to customer, "payment_method" to "pm_card_visa", "confirm" to "true")).json
        val charge = pi.s("latest_charge")
        assertEquals("v", s.actor("POST", "/v1/charges/$charge", form("metadata[k]" to "v")).json["metadata"]!!.obj().s("k"))
        assertEquals(charge, s.actor("GET", "/v1/charges?payment_intent=${pi.s("id")}").json["data"]!!.arr()[0].obj().s("id"))
        assertEquals(1, s.actor("GET", "/v1/charges?customer=$customer").json["data"]!!.arr().size)

        val nested = s.actor("POST", "/v1/charges/$charge/refunds", form("amount" to "500")).json
        assertEquals("re_", nested.s("id").take(3))
        val byIntent = s.actor("POST", "/v1/refunds", form("payment_intent" to pi.s("id"), "amount" to "500", "reason" to "requested_by_customer")).json
        assertEquals("note", s.actor("POST", "/v1/refunds/${byIntent.s("id")}", form("metadata[why]" to "note")).json["metadata"]!!.obj().s("why"))
        assertEquals(byIntent.s("id"), s.actor("GET", "/v1/refunds/${byIntent.s("id")}").str("id"))
        assertEquals(2, s.actor("GET", "/v1/refunds?charge=$charge").json["data"]!!.arr().size)
        assertEquals(2, s.actor("GET", "/v1/refunds?payment_intent=${pi.s("id")}").json["data"]!!.arr().size)
        assertEquals(400, s.actor("POST", "/v1/refunds", form("charge" to charge, "amount" to "5000")).status)
        assertEquals(400, s.actor("POST", "/v1/refunds", form("charge" to charge, "amount" to "-1")).status)
        assertEquals(400, s.actor("POST", "/v1/refunds", "").status)
        s.actor("POST", "/v1/refunds", form("charge" to charge))
        assertEquals("charge_already_refunded", s.actor("POST", "/v1/refunds", form("charge" to charge)).json["error"]!!.obj().s("code"))
        val unpaid = s.actor("POST", "/v1/payment_intents", form("amount" to "100", "currency" to "usd")).str("id")
        assertEquals(400, s.actor("POST", "/v1/refunds", form("payment_intent" to unpaid)).status)
        val declined = s.actor("POST", "/v1/payment_intents", form("amount" to "100", "currency" to "usd", "payment_method" to "pm_card_chargeDeclined", "confirm" to "true"))
        assertEquals(402, declined.status)
        val failedCharge = declined.json["error"]!!.obj()["payment_intent"]!!.obj().s("latest_charge")
        assertEquals(400, s.actor("POST", "/v1/refunds", form("charge" to failedCharge)).status)

        val product = s.actor("POST", "/v1/products", form("name" to "Widget", "description" to "d", "metadata[a]" to "b")).str("id")
        assertEquals("Gadget", s.actor("POST", "/v1/products/$product", form("name" to "Gadget", "description" to "e", "active" to "false", "default_price" to "price_x", "metadata[a]" to "")).str("name"))
        assertTrue(s.actor("GET", "/v1/products?active=false").json["data"]!!.arr().any { it.obj().s("id") == product })
        val price = s.actor("POST", "/v1/prices", form("product" to product, "currency" to "usd", "unit_amount" to "100", "nickname" to "n")).str("id")
        assertEquals("m", s.actor("POST", "/v1/prices/$price", form("nickname" to "m", "active" to "false", "metadata[x]" to "y")).str("nickname"))
        assertEquals(1, s.actor("GET", "/v1/prices?product=$product&active=false").json["data"]!!.arr().size)
        assertEquals("true", s.actor("DELETE", "/v1/products/$product").json.s("deleted"))

        val (subscriber, _) = s.customerWithCard(email = "inv@example.com")
        val plan = s.monthlyPrice(1200)
        val sub = s.actor("POST", "/v1/subscriptions", form("customer" to subscriber, "items[0][price]" to plan)).json
        val invoice = sub.s("latest_invoice")
        assertEquals("y", s.actor("POST", "/v1/invoices/$invoice", form("metadata[x]" to "y")).json["metadata"]!!.obj().s("x"))
        assertEquals(1, s.actor("GET", "/v1/invoices?customer=$subscriber&status=paid").json["data"]!!.arr().size)
        assertEquals("void", s.actor("POST", "/v1/invoices/$invoice/void", "").str("status"))
        assertEquals("paid", s.actor("POST", "/v1/invoices/$invoice/pay", "").str("status"))
        assertEquals(1, s.actor("GET", "/v1/subscriptions?customer=$subscriber&status=active").json["data"]!!.arr().size)

        val event = s.actor("GET", "/v1/events?limit=1&episode_id=ep-1").json["data"]!!.arr()[0].obj()
        assertEquals(event.s("id"), s.actor("GET", "/v1/events/${event.s("id")}").str("id"))
        assertEquals(1, s.actor("GET", "/v1/customers?email=money@example.com").json["data"]!!.arr().size)
        assertEquals("Money", s.actor("POST", "/v1/customers/$customer", form("name" to "Money", "description" to "d", "phone" to "1", "metadata[a]" to "b")).str("name"))
        assertEquals("true", s.actor("DELETE", "/v1/customers/$subscriber").json.s("deleted"))
        assertEquals("canceled", s.subscription(sub.s("id")).s("status"))
        assertEquals(404, s.actor("GET", "/v1/customers/$subscriber").status)
        assertEquals(200, s.actor("GET", "/", key = null).status)
        assertEquals(404, s.actor("GET", "/v1/nothing-here").status)
    }

    @Test
    fun `a 0_1 state file with a scenario fault loads as a named fault`() {
        val path = Files.createTempDirectory("fs-legacy").resolve("state.json")
        Files.writeString(
            path,
            """{"seed":7,"idCount":0,"revision":3,"faultInjection":{"id":"refund_response_loss_once","operation":"refund.create",
               "phase":"after_commit","responseStatus":503,"responseCode":"injected_response_loss","remaining":1,"injectedCount":0},
               "customers":[],"paymentMethods":[],"paymentIntents":[],"charges":[]}""",
        )
        val sim = Simulator.boot(path, 1L)
        val fault = sim.faults().single()
        assertEquals("refund-response-loss", fault.name)
        assertEquals("commit_then_error", fault.kind)
        assertEquals(1, fault.remaining)
        assertEquals(3, sim.read { it.revision })
        assertEquals("refund_response_loss_once", sim.read { it.faultInjection!!.id })
        sim.close()
    }
}
