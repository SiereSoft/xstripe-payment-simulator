package com.fakestripe.billing

import com.fakestripe.error.StripeException
import com.fakestripe.model.ScheduleItem
import com.fakestripe.model.SchedulePhase
import com.fakestripe.model.Subscription
import com.fakestripe.model.SubscriptionItem
import com.fakestripe.model.SubscriptionSchedule
import com.fakestripe.store.DataStore
import com.fakestripe.util.StripeParams

/** Future recurring terms; billing and payment collection remain owned by BillingOps. */
object ScheduleOps {
    private val prorations = setOf("none", "create_prorations", "always_invoice")
    private val phaseKey = Regex("phases\\[\\d+](?:\\[(?:start_date|end_date|iterations|proration_behavior|billing_cycle_anchor)]|\\[metadata]\\[[^]]+]|\\[duration]\\[(?:interval|interval_count)]|\\[items]\\[\\d+]\\[(?:price|quantity)])")

    fun create(store: DataStore, params: StripeParams): SubscriptionSchedule {
        // Stripe requires migration and custom phase configuration to be separate operations.
        params.keys().firstOrNull { it != "from_subscription" && !it.startsWith("expand[") }?.let {
            invalid("from_subscription cannot be combined with other schedule parameters.", it)
        }
        val sub = store.requireSubscription(params.require("from_subscription"))
        if (sub.schedule != null) invalid("The subscription is already attached to a schedule.", "from_subscription")
        if (sub.status !in setOf("active", "past_due") || sub.cancelAtPeriodEnd) {
            invalid("Migrate an active or past-due recurring subscription without a pending cancellation.", "from_subscription")
        }
        val phase = SchedulePhase(
            sub.currentPeriodStart, sub.currentPeriodEnd,
            sub.items.map { ScheduleItem(it.priceId, it.quantity) }, sub.currency,
        )
        val schedule = SubscriptionSchedule(
            store.newId("sub_sched"), store.now(), sub.customer, sub.id, listOf(phase),
            defaultPaymentMethod = sub.defaultPaymentMethod,
        )
        store.subscriptionSchedules[schedule.id] = schedule
        sub.schedule = schedule.id
        store.recordEvent("subscription_schedule.created", schedule.toApiJson())
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return schedule
    }

    fun update(store: DataStore, schedule: SubscriptionSchedule, params: StripeParams): SubscriptionSchedule {
        active(schedule)
        params.keys().firstOrNull {
            it !in setOf("end_behavior", "proration_behavior", "metadata") &&
                !it.startsWith("metadata[") && !it.startsWith("expand[") && !phaseKey.matches(it)
        }?.let { invalid("Unsupported schedule parameter.", it) }
        val end = params.opt("end_behavior") ?: schedule.endBehavior
        if (end !in setOf("release", "cancel")) invalid("Choose release or cancel.", "end_behavior")
        params.opt("proration_behavior")?.let { if (it !in prorations) invalid("Invalid proration behavior.", "proration_behavior") }
        val sub = store.requireSubscription(requireNotNull(schedule.subscription))
        val indices = params.indices("phases")
        val phases = if (indices.isEmpty()) schedule.phases else {
            if (indices.size > 10 || indices != indices.indices.toList()) invalid("Supply 1–10 consecutive phase indices.", "phases")
            val previous = schedule.phases[schedule.currentPhase]
            val proposed = ArrayList<SchedulePhase>()
            indices.forEach { i ->
                val prefix = "phases[$i]"
                val expectedStart = proposed.lastOrNull()?.endDate ?: previous.startDate
                val start = params.long("$prefix[start_date]") ?: expectedStart
                if (start != expectedStart) invalid("Phases must be contiguous and retain the active phase start.", "$prefix[start_date]")
                val itemIndices = params.indices("$prefix[items]")
                if (itemIndices.isEmpty() || itemIndices.size > 20 || itemIndices != itemIndices.indices.toList()) {
                    invalid("Supply 1–20 consecutive recurring items.", "$prefix[items]")
                }
                val items = itemIndices.map { n ->
                    val key = "$prefix[items][$n]"
                    ScheduleItem(params.require("$key[price]"), BillingOps.quantity(params.opt("$key[quantity]"), "$key[quantity]", 1) ?: 1)
                }
                val prices = items.map { store.requirePrice(it.price) }
                BillingOps.requireCompatiblePrices(prices, "$prefix[items]")
                if (prices.first().currency != sub.currency) invalid("Schedule currency must match the subscription.", "$prefix[items]")
                val price = prices.first()
                val period = BillingOps.intervalSeconds(requireNotNull(price.recurringInterval), price.recurringIntervalCount)
                val iterations = params.long("$prefix[iterations]")
                val duration = params.opt("$prefix[duration][interval]")
                if (duration == null && params.has("$prefix[duration][interval_count]")) {
                    invalid("A duration count requires its interval.", "$prefix[duration][interval]")
                }
                val explicitEnd = params.long("$prefix[end_date]")
                if (listOfNotNull(iterations, duration, explicitEnd).size > 1) invalid("Use only one phase duration.", prefix)
                if (iterations != null && iterations !in 1..1200) invalid("Use 1–1200 iterations.", "$prefix[iterations]")
                val length = if (duration != null) {
                    if (duration !in setOf("day", "week", "month", "year")) invalid("Invalid duration interval.", "$prefix[duration][interval]")
                    val count = params.long("$prefix[duration][interval_count]") ?: 1
                    if (count !in 1..1200) invalid("Use 1–1200 intervals.", "$prefix[duration][interval_count]")
                    BillingOps.intervalSeconds(duration, count.toInt())
                } else iterations?.let { checked(prefix) { Math.multiplyExact(period, it) } }
                val until = explicitEnd ?: length?.let { checked(prefix) { Math.addExact(start, it) } }
                    ?: if (i == 0) previous.endDate else throw StripeException.missingParam("$prefix[end_date]")
                val anchor = params.opt("$prefix[billing_cycle_anchor]") ?: "automatic"
                if (anchor !in setOf("automatic", "phase_start")) invalid("Invalid billing cycle anchor.", "$prefix[billing_cycle_anchor]")
                val proration = params.opt("$prefix[proration_behavior]") ?: "create_prorations"
                if (proration !in prorations) invalid("Invalid proration behavior.", "$prefix[proration_behavior]")
                if (until > Long.MAX_VALUE / 1000) invalid("Phase timestamp is out of range.", "$prefix[end_date]")
                if (until <= maxOf(start, store.now())) invalid("Phase end must follow its start and current time.", "$prefix[end_date]")
                val basis = if (i == 0) sub.currentPeriodStart else start
                if ((until - basis) % period != 0L) invalid("Only complete recurring billing periods are supported.", "$prefix[end_date]")
                proposed += SchedulePhase(start, until, items, sub.currency, params.subMap("$prefix[metadata]"), proration, anchor)
            }
            if (proposed.first().items != sub.items.map { ScheduleItem(it.priceId, it.quantity) }) {
                invalid("Active phase items must stay unchanged; configure future phases or release the schedule first.", "phases[0][items]")
            }
            schedule.phases.take(schedule.currentPhase) + proposed
        }
        // Validation is complete before metadata, phases or the underlying subscription change.
        schedule.endBehavior = end
        schedule.phases = phases
        patch(schedule.metadata, params, "metadata")
        if (indices.isNotEmpty()) patch(sub.metadata, params, "phases[0][metadata]")
        store.recordEvent("subscription_schedule.updated", schedule.toApiJson())
        return schedule
    }

    fun release(store: DataStore, schedule: SubscriptionSchedule, params: StripeParams): SubscriptionSchedule {
        active(schedule)
        params.keys().firstOrNull { it != "preserve_cancel_date" && !it.startsWith("expand[") }?.let { invalid("Unsupported release parameter.", it) }
        val preserve = params.opt("preserve_cancel_date") ?: "false"
        if (preserve !in setOf("true", "false", "1", "0")) invalid("Invalid boolean.", "preserve_cancel_date")
        val sub = store.requireSubscription(requireNotNull(schedule.subscription))
        schedule.status = "released"
        schedule.releasedAt = store.now()
        schedule.releasedSubscription = sub.id
        schedule.subscription = null
        sub.schedule = null
        if (preserve in setOf("false", "0")) sub.cancelAtPeriodEnd = false
        store.recordEvent("subscription_schedule.released", schedule.toApiJson())
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
        return schedule
    }

    fun cancel(store: DataStore, schedule: SubscriptionSchedule, params: StripeParams): SubscriptionSchedule {
        active(schedule)
        params.keys().firstOrNull { it !in setOf("invoice_now", "prorate") && !it.startsWith("expand[") }?.let { invalid("Unsupported cancellation parameter.", it) }
        for (key in listOf("invoice_now", "prorate")) {
            params.opt(key)?.let { if (it !in setOf("true", "false", "1", "0")) invalid("Invalid boolean.", key) }
        }
        if (params.opt("prorate") in setOf("true", "1")) {
            invalid("Prorated schedule cancellation is not supported; release and reconcile explicitly.", "prorate")
        }
        val sub = store.requireSubscription(requireNotNull(schedule.subscription))
        if (sub.pendingProrations.isNotEmpty()) invalid("Release and reconcile pending invoice items before cancellation.", "invoice_now")
        BillingOps.cancelSubscription(store, sub, false)
        return schedule
    }

    /** Called by the shared scheduler before billing the next period at the same timestamp. */
    fun advance(store: DataStore, schedule: SubscriptionSchedule) {
        if (schedule.status != "active") return
        val sub = store.requireSubscription(requireNotNull(schedule.subscription))
        if (schedule.currentPhase + 1 == schedule.phases.size) {
            schedule.status = "completed"
            schedule.completedAt = store.now()
            sub.schedule = null
            store.recordEvent("subscription_schedule.completed", schedule.toApiJson())
            if (schedule.endBehavior == "cancel") BillingOps.cancelSubscription(store, sub, false)
            return
        }
        schedule.currentPhase += 1
        val phase = schedule.phases[schedule.currentPhase]
        val oldPrice = store.requirePrice(sub.items.first().priceId)
        phase.items.forEachIndexed { i, item ->
            val existing = sub.items.getOrNull(i)
            if (existing == null) sub.items += SubscriptionItem(store.newId("si"), store.now(), item.price, item.quantity, sub.id)
            else { existing.priceId = item.price; existing.quantity = item.quantity }
        }
        while (sub.items.size > phase.items.size) sub.items.removeAt(sub.items.lastIndex)
        phase.metadata.forEach { (k, v) -> if (v.isEmpty()) sub.metadata.remove(k) else sub.metadata[k] = v }
        val nextPrice = store.requirePrice(phase.items.first().price)
        if (phase.billingCycleAnchor == "phase_start" ||
            oldPrice.recurringInterval != nextPrice.recurringInterval || oldPrice.recurringIntervalCount != nextPrice.recurringIntervalCount
        ) sub.billingCycleAnchor = phase.startDate
        store.recordEvent("subscription_schedule.updated", schedule.toApiJson())
        store.recordEvent("customer.subscription.updated", store.subscriptionJson(sub))
    }

    fun subscriptionCancelled(store: DataStore, sub: Subscription) {
        val schedule = sub.schedule?.let(store.subscriptionSchedules::get) ?: return
        if (schedule.status != "active") return
        schedule.status = "canceled"
        schedule.canceledAt = store.now()
        sub.schedule = null
        store.recordEvent("subscription_schedule.canceled", schedule.toApiJson())
    }

    private fun patch(target: MutableMap<String, String>, params: StripeParams, key: String) {
        if (params.opt(key) == "") target.clear()
        params.subMap(key).forEach { (k, v) -> if (v.isEmpty()) target.remove(k) else target[k] = v }
    }
    private fun active(schedule: SubscriptionSchedule) {
        if (schedule.status != "active") invalid("Only an active schedule can be changed.", "subscription_schedule")
    }
    private fun checked(param: String, operation: () -> Long): Long = try { operation() } catch (_: ArithmeticException) {
        invalid("Phase duration is out of range.", param)
    }
    private fun invalid(message: String, param: String): Nothing = throw StripeException.invalidRequest(message, param = param)
}
