package com.fakestripe.routes

import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.chargeRoutes(sim: Simulator) {

    // Retrieve
    get("/v1/charges/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store -> store.expand(store.chargeJson(store.requireCharge(id)), params) }
        call.respondStripe(json)
    }

    // Update (metadata / description)
    post("/v1/charges/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val charge = store.requireCharge(id)
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) charge.metadata.remove(k) else charge.metadata[k] = v }
            store.expand(store.chargeJson(charge), params)
        }
        call.respondStripe(json)
    }

    // List (optional customer / payment_intent filters)
    get("/v1/charges") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val paymentIntent = params.opt("payment_intent")
            val all = store.charges.values.filter {
                (customer == null || it.customer == customer) &&
                    (paymentIntent == null || it.paymentIntent == paymentIntent)
            }
            store.paginated(all, "/v1/charges", params, { it.id }, { it.created }, { store.chargeJson(it) })
        }
        call.respondStripe(json)
    }
}
