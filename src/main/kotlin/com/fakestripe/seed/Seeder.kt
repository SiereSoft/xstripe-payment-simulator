package com.fakestripe.seed

import com.fakestripe.cards.TestCards
import com.fakestripe.model.Charge
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.store.DataStore
import java.util.Random

/**
 * Builds a deterministic pre-populated world from a seed. The SAME seed always
 * produces the SAME customers, cards and payment history — the reproducible
 * starting state that RL training requires. `/v1/admin/reset?seed=N` calls this.
 */
object Seeder {
    private const val ANCHOR = 1_767_225_600L // 2026-01-01T00:00:00Z, backdate anchor
    private const val DAY = 86_400L

    private data class Person(val name: String, val email: String)

    private val roster = listOf(
        Person("Jane Doe", "jane@example.com"),
        Person("John Smith", "john@example.com"),
        Person("Acme Corp", "billing@acme.example.com"),
        Person("Maria Garcia", "maria@example.com"),
        Person("Kenji Tanaka", "kenji@example.com"),
        Person("Priya Patel", "priya@example.com"),
    )

    fun build(seed: Long): DataStore {
        val store = DataStore(seed)
        val rng = Random(seed)

        roster.forEachIndexed { i, person ->
            val created = ANCHOR + i * DAY
            val customer = Customer(
                id = store.newId("cus"),
                created = created,
                email = person.email,
                name = person.name,
            )
            store.customers[customer.id] = customer

            // Roughly 2 of every 3 customers have a saved card.
            val pm: PaymentMethod? = if (rng.nextInt(3) != 0) {
                PaymentMethod(
                    id = store.newId("pm"),
                    created = created + 60,
                    number = "4242424242424242",
                    expMonth = 12,
                    expYear = 2034,
                    customer = customer.id,
                    billingName = person.name,
                    billingEmail = person.email,
                ).also {
                    store.paymentMethods[it.id] = it
                    customer.defaultPaymentMethod = it.id
                }
            } else null

            // About half of carded customers have one past successful payment,
            // giving refund/lookup tasks something to operate on.
            if (pm != null && rng.nextInt(2) == 0) {
                val amount = (rng.nextInt(90) + 10) * 100L // $10.00 – $99.00
                seedPaidIntent(store, customer, pm, amount, created + 2 * DAY)
            }
        }
        return store
    }

    private fun seedPaidIntent(
        store: DataStore,
        customer: Customer,
        pm: PaymentMethod,
        amount: Long,
        created: Long,
    ) {
        val piId = store.newId("pi")
        val chId = store.newId("ch")
        val card = TestCards.forNumber(pm.number)

        store.charges[chId] = Charge(
            id = chId,
            created = created,
            amount = amount,
            currency = "usd",
            paymentIntent = piId,
            paymentMethod = pm.id,
            customer = customer.id,
            status = "succeeded",
            paid = true,
            captured = true,
            amountCaptured = amount,
            cardBrand = card.brand,
            cardLast4 = TestCards.last4(pm.number),
            cardExpMonth = pm.expMonth,
            cardExpYear = pm.expYear,
            cardFunding = card.funding,
            cardCountry = card.country,
            outcome = Charge.approvedOutcome(),
            billingName = customer.name,
            billingEmail = customer.email,
        )

        store.paymentIntents[piId] = PaymentIntent(
            id = piId,
            created = created,
            amount = amount,
            currency = "usd",
            clientSecret = store.newClientSecret(piId),
            status = PaymentIntent.Status.SUCCEEDED,
            paymentMethod = pm.id,
            customer = customer.id,
            amountReceived = amount,
            latestCharge = chId,
        )
    }
}
