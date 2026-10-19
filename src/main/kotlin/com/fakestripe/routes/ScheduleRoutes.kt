package com.fakestripe.routes

import com.fakestripe.billing.ScheduleOps
import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.scheduleRoutes(sim: Simulator) {
    post("/v1/subscription_schedules") {
        val params = call.formParams()
        call.respondStripe(sim.write { store -> store.expand(ScheduleOps.create(store, params).toApiJson(), params) })
    }
    get("/v1/subscription_schedules") {
        val params = call.queryParams()
        call.respondStripe(sim.read { store ->
            val rows = store.subscriptionSchedules.values.filter { params.opt("customer")?.let { id -> it.customer == id } ?: true }
            store.paginated(rows, "/v1/subscription_schedules", params, { it.id }, { it.created }, { it.toApiJson() })
        })
    }
    get("/v1/subscription_schedules/{id}") {
        val params = call.queryParams()
        call.respondStripe(sim.read { store -> store.expand(store.requireSchedule(call.parameters["id"]!!).toApiJson(), params) })
    }
    post("/v1/subscription_schedules/{id}") {
        val params = call.formParams()
        call.respondStripe(sim.write { store -> store.expand(ScheduleOps.update(store, store.requireSchedule(call.parameters["id"]!!), params).toApiJson(), params) })
    }
    post("/v1/subscription_schedules/{id}/release") {
        val params = call.formParams()
        call.respondStripe(sim.write { store -> store.expand(ScheduleOps.release(store, store.requireSchedule(call.parameters["id"]!!), params).toApiJson(), params) })
    }
    post("/v1/subscription_schedules/{id}/cancel") {
        val params = call.formParams()
        call.respondStripe(sim.write { store -> store.expand(ScheduleOps.cancel(store, store.requireSchedule(call.parameters["id"]!!), params).toApiJson(), params) })
    }
}
