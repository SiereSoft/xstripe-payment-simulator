package com.fakestripe

import com.fakestripe.store.Simulator
import com.stripe.Stripe
import com.stripe.exception.CardException
import com.stripe.model.Customer
import com.stripe.model.PaymentIntent
import com.stripe.model.Price
import com.stripe.model.Product
import com.stripe.model.checkout.Session
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerListParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.PriceCreateParams
import com.stripe.param.ProductCreateParams
import com.stripe.param.checkout.SessionCreateParams
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The bounty's headline compatibility proof: Stripe's **official, unmodified**
 * Java SDK, with only its base URL overridden, drives the simulator end to end.
 * If our response shapes were wrong, the SDK would fail to deserialize into its
 * typed objects — so this passing is strong evidence of wire compatibility.
 */
class StripeJavaSdkTest {

    @Test
    fun `official stripe-java SDK runs unmodified against the simulator`() {
        val sim = Simulator.boot(Files.createTempFile("fs-sdk", ".json"), 1L)
        val engine = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(sim) }
        engine.start(wait = false)
        val port = runBlocking { engine.resolvedConnectors().first().port }

        // The ONLY changes to the SDK: a test key + the base address. Nothing patched.
        Stripe.apiKey = "sk_test_123"
        Stripe.overrideApiBase("http://127.0.0.1:$port")
        try {
            // Create a customer
            val customer = Customer.create(
                CustomerCreateParams.builder().setEmail("jane@example.com").setName("Jane").build(),
            )
            assertTrue(customer.id.startsWith("cus_"))
            assertEquals("jane@example.com", customer.email)

            // Create + confirm a PaymentIntent with a good card -> succeeds
            val pi = PaymentIntent.create(
                PaymentIntentCreateParams.builder()
                    .setAmount(2000L)
                    .setCurrency("usd")
                    .setCustomer(customer.id)
                    .setPaymentMethod("pm_card_visa")
                    .setConfirm(true)
                    .build(),
            )
            assertEquals("succeeded", pi.status)
            assertEquals(2000L, pi.amountReceived)
            assertTrue(pi.latestCharge.startsWith("ch_"))

            // Retrieve it back
            val fetched = PaymentIntent.retrieve(pi.id)
            assertEquals(pi.id, fetched.id)
            assertEquals("succeeded", fetched.status)

            // List customers
            val list = Customer.list(CustomerListParams.builder().setLimit(3L).build())
            assertTrue(list.data.isNotEmpty())

            // Checkout + customer portal: the SDK must deserialize both session objects,
            // and the hosted URL has to come back as a usable string.
            val product = Product.create(ProductCreateParams.builder().setName("Gold Plan").build())
            val price = Price.create(
                PriceCreateParams.builder()
                    .setProduct(product.id)
                    .setCurrency("usd")
                    .setUnitAmount(1500L)
                    .setRecurring(PriceCreateParams.Recurring.builder().setInterval(PriceCreateParams.Recurring.Interval.MONTH).build())
                    .build(),
            )
            val session = Session.create(
                SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .addLineItem(SessionCreateParams.LineItem.builder().setPrice(price.id).setQuantity(1L).build())
                    .setCustomerEmail("jane@example.com")
                    .setClientReferenceId("subscriber-42")
                    .setSuccessUrl("https://example.com/account?checkout=done")
                    .setCancelUrl("https://example.com/upgrade")
                    .build(),
            )
            assertTrue(session.id.startsWith("cs_"))
            assertEquals("open", session.status)
            assertEquals("subscriber-42", session.clientReferenceId)
            assertTrue(session.url.contains("/checkout/${session.id}"), "hosted url: ${session.url}")

            val portal = com.stripe.model.billingportal.Session.create(
                com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(customer.id)
                    .setReturnUrl("https://example.com/account")
                    .build(),
            )
            assertTrue(portal.url.contains("/billing_portal/${portal.id}"), "portal url: ${portal.url}")

            // A declined card must raise the SDK's typed CardException
            try {
                PaymentIntent.create(
                    PaymentIntentCreateParams.builder()
                        .setAmount(500L)
                        .setCurrency("usd")
                        .setPaymentMethod("pm_card_chargeDeclined")
                        .setConfirm(true)
                        .build(),
                )
                fail("expected a CardException for a declined card")
            } catch (e: CardException) {
                assertEquals("card_declined", e.code)
            }
        } finally {
            Stripe.overrideApiBase(null)
            engine.stop(500, 1000)
        }
    }
}
