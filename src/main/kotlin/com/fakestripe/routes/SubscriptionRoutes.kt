package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
import com.fakestripe.cards.TestCards
import com.fakestripe.control.ControlException
import com.fakestripe.control.ControlProtocol
import com.fakestripe.error.StripeException
import com.fakestripe.model.Subscription
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.subscriptionRoutes(sim: Simulator) {

    post("/v1/subscriptions") {
        val params = call.formParams()
        val json = sim.write { store ->
            store.expand(store.subscriptionJson(BillingOps.createSubscription(store, params), params.basil), params)
        }
        call.respondStripe(json)
    }

    get("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.subscriptionJson(store.requireSubscription(id), params.basil), params) })
    }

    // Update — upgrade/downgrade price (with proration), quantity, or cancel_at_period_end
    post("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val sub = store.requireSubscription(id)
            store.expand(store.subscriptionJson(BillingOps.updateSubscription(store, sub, params), params.basil), params)
        }
        call.respondStripe(json)
    }

    // Cancel immediately
    delete("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.write { store ->
            val sub = store.requireSubscription(id)
            store.subscriptionJson(BillingOps.cancelSubscription(store, sub, atPeriodEnd = false), params.basil)
        }
        call.respondStripe(json)
    }

    get("/v1/subscriptions") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val status = params.opt("status")
            val all = store.subscriptions.values.filter {
                (customer == null || it.customer == customer) && (status == null || it.status == status)
            }
            store.paginated(all, "/v1/subscriptions", params, { it.id }, { it.created }, { store.subscriptionJson(it, params.basil) })
        }
        call.respondStripe(json)
    }
}

/**
 * `/v1/subscription_items`: retrieve, list (by `subscription`, required as at Stripe) and update
 * one item. An update prorates per `proration_behavior` and emits `customer.subscription.updated`.
 */
fun Route.subscriptionItemRoutes(sim: Simulator) {

    get("/v1/subscription_items/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store ->
            val sub = store.subscriptions.values.firstOrNull { s -> s.items.any { it.id == id } }
                ?: throw StripeException.resourceMissing("subscription_item", id)
            val item = sub.items.first { it.id == id }
            store.expand(item.toApiJson(store.prices[item.priceId]?.toApiJson(), sub.period.takeIf { params.basil }), params)
        }
        call.respondStripe(json)
    }

    post("/v1/subscription_items/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val item = BillingOps.updateSubscriptionItem(store, id, params)
            val period = store.subscriptions[item.subscription]?.period?.takeIf { params.basil }
            store.expand(item.toApiJson(store.prices[item.priceId]?.toApiJson(), period), params)
        }
        call.respondStripe(json)
    }

    get("/v1/subscription_items") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val subscriptionId = params.require("subscription")
            val sub = store.requireSubscription(subscriptionId)
            store.paginated(
                sub.items, "/v1/subscription_items", params, { it.id }, { it.created },
                { it.toApiJson(store.prices[it.priceId]?.toApiJson(), sub.period.takeIf { params.basil }) },
            )
        }
        call.respondStripe(json)
    }
}

/**
 * Controller-only subscription tools for exercising recurring billing and dunning:
 *
 *   - GET  /v1/admin/subscriptions?status=&customer=        a compact list (times ISO-8601)
 *   - POST /v1/admin/subscriptions/{id}/renew               renew now, without moving the clock
 *   - POST /v1/admin/subscriptions/{id}/fail_next_renewal   `{"count": 1, "card": "4000000000000002"}`:
 *     the next `count` renewals charge that declining test card instead of the default payment
 *     method (`invoice.payment_failed`, `past_due`); `count: 0` clears it
 */
fun Route.subscriptionAdminRoutes(sim: Simulator) {
    get("/v1/admin/subscriptions") {
        val params = call.queryParams()
        val status = params.opt("status")
        val customer = params.opt("customer")
        val json = sim.read { store ->
            buildJsonObject {
                put("subscriptions", JsonArray(store.subscriptions.values.filter {
                    (status == null || it.status == status) && (customer == null || it.customer == customer)
                }.map { subscriptionSummary(store, it) }))
            }
        }
        call.respondStripe(json)
    }

    post("/v1/admin/subscriptions/{id}/fail_next_renewal") {
        val id = call.parameters["id"]!!
        val input = call.controlInput()
        val count = input.string("count")?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 0..100 } ?: throw ControlException.invalid("`count` must be 0..100.", "count")
        } ?: 1
        val card = input.string("card")?.filter { it.isDigit() }
        if (card != null && TestCards.forNumber(card).outcome !is TestCards.Outcome.Decline) {
            throw ControlException.invalid("`card` must be a declining test card such as 4000000000000002 or 4000000000009995.", "card")
        }
        val json = sim.write { store ->
            val sub = store.requireSubscription(id)
            if (BillingOps.isTerminal(sub)) {
                throw ControlException.conflict("subscription_status_invalid", "Subscription $id is ${sub.status} and never renews.")
            }
            BillingOps.failNextRenewals(sub, count, card)
            subscriptionSummary(store, sub)
        }
        call.respondStripe(json)
    }

    post("/v1/admin/subscriptions/{id}/renew") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            store.subscriptionJson(BillingOps.renewSubscription(store, store.requireSubscription(id)))
        }
        call.respondStripe(json)
    }
}

/** What a developer needs to see of a subscription at a glance (controller shape: ISO-8601 times). */
private fun subscriptionSummary(store: DataStore, sub: Subscription): JsonObject = buildJsonObject {
    fun iso(seconds: Long?) = seconds?.let { ControlProtocol.iso(it * 1000) }
    val prices = sub.items.mapNotNull { store.prices[it.priceId] }
    put("id", sub.id)
    put("status", sub.status)
    put("customer", sub.customer)
    put("created", iso(sub.created))
    put("current_period_start", iso(sub.currentPeriodStart))
    put("current_period_end", iso(sub.currentPeriodEnd))
    put("cancel_at_period_end", sub.cancelAtPeriodEnd)
    put("cancel_at", iso(sub.cancelAt))
    put("ended_at", iso(sub.endedAt))
    put("trial_end", iso(sub.trialEnd))
    put("amount", sub.items.sumOf { item -> (store.prices[item.priceId]?.unitAmount ?: 0L) * item.quantity })
    put("currency", sub.currency)
    put("interval", prices.firstOrNull()?.recurringInterval)
    put("interval_count", prices.firstOrNull()?.recurringIntervalCount)
    put("items", JsonArray(sub.items.map { item ->
        buildJsonObject {
            put("id", item.id)
            put("price", item.priceId)
            put("unit_amount", store.prices[item.priceId]?.unitAmount)
            put("quantity", item.quantity)
        }
    }))
    put("default_payment_method", sub.defaultPaymentMethod)
    put("latest_invoice", sub.latestInvoice)
    put("invoices", JsonArray(store.invoices.values.filter { it.subscription == sub.id }.map { invoice ->
        buildJsonObject {
            put("id", invoice.id)
            put("status", invoice.status)
            put("total", invoice.total)
            put("billing_reason", invoice.billingReason)
            put("period_start", iso(invoice.periodStart))
            put("period_end", iso(invoice.periodEnd))
        }
    }))
    put("fail_next_renewals", sub.failNextRenewals)
    put("metadata", buildJsonObject { sub.metadata.forEach { (k, v) -> put(k, v) } })
}
