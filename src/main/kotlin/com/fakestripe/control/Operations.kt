package com.fakestripe.control

/**
 * The simulator's documented operation identifiers (listed in docs/AGENT-GUIDE.md).
 *
 * Faults match on these (`{"match": {"operation": "refund.create"}}`) and the journal records
 * them. Each identifier names one Stripe API operation (or one hosted-page action), so a fault on
 * `refund.create` hits both `POST /v1/refunds` and the legacy `POST /v1/charges/{id}/refunds`.
 */
object Operations {
    data class Route(val method: String, val pattern: String, val operation: String)

    val routes: List<Route> = listOf(
        Route("GET", "/", "service.info"),

        Route("POST", "/v1/customers", "customer.create"),
        Route("GET", "/v1/customers", "customer.list"),
        Route("GET", "/v1/customers/*", "customer.retrieve"),
        Route("POST", "/v1/customers/*", "customer.update"),
        Route("DELETE", "/v1/customers/*", "customer.delete"),
        Route("GET", "/v1/customers/*/payment_methods", "customer.list_payment_methods"),

        Route("POST", "/v1/payment_methods", "payment_method.create"),
        Route("GET", "/v1/payment_methods", "payment_method.list"),
        Route("GET", "/v1/payment_methods/*", "payment_method.retrieve"),
        Route("POST", "/v1/payment_methods/*", "payment_method.update"),
        Route("POST", "/v1/payment_methods/*/attach", "payment_method.attach"),
        Route("POST", "/v1/payment_methods/*/detach", "payment_method.detach"),

        Route("POST", "/v1/payment_intents", "payment_intent.create"),
        Route("GET", "/v1/payment_intents", "payment_intent.list"),
        Route("GET", "/v1/payment_intents/*", "payment_intent.retrieve"),
        Route("POST", "/v1/payment_intents/*", "payment_intent.update"),
        Route("POST", "/v1/payment_intents/*/confirm", "payment_intent.confirm"),
        Route("POST", "/v1/payment_intents/*/capture", "payment_intent.capture"),
        Route("POST", "/v1/payment_intents/*/cancel", "payment_intent.cancel"),

        Route("GET", "/v1/charges", "charge.list"),
        Route("GET", "/v1/charges/*", "charge.retrieve"),
        Route("POST", "/v1/charges/*", "charge.update"),

        Route("POST", "/v1/refunds", "refund.create"),
        Route("POST", "/v1/charges/*/refunds", "refund.create"),
        Route("GET", "/v1/refunds", "refund.list"),
        Route("GET", "/v1/refunds/*", "refund.retrieve"),
        Route("POST", "/v1/refunds/*", "refund.update"),

        Route("POST", "/v1/products", "product.create"),
        Route("GET", "/v1/products", "product.list"),
        Route("GET", "/v1/products/*", "product.retrieve"),
        Route("POST", "/v1/products/*", "product.update"),
        Route("DELETE", "/v1/products/*", "product.delete"),

        Route("POST", "/v1/prices", "price.create"),
        Route("GET", "/v1/prices", "price.list"),
        Route("GET", "/v1/prices/*", "price.retrieve"),
        Route("POST", "/v1/prices/*", "price.update"),

        Route("POST", "/v1/subscription_schedules", "subscription_schedule.create"),
        Route("GET", "/v1/subscription_schedules", "subscription_schedule.list"),
        Route("GET", "/v1/subscription_schedules/*", "subscription_schedule.retrieve"),
        Route("POST", "/v1/subscription_schedules/*", "subscription_schedule.update"),
        Route("POST", "/v1/subscription_schedules/*/release", "subscription_schedule.release"),
        Route("POST", "/v1/subscription_schedules/*/cancel", "subscription_schedule.cancel"),
        Route("POST", "/v1/subscriptions", "subscription.create"),
        Route("GET", "/v1/subscriptions", "subscription.list"),
        Route("GET", "/v1/subscriptions/*", "subscription.retrieve"),
        Route("POST", "/v1/subscriptions/*", "subscription.update"),
        Route("DELETE", "/v1/subscriptions/*", "subscription.cancel"),

        Route("GET", "/v1/subscription_items", "subscription_item.list"),
        Route("GET", "/v1/subscription_items/*", "subscription_item.retrieve"),
        Route("POST", "/v1/subscription_items/*", "subscription_item.update"),

        Route("GET", "/v1/invoices", "invoice.list"),
        Route("GET", "/v1/invoices/*", "invoice.retrieve"),
        Route("POST", "/v1/invoices/*", "invoice.update"),
        Route("POST", "/v1/invoices/*/pay", "invoice.pay"),
        Route("POST", "/v1/invoices/*/void", "invoice.void"),

        Route("GET", "/v1/invoice_payments", "invoice_payment.list"),
        Route("GET", "/v1/invoice_payments/*", "invoice_payment.retrieve"),

        Route("GET", "/v1/account", "account.retrieve"),

        Route("POST", "/v1/checkout/sessions", "checkout.session.create"),
        Route("GET", "/v1/checkout/sessions", "checkout.session.list"),
        Route("GET", "/v1/checkout/sessions/*", "checkout.session.retrieve"),
        Route("POST", "/v1/checkout/sessions/*/expire", "checkout.session.expire"),

        Route("POST", "/v1/billing_portal/sessions", "billing_portal.session.create"),

        Route("GET", "/v1/events", "event.list"),
        Route("GET", "/v1/events/*", "event.retrieve"),

        // Hosted pages (the browser half of Checkout and the customer portal).
        Route("GET", "/checkout/*", "checkout.page.view"),
        Route("POST", "/checkout/*/pay", "checkout.page.pay"),
        Route("POST", "/checkout/*/cancel", "checkout.page.cancel"),
        Route("GET", "/checkout/*/authenticate", "checkout.page.authenticate_view"),
        Route("POST", "/checkout/*/authenticate", "checkout.page.authenticate"),
        Route("GET", "/billing_portal/*", "billing_portal.page.view"),
        Route("POST", "/billing_portal/*/subscriptions/*/*", "billing_portal.page.action"),
    )

    /** Every documented identifier, for validating fault matches. */
    val identifiers: Set<String> = routes.map { it.operation }.toSortedSet()

    /** The operation a request performs, or null for a path the simulator does not serve. */
    fun resolve(method: String, path: String): String? =
        routes.firstOrNull { it.method.equals(method, ignoreCase = true) && PathPattern.matches(it.pattern, path) }
            ?.operation
}

/**
 * Path patterns from section 5 of the protocol: matched segment by segment, `*` matching exactly
 * one segment and a trailing `**` any remainder (including none).
 */
object PathPattern {
    fun segments(path: String): List<String> = path.trim().split('/').filter { it.isNotEmpty() }

    fun isValid(pattern: String): Boolean {
        if (!pattern.startsWith("/")) return false
        val parts = segments(pattern)
        return parts.withIndex().all { (i, part) -> part != "**" || i == parts.lastIndex }
    }

    fun matches(pattern: String, path: String): Boolean {
        val want = segments(pattern)
        val have = segments(path)
        for ((i, part) in want.withIndex()) {
            if (part == "**" && i == want.lastIndex) return true
            if (i >= have.size) return false
            if (part != "*" && part != have[i]) return false
        }
        return want.size == have.size
    }
}
