package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
import com.fakestripe.error.StripeException
import com.fakestripe.store.Simulator
import com.fakestripe.util.StripeParams
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Basil's includable `payments` list: `expand[]=payments` on one invoice, `data.payments` on a list. */
private fun StripeParams.includesPayments(path: String): Boolean = basil && path in expand

fun Route.invoiceRoutes(sim: Simulator) {

    get("/v1/invoices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(
            sim.read { store ->
                store.expand(store.invoiceJson(store.requireInvoice(id), params.basil, params.includesPayments("payments")), params)
            },
        )
    }

    // Update (metadata)
    post("/v1/invoices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val inv = store.requireInvoice(id)
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) inv.metadata.remove(k) else inv.metadata[k] = v }
            store.expand(store.invoiceJson(inv, params.basil, params.includesPayments("payments")), params)
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
            store.expand(store.invoiceJson(inv, params.basil, params.includesPayments("payments")), params)
        }
        call.respondStripe(json)
    }

    // Void an invoice
    post("/v1/invoices/{id}/void") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val inv = store.requireInvoice(id)
            BillingOps.voidInvoice(store, inv)
            store.invoiceJson(inv, params.basil, params.includesPayments("payments"))
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
            val payments = params.includesPayments("data.payments")
            store.paginated(all, "/v1/invoices", params, { it.id }, { it.created }, { store.invoiceJson(it, params.basil, payments) })
        }
        call.respondStripe(json)
    }
}

/**
 * Basil's `/v1/invoice_payments`: list (by `invoice`, or `payment[payment_intent]`, optionally
 * `status`) and retrieve.
 */
fun Route.invoicePaymentRoutes(sim: Simulator) {

    get("/v1/invoice_payments/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store ->
            val payment = store.invoicePayments().firstOrNull { it.id == id }
                ?: throw StripeException.resourceMissing("invoice_payment", id)
            store.expand(payment.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    get("/v1/invoice_payments") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val invoice = params.opt("invoice")
            val intent = params.subMap("payment")["payment_intent"]
            val status = params.opt("status")
            val all = store.invoicePayments().filter {
                (invoice == null || it.invoice == invoice) &&
                    (intent == null || it.paymentIntent == intent) &&
                    (status == null || it.status == status)
            }.toList()
            store.paginated(all, "/v1/invoice_payments", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
