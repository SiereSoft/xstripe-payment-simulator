package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.invoiceRoutes(sim: Simulator) {

    get("/v1/invoices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.invoiceJson(store.requireInvoice(id)), params) })
    }

    // Update (metadata)
    post("/v1/invoices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val inv = store.requireInvoice(id)
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) inv.metadata.remove(k) else inv.metadata[k] = v }
            store.expand(store.invoiceJson(inv), params)
        }
        call.respondStripe(json)
    }

    // Attempt payment of an open invoice
    post("/v1/invoices/{id}/pay") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val inv = store.requireInvoice(id)
            val pmId = params.opt("payment_method")
                ?: store.customers[inv.customer]?.defaultPaymentMethod
            BillingOps.payInvoice(store, inv, inv.customer, pmId)
            store.expand(store.invoiceJson(inv), params)
        }
        call.respondStripe(json)
    }

    // Void an invoice
    post("/v1/invoices/{id}/void") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            val inv = store.requireInvoice(id)
            inv.status = "void"
            inv.paid = false
            inv.amountRemaining = 0
            store.invoiceJson(inv)
        }
        call.respondStripe(json)
    }

    get("/v1/invoices") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val subscription = params.opt("subscription")
            val status = params.opt("status")
            val all = store.invoices.values.filter {
                (customer == null || it.customer == customer) &&
                    (subscription == null || it.subscription == subscription) &&
                    (status == null || it.status == status)
            }
            store.paginated(all, "/v1/invoices", params, { it.id }, { it.created }, { store.invoiceJson(it) })
        }
        call.respondStripe(json)
    }
}
