package com.fakestripe.routes

import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.eventRoutes(sim: Simulator) {

    get("/v1/events/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.requireEvent(id).toApiJson(), params) })
    }

    get("/v1/events") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val type = params.opt("type")
            val all = store.events.values.filter { type == null || it.type == type }
            store.paginated(all, "/v1/events", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}
