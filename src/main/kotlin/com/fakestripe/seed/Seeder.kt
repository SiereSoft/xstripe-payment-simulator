package com.fakestripe.seed

import com.fakestripe.cards.TestCards
import com.fakestripe.model.Charge
import com.fakestripe.model.Customer
import com.fakestripe.model.Invoice
import com.fakestripe.model.InvoiceLine
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.model.Price
import com.fakestripe.model.Product
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionItem
import com.fakestripe.store.DataStore
import com.fakestripe.store.ScenarioState
import java.util.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a deterministic pre-populated world from a seed. The SAME seed always
 * produces the SAME customers, cards and payment history — the reproducible
 * starting state that RL training requires. `/v1/admin/reset?seed=N` calls this.
 */
object Seeder {
    const val DUPLICATE_PAYMENTS = "duplicate_payments"
    const val PAST_DUE_SUBSCRIPTION = "past_due_subscription"
    const val UPGRADE_CANDIDATE = "upgrade_candidate"
    const val CANCEL_CANDIDATE = "cancel_candidate"
    const val PAYMENT_INVESTIGATION = "payment_investigation"
    val supportedScenarios: Set<String> = setOf(
        DUPLICATE_PAYMENTS,
        PAST_DUE_SUBSCRIPTION,
        UPGRADE_CANDIDATE,
        CANCEL_CANDIDATE,
        PAYMENT_INVESTIGATION,
    )

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

    fun build(seed: Long, scenario: String? = null): DataStore {
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

        seedCatalog(store)
        when (scenario) {
            null -> Unit
            DUPLICATE_PAYMENTS -> seedDuplicatePayments(store)
            PAST_DUE_SUBSCRIPTION -> seedPastDueSubscription(store)
            UPGRADE_CANDIDATE -> seedUpgradeCandidate(store)
            CANCEL_CANDIDATE -> seedCancelCandidate(store)
            PAYMENT_INVESTIGATION -> seedPaymentInvestigation(store)
            else -> throw IllegalArgumentException("Unsupported scenario: $scenario")
        }
        return store
    }

    /**
     * Two related successful charges for one customer, plus an unrelated charge.
     * The target customer, IDs, amounts, and which amount appears first all vary
     * by seed while the verifier context preserves the intended answer.
     */
    private fun seedDuplicatePayments(store: DataStore) {
        val rng = Random(store.seed xor 0x5EED_D00DL)
        val customers = store.customers.values.toList()
        val targetIndex = rng.nextInt(customers.size)
        val target = customers[targetIndex]
        val targetPaymentMethod = ensurePaymentMethod(store, target)

        val smallerAmount = (rng.nextInt(80) + 20) * 100L
        val largerAmount = smallerAmount + (rng.nextInt(70) + 10) * 100L
        val smallerFirst = rng.nextBoolean()
        val anchor = ANCHOR + 60 * DAY + Math.floorMod(store.seed, 20L) * DAY
        val smallerCreated = if (smallerFirst) anchor + 60 else anchor
        val largerCreated = if (smallerFirst) anchor else anchor + 60
        val pairId = "dup_${java.lang.Long.toUnsignedString(store.seed, 36)}"
        val pairMetadata = mapOf(
            "gym_scenario" to DUPLICATE_PAYMENTS,
            "gym_pair" to pairId,
        )

        val smaller = seedPaidIntent(
            store = store,
            customer = target,
            pm = targetPaymentMethod,
            amount = smallerAmount,
            created = smallerCreated,
            description = "Duplicate order $pairId",
            metadata = pairMetadata,
        )
        val larger = seedPaidIntent(
            store = store,
            customer = target,
            pm = targetPaymentMethod,
            amount = largerAmount,
            created = largerCreated,
            description = "Duplicate order $pairId",
            metadata = pairMetadata,
        )

        val distractor = customers[(targetIndex + 1) % customers.size]
        seedPaidIntent(
            store = store,
            customer = distractor,
            pm = ensurePaymentMethod(store, distractor),
            amount = (rng.nextInt(90) + 10) * 100L,
            created = anchor - DAY,
            description = "Unrelated order",
        )

        store.scenario = ScenarioState(
            id = DUPLICATE_PAYMENTS,
            instructionContext = buildJsonObject {
                put("customer_name", target.name)
            },
            verifierContext = buildJsonObject {
                put("customer_id", target.id)
                put("pair_id", pairId)
                put("smaller_charge_id", smaller.id)
                put("larger_charge_id", larger.id)
                put(
                    "relevant_charge_ids",
                    JsonArray(listOf(JsonPrimitive(smaller.id), JsonPrimitive(larger.id))),
                )
            },
        )
    }

    /** A past-due renewal with a declining default card and a usable replacement. */
    private fun seedPastDueSubscription(store: DataStore) {
        val rng = Random(store.seed xor 0x51A7_D0EL)
        val customers = store.customers.values.toList()
        val targetIndex = rng.nextInt(customers.size)
        val target = customers[targetIndex]
        val distractor = customers[(targetIndex + 1) % customers.size]
        val basicMonthly = price(store, "Basic Plan", "month")
        val proMonthly = price(store, "Pro Plan", "month")
        val anchor = scenarioAnchor(store.seed)
        val targetQuantity = rng.nextInt(3).toLong() + 1

        lateinit var targetFixture: SubscriptionFixture
        if (rng.nextBoolean()) {
            targetFixture = seedSubscription(
                store, target, basicMonthly, targetQuantity, anchor - 30 * DAY,
                scenarioMetadata(PAST_DUE_SUBSCRIPTION, "target"),
            )
            seedSubscription(
                store, distractor, proMonthly, rng.nextInt(2).toLong() + 1, anchor - 18 * DAY,
                scenarioMetadata(PAST_DUE_SUBSCRIPTION, "distractor"),
            )
        } else {
            seedSubscription(
                store, distractor, proMonthly, rng.nextInt(2).toLong() + 1, anchor - 18 * DAY,
                scenarioMetadata(PAST_DUE_SUBSCRIPTION, "distractor"),
            )
            targetFixture = seedSubscription(
                store, target, basicMonthly, targetQuantity, anchor - 30 * DAY,
                scenarioMetadata(PAST_DUE_SUBSCRIPTION, "target"),
            )
        }

        val replacement = store.requirePaymentMethod(targetFixture.subscription.defaultPaymentMethod!!)
        val declining = newPaymentMethod(store, target, "4000000000000002", anchor - DAY)
        target.defaultPaymentMethod = declining.id
        targetFixture.subscription.defaultPaymentMethod = declining.id
        val renewalStart = targetFixture.subscription.currentPeriodEnd
        val renewalEnd = renewalStart + 30 * DAY
        targetFixture.subscription.currentPeriodStart = renewalStart
        targetFixture.subscription.currentPeriodEnd = renewalEnd
        targetFixture.subscription.status = "past_due"
        val renewalAmount = (basicMonthly.unitAmount ?: 0) * targetQuantity
        val failedIntent = seedFailedIntent(
            store, target, declining, renewalAmount, anchor,
            scenarioMetadata(PAST_DUE_SUBSCRIPTION, "failed_renewal"),
        )
        val renewalInvoice = Invoice(
            id = store.newId("in"),
            created = anchor,
            number = invoiceNumber(store),
            customer = target.id,
            subscription = targetFixture.subscription.id,
            status = "open",
            subtotal = renewalAmount,
            total = renewalAmount,
            amountDue = renewalAmount,
            amountPaid = 0,
            amountRemaining = renewalAmount,
            currency = "usd",
            lines = mutableListOf(
                invoiceLine(
                    store, basicMonthly, targetQuantity, renewalStart, renewalEnd,
                    targetFixture.subscription,
                ),
            ),
            paid = false,
            billingReason = "subscription_cycle",
            paymentIntent = failedIntent.id,
            periodStart = renewalStart,
            periodEnd = renewalEnd,
            metadata = scenarioMetadata(PAST_DUE_SUBSCRIPTION, "target").toMutableMap(),
        ).also { store.invoices[it.id] = it }
        targetFixture.subscription.latestInvoice = renewalInvoice.id

        store.scenario = ScenarioState(
            id = PAST_DUE_SUBSCRIPTION,
            instructionContext = buildJsonObject { put("customer_name", target.name) },
            verifierContext = buildJsonObject {
                put("customer_id", target.id)
                put("subscription_id", targetFixture.subscription.id)
                put("invoice_id", renewalInvoice.id)
                put("declining_payment_method_id", declining.id)
                put("replacement_payment_method_id", replacement.id)
            },
        )
    }

    /** An active Basic-monthly subscription and the exact Pro-annual upgrade target. */
    private fun seedUpgradeCandidate(store: DataStore) {
        val rng = Random(store.seed xor 0x0A6A_DE1L)
        val customers = store.customers.values.toList()
        val targetIndex = rng.nextInt(customers.size)
        val target = customers[targetIndex]
        val distractor = customers[(targetIndex + 1) % customers.size]
        val basicMonthly = price(store, "Basic Plan", "month")
        val proMonthly = price(store, "Pro Plan", "month")
        val proAnnual = price(store, "Pro Plan", "year")
        val anchor = scenarioAnchor(store.seed)
        lateinit var targetFixture: SubscriptionFixture

        if (rng.nextBoolean()) {
            targetFixture = seedSubscription(
                store, target, basicMonthly, 1, anchor - rng.nextInt(18) * DAY,
                scenarioMetadata(UPGRADE_CANDIDATE, "target"),
            )
            seedSubscription(
                store, distractor, proMonthly, rng.nextInt(3).toLong() + 1, anchor - 7 * DAY,
                scenarioMetadata(UPGRADE_CANDIDATE, "distractor"),
            )
        } else {
            seedSubscription(
                store, distractor, proMonthly, rng.nextInt(3).toLong() + 1, anchor - 7 * DAY,
                scenarioMetadata(UPGRADE_CANDIDATE, "distractor"),
            )
            targetFixture = seedSubscription(
                store, target, basicMonthly, 1, anchor - rng.nextInt(18) * DAY,
                scenarioMetadata(UPGRADE_CANDIDATE, "target"),
            )
        }

        store.scenario = ScenarioState(
            id = UPGRADE_CANDIDATE,
            instructionContext = buildJsonObject { put("customer_name", target.name) },
            verifierContext = buildJsonObject {
                put("customer_id", target.id)
                put("subscription_id", targetFixture.subscription.id)
                put("subscription_item_id", targetFixture.subscription.items.single().id)
                put("source_price_id", basicMonthly.id)
                put("target_price_id", proAnnual.id)
            },
        )
    }

    /** An active subscription that must remain usable until its current period ends. */
    private fun seedCancelCandidate(store: DataStore) {
        val rng = Random(store.seed xor 0x0CA_CE1L)
        val customers = store.customers.values.toList()
        val targetIndex = rng.nextInt(customers.size)
        val target = customers[targetIndex]
        val distractor = customers[(targetIndex + 1) % customers.size]
        val plans = listOf(price(store, "Basic Plan", "month"), price(store, "Pro Plan", "month"))
        val targetPrice = plans[rng.nextInt(plans.size)]
        val distractorPrice = plans[(plans.indexOf(targetPrice) + 1) % plans.size]
        val anchor = scenarioAnchor(store.seed)
        lateinit var targetFixture: SubscriptionFixture

        if (rng.nextBoolean()) {
            targetFixture = seedSubscription(
                store, target, targetPrice, rng.nextInt(2).toLong() + 1, anchor - rng.nextInt(20) * DAY,
                scenarioMetadata(CANCEL_CANDIDATE, "target"),
            )
            seedSubscription(
                store, distractor, distractorPrice, 1, anchor - 5 * DAY,
                scenarioMetadata(CANCEL_CANDIDATE, "distractor"),
            )
        } else {
            seedSubscription(
                store, distractor, distractorPrice, 1, anchor - 5 * DAY,
                scenarioMetadata(CANCEL_CANDIDATE, "distractor"),
            )
            targetFixture = seedSubscription(
                store, target, targetPrice, rng.nextInt(2).toLong() + 1, anchor - rng.nextInt(20) * DAY,
                scenarioMetadata(CANCEL_CANDIDATE, "target"),
            )
        }

        store.scenario = ScenarioState(
            id = CANCEL_CANDIDATE,
            instructionContext = buildJsonObject {
                put("customer_name", target.name)
                put("paid_through", targetFixture.subscription.currentPeriodEnd)
            },
            verifierContext = buildJsonObject {
                put("customer_id", target.id)
                put("subscription_id", targetFixture.subscription.id)
                put("expected_period_end", targetFixture.subscription.currentPeriodEnd)
            },
        )
    }

    /** Several plausible payments with one target failure or authentication hold. */
    private fun seedPaymentInvestigation(store: DataStore) {
        val rng = Random(store.seed xor 0x1A6E_571L)
        val customers = store.customers.values.toList()
        val targetIndex = rng.nextInt(customers.size)
        val target = customers[targetIndex]
        val distractor = customers[(targetIndex + 1) % customers.size]
        val anchor = scenarioAnchor(store.seed)
        val relevantAmount = (rng.nextInt(120) + 20) * 100L
        val relevantFirst = rng.nextBoolean()
        val requiresAction = rng.nextBoolean()
        var relevant: PaymentIntent? = null

        fun seedRelevant() {
            relevant = if (requiresAction) {
                val pm = newPaymentMethod(store, target, "4000002500003155", anchor)
                seedRequiresActionIntent(
                    store, target, pm, relevantAmount, anchor,
                    scenarioMetadata(PAYMENT_INVESTIGATION, "target"),
                )
            } else {
                val pm = newPaymentMethod(store, target, "4000000000000002", anchor)
                seedFailedIntent(
                    store, target, pm, relevantAmount, anchor,
                    scenarioMetadata(PAYMENT_INVESTIGATION, "target"),
                )
            }
        }

        if (relevantFirst) seedRelevant()
        seedPaidIntent(
            store, target, ensurePaymentMethod(store, target), (rng.nextInt(80) + 10) * 100L,
            anchor - DAY, "Prior customer payment",
            scenarioMetadata(PAYMENT_INVESTIGATION, "distractor"),
        )
        seedPaidIntent(
            store, distractor, ensurePaymentMethod(store, distractor), (rng.nextInt(80) + 10) * 100L,
            anchor + DAY, "Unrelated customer payment",
            scenarioMetadata(PAYMENT_INVESTIGATION, "distractor"),
        )
        if (!relevantFirst) seedRelevant()
        val targetIntent = requireNotNull(relevant)

        store.scenario = ScenarioState(
            id = PAYMENT_INVESTIGATION,
            instructionContext = buildJsonObject {
                put("customer_name", target.name)
                put("complaint_amount", relevantAmount)
            },
            verifierContext = buildJsonObject {
                put("customer_id", target.id)
                put("payment_intent_id", targetIntent.id)
                put("expected_status", targetIntent.status)
            },
        )
    }

    private data class SubscriptionFixture(
        val subscription: Subscription,
        val invoice: Invoice,
    )

    private fun scenarioAnchor(seed: Long): Long =
        ANCHOR + 60 * DAY + Math.floorMod(seed, 20L) * DAY

    private fun scenarioMetadata(scenario: String, role: String): Map<String, String> = mapOf(
        "gym_scenario" to scenario,
        "gym_role" to role,
    )

    private fun price(store: DataStore, productName: String, interval: String): Price =
        store.prices.values.single {
            store.products[it.product]?.name == productName && it.recurringInterval == interval
        }

    private fun seedSubscription(
        store: DataStore,
        customer: Customer,
        price: Price,
        quantity: Long,
        created: Long,
        metadata: Map<String, String>,
    ): SubscriptionFixture {
        val period = if (price.recurringInterval == "year") 365 * DAY else 30 * DAY
        val subscriptionId = store.newId("sub")
        val item = SubscriptionItem(
            id = store.newId("si"),
            created = created,
            priceId = price.id,
            quantity = quantity,
            subscription = subscriptionId,
            metadata = metadata.toMutableMap(),
        )
        val subscription = Subscription(
            id = subscriptionId,
            created = created,
            customer = customer.id,
            status = "active",
            items = mutableListOf(item),
            currentPeriodStart = created,
            currentPeriodEnd = created + period,
            currency = price.currency,
            defaultPaymentMethod = ensurePaymentMethod(store, customer).id,
            metadata = metadata.toMutableMap(),
        ).also { store.subscriptions[it.id] = it }
        val total = (price.unitAmount ?: 0) * quantity
        val paidCharge = seedPaidIntent(
            store, customer, store.requirePaymentMethod(subscription.defaultPaymentMethod!!), total,
            created + 60, "Initial subscription payment", metadata,
        )
        val invoice = Invoice(
            id = store.newId("in"),
            created = created + 120,
            number = invoiceNumber(store),
            customer = customer.id,
            subscription = subscription.id,
            status = "paid",
            subtotal = total,
            total = total,
            amountDue = total,
            amountPaid = total,
            amountRemaining = 0,
            currency = price.currency,
            lines = mutableListOf(
                invoiceLine(
                    store, price, quantity, subscription.currentPeriodStart,
                    subscription.currentPeriodEnd, subscription,
                ),
            ),
            paid = true,
            billingReason = "subscription_create",
            paymentIntent = paidCharge.paymentIntent,
            periodStart = subscription.currentPeriodStart,
            periodEnd = subscription.currentPeriodEnd,
            metadata = metadata.toMutableMap(),
        ).also { store.invoices[it.id] = it }
        subscription.latestInvoice = invoice.id
        return SubscriptionFixture(subscription, invoice)
    }

    private fun invoiceLine(
        store: DataStore,
        price: Price,
        quantity: Long,
        periodStart: Long,
        periodEnd: Long,
        subscription: Subscription,
    ): InvoiceLine = InvoiceLine(
        id = store.newId("il"),
        amount = (price.unitAmount ?: 0) * quantity,
        currency = price.currency,
        description = "${store.products[price.product]?.name ?: "Subscription"} × $quantity",
        priceId = price.id,
        quantity = quantity,
        periodStart = periodStart,
        periodEnd = periodEnd,
        subscription = subscription.id,
        subscriptionItem = subscription.items.single().id,
    )

    private fun invoiceNumber(store: DataStore): String =
        "INV-" + (store.invoices.size + 1).toString().padStart(4, '0')

    private fun seedFailedIntent(
        store: DataStore,
        customer: Customer,
        paymentMethod: PaymentMethod,
        amount: Long,
        created: Long,
        metadata: Map<String, String>,
    ): PaymentIntent {
        val intentId = store.newId("pi")
        val chargeId = store.newId("ch")
        val card = TestCards.forNumber(paymentMethod.number)
        val message = "Your card was declined."
        val charge = Charge(
            id = chargeId,
            created = created,
            amount = amount,
            currency = "usd",
            paymentIntent = intentId,
            paymentMethod = paymentMethod.id,
            customer = customer.id,
            status = "failed",
            paid = false,
            captured = false,
            amountCaptured = 0,
            cardBrand = card.brand,
            cardLast4 = TestCards.last4(paymentMethod.number),
            cardExpMonth = paymentMethod.expMonth,
            cardExpYear = paymentMethod.expYear,
            cardFunding = card.funding,
            cardCountry = card.country,
            failureCode = "card_declined",
            failureMessage = message,
            outcome = Charge.declinedOutcome("generic_decline"),
            billingName = customer.name,
            billingEmail = customer.email,
            metadata = metadata.toMutableMap(),
        ).also { store.charges[it.id] = it }
        return PaymentIntent(
            id = intentId,
            created = created,
            amount = amount,
            currency = "usd",
            clientSecret = store.newClientSecret(intentId),
            status = PaymentIntent.Status.REQUIRES_PAYMENT_METHOD,
            paymentMethod = paymentMethod.id,
            customer = customer.id,
            latestCharge = charge.id,
            lastPaymentError = buildJsonObject {
                put("type", "card_error")
                put("code", "card_declined")
                put("decline_code", "generic_decline")
                put("message", message)
                put("charge", charge.id)
            },
            metadata = metadata.toMutableMap(),
        ).also { store.paymentIntents[it.id] = it }
    }

    private fun seedRequiresActionIntent(
        store: DataStore,
        customer: Customer,
        paymentMethod: PaymentMethod,
        amount: Long,
        created: Long,
        metadata: Map<String, String>,
    ): PaymentIntent {
        val intentId = store.newId("pi")
        return PaymentIntent(
            id = intentId,
            created = created,
            amount = amount,
            currency = "usd",
            clientSecret = store.newClientSecret(intentId),
            status = PaymentIntent.Status.REQUIRES_ACTION,
            paymentMethod = paymentMethod.id,
            customer = customer.id,
            nextAction = buildJsonObject {
                put("type", "use_stripe_sdk")
                put("use_stripe_sdk", buildJsonObject { put("type", "three_d_secure_redirect") })
            },
            metadata = metadata.toMutableMap(),
        ).also { store.paymentIntents[it.id] = it }
    }

    private fun ensurePaymentMethod(store: DataStore, customer: Customer): PaymentMethod =
        store.paymentMethods.values.firstOrNull {
            it.customer == customer.id && TestCards.forNumber(it.number).outcome is TestCards.Outcome.Succeed
        } ?: newPaymentMethod(store, customer, "4242424242424242", ANCHOR).also {
            customer.defaultPaymentMethod = it.id
        }

    private fun newPaymentMethod(
        store: DataStore,
        customer: Customer,
        number: String,
        created: Long,
    ): PaymentMethod = PaymentMethod(
        id = store.newId("pm"),
        created = created,
        number = number,
        expMonth = 12,
        expYear = 2034,
        customer = customer.id,
        billingName = customer.name,
        billingEmail = customer.email,
    ).also { store.paymentMethods[it.id] = it }

    /** A tiny Basic/Pro catalog so subscription + upgrade tasks have prices to use. */
    private fun seedCatalog(store: DataStore) {
        fun product(name: String) = Product(
            id = store.newId("prod"), created = ANCHOR, updated = ANCHOR, name = name,
        ).also { store.products[it.id] = it }

        fun price(product: Product, amount: Long, interval: String, default: Boolean = false) = Price(
            id = store.newId("price"), created = ANCHOR, product = product.id,
            currency = "usd", unitAmount = amount, recurringInterval = interval,
        ).also {
            store.prices[it.id] = it
            if (default) product.defaultPrice = it.id
        }

        val basic = product("Basic Plan")
        price(basic, 1000, "month", default = true)          // $10 / month

        val pro = product("Pro Plan")
        price(pro, 3000, "month", default = true)            // $30 / month
        price(pro, 30000, "year")                            // $300 / year
    }

    private fun seedPaidIntent(
        store: DataStore,
        customer: Customer,
        pm: PaymentMethod,
        amount: Long,
        created: Long,
        description: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Charge {
        val piId = store.newId("pi")
        val chId = store.newId("ch")
        val card = TestCards.forNumber(pm.number)

        val charge = Charge(
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
            description = description,
            metadata = LinkedHashMap(metadata),
        ).also { store.charges[chId] = it }

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
            description = description,
            metadata = LinkedHashMap(metadata),
        )
        return charge
    }
}
