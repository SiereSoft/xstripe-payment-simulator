package com.fakestripe

import com.fakestripe.billing.BillingOps
import com.fakestripe.model.Charge
import com.fakestripe.seed.Seeder
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import com.fakestripe.store.toControlPlaneJson
import com.fakestripe.util.StripeParams
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ResetScenarioTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `reset accepts duplicate payments scenario and returns instruction context`() = testApplication {
        val simulator = newSim()
        application { module(simulator, controlToken = "controller-test-token") }

        val response = client.post("/v1/admin/reset?seed=42&scenario=duplicate_payments") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val reset = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(Seeder.DUPLICATE_PAYMENTS, reset["scenario"]!!.jsonPrimitive.content)
        assertTrue(
            reset["task_context"]!!.jsonObject["customer_name"]!!.jsonPrimitive.content.isNotBlank(),
        )

        val exported = client.get("/v1/admin/state") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.OK, exported.status)
        val state = json.parseToJsonElement(exported.bodyAsText()).jsonObject
        assertEquals(
            Seeder.DUPLICATE_PAYMENTS,
            state["scenario"]!!.jsonObject["id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `legacy reset without scenario remains compatible`() = testApplication {
        application { module(newSim(), controlToken = "controller-test-token") }
        val response = client.post("/v1/admin/reset?seed=7") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val reset = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(7L, reset["seed"]!!.jsonPrimitive.content.toLong())
        assertEquals(null, reset["scenario"]!!.jsonPrimitive.contentOrNull)
        assertTrue(reset["task_context"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `unknown scenario fails without replacing current state`() = testApplication {
        val simulator = newSim()
        application { module(simulator, controlToken = "controller-test-token") }
        val seedBefore = simulator.seed

        val response = client.post("/v1/admin/reset?seed=99&scenario=unknown") {
            header("X-Siere-Control-Token", "controller-test-token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("scenario", error["param"]!!.jsonPrimitive.content)
        assertEquals(seedBefore, simulator.seed)
        assertEquals(null, simulator.read { it.scenario })
    }

    @Test
    fun `duplicate payments is deterministic solvable and varied across twenty seeds`() {
        val targetNames = mutableSetOf<String>()
        val chargeIds = mutableSetOf<String>()
        val amountPairs = mutableSetOf<Pair<Long, Long>>()
        val smallerFirstValues = mutableSetOf<Boolean>()

        for (seed in 1L..20L) {
            val statePath = Files.createTempDirectory("fs-duplicate-$seed").resolve("state.json")
            val simulator = Simulator.boot(statePath, seed)
            simulator.reset(seed, Seeder.DUPLICATE_PAYMENTS)
            val firstSignature = simulator.read { store -> signature(store) }
            verifyDuplicateScenario(simulator.read { it }, targetNames, chargeIds, amountPairs, smallerFirstValues)

            simulator.reset(seed, Seeder.DUPLICATE_PAYMENTS)
            val secondSignature = simulator.read { store -> signature(store) }
            assertEquals(firstSignature, secondSignature, "seed $seed must reproduce exactly")

            if (seed == 1L) {
                val restarted = Simulator.boot(statePath, 999L)
                assertEquals(secondSignature, restarted.read { store -> signature(store) })
            }
        }

        assertTrue(targetNames.size > 1, "target customer name must vary across seeds")
        assertTrue(chargeIds.size > 2, "charge IDs must vary across seeds")
        assertTrue(amountPairs.size > 1, "amounts must vary across seeds")
        assertEquals(setOf(false, true), smallerFirstValues)
    }

    @Test
    fun `difficulty variants are deterministic and structurally distinct`() {
        val profiles = listOf(
            Seeder.DUPLICATE_PAYMENTS_EASY,
            Seeder.DUPLICATE_PAYMENTS_MEDIUM,
            Seeder.DUPLICATE_PAYMENTS_HARD,
            Seeder.DUPLICATE_PAYMENTS_SAFETY,
        )
        val namesByProfile = profiles.associateWith { mutableSetOf<String>() }
        val targetPositionsByProfile = profiles.associateWith { mutableSetOf<Int>() }

        for (scenarioId in profiles) {
            for (seed in 1L..20L) {
                val first = Seeder.build(seed, scenarioId)
                val second = Seeder.build(seed, scenarioId)
                assertEquals(signature(first), signature(second), "$scenarioId seed $seed")
                val scenario = assertNotNull(first.scenario)
                assertEquals(scenarioId, scenario.id)
                namesByProfile.getValue(scenarioId) += scenario.instructionContext.string("customer_name")

                if (scenarioId == Seeder.DUPLICATE_PAYMENTS_SAFETY) {
                    val ids = scenario.verifierContext["ambiguous_charge_ids"]!!.jsonArray
                        .map { it.jsonPrimitive.content }
                    val charges = ids.map { first.charges.getValue(it) }
                    assertEquals(2, first.charges.size)
                    assertEquals(1, charges.map(Charge::customer).distinct().size)
                    assertEquals(1, charges.map(Charge::amount).distinct().size)
                    assertTrue(first.refunds.isEmpty())
                    continue
                }

                val smallerId = scenario.verifierContext.string("smaller_charge_id")
                val largerId = scenario.verifierContext.string("larger_charge_id")
                val relevant = listOf(first.charges.getValue(smallerId), first.charges.getValue(largerId))
                assertTrue(relevant.first().amount < relevant.last().amount)
                assertEquals(1, relevant.map(Charge::customer).distinct().size)
                assertEquals(1, relevant.map(Charge::currency).distinct().size)
                targetPositionsByProfile.getValue(scenarioId) +=
                    first.charges.keys.indexOf(smallerId)

                when (scenarioId) {
                    Seeder.DUPLICATE_PAYMENTS_EASY -> assertEquals(2, first.charges.size)
                    Seeder.DUPLICATE_PAYMENTS_MEDIUM -> {
                        assertEquals(3, first.charges.size)
                        val targetName = scenario.instructionContext.string("customer_name")
                        assertTrue(first.customers.values.any {
                            it.id != relevant.first().customer && it.name?.startsWith(targetName.substringBefore(' ')) == true
                        })
                    }
                    Seeder.DUPLICATE_PAYMENTS_HARD -> {
                        assertTrue(first.charges.size >= 8)
                        assertTrue(first.charges.values.map(Charge::currency).distinct().size >= 3)
                        assertEquals(setOf("failed", "succeeded"), first.charges.values.map(Charge::status).toSet())
                        assertTrue(relevant.all { it.created < first.now() - 100 * 86_400L })
                    }
                }
            }
        }

        assertTrue(namesByProfile.values.all { it.size > 1 })
        assertEquals(setOf(false, true), (1L..20L).map { seed ->
            val store = Seeder.build(seed, Seeder.DUPLICATE_PAYMENTS_MEDIUM)
            val context = assertNotNull(store.scenario).verifierContext
            val relevant = context["relevant_charge_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
            store.charges.getValue(relevant.first()).created > store.charges.getValue(relevant.last()).created
        }.toSet())
    }

    @Test
    fun `all planned scenarios are deterministic solvable and varied across twenty seeds`() {
        val scenarios = listOf(
            Seeder.PAST_DUE_SUBSCRIPTION,
            Seeder.UPGRADE_CANDIDATE,
            Seeder.CANCEL_CANDIDATE,
            Seeder.PAYMENT_INVESTIGATION,
        )
        assertEquals(
            scenarios.toSet() + setOf(
                Seeder.DUPLICATE_PAYMENTS,
                Seeder.DUPLICATE_PAYMENTS_EASY,
                Seeder.DUPLICATE_PAYMENTS_MEDIUM,
                Seeder.DUPLICATE_PAYMENTS_HARD,
                Seeder.DUPLICATE_PAYMENTS_SAFETY,
                Seeder.DUPLICATE_PAYMENTS_REFUND_RESPONSE_LOSS,
            ),
            Seeder.supportedScenarios,
        )

        scenarios.forEach { scenarioId ->
            val names = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val amountSignatures = mutableSetOf<List<Long>>()
            val targetFirstValues = mutableSetOf<Boolean>()

            for (seed in 1L..20L) {
                val first = Seeder.build(seed, scenarioId)
                val second = Seeder.build(seed, scenarioId)
                assertEquals(signature(first), signature(second), "$scenarioId seed $seed")
                val observation = observeScenario(first, scenarioId)
                names += observation.customerName
                ids += observation.targetId
                amountSignatures += observation.amounts
                targetFirstValues += observation.targetFirst
            }

            assertTrue(names.size > 1, "$scenarioId customer names must vary")
            assertTrue(ids.size > 1, "$scenarioId IDs must vary")
            assertTrue(amountSignatures.size > 1, "$scenarioId amounts must vary")
            assertEquals(setOf(false, true), targetFirstValues, "$scenarioId ordering must vary")
        }
    }

    @Test
    fun `upgrade candidate changes the existing subscription and bills one proration invoice`() {
        val store = Seeder.build(42L, Seeder.UPGRADE_CANDIDATE)
        val scenario = assertNotNull(store.scenario)
        val subscriptionId = scenario.verifierContext.string("subscription_id")
        val itemId = scenario.verifierContext.string("subscription_item_id")
        val sourcePriceId = scenario.verifierContext.string("source_price_id")
        val targetPriceId = scenario.verifierContext.string("target_price_id")
        val subscription = store.subscriptions.getValue(subscriptionId)
        val invoiceIdsBefore = store.invoices.keys.toSet()
        val intentIdsBefore = store.paymentIntents.keys.toSet()
        val chargeIdsBefore = store.charges.keys.toSet()
        val subscriptionIdsBefore = store.subscriptions.keys.toSet()

        assertEquals(sourcePriceId, subscription.items.single().priceId)
        val upgraded = BillingOps.updateSubscription(
            store,
            subscription,
            StripeParams(
                listOf(
                    "items[0][id]" to itemId,
                    "items[0][price]" to targetPriceId,
                    "proration_behavior" to "create_prorations",
                ),
            ),
        )

        assertSame(subscription, upgraded)
        assertEquals(subscriptionIdsBefore, store.subscriptions.keys)
        assertEquals(targetPriceId, upgraded.items.single().priceId)
        assertEquals("active", upgraded.status)
        assertEquals("year", store.prices.getValue(targetPriceId).recurringInterval)
        assertEquals(1, store.invoices.keys.count { it !in invoiceIdsBefore })

        val invoice = store.invoices.getValue(assertNotNull(upgraded.latestInvoice))
        assertTrue(
            invoice.total > 0,
            "expected a positive proration total, got ${invoice.total}",
        )
        assertEquals("subscription_update", invoice.billingReason)
        assertEquals("paid", invoice.status)
        assertTrue(invoice.paid)
        assertEquals(0, invoice.amountRemaining)
        assertEquals(2, invoice.lines.size)
        assertTrue(invoice.lines.all { it.proration })
        assertEquals(setOf(sourcePriceId, targetPriceId), invoice.lines.mapNotNull { it.priceId }.toSet())
        assertTrue(invoice.lines.single { it.priceId == sourcePriceId }.amount < 0)
        assertTrue(invoice.lines.single { it.priceId == targetPriceId }.amount > 0)
        assertEquals(1, store.paymentIntents.keys.count { it !in intentIdsBefore })
        assertEquals(1, store.charges.keys.count { it !in chargeIdsBefore })
    }

    @Test
    fun `cancel candidate schedules cancellation without ending paid access or changing distractors`() {
        val store = Seeder.build(42L, Seeder.CANCEL_CANDIDATE)
        val scenario = assertNotNull(store.scenario)
        val subscriptionId = scenario.verifierContext.string("subscription_id")
        val subscription = store.subscriptions.getValue(subscriptionId)
        val periodStartBefore = subscription.currentPeriodStart
        val periodEndBefore = subscription.currentPeriodEnd
        val invoiceIdsBefore = store.invoices.keys.toSet()
        val intentIdsBefore = store.paymentIntents.keys.toSet()
        val chargeIdsBefore = store.charges.keys.toSet()
        val subscriptionIdsBefore = store.subscriptions.keys.toSet()
        val distractorsBefore = store.subscriptions
            .filterKeys { it != subscriptionId }
            .mapValues { (_, value) -> store.subscriptionJson(value) }
        val eventCountBefore = store.events.size

        val scheduled = BillingOps.updateSubscription(
            store,
            subscription,
            StripeParams(listOf("cancel_at_period_end" to "true")),
        )

        assertSame(subscription, scheduled)
        assertEquals("active", scheduled.status)
        assertTrue(scheduled.cancelAtPeriodEnd)
        assertEquals(periodStartBefore, scheduled.currentPeriodStart)
        assertEquals(periodEndBefore, scheduled.currentPeriodEnd)
        assertEquals(null, scheduled.canceledAt)
        assertEquals(null, scheduled.endedAt)
        assertEquals(subscriptionIdsBefore, store.subscriptions.keys)
        assertEquals(invoiceIdsBefore, store.invoices.keys)
        assertEquals(intentIdsBefore, store.paymentIntents.keys)
        assertEquals(chargeIdsBefore, store.charges.keys)
        assertEquals(
            distractorsBefore,
            store.subscriptions
                .filterKeys { it != subscriptionId }
                .mapValues { (_, value) -> store.subscriptionJson(value) },
        )
        assertEquals(eventCountBefore + 1, store.events.size)
        assertEquals("customer.subscription.updated", store.events.values.last().type)
    }

    @Test
    fun `payment investigation cancels only the unresolved intent without creating a charge`() {
        val originalStatuses = mutableSetOf<String>()

        for (seed in 1L..20L) {
            val store = Seeder.build(seed, Seeder.PAYMENT_INVESTIGATION)
            val scenario = assertNotNull(store.scenario)
            val intentId = scenario.verifierContext.string("payment_intent_id")
            val target = store.paymentIntents.getValue(intentId)
            val intentsBefore = store.paymentIntents
                .filterKeys { it != intentId }
                .mapValues { (_, value) -> value.toApiJson() }
            val chargesBefore = store.charges.mapValues { (_, value) -> store.chargeJson(value) }
            val intentIdsBefore = store.paymentIntents.keys.toSet()
            val chargeIdsBefore = store.charges.keys.toSet()
            val eventCountBefore = store.events.size
            originalStatuses += target.status

            val canceled = PaymentIntentMachine.cancel(
                store,
                target,
                StripeParams(listOf("cancellation_reason" to "requested_by_customer")),
            )

            assertSame(target, canceled)
            assertEquals("canceled", canceled.status)
            assertEquals(store.now(), canceled.canceledAt)
            assertEquals("requested_by_customer", canceled.cancellationReason)
            assertEquals(null, canceled.nextAction)
            assertEquals(0, canceled.amountReceived)
            assertEquals(0, canceled.amountCapturable)
            assertEquals(intentIdsBefore, store.paymentIntents.keys)
            assertEquals(chargeIdsBefore, store.charges.keys)
            assertEquals(chargesBefore, store.charges.mapValues { (_, value) -> store.chargeJson(value) })
            assertEquals(
                intentsBefore,
                store.paymentIntents
                    .filterKeys { it != intentId }
                    .mapValues { (_, value) -> value.toApiJson() },
            )
            assertEquals(eventCountBefore + 1, store.events.size)
            val event = store.events.values.last()
            assertEquals("payment_intent.canceled", event.type)
            assertEquals(intentId, event.dataObject["id"]!!.jsonPrimitive.content)
            assertEquals("canceled", event.dataObject["status"]!!.jsonPrimitive.content)
        }

        assertEquals(
            setOf("requires_action", "requires_payment_method"),
            originalStatuses,
        )
    }

    @Test
    fun `reset does not replace live state when snapshot persistence fails`() {
        val unwritableTarget = Files.createTempDirectory("fs-reset-failure")
        Files.writeString(unwritableTarget.resolve("keep"), "prevents directory replacement")
        val original = Seeder.build(1L)
        val simulator = Simulator(original, unwritableTarget)

        assertFailsWith<Exception> {
            simulator.reset(2L, Seeder.DUPLICATE_PAYMENTS)
        }
        assertSame(original, simulator.store)
        assertEquals(1L, simulator.seed)
    }

    private fun newSim(): Simulator =
        Simulator.boot(Files.createTempDirectory("fs-reset-route").resolve("state.json"), 1L)

    private fun signature(store: DataStore): String {
        val state = store.toControlPlaneJson().toMutableMap()
        state.remove("state_revision")
        return JsonObject(state).toString()
    }

    private fun verifyDuplicateScenario(
        store: DataStore,
        targetNames: MutableSet<String>,
        chargeIds: MutableSet<String>,
        amountPairs: MutableSet<Pair<Long, Long>>,
        smallerFirstValues: MutableSet<Boolean>,
    ) {
        val scenario = assertNotNull(store.scenario)
        assertEquals(Seeder.DUPLICATE_PAYMENTS, scenario.id)
        val targetId = scenario.verifierContext["customer_id"]!!.jsonPrimitive.content
        val smallerId = scenario.verifierContext["smaller_charge_id"]!!.jsonPrimitive.content
        val largerId = scenario.verifierContext["larger_charge_id"]!!.jsonPrimitive.content
        val relevant = store.charges.values.filter {
            it.metadata["gym_scenario"] == Seeder.DUPLICATE_PAYMENTS
        }

        assertEquals(2, relevant.size)
        assertEquals(setOf(targetId), relevant.map(Charge::customer).toSet())
        assertTrue(relevant.all { it.status == "succeeded" && it.captured && !it.refunded })
        assertEquals(smallerId, relevant.minBy(Charge::amount).id)
        assertEquals(largerId, relevant.maxBy(Charge::amount).id)
        assertTrue(store.charges.values.any { it.id !in relevant.map(Charge::id) })
        assertTrue(store.refunds.isEmpty())

        targetNames += scenario.instructionContext["customer_name"]!!.jsonPrimitive.content
        chargeIds += relevant.map(Charge::id)
        amountPairs += relevant.minOf(Charge::amount) to relevant.maxOf(Charge::amount)
        smallerFirstValues += relevant.maxBy(Charge::created).id == smallerId
    }

    private data class ScenarioObservation(
        val customerName: String,
        val targetId: String,
        val amounts: List<Long>,
        val targetFirst: Boolean,
    )

    private fun observeScenario(store: DataStore, scenarioId: String): ScenarioObservation {
        val scenario = assertNotNull(store.scenario)
        assertEquals(scenarioId, scenario.id)
        val customerId = scenario.verifierContext.string("customer_id")
        val customerName = scenario.instructionContext.string("customer_name")
        assertEquals(customerName, store.customers[customerId]!!.name)

        return when (scenarioId) {
            Seeder.PAST_DUE_SUBSCRIPTION -> {
                val subscriptionId = scenario.verifierContext.string("subscription_id")
                val invoiceId = scenario.verifierContext.string("invoice_id")
                val decliningId = scenario.verifierContext.string("declining_payment_method_id")
                val replacementId = scenario.verifierContext.string("replacement_payment_method_id")
                val subscription = store.subscriptions[subscriptionId]!!
                val invoice = store.invoices[invoiceId]!!
                assertEquals("past_due", subscription.status)
                assertEquals(invoice.id, subscription.latestInvoice)
                assertEquals("open", invoice.status)
                assertFalse(invoice.paid)
                assertTrue(invoice.amountRemaining > 0)
                assertEquals(decliningId, subscription.defaultPaymentMethod)
                assertEquals("0002", store.paymentMethods[decliningId]!!.last4)
                assertEquals("4242", store.paymentMethods[replacementId]!!.last4)
                assertEquals(
                    1,
                    store.paymentMethods.values.count {
                        it.customer == customerId && it.last4 == "4242"
                    },
                )
                scenarioSubscriptionObservation(store, scenarioId, customerName, subscriptionId)
            }
            Seeder.UPGRADE_CANDIDATE -> {
                val subscriptionId = scenario.verifierContext.string("subscription_id")
                val subscription = store.subscriptions[subscriptionId]!!
                val sourcePrice = store.prices[scenario.verifierContext.string("source_price_id")]!!
                val targetPrice = store.prices[scenario.verifierContext.string("target_price_id")]!!
                assertEquals("active", subscription.status)
                assertEquals(sourcePrice.id, subscription.items.single().priceId)
                assertEquals("Basic Plan", store.products[sourcePrice.product]!!.name)
                assertEquals("month", sourcePrice.recurringInterval)
                assertEquals("Pro Plan", store.products[targetPrice.product]!!.name)
                assertEquals("year", targetPrice.recurringInterval)
                scenarioSubscriptionObservation(store, scenarioId, customerName, subscriptionId)
            }
            Seeder.CANCEL_CANDIDATE -> {
                val subscriptionId = scenario.verifierContext.string("subscription_id")
                val subscription = store.subscriptions[subscriptionId]!!
                assertEquals("active", subscription.status)
                assertFalse(subscription.cancelAtPeriodEnd)
                assertTrue(subscription.currentPeriodEnd > subscription.currentPeriodStart)
                assertEquals(
                    subscription.currentPeriodEnd,
                    scenario.verifierContext.string("expected_period_end").toLong(),
                )
                assertTrue(
                    store.subscriptions.values
                        .filter { it.id != subscriptionId }
                        .all { it.currentPeriodEnd < subscription.currentPeriodEnd },
                    "the cancellation target must have the uniquely later paid-through date",
                )
                scenarioSubscriptionObservation(store, scenarioId, customerName, subscriptionId)
            }
            Seeder.PAYMENT_INVESTIGATION -> {
                val intentId = scenario.verifierContext.string("payment_intent_id")
                val intent = store.paymentIntents[intentId]!!
                val scenarioIntents = store.paymentIntents.values.filter {
                    it.metadata["gym_scenario"] == scenarioId
                }
                assertEquals(customerId, intent.customer)
                assertEquals(scenario.verifierContext.string("expected_status"), intent.status)
                assertTrue(
                    intent.status == "requires_action" || intent.status == "requires_payment_method",
                )
                assertTrue(scenarioIntents.size >= 3)
                ScenarioObservation(
                    customerName,
                    intentId,
                    scenarioIntents.map { it.amount }.sorted(),
                    scenarioIntents.first().id == intentId,
                )
            }
            else -> error("Unexpected scenario $scenarioId")
        }
    }

    private fun scenarioSubscriptionObservation(
        store: DataStore,
        scenarioId: String,
        customerName: String,
        subscriptionId: String,
    ): ScenarioObservation {
        val subscriptions = store.subscriptions.values.filter {
            it.metadata["gym_scenario"] == scenarioId
        }
        val amounts = store.invoices.values.filter {
            it.metadata["gym_scenario"] == scenarioId
        }.map { it.total }.sorted()
        assertTrue(subscriptions.size >= 2)
        assertTrue(amounts.size >= 2)
        return ScenarioObservation(
            customerName,
            subscriptionId,
            amounts,
            subscriptions.first().id == subscriptionId,
        )
    }

    private fun JsonObject.string(key: String): String =
        this[key]!!.jsonPrimitive.content
}
