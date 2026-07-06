package com.fakestripe.store

import com.fakestripe.error.StripeException
import com.fakestripe.model.Charge
import com.fakestripe.model.Customer
import com.fakestripe.model.PaymentIntent
import com.fakestripe.model.PaymentMethod
import com.fakestripe.util.IdGenerator
import kotlinx.serialization.json.JsonObject

/**
 * The whole simulated world, held in memory. Insertion order is preserved so
 * listing is stable; a single [DataStore] instance is guarded by one lock in
 * [Simulator], which keeps ID generation and mutations deterministic.
 */
class DataStore(val seed: Long, idCount: Int = 0) {
    val ids = IdGenerator(seed, idCount)

    val customers = LinkedHashMap<String, Customer>()
    val paymentMethods = LinkedHashMap<String, PaymentMethod>()
    val paymentIntents = LinkedHashMap<String, PaymentIntent>()
    val charges = LinkedHashMap<String, Charge>()

    fun newId(prefix: String): String = ids.next(prefix)

    /** Stripe client secrets look like `pi_123_secret_abc`; keep them deterministic. */
    fun newClientSecret(objectId: String): String =
        objectId + "_secret_" + ids.next("s").substringAfter('_')

    /** Wall-clock seconds for runtime-created objects (timestamps are ignored by checkers). */
    fun now(): Long = System.currentTimeMillis() / 1000

    fun requireCustomer(id: String): Customer =
        customers[id]?.takeIf { !it.deleted } ?: throw StripeException.resourceMissing("customer", id)

    fun requirePaymentMethod(id: String): PaymentMethod =
        paymentMethods[id] ?: throw StripeException.resourceMissing("payment_method", id)

    fun requirePaymentIntent(id: String): PaymentIntent =
        paymentIntents[id] ?: throw StripeException.resourceMissing("payment_intent", id, param = "intent")

    fun requireCharge(id: String): Charge =
        charges[id] ?: throw StripeException.resourceMissing("charge", id)

    /** Resolve any id to its API JSON — used to satisfy `expand[]`. */
    fun resolveJson(id: String): JsonObject? = when {
        id.startsWith("cus_") -> customers[id]?.toApiJson()
        id.startsWith("pm_") -> paymentMethods[id]?.toApiJson()
        id.startsWith("pi_") -> paymentIntents[id]?.toApiJson()
        id.startsWith("ch_") -> charges[id]?.toApiJson()
        else -> null
    }
}
