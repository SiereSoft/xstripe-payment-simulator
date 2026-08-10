package com.fakestripe

import com.fakestripe.model.Charge
import com.fakestripe.seed.Seeder
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import com.fakestripe.store.toControlPlaneJson
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
    fun `all planned scenarios are deterministic solvable and varied across twenty seeds`() {
        val scenarios = listOf(
            Seeder.PAST_DUE_SUBSCRIPTION,
            Seeder.UPGRADE_CANDIDATE,
            Seeder.CANCEL_CANDIDATE,
            Seeder.PAYMENT_INVESTIGATION,
        )
        assertEquals(scenarios.toSet() + Seeder.DUPLICATE_PAYMENTS, Seeder.supportedScenarios)

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
