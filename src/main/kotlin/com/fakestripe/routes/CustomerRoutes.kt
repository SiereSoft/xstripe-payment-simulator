package com.fakestripe.routes

import com.fakestripe.model.Customer
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.customerRoutes(sim: Simulator) {

    // Create
    post("/v1/customers") {
        val params = call.formParams()
        val json = sim.write { store ->
            val customer = Customer(
                id = store.newId("cus"),
                created = store.now(),
                email = params.opt("email"),
                name = params.opt("name"),
                description = params.opt("description"),
                phone = params.opt("phone"),
            )
            customer.metadata.putAll(params.subMap("metadata"))
            params.subMap("invoice_settings")["default_payment_method"]?.let {
                customer.defaultPaymentMethod = it
            }
            store.customers[customer.id] = customer
            store.expand(customer.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Retrieve
    get("/v1/customers/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store -> store.expand(store.requireCustomer(id).toApiJson(), params) }
        call.respondStripe(json)
    }

    // Update
    post("/v1/customers/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val c = store.requireCustomer(id)
            if (params.has("email")) c.email = params.opt("email")
            if (params.has("name")) c.name = params.opt("name")
            if (params.has("description")) c.description = params.opt("description")
            if (params.has("phone")) c.phone = params.opt("phone")
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) c.metadata.remove(k) else c.metadata[k] = v }
            params.subMap("invoice_settings")["default_payment_method"]?.let { c.defaultPaymentMethod = it.ifEmpty { null } }
            store.expand(c.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Delete
    delete("/v1/customers/{id}") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            val c = store.requireCustomer(id)
            c.deleted = true
            // Detach the customer's payment methods.
            store.paymentMethods.values.filter { it.customer == id }.forEach { it.customer = null }
            c.toDeletedJson()
        }
        call.respondStripe(json)
    }

    // List
    get("/v1/customers") {
        val params = call.queryParams()
        val emailFilter = params.opt("email")
        val json = sim.read { store ->
            val all = store.customers.values.filter { !it.deleted && (emailFilter == null || it.email == emailFilter) }
            store.paginated(all, "/v1/customers", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }

    // List a customer's payment methods
    get("/v1/customers/{id}/payment_methods") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store ->
            store.requireCustomer(id)
            val all = store.paymentMethods.values.filter { it.customer == id }
            store.paginated(all, "/v1/customers/$id/payment_methods", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
