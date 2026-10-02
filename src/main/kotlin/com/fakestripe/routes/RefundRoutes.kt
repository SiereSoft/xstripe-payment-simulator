package com.fakestripe.routes

import com.fakestripe.error.StripeException
import com.fakestripe.model.Charge
import com.fakestripe.model.Refund
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import com.fakestripe.util.StripeParams
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.refundRoutes(sim: Simulator) {

    // Create — refund a charge or a payment_intent (full or partial)
    post("/v1/refunds") {
        val params = call.formParams()
        val json = sim.write { store ->
            val charge = resolveRefundTarget(store, params.opt("charge"), params.opt("payment_intent"))
            store.expand(createRefund(store, charge, params).toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Legacy nested create: POST /v1/charges/{id}/refunds
    post("/v1/charges/{id}/refunds") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val charge = store.requireCharge(id)
            store.expand(createRefund(store, charge, params).toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // Retrieve
    get("/v1/refunds/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store -> store.expand(store.requireRefund(id).toApiJson(), params) }
        call.respondStripe(json)
    }

    // Update (metadata)
    post("/v1/refunds/{id}") {
        val id = call.parameters["id"]!!
        val params = call.formParams()
        val json = sim.write { store ->
            val refund = store.requireRefund(id)
            params.subMap("metadata").forEach { (k, v) -> if (v.isEmpty()) refund.metadata.remove(k) else refund.metadata[k] = v }
            store.expand(refund.toApiJson(), params)
        }
        call.respondStripe(json)
    }

    // List (optional charge / payment_intent filters)
    get("/v1/refunds") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val charge = params.opt("charge")
            val paymentIntent = params.opt("payment_intent")
            val all = store.refunds.values.filter {
                (charge == null || it.charge == charge) &&
                    (paymentIntent == null || it.paymentIntent == paymentIntent)
            }
            store.paginated(all, "/v1/refunds", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }
}

private fun resolveRefundTarget(store: DataStore, chargeId: String?, paymentIntentId: String?): Charge {
    if (chargeId != null) return store.requireCharge(chargeId)
    if (paymentIntentId != null) {
        val pi = store.requirePaymentIntent(paymentIntentId)
        val latest = pi.latestCharge
            ?: throw StripeException.invalidRequest("PaymentIntent $paymentIntentId has no charge to refund.", param = "payment_intent")
        return store.requireCharge(latest)
    }
    throw StripeException.invalidRequest("One of `charge` or `payment_intent` is required.", param = "charge")
}

/** Validate + apply a refund to a charge, mutating the charge's refunded totals. */
private fun createRefund(store: DataStore, charge: Charge, params: StripeParams): Refund {
    if (charge.status != "succeeded" || !charge.captured) {
        throw StripeException.invalidRequest(
            "Charge ${charge.id} cannot be refunded because it has not been successfully captured.",
            param = "charge",
        )
    }
    val remaining = charge.amount - charge.amountRefunded
    if (remaining <= 0) {
        throw StripeException.invalidRequest(
            "Charge ${charge.id} has already been fully refunded.",
            code = "charge_already_refunded", param = "charge",
        )
    }
    val amount = params.long("amount") ?: remaining
    if (amount <= 0) {
        throw StripeException.invalidRequest("Invalid positive integer", param = "amount", code = "parameter_invalid_integer")
    }
    if (amount > remaining) {
        throw StripeException.invalidRequest(
            "Refund amount ($amount) is greater than unrefunded amount on charge ($remaining).",
            param = "amount",
        )
    }

    val refund = Refund(
        id = store.newId("re"),
        created = store.now(),
        amount = amount,
        currency = charge.currency,
        charge = charge.id,
        paymentIntent = charge.paymentIntent,
        reason = params.opt("reason"),
    )
    refund.metadata.putAll(params.subMap("metadata"))
    store.refunds[refund.id] = refund

    charge.amountRefunded += amount
    charge.refunded = charge.amountRefunded >= charge.amount
    store.recordEvent("charge.refunded", store.chargeJson(charge))
    return refund
}
