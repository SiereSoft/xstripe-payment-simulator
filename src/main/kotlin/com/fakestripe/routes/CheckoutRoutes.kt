package com.fakestripe.routes

import com.fakestripe.billing.CheckoutOps
import com.fakestripe.hosted.date
import com.fakestripe.hosted.esc
import com.fakestripe.hosted.money
import com.fakestripe.hosted.page
import com.fakestripe.model.CheckoutSession
import com.fakestripe.store.DataStore
import com.fakestripe.store.Simulator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `/v1/checkout/sessions` plus the page its `url` points at.
 *
 * The API half is unremarkable. The page is the point: an integration's next step
 * after creating a session is a browser navigating to `url`, so there has to be
 * something there that takes a card and — only if it charges — completes the session,
 * emits `checkout.session.completed`, and redirects to `success_url`.
 */
fun Route.checkoutRoutes(sim: Simulator) {

    post("/v1/checkout/sessions") {
        val params = call.formParams()
        val baseUrl = call.publicBaseUrl()
        val json = sim.write { store -> store.expand(CheckoutOps.createSession(store, params, baseUrl).toApiJson(), params) }
        call.respondStripe(json)
    }

    get("/v1/checkout/sessions/{id}") {
        val id = call.parameters["id"]!!
        val params = call.queryParams()
        val json = sim.read { store ->
            store.expand(CheckoutOps.expireIfStale(store, store.requireCheckoutSession(id)).toApiJson(), params)
        }
        call.respondStripe(json)
    }

    get("/v1/checkout/sessions") {
        val params = call.queryParams()
        val json = sim.read { store ->
            val customer = params.opt("customer")
            val subscription = params.opt("subscription")
            val all = store.checkoutSessions.values.filter {
                (customer == null || it.customer == customer) &&
                    (subscription == null || it.subscription == subscription)
            }
            store.paginated(all, "/v1/checkout/sessions", params, { it.id }, { it.created }, { it.toApiJson() })
        }
        call.respondStripe(json)
    }

    post("/v1/checkout/sessions/{id}/expire") {
        val id = call.parameters["id"]!!
        val json = sim.write { store ->
            val session = store.requireCheckoutSession(id)
            if (session.status == "open") {
                session.status = "expired"
                store.recordEvent("checkout.session.expired", session.toApiJson())
            }
            session.toApiJson()
        }
        call.respondStripe(json)
    }

    // --- the hosted page (browser-facing, no API key) -----------------------

    get("/checkout/{id}") {
        val id = call.parameters["id"]!!
        val error = call.request.queryParameters["error"]
        val html = sim.write { store ->
            val session = store.checkoutSessions[id] ?: return@write null
            checkoutPage(store, CheckoutOps.expireIfStale(store, session), error)
        }
        if (html == null) call.respondHtml(missingPage("checkout session"), HttpStatusCode.NotFound)
        else call.respondHtml(html)
    }

    post("/checkout/{id}/pay") {
        val id = call.parameters["id"]!!
        val card = call.formParams().opt("card_number").orEmpty()
        val result = sim.write { store ->
            val session = store.checkoutSessions[id] ?: return@write null
            CheckoutOps.pay(store, session, card)
        }
        respondPayResult(call, id, result)
    }

    // The simulated 3-D Secure challenge, like the test page Stripe shows for authentication cards.
    get("/checkout/{id}/authenticate") {
        val id = call.parameters["id"]!!
        val html = sim.read { store ->
            val session = store.checkoutSessions[id] ?: return@read null
            authenticationPage(store, session)
        }
        if (html == null) call.respondHtml(missingPage("checkout session"), HttpStatusCode.NotFound)
        else call.respondHtml(html)
    }

    post("/checkout/{id}/authenticate") {
        val id = call.parameters["id"]!!
        val outcome = call.formParams().opt("outcome").orEmpty()
        val result = sim.write { store ->
            val session = store.checkoutSessions[id] ?: return@write null
            CheckoutOps.authenticate(store, session, outcome)
        }
        respondPayResult(call, id, result)
    }

    post("/checkout/{id}/cancel") {
        val id = call.parameters["id"]!!
        val session = sim.read { store -> store.checkoutSessions[id] }
        when {
            session == null -> call.respondHtml(missingPage("checkout session"), HttpStatusCode.NotFound)
            // Cancelling grants nothing and creates nothing — it is only a redirect.
            session.cancelUrl != null -> call.seeOther(session.cancelUrl!!)
            else -> call.respondHtml(
                page("Checkout cancelled", "<h1>Checkout cancelled</h1><p class=\"muted\">Nothing was charged and nothing was created.</p>"),
            )
        }
    }
}

private suspend fun respondPayResult(call: ApplicationCall, id: String, result: CheckoutOps.PayResult?) {
    when (result) {
        null -> call.respondHtml(missingPage("checkout session"), HttpStatusCode.NotFound)
        is CheckoutOps.PayResult.Redirect -> call.seeOther(result.url)
        is CheckoutOps.PayResult.Authenticate -> call.seeOther(result.url)
        is CheckoutOps.PayResult.Failed ->
            call.seeOther("/checkout/$id?error=" + java.net.URLEncoder.encode(result.message, "UTF-8"))
    }
}

private fun authenticationPage(store: DataStore, session: CheckoutSession): String {
    val pm = session.pendingAuthentication?.let { store.paymentMethods[it] }
        ?: return page(
            "Authentication · fake-stripe",
            "<h1>Nothing to authenticate</h1><p class=\"muted\">There is no payment waiting for 3-D Secure.</p>" +
                "<form method=\"get\" action=\"/checkout/${esc(session.id)}\"><button class=\"secondary\">Back to checkout</button></form>",
        )
    val body = StringBuilder()
    body.append("<h1>3D Secure test payment</h1>")
    body.append("<p class=\"muted\">${esc(pm.brand)} ending in ${esc(pm.last4)}</p>")
    body.append(
        "<p>This is the simulator's stand-in for the bank's authentication page. Choose how the " +
            "customer's bank answers.</p>",
    )
    body.append(
        "<form method=\"post\" action=\"/checkout/${esc(session.id)}/authenticate\">" +
            "<input type=\"hidden\" name=\"outcome\" value=\"complete\">" +
            "<button class=\"primary\">Complete authentication</button></form>",
    )
    body.append(
        "<form method=\"post\" action=\"/checkout/${esc(session.id)}/authenticate\">" +
            "<input type=\"hidden\" name=\"outcome\" value=\"fail\">" +
            "<button class=\"danger\">Fail authentication</button></form>",
    )
    return page("Authentication · fake-stripe", body.toString())
}

private fun checkoutPage(store: DataStore, session: CheckoutSession, error: String?): String {
    val heading = if (session.mode == "subscription") "Subscribe" else "Pay"
    val body = StringBuilder()

    if (error != null) body.append("<div class=\"error\">${esc(error)}</div>")

    body.append("<h1>$heading</h1>")
    body.append("<p class=\"muted\">${esc(session.customerEmail ?: "No email supplied")}</p>")
    body.append("<div class=\"amount\">${esc(money(session.amountTotal, session.currency))}</div>")
    if (session.hasTrial) {
        val recurring = session.lines.sumOf { (store.prices[it.priceId]?.unitAmount ?: 0) * it.quantity }
        val interval = session.lines.firstOrNull()?.let { store.prices[it.priceId]?.recurringInterval }.orEmpty()
        val trial = session.trialPeriodDays?.let { "$it-day free trial" }
            ?: session.trialEnd?.let { "Free trial until ${date(it)}" }
        body.append(
            "<p class=\"muted\">${esc(trial)}, then ${esc(money(recurring, session.currency))} / ${esc(interval)}</p>",
        )
    }

    session.lines.forEach { line ->
        val price = store.prices[line.priceId]
        val name = price?.let { store.products[it.product]?.name } ?: line.priceId
        val each = money(price?.unitAmount ?: 0, session.currency)
        val interval = price?.recurringInterval?.let { " / $it" } ?: ""
        body.append(
            "<div class=\"row\"><span>${esc(name)} × ${line.quantity}</span>" +
                "<span class=\"muted\">${esc(each)}${esc(interval)}</span></div>",
        )
    }

    when (session.status) {
        "complete" -> {
            body.append("<p class=\"muted\">This session is already complete — it will not charge again.</p>")
            body.append(
                "<form method=\"get\" action=\"${esc(CheckoutOps.resolveSuccessUrl(session))}\">" +
                    "<button class=\"secondary\">Continue</button></form>",
            )
        }
        "expired" -> body.append(
            "<p class=\"muted\">This session expired on ${esc(date(session.expiresAt))}. Create a new one.</p>",
        )
        else -> {
            body.append("<form method=\"post\" action=\"/checkout/${esc(session.id)}/pay\">")
            body.append("<label for=\"card_number\">Card number</label>")
            body.append(
                "<input id=\"card_number\" name=\"card_number\" value=\"4242424242424242\" " +
                    "autocomplete=\"off\" inputmode=\"numeric\">",
            )
            val action = if (session.hasTrial) "Start trial" else "Pay ${money(session.amountTotal, session.currency)}"
            body.append("<button class=\"primary\">${esc(action)}</button>")
            body.append("</form>")
            body.append(
                "<form method=\"post\" action=\"/checkout/${esc(session.id)}/cancel\">" +
                    "<button class=\"secondary\">Cancel</button></form>",
            )
            body.append(
                "<div class=\"note\"><strong>Test cards</strong><br>" +
                    "<code>4242424242424242</code> succeeds · " +
                    "<code>4000000000000002</code> declines · " +
                    "<code>4000000000009995</code> insufficient funds · " +
                    "<code>4000002500003155</code> asks for 3-D Secure<br>" +
                    "Paying emits <code>checkout.session.completed</code> before the redirect — " +
                    "that event, not the redirect, is what grants access.</div>",
            )
        }
    }
    return page("Checkout · fake-stripe", body.toString())
}

internal fun missingPage(what: String): String =
    page("Not found", "<h1>No such $what</h1><p class=\"muted\">This link is wrong, or the world was reset.</p>")
