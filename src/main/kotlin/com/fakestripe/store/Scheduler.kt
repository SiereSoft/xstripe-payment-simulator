package com.fakestripe.store

import com.fakestripe.billing.BillingOps
import com.fakestripe.billing.ScheduleOps

/**
 * Work that falls due on the simulated clock: checkout-session expiry, trial reminders and trial
 * ends, subscription renewals (with their invoices, payment attempts and dunning) and webhook
 * attempts. Items are derived from the world itself, so nothing extra is persisted and a snapshot
 * restore brings its schedule with it.
 *
 * Items are ordered by due time, then by [Item.rank], then by creation order, which makes one long
 * clock move settle exactly like many short ones.
 */
object Scheduler {
    const val DAY_SECONDS = 86_400L

    /** `customer.subscription.trial_will_end` fires this long before a trial ends. */
    const val TRIAL_WILL_END_LEAD_SECONDS = 3 * DAY_SECONDS

    sealed class Item(val atMs: Long, val rank: Int, val order: Int, val key: String) {
        class ExpireSession(atMs: Long, order: Int, val sessionId: String) :
            Item(atMs, 0, order, "expire:$sessionId")

        class TrialWillEnd(atMs: Long, order: Int, val subscriptionId: String) :
            Item(atMs, 1, order, "trial_will_end:$subscriptionId")

        class TrialEnd(atMs: Long, order: Int, val subscriptionId: String) :
            Item(atMs, 2, order, "trial_end:$subscriptionId")

        class ScheduleTransition(atMs: Long, order: Int, val scheduleId: String) :
            Item(atMs, 2, order, "schedule:$scheduleId")

        class Renew(atMs: Long, order: Int, val subscriptionId: String) :
            Item(atMs, 3, order, "renew:$subscriptionId")

        class Webhook(atMs: Long, order: Int, val jobId: String) :
            Item(atMs, 4, order, "webhook:$jobId")
    }

    private val ordering = compareBy<Item>({ it.atMs }, { it.rank }, { it.order })

    /** The earliest world item (not webhooks) due at or before [limitMs]. */
    fun nextWorld(store: DataStore, limitMs: Long, skip: Set<String> = emptySet()): Item? =
        worldItems(store).filter { it.atMs <= limitMs && it.key !in skip }.minWithOrNull(ordering)

    /** The earliest open webhook job due at or before [limitMs]. */
    fun nextWebhook(store: DataStore, limitMs: Long, skip: Set<String> = emptySet()): Item.Webhook? =
        webhookItems(store).filter { it.atMs <= limitMs && it.key !in skip }.minWithOrNull(ordering)
            as Item.Webhook?

    /** The earliest item of either kind due at or before [limitMs]. */
    fun next(store: DataStore, limitMs: Long, includeWebhooks: Boolean, skip: Set<String> = emptySet()): Item? {
        val world = nextWorld(store, limitMs, skip)
        val hook = if (includeWebhooks) nextWebhook(store, limitMs, skip) else null
        return listOfNotNull(world, hook).minWithOrNull(ordering)
    }

    private fun worldItems(store: DataStore): Sequence<Item> = sequence {
        store.checkoutSessions.values.forEachIndexed { i, session ->
            if (session.status == "open") yield(Item.ExpireSession((session.expiresAt + 1) * 1000, i, session.id))
        }
        store.subscriptionSchedules.values.forEachIndexed { i, schedule ->
            if (schedule.status == "active") yield(Item.ScheduleTransition(schedule.phases[schedule.currentPhase].endDate * 1000, i, schedule.id))
        }
        store.subscriptions.values.forEachIndexed { i, sub ->
            when {
                sub.status == "trialing" && sub.trialEnd != null -> {
                    val trialEnd = sub.trialEnd!!
                    if (!sub.trialWillEndSent) {
                        val start = sub.trialStart ?: sub.created
                        val at = maxOf(trialEnd - TRIAL_WILL_END_LEAD_SECONDS, start)
                        yield(Item.TrialWillEnd(at * 1000, i, sub.id))
                    }
                    yield(Item.TrialEnd(trialEnd * 1000, i, sub.id))
                }
                sub.status == "active" || sub.status == "past_due" ->
                    yield(Item.Renew(sub.currentPeriodEnd * 1000, i, sub.id))
            }
        }
    }

    private fun webhookItems(store: DataStore): Sequence<Item> = sequence {
        store.outbox.values.forEachIndexed { i, job ->
            val at = job.nextAttemptAtMs
            if (job.open && at != null) yield(Item.Webhook(at, i, job.id))
        }
    }

    /** What a settled world item did, for the journal. */
    data class Applied(val operation: String, val refs: Map<String, String>)

    /** Apply one world item at the store's current (overridden) time. */
    fun apply(store: DataStore, item: Item): Applied = when (item) {
        is Item.ExpireSession -> {
            val session = store.requireCheckoutSession(item.sessionId)
            session.status = "expired"
            store.recordEvent("checkout.session.expired", session.toApiJson())
            Applied("checkout.session.expire", mapOf("checkout_session" to session.id))
        }
        is Item.TrialWillEnd -> {
            val sub = store.requireSubscription(item.subscriptionId)
            sub.trialWillEndSent = true
            store.recordEvent("customer.subscription.trial_will_end", store.subscriptionJson(sub))
            Applied("subscription.trial_will_end", mapOf("subscription" to sub.id))
        }
        is Item.TrialEnd -> {
            val sub = BillingOps.endTrial(store, store.requireSubscription(item.subscriptionId))
            Applied("subscription.trial_end", refsOf(sub.id, sub.latestInvoice))
        }
        is Item.ScheduleTransition -> {
            val schedule = store.requireSchedule(item.scheduleId)
            ScheduleOps.advance(store, schedule)
            Applied("subscription_schedule.transition", mapOf("subscription_schedule" to schedule.id))
        }
        is Item.Renew -> {
            val sub = BillingOps.renewSubscription(store, store.requireSubscription(item.subscriptionId))
            Applied("subscription.renew", refsOf(sub.id, sub.latestInvoice))
        }
        is Item.Webhook -> error("webhook attempts are delivered by the simulator, not applied")
    }

    private fun refsOf(subscriptionId: String, invoiceId: String?): Map<String, String> =
        buildMap {
            put("subscription", subscriptionId)
            if (invoiceId != null) put("invoice", invoiceId)
        }

    /** Finished jobs kept in the outbox; older delivered/exhausted ones are pruned first. */
    const val MAX_FINISHED_JOBS = 10_000

    fun pruneOutbox(store: DataStore) {
        val finished = store.outbox.values.filter { !it.open }
        if (finished.size <= MAX_FINISHED_JOBS) return
        finished.take(finished.size - MAX_FINISHED_JOBS).forEach { store.outbox.remove(it.id) }
    }
}
