package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
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

/** Controller-only forced renewal used to exercise recurring billing and dunning. */
fun Route.subscriptionAdminRoutes(sim: Simulator, controlToken: String?) {
    post("/v1/admin/subscriptions/{id}/renew") {
        if (!call.requireController(controlToken)) return@post
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            store.subscriptionJson(BillingOps.renewSubscription(store, store.requireSubscription(id)))
        }
        call.respondStripe(json)
    }
}
