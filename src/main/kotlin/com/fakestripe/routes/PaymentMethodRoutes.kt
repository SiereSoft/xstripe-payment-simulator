package com.fakestripe.routes

import com.fakestripe.cards.TestCards
import com.fakestripe.error.StripeException
import com.fakestripe.model.PaymentMethod
import com.fakestripe.statemachine.PaymentIntentMachine
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.paymentMethodRoutes(sim: Simulator) {

    // Create (card only)
    post("/v1/payment_methods") {
        val params = call.formParams()
        val json = sim.write { store ->
            val type = params.opt("type") ?: "card"
            if (type != "card") {
                throw StripeException.invalidRequest(
                    "This simulator only supports card payment methods (got '$type').", param = "type",
                )
            }
            // Accept a raw card number or a shared test token (card[token]=tok_visa / pm_card_visa).
            val token = params.subMap("card")["token"]
            val number = params.subMap("card")["number"]
                ?: token?.let { TestCards.numberForToken(it) }
                ?: throw StripeException.missingParam("card[number]")

            val pm = PaymentMethod(
                id = store.newId("pm"),
                created = store.now(),
                number = number,
                expMonth = params.subMap("card")["exp_month"]?.toIntOrNull() ?: 12,
                expYear = params.subMap("card")["exp_year"]?.toIntOrNull() ?: 2034,
                billingName = params.subMap("billing_details")["name"],
                billingEmail = params.subMap("billing_details")["email"],
                billingPhone = params.subMap("billing_details")["phone"],
            )
            pm.metadata.putAll(params.subMap("metadata"))
            store.paymentMethods[pm.id] = pm
            store.expand(pm.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Retrieve
    get("/v1/payment_methods/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store -> store.expand(store.requirePaymentMethod(id).toApiJson(), params) }
        call.respondStripe(json)
    }

    // Update (billing details / metadata)
    post("/v1/payment_methods/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pm = store.requirePaymentMethod(id)
            params.subMap("billing_details").let { bd ->
                if (bd.containsKey("name")) pm.billingName = bd["name"]
                if (bd.containsKey("email")) pm.billingEmail = bd["email"]
                if (bd.containsKey("phone")) pm.billingPhone = bd["phone"]
            }
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) pm.metadata.remove(k) else pm.metadata[k] = v }
            store.expand(pm.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Attach to a customer
    post("/v1/payment_methods/{id}/attach") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            // Accept shared test tokens (pm_card_visa, …) too — real Stripe attaches
            // those in test mode, so materialize them here rather than 404.
            val pm = PaymentIntentMachine.resolvePaymentMethod(store, id)
            val customerId = params.require("customer")
            store.requireCustomer(customerId)
            pm.customer = customerId
            store.expand(pm.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Detach
    post("/v1/payment_methods/{id}/detach") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val pm = store.requirePaymentMethod(id)
            // If it was a customer's default, clear that pointer.
            pm.customer?.let { cid -> store.customers[cid]?.let { if (it.defaultPaymentMethod == id) it.defaultPaymentMethod = null } }
            pm.customer = null
            store.expand(pm.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // List (filtered by customer; type optional, card only here)
    get("/v1/payment_methods") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val all = store.paymentMethods.values.filter { customer == null || it.customer == customer }
            store.paginated(all, "/v1/payment_methods", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
