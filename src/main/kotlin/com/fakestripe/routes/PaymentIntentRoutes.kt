package com.fakestripe.routes

import com.fakestripe.model.PaymentIntent
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.paymentIntentRoutes(sim: Simulator) {

    // Create (optionally confirm=true)
    post("/v1/payment_intents") {
        val params = call.formParams()
        val json = sim.write { store ->
            val pi = PaymentIntentMachine.create(store, params)
            store.expand(pi.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Retrieve
    get("/v1/payment_intents/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store -> store.expand(store.requirePaymentIntent(id).toApiJson(), params) }
        call.respondStripe(json)
    }

    // Update (limited fields while not in a terminal state)
    post("/v1/payment_intents/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pi = store.requirePaymentIntent(id)
            if (params.has("description")) pi.description = params.opt("description")
            if (params.has("receipt_email")) pi.receiptEmail = params.opt("receipt_email")
            if (params.has("payment_method")) {
                val pm = PaymentIntentMachine.resolvePaymentMethod(store, params.require("payment_method"))
                pi.paymentMethod = pm.id
                if (pi.status == PaymentIntent.Status.REQUIRES_PAYMENT_METHOD) {
                    pi.status = PaymentIntent.Status.REQUIRES_CONFIRMATION
                }
            }
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) pi.metadata.remove(k) else pi.metadata[k] = v }
            store.expand(pi.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Confirm
    post("/v1/payment_intents/{id}/confirm") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pi = store.requirePaymentIntent(id)
            PaymentIntentMachine.confirm(store, pi, params.opt("payment_method"), params)
            store.expand(pi.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Capture (manual-capture flow)
    post("/v1/payment_intents/{id}/capture") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pi = store.requirePaymentIntent(id)
            PaymentIntentMachine.capture(store, pi, params)
            store.expand(pi.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Cancel
    post("/v1/payment_intents/{id}/cancel") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pi = store.requirePaymentIntent(id)
            PaymentIntentMachine.cancel(store, pi, params)
            store.expand(pi.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // List (optional customer filter)
    get("/v1/payment_intents") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val all = store.paymentIntents.values.filter { customer == null || it.customer == customer }
            store.paginated(all, "/v1/payment_intents", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
