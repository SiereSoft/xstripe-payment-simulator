package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
import com.fakestripe.error.StripeException
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.subscriptionRoutes(sim: Simulator) {

    post("/v1/subscriptions") {
        val params = call.formParams()
        val json = sim.write { store ->
            store.expand(store.subscriptionJson(BillingOps.createSubscription(store, params)), params)
        }
        call.respondStripe(json)
    }

    get("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.subscriptionJson(store.requireSubscription(id)), params) })
    }

    // Update — upgrade/downgrade price (with proration), quantity, or cancel_at_period_end
    post("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val sub = store.requireSubscription(id)
            store.expand(store.subscriptionJson(BillingOps.updateSubscription(store, sub, params)), params)
        }
        call.respondStripe(json)
    }

    // Cancel immediately
    delete("/v1/subscriptions/{id}") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            val sub = store.requireSubscription(id)
            store.subscriptionJson(BillingOps.cancelSubscription(store, sub, atPeriodEnd = false))
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
            store.paginated(all, "/v1/subscriptions", params, { it.id }, { it.created }, { store.subscriptionJson(it) })
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
            val item = store.subscriptions.values.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }
                ?: throw StripeException.resourceMissing("subscription_item", id)
            store.expand(item.toApiJson(store.prices[item.priceId]?.toApiJson()), params)
        }
        call.respondStripe(json)
    }

    post("/v1/subscription_items/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val item = BillingOps.updateSubscriptionItem(store, id, params)
            store.expand(item.toApiJson(store.prices[item.priceId]?.toApiJson()), params)
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
                { it.toApiJson(store.prices[it.priceId]?.toApiJson()) },
            )
        }
        call.respondStripe(json)
    }
}

/** Controller-only forced renewal used to exercise recurring billing and dunning. */
fun Route.subscriptionAdminRoutes(sim: Simulator) {
    post("/v1/admin/subscriptions/{id}/renew") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            store.subscriptionJson(BillingOps.renewSubscription(store, store.requireSubscription(id)))
        }
        call.respondStripe(json)
    }
}
