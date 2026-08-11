package com.fakestripe.routes

import com.fakestripe.model.Product
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.productRoutes(sim: Simulator) {

    post("/v1/products") {
        val params = call.formParams()
        val json = sim.write { store ->
            val now = store.now()
            val product = Product(
                id = store.newId("prod"),
                created = now,
                updated = now,
                name = params.require("name"),
                description = params.opt("description"),
                active = params.bool("active") ?: true,
                defaultPrice = params.opt("default_price"),
            )
            product.metadata.putAll(params.subMap("metadata"))
            store.products[product.id] = product
            store.expand(product.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    get("/v1/products/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.requireProduct(id).toApiJson(), params) })
    }

    post("/v1/products/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val p = store.requireProduct(id)
            if (params.has("name")) p.name = params.require("name")
            if (params.has("description")) p.description = params.opt("description")
            if (params.has("active")) p.active = params.bool("active") ?: p.active
            if (params.has("default_price")) p.defaultPrice = params.opt("default_price")
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) p.metadata.remove(k) else p.metadata[k] = v }
            p.updated = store.now()
            store.expand(p.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    delete("/v1/products/{id}") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            val p = store.requireProduct(id)
            store.products.remove(id)
            p.toDeletedJson()
        }
        call.respondStripe(json)
    }

    get("/v1/products") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val active = params.bool("active")
            val all = store.products.values.filter { active == null || it.active == active }
            store.paginated(all, "/v1/products", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
