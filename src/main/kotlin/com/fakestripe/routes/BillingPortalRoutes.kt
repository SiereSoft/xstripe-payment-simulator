package com.fakestripe.routes

import com.fakestripe.billing.BillingOps
import com.fakestripe.hosted.date
import com.fakestripe.hosted.esc
import com.fakestripe.hosted.money
import com.fakestripe.hosted.page
import com.fakestripe.model.BillingPortalSession
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `/v1/billing_portal/sessions` plus the page it points at.
 *
 * Stripe's portal does a dozen things; the simulated one does the two that
 * integrations actually have code behind — cancel at period end, and undo that
 * cancellation — each emitting `customer.subscription.updated`.
 */
fun Route.billingPortalRoutes(sim: Simulator) {

    post("/v1/billing_portal/sessions") {
        val params = call.formParams()
        val baseUrl = call.publicBaseUrl()
        val json = sim.write { store ->
            val customerId = params.require("customer")
            store.requireCustomer(customerId)
            val id = store.newId("bps")
            val session = BillingPortalSession(
                id = id,
                created = store.now(),
                customer = customerId,
                returnUrl = params.opt("return_url"),
                url = "${baseUrl.trimEnd('/')}/billing_portal/$id",
            )
            store.portalSessions[id] = session
            session.toApiJson()
        }
        call.respondStripe(json)
    }

    // --- the hosted page (browser-facing, no API key) -----------------------

    get("/billing_portal/{id}") {
        val id = call.parameters["id"]!!
        val html = sim.read { store ->
            store.portalSessions[id]?.let { portalPage(store, it) }
        }
        // The page renders only current subscription state, and no-store stops the browser's
        // back/forward cache from replaying a stale cancel snapshot after a renew (or vice
        // versa) — the contradictory state a real hosted portal never shows.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (html == null) call.respondHtml(missingPage("billing portal session"), HttpStatusCode.NotFound)
        else call.respondHtml(html)
    }

    post("/billing_portal/{id}/subscriptions/{subId}/{action}") {
        val id = call.parameters["id"]!!
        val subId = call.parameters["subId"]!!
        val action = call.parameters["action"]!!
        val notice = sim.write { store ->
            val session = store.portalSessions[id] ?: return@write null
            val sub = store.subscriptions[subId]?.takeIf { it.customer == session.customer }
                ?: return@write "That subscription does not belong to this account."
            when (action) {
                // Cancelling in the portal never ends the period the customer paid for.
                "cancel" -> {
                    BillingOps.cancelSubscription(store, sub, atPeriodEnd = true)
                    "Your plan will end on ${date(sub.currentPeriodEnd)}."
                }
                "resume" -> {
                    BillingOps.resumeSubscription(store, sub)
                    "Your plan will renew on ${date(sub.currentPeriodEnd)}."
                }
                else -> "Unknown action."
            }
        }
        // Redirect to the bare portal URL — never a per-action ?notice=. A distinct URL per
        // action is exactly what lets Back walk into a stale, contradictory state; one URL that
        // always shows current state cannot. The button + "ends/renews on" line is the feedback.
        if (notice == null) call.respondHtml(missingPage("billing portal session"), HttpStatusCode.NotFound)
        else call.seeOther("/billing_portal/$id")
    }
}

private fun portalPage(store: DataStore, session: BillingPortalSession): String {
    val customer = store.customers[session.customer]
    val body = StringBuilder()

    body.append("<h1>Manage billing</h1>")
    body.append("<p class=\"muted\">${esc(customer?.email ?: session.customer)}</p>")

    val subs = store.subscriptions.values.filter { it.customer == session.customer }
        .sortedByDescending { it.created }

    body.append("<h2>Subscriptions</h2>")
    if (subs.isEmpty()) {
        body.append("<p class=\"muted\">No subscriptions on this account.</p>")
    }
    subs.forEach { sub ->
        val price = store.prices[sub.items.first().priceId]
        val name = price?.let { store.products[it.product]?.name } ?: sub.id
        val each = money(price?.unitAmount ?: 0, sub.currency)
        val interval = price?.recurringInterval?.let { " / $it" }.orEmpty()
        body.append("<div class=\"row\"><span><strong>${esc(name)}</strong><br>")
        body.append("<span class=\"muted\">${esc(each)}${esc(interval)} · ${esc(sub.status)}</span></span>")
        body.append("<span class=\"muted\">")
        body.append(
            when {
                BillingOps.isTerminal(sub) -> "ended ${esc(date(sub.endedAt ?: sub.currentPeriodEnd))}"
                sub.cancelAtPeriodEnd -> "ends ${esc(date(sub.currentPeriodEnd))}"
                else -> "renews ${esc(date(sub.currentPeriodEnd))}"
            },
        )
        body.append("</span></div>")

        if (!BillingOps.isTerminal(sub)) {
            val action = if (sub.cancelAtPeriodEnd) "resume" else "cancel"
            val label = if (sub.cancelAtPeriodEnd) "Renew plan" else "Cancel plan"
            val style = if (sub.cancelAtPeriodEnd) "secondary" else "danger"
            body.append(
                "<form method=\"post\" action=\"/billing_portal/${esc(session.id)}/subscriptions/${esc(sub.id)}/$action\">" +
                    "<button class=\"$style inline\">$label</button></form>",
            )
        }
    }

    val invoices = store.invoices.values.filter { it.customer == session.customer }
        .sortedByDescending { it.created }.take(5)
    if (invoices.isNotEmpty()) {
        body.append("<h2>Recent invoices</h2>")
        invoices.forEach { inv ->
            body.append(
                "<div class=\"row\"><span>${esc(inv.number)} <span class=\"muted\">${esc(date(inv.created))}</span></span>" +
                    "<span class=\"muted\">${esc(money(inv.total, inv.currency))} · ${esc(inv.status)}</span></div>",
            )
        }
    }

    if (session.returnUrl != null) {
        body.append("<form method=\"get\" action=\"${esc(session.returnUrl)}\"><button class=\"secondary\">Return to site</button></form>")
    }
    return page("Billing · fake-stripe", body.toString())
}
