package com.fakestripe.routes

import com.fakestripe.model.Price
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.priceRoutes(sim: Simulator) {

    post("/v1/prices") {
        val params = call.formParams()
        val json = sim.write { store ->
            val product = params.require("product").also { store.requireProduct(it) }
            val recurring = params.subMap("recurring")
            val price = Price(
                id = store.newId("price"),
                created = store.now(),
                product = product,
                currency = params.require("currency").lowercase(),
                unitAmount = params.long("unit_amount"),
                active = params.bool("active") ?: true,
                nickname = params.opt("nickname"),
                recurringInterval = recurring["interval"],
                recurringIntervalCount = recurring["interval_count"]?.toIntOrNull() ?: 1,
            )
            price.metadata.putAll(params.subMap("metadata"))
            store.prices[price.id] = price
            store.expand(price.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    get("/v1/prices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.requirePrice(id).toApiJson(), params) })
    }

    post("/v1/prices/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val price = store.requirePrice(id)
            if (params.has("active")) price.active = params.bool("active") ?: price.active
            if (params.has("nickname")) price.nickname = params.opt("nickname")
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) price.metadata.remove(k) else price.metadata[k] = v }
            store.expand(price.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    get("/v1/prices") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val product = params.opt("product")
            val active = params.bool("active")
            val all = store.prices.values.filter {
                (product == null || it.product == product) && (active == null || it.active == active)
            }
            store.paginated(all, "/v1/prices", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
