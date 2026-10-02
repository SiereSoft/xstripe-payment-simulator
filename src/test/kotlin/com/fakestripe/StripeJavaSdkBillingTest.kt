package com.fakestripe

import com.stripe.Stripe
import com.stripe.model.Customer
import com.stripe.model.Invoice
import com.stripe.model.PaymentMethod
import com.stripe.model.Price
import com.stripe.model.Subscription
import com.stripe.model.SubscriptionItem
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.CustomerCreateParams
import com.stripe.param.CustomerUpdateParams
import com.stripe.param.PaymentMethodAttachParams
import com.stripe.param.PriceCreateParams
import com.stripe.param.SubscriptionCreateParams
import com.stripe.param.SubscriptionItemListParams
import com.stripe.param.SubscriptionItemUpdateParams
import com.stripe.param.SubscriptionUpdateParams
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.checkout.SessionRetrieveParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The official stripe-java SDK, unmodified, driving the 0.2.0 additions: inline `product_data`,
 * trials, several Checkout line items with `subscription_data`, subscription items and `expand[]`.
 */
class StripeJavaSdkBillingTest {

    @Test
    fun `stripe-java drives trials, add-ons, item updates and expansion`() = LiveSim().use { s ->
        s.resetAt()
        Stripe.apiKey = "sk_test_123"
        Stripe.overrideApiBase("http://127.0.0.1:${s.actorPort}")
        try {
            val plan = Price.create(
                PriceCreateParams.builder()
                    .setCurrency("eur")
                    .setUnitAmount(2900L)
                    .setRecurring(PriceCreateParams.Recurring.builder().setInterval(PriceCreateParams.Recurring.Interval.MONTH).build())
                    .setProductData(PriceCreateParams.ProductData.builder().setName("SSF Basic").build())
                    .putMetadata("planId", "basic")
                    .build(),
            )
            val addon = Price.create(
                PriceCreateParams.builder()
                    .setCurrency("eur")
                    .setUnitAmount(500L)
                    .setRecurring(PriceCreateParams.Recurring.builder().setInterval(PriceCreateParams.Recurring.Interval.MONTH).build())
                    .setProductData(PriceCreateParams.ProductData.builder().setName("Extra till").build())
                    .build(),
            )
            assertTrue(plan.product.startsWith("prod_"))

            val customer = Customer.create(CustomerCreateParams.builder().setEmail("sdk@example.com").putMetadata("accountId", "a1").build())
            // Stripe's shared test card, attached the way the SDK's own examples do.
            val pm = PaymentMethod().apply { id = "pm_card_visa" }
                .attach(PaymentMethodAttachParams.builder().setCustomer(customer.id).build())
            customer.update(
                CustomerUpdateParams.builder()
                    .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder().setDefaultPaymentMethod(pm.id).build())
                    .build(),
            )

            val subscription = Subscription.create(
                SubscriptionCreateParams.builder()
                    .setCustomer(customer.id)
                    .addItem(SubscriptionCreateParams.Item.builder().setPrice(plan.id).build())
                    .addItem(SubscriptionCreateParams.Item.builder().setPrice(addon.id).setQuantity(2L).build())
                    .setTrialPeriodDays(7L)
                    .putMetadata("storeId", "store-1")
                    .build(),
                RequestOptions.builder().setIdempotencyKey("sdk-sub-1").build(),
            )
            assertEquals("trialing", subscription.status)
            assertEquals(T0_SECONDS + 7 * DAY_SECONDS, subscription.trialEnd)
            assertEquals(2, subscription.items.data.size)

            val addonItem = subscription.items.data.single { it.price.id == addon.id }
            val updated = SubscriptionItem.retrieve(addonItem.id).update(
                SubscriptionItemUpdateParams.builder()
                    .setQuantity(5L)
                    .setProrationBehavior(SubscriptionItemUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                    .build(),
            )
            assertEquals(5L, updated.quantity)
            val items = SubscriptionItem.list(SubscriptionItemListParams.builder().setSubscription(subscription.id).build())
            assertEquals(setOf(1L, 5L), items.data.map { it.quantity }.toSet())

            val changed = Subscription.retrieve(subscription.id).update(
                SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder().setId(addonItem.id).setQuantity(1L).build())
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE)
                    .build(),
            )
            assertEquals(1L, changed.items.data.single { it.id == addonItem.id }.quantity)

            s.advance(7 * DAY_SECONDS)
            val active = Subscription.retrieve(subscription.id)
            assertEquals("active", active.status)
            val firstInvoice = Invoice.retrieve(active.latestInvoice)
            assertEquals(3400L, firstInvoice.total)
            assertEquals("paid", firstInvoice.status)

            val session = Session.create(
                SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customer.id)
                    .addLineItem(SessionCreateParams.LineItem.builder().setPrice(plan.id).setQuantity(1L).build())
                    .addLineItem(SessionCreateParams.LineItem.builder().setPrice(addon.id).setQuantity(3L).build())
                    .setSubscriptionData(
                        SessionCreateParams.SubscriptionData.builder().putMetadata("storeId", "store-2").setTrialPeriodDays(14L).build(),
                    )
                    .setClientReferenceId("store-2")
                    .setSuccessUrl("https://cp.example/ok?session={CHECKOUT_SESSION_ID}")
                    .setCancelUrl("https://cp.example/cancel")
                    .build(),
            )
            assertEquals(0L, session.amountTotal)
            val paid = s.actor("POST", "/checkout/${session.id}/pay", form("card_number" to "4242424242424242"), key = null)
            assertEquals("https://cp.example/ok?session=${session.id}", paid.header("Location"))

            val expanded = Session.retrieve(
                session.id,
                SessionRetrieveParams.builder().addExpand("subscription").addExpand("customer").build(),
                null,
            )
            assertEquals("complete", expanded.status)
            assertEquals("trialing", expanded.subscriptionObject.status)
            assertEquals("store-2", expanded.subscriptionObject.metadata["storeId"])
            assertEquals(listOf(1L, 3L), expanded.subscriptionObject.items.data.map { it.quantity })
            assertEquals("sdk@example.com", expanded.customerObject.email)
        } finally {
            Stripe.overrideApiBase(null)
        }
    }
}
