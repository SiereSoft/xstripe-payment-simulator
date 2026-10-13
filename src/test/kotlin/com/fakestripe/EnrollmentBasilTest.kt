package com.fakestripe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The SSF enrollment server's Stripe adapter as the client: API version 2025-06-30.basil, Checkout
 * with inline `price_data`, the paid first invoice with its InvoicePayment, renewals on the simulated
 * clock, a paid addition, a price amendment, cancellation at period end — and the checks it makes
 * on every answer.
 */
class EnrollmentBasilTest {

    private val basil = mapOf("Stripe-Version" to "2025-06-30.basil")

    private fun LiveSim.get(path: String): Reply = actor("GET", path, headers = basil)
    private fun LiveSim.post(path: String, body: String, key: String): Reply =
        actor("POST", path, body, headers = basil + ("Idempotency-Key" to key))

    private val metadata = listOf(
        "ssf_purchase_id" to "p-1", "ssf_subscriber_id" to "s-1", "ssf_business_id" to "b-1",
        "ssf_operator_account" to "acct_ssf_local",
    )

    private fun subscriptionCheckout(amount: Long, interval: String = "month", purchase: String = "p-1", currency: String = "eur") = form(
        "mode" to "subscription",
        "success_url" to "https://127.0.0.1/done", "cancel_url" to "https://127.0.0.1/done",
        "client_reference_id" to purchase, "payment_method_types[0]" to "card",
        "line_items[0][quantity]" to "1", "line_items[0][price_data][currency]" to currency,
        "line_items[0][price_data][unit_amount]" to amount.toString(),
        "line_items[0][price_data][product_data][name]" to "SSF subscription",
        "automatic_tax[enabled]" to "false", "allow_promotion_codes" to "false",
        *metadata.flatMap { (k, v) -> listOf("metadata[$k]" to v, "subscription_data[metadata][$k]" to v) }.toTypedArray(),
        "line_items[0][price_data][recurring][interval]" to interval,
    )

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.obj()

    @Test
    fun `the enrollment subscription cycle against basil shapes`() = LiveSim().use { s ->
        s.resetAt()
        val account = s.get("/v1/account")
        assertEquals("acct_ssf_local", account.str("id"))
        assertEquals("account", account.str("object"))
        assertEquals("2025-06-30.basil", account.header("Stripe-Version"))

        val created = s.post("/v1/checkout/sessions", subscriptionCheckout(2900), "ssf-checkout-p-1")
        assertEquals(200, created.status, created.text)
        val session = created.json
        assertEquals(2900, session.l("amount_total"))
        assertEquals("eur", session.s("currency"))
        assertEquals("subscription", session.s("mode"))
        assertEquals("open", session.s("status"))
        assertEquals("acct_ssf_local", session.obj("metadata").s("ssf_operator_account"))
        assertEquals("false", session.s("livemode"))
        assertTrue(session.s("url").endsWith("/checkout/${session.s("id")}"))

        // Recovery search: the adapter pages through a created window and matches client_reference_id.
        val window = s.get("/v1/checkout/sessions?limit=100&created[gte]=${T0_SECONDS - 300}&created[lte]=${T0_SECONDS + 300}").json
        assertEquals(listOf("p-1"), window["data"]!!.arr().map { it.obj().s("client_reference_id") })
        assertEquals("false", window.s("has_more"))
        assertTrue(s.get("/v1/checkout/sessions?created[gt]=${T0_SECONDS + 300}").json["data"]!!.arr().isEmpty())

        val completed = s.control("POST", "/v1/admin/checkout/sessions/${session.s("id")}/complete", """{"card":"4242424242424242"}""")
        assertEquals("complete", completed.str("outcome"), completed.text)

        val paid = s.get("/v1/checkout/sessions/${session.s("id")}").json
        assertEquals("complete", paid.s("status"))
        assertEquals("paid", paid.s("payment_status"))
        val subscriptionId = paid.s("subscription")
        val firstInvoiceId = paid.s("invoice")
        val customer = paid.s("customer")

        // Basil: the period lives on the item.
        val subscription = s.get("/v1/subscriptions/$subscriptionId").json
        assertFalse(subscription.containsKey("current_period_end"))
        assertEquals("active", subscription.s("status"))
        assertEquals(JsonNull, subscription["cancel_at"])
        assertEquals("acct_ssf_local", subscription.obj("metadata").s("ssf_operator_account"))
        val item = subscription.items().single()
        assertEquals(1, item.l("quantity"))
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, item.l("current_period_end"))
        val price = item.obj("price")
        assertEquals(2900, price.l("unit_amount"))
        assertEquals("month", price.obj("recurring").s("interval"))
        assertEquals(1, price.obj("recurring").l("interval_count"))
        assertEquals("false", price.s("active"), "an inline price is not reusable")
        assertEquals("SSF subscription", s.get("/v1/products/${price.s("product")}").str("name"))

        // The first invoice, funded by exactly one InvoicePayment.
        val invoice = s.get("/v1/invoices/$firstInvoiceId?expand[]=payments").json
        assertFalse(invoice.containsKey("subscription"))
        assertFalse(invoice.containsKey("payment_intent"))
        assertFalse(invoice.containsKey("paid"))
        assertEquals(subscriptionId, invoice.obj("parent").obj("subscription_details").s("subscription"))
        assertEquals("p-1", invoice.obj("parent").obj("subscription_details").obj("metadata").s("ssf_purchase_id"))
        assertEquals("paid", invoice.s("status"))
        for (field in listOf("amount_paid", "amount_due", "total")) assertEquals(2900, invoice.l(field))
        for (field in listOf("amount_remaining", "post_payment_credit_notes_amount", "pre_payment_credit_notes_amount")) {
            assertEquals(0, invoice.l(field))
        }
        assertEquals(customer, invoice.s("customer"))
        assertEquals(T0_SECONDS, invoice.obj("status_transitions").l("paid_at"))
        val line = invoice.lines().single()
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, line.obj("period").l("end"))
        assertEquals(price.s("id"), line.obj("pricing").obj("price_details").s("price"))
        assertEquals(item.s("id"), line.obj("parent").obj("subscription_item_details").s("subscription_item"))
        val payments = invoice.obj("payments")
        assertEquals("false", payments.s("has_more"))
        val allocation = payments["data"]!!.arr().single().obj()
        assertEquals(firstInvoiceId, allocation.s("invoice"))
        assertEquals("paid", allocation.s("status"))
        assertEquals(2900, allocation.l("amount_paid"))
        assertEquals("payment_intent", allocation.obj("payment").s("type"))
        val intentId = allocation.obj("payment").s("payment_intent")
        assertEquals(allocation.s("id"), s.get("/v1/invoice_payments?invoice=$firstInvoiceId").json["data"]!!.arr().single().obj().s("id"))
        assertFalse(s.get("/v1/invoices/$firstInvoiceId").json.containsKey("payments"), "payments is includable only")

        val intent = s.get("/v1/payment_intents/$intentId").json
        assertEquals("succeeded", intent.s("status"))
        assertEquals(2900, intent.l("amount_received"))
        assertEquals(customer, intent.s("customer"))
        assertFalse(intent.containsKey("invoice"))
        val charge = s.get("/v1/charges/${intent.s("latest_charge")}").json
        assertEquals(intentId, charge.s("payment_intent"))
        assertEquals("true", charge.s("paid"))
        assertEquals("true", charge.s("captured"))
        assertEquals("false", charge.s("refunded"))
        assertEquals("false", charge.s("disputed"))
        assertEquals(2900, charge.l("amount_captured"))

        // A paid addition: payment mode for the same customer, metadata on the PaymentIntent.
        val addition = s.post(
            "/v1/checkout/sessions",
            form(
                "mode" to "payment", "success_url" to "https://127.0.0.1/done", "cancel_url" to "https://127.0.0.1/done",
                "client_reference_id" to "p-2", "payment_method_types[0]" to "card", "customer" to customer,
                "line_items[0][quantity]" to "1", "line_items[0][price_data][currency]" to "eur",
                "line_items[0][price_data][unit_amount]" to "700",
                "line_items[0][price_data][product_data][name]" to "SSF paid additions",
                "payment_intent_data[metadata][ssf_purchase_id]" to "p-2", "metadata[ssf_purchase_id]" to "p-2",
            ),
            "ssf-checkout-p-2",
        ).json
        assertEquals(700, addition.l("amount_total"))
        s.control("POST", "/v1/admin/checkout/sessions/${addition.s("id")}/complete", "{}")
        val additionPaid = s.get("/v1/checkout/sessions/${addition.s("id")}").json
        assertEquals("paid", additionPaid.s("payment_status"))
        val additionIntent = s.get("/v1/payment_intents/${additionPaid.s("payment_intent")}").json
        assertEquals("p-2", additionIntent.obj("metadata").s("ssf_purchase_id"))
        assertEquals(customer, additionIntent.s("customer"))
        assertEquals(700, s.get("/v1/charges/${additionIntent.s("latest_charge")}").json.l("amount_captured"))

        // Amend the next period's price without proration.
        val amended = s.post(
            "/v1/prices",
            form(
                "product" to price.s("product"), "currency" to "eur", "unit_amount" to "3600",
                "recurring[interval]" to "month", "recurring[interval_count]" to "1",
            ),
            "ssf-price-x",
        ).str("id")
        val updated = s.post(
            "/v1/subscriptions/$subscriptionId",
            form("items[0][id]" to item.s("id"), "items[0][price]" to amended, "items[0][quantity]" to "1", "proration_behavior" to "none"),
            "ssf-amend-x",
        ).json
        assertEquals(3600, updated.items().single().obj("price").l("unit_amount"))

        // Renewal: the clock passes the period end, a second paid invoice moves the item's period.
        s.advance(30 * DAY_SECONDS + 60)
        val invoices = s.get("/v1/invoices?subscription=$subscriptionId&status=paid&limit=24").json["data"]!!.arr().map { it.obj() }
        assertEquals(2, invoices.size)
        val renewal = invoices.first()
        assertEquals(3600, renewal.l("total"))
        assertEquals(T0_SECONDS + 30 * DAY_SECONDS, renewal.lines().single().obj("period").l("start"))
        assertEquals(T0_SECONDS + 60 * DAY_SECONDS, renewal.lines().single().obj("period").l("end"))
        val renewalPayment = s.get("/v1/invoices/${renewal.s("id")}?expand[]=payments").json.obj("payments")["data"]!!.arr().single().obj()
        assertEquals(3600, renewalPayment.l("amount_paid"))
        assertEquals(T0_SECONDS + 60 * DAY_SECONDS, s.get("/v1/subscriptions/$subscriptionId").json.items().single().l("current_period_end"))

        // Cancel at period end: scheduled, then ended when the term runs out.
        val scheduled = s.post("/v1/subscriptions/$subscriptionId", form("cancel_at_period_end" to "true"), "ssf-cancel-x").json
        assertEquals("true", scheduled.s("cancel_at_period_end"))
        assertEquals(T0_SECONDS + 60 * DAY_SECONDS, scheduled.l("cancel_at"))
        s.advance(30 * DAY_SECONDS)
        val ended = s.get("/v1/subscriptions/$subscriptionId").json
        assertEquals("canceled", ended.s("status"))
        assertEquals(T0_SECONDS + 60 * DAY_SECONDS, ended.l("ended_at"))
        assertEquals(2, s.get("/v1/invoices?subscription=$subscriptionId&status=paid").json["data"]!!.arr().size)

        // Clients that name no version keep the 2024-06-20 shapes.
        val legacy = s.actor("GET", "/v1/subscriptions/$subscriptionId").json
        assertEquals(T0_SECONDS + 60 * DAY_SECONDS, legacy.l("current_period_end"))
        assertFalse(legacy.items().single().containsKey("current_period_end"))
        val legacyInvoice = s.actor("GET", "/v1/invoices/$firstInvoiceId?expand[]=payments").json
        assertEquals(subscriptionId, legacyInvoice.s("subscription"))
        assertEquals(intentId, legacyInvoice.s("payment_intent"))
        assertEquals("true", legacyInvoice.s("paid"))
        assertNull(legacyInvoice["payments"])
        assertNull(legacyInvoice["parent"])
        assertEquals("2024-06-20", s.actor("GET", "/v1/account").header("Stripe-Version"))
    }

    @Test
    fun `checkout refuses amounts and currencies stripe cannot charge`() = LiveSim().use { s ->
        s.resetAt()
        val catalogue = s.state().let { it["prices"]!!.arr().size to it["products"]!!.arr().size }
        fun code(body: String): String? {
            val reply = s.actor("POST", "/v1/checkout/sessions", body, headers = basil)
            assertEquals(400, reply.status, reply.text)
            return reply.json.obj("error").s("code")
        }
        assertEquals("amount_too_small", code(subscriptionCheckout(10)))
        assertEquals("amount_too_large", code(subscriptionCheckout(100_000_000)))
        assertEquals("currency_not_supported", code(subscriptionCheckout(2900, currency = "xyz")))
        assertEquals(400, s.actor("POST", "/v1/checkout/sessions", subscriptionCheckout(2900, interval = "fortnight")).status)
        val payment = form(
            "mode" to "payment", "success_url" to "https://127.0.0.1/done",
            "line_items[0][price_data][currency]" to "usd", "line_items[0][price_data][unit_amount]" to "0",
            "line_items[0][price_data][product_data][name]" to "Nothing",
        )
        assertEquals("amount_too_small", code(payment))
        assertEquals(
            400,
            s.actor(
                "POST", "/v1/checkout/sessions",
                subscriptionCheckout(2900) + "&" + form("payment_intent_data[metadata][x]" to "y"),
            ).status,
        )
        assertEquals(catalogue, s.state().let { it["prices"]!!.arr().size to it["products"]!!.arr().size }, "a refused session creates nothing")
    }

    @Test
    fun `the controller lists subscriptions and forces a failed renewal`() = LiveSim().use { s ->
        s.resetAt()
        val session = s.actor("POST", "/v1/checkout/sessions", subscriptionCheckout(2900)).json
        s.control("POST", "/v1/admin/checkout/sessions/${session.s("id")}/complete", """{"card":"4000002500003155"}""")
        val listed = s.control("GET", "/v1/admin/subscriptions?status=active").json["subscriptions"]!!.arr().single().obj()
        val id = listed.s("id")
        assertEquals(2900, listed.l("amount"))
        assertEquals("2026-11-04T08:00:00.000Z", listed.s("current_period_end"))

        assertEquals(400, s.control("POST", "/v1/admin/subscriptions/$id/fail_next_renewal", """{"card":"4242424242424242"}""").status)
        assertEquals(1, s.control("POST", "/v1/admin/subscriptions/$id/fail_next_renewal", "{}").json.l("fail_next_renewals"))
        s.control("POST", "/v1/admin/subscriptions/$id/renew")
        val dunned = s.subscription(id)
        assertEquals("past_due", dunned.s("status"))
        val failed = s.invoice(dunned.s("latest_invoice"))
        assertEquals("open", failed.s("status"))
        assertTrue(failed.s("payment_intent").startsWith("pi_"), "the declined PaymentIntent stays linked")
        val open = s.actor("GET", "/v1/invoices/${failed.s("id")}?expand[]=payments", headers = basil).json
            .obj("payments")["data"]!!.arr().single().obj()
        assertEquals("open", open.s("status"))
        assertTrue(s.eventTypes().contains("invoice.payment_failed"))

        // The next renewal uses the default card again and the customer recovers.
        s.control("POST", "/v1/admin/subscriptions/$id/renew")
        assertEquals("active", s.subscription(id).s("status"))
        assertEquals(0, s.control("GET", "/v1/admin/subscriptions").json["subscriptions"]!!.arr().single().obj().l("fail_next_renewals"))
    }

    @Test
    fun `webhooks can be signed with the wall clock and the account id is configurable`() =
        LiveSim(extraEnv = mapOf("FAKE_STRIPE_WEBHOOK_SIGN_WALL_CLOCK" to "1", "FAKE_STRIPE_ACCOUNT_ID" to "acct_operator_7")).use { s ->
            Receiver().use { receiver ->
                s.resetAt(now = "2027-03-01T00:00:00.000Z")
                assertEquals("acct_operator_7", s.actor("GET", "/v1/account").str("id"))
                val config = s.control("GET", "/v1/admin/config")
                assertEquals("wall", config.str("webhook_signature_clock"))
                assertEquals("acct_operator_7", config.str("account_id"))
                s.control("POST", "/v1/admin/webhook", """{"url":"${receiver.url}","secret":"whsec_enroll"}""")
                s.actor("POST", "/v1/customers", form("email" to "a@example.com"))
                val delivery = assertNotNull(receiver.await())
                val t = delivery.signature.substringAfter("t=").substringBefore(",")
                assertTrue(kotlin.math.abs(t.toLong() - System.currentTimeMillis() / 1000) <= 300, "t=$t follows the wall clock")
                assertEquals(hmacHex("whsec_enroll", "$t.${delivery.body}"), delivery.signature.substringAfter("v1="))

                assertEquals("simulated", s.control("PATCH", "/v1/admin/config", """{"webhook_signature_clock":"simulated"}""").str("webhook_signature_clock"))
                assertEquals(400, s.control("PATCH", "/v1/admin/config", """{"webhook_signature_clock":"sundial"}""").status)
                s.actor("POST", "/v1/customers", form("email" to "b@example.com"))
                val simulated = assertNotNull(receiver.await())
                assertEquals("1803859200", simulated.signature.substringAfter("t=").substringBefore(","))
            }
        }

    @Test
    fun `start-up refuses malformed new options`() {
        assertEquals(
            "FAKE_STRIPE_ACCOUNT_ID must look like a Stripe account id: acct_ followed by letters, digits or underscores.",
            runCatching { accountIdFrom(mapOf("FAKE_STRIPE_ACCOUNT_ID" to "ssf")) }.exceptionOrNull()?.message,
        )
        assertEquals("acct_ssf_local", accountIdFrom(emptyMap()))
        assertEquals(false, flag(mapOf(WALL_CLOCK_SIGNING_ENV to ""), WALL_CLOCK_SIGNING_ENV))
        assertEquals(true, flag(mapOf(WALL_CLOCK_SIGNING_ENV to "true"), WALL_CLOCK_SIGNING_ENV))
        assertTrue(runCatching { flag(mapOf(WALL_CLOCK_SIGNING_ENV to "maybe"), WALL_CLOCK_SIGNING_ENV) }.isFailure)
    }
}
