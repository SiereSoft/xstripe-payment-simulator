package com.fakestripe.util

import com.fakestripe.BuildInfo

/**
 * Stripe API versions as a client asks for them with the `Stripe-Version` header.
 *
 * Without the header a request is answered in the account's default version, which for the
 * simulator is [BuildInfo.STRIPE_API_VERSION] (2024-06-20). A request for `2025-03-31.basil` or any
 * later version is answered in the shapes basil introduced (see docs/COMPATIBILITY.md):
 *
 * - Subscriptions: `current_period_start` / `current_period_end` move from the subscription to each
 *   subscription item.
 * - Invoices: `subscription`, `payment_intent` and `paid` give way to `parent.subscription_details`
 *   and the `payments` list (InvoicePayments, included with `expand[]=payments`); invoice lines name
 *   their price in `pricing.price_details` and their subscription item in `parent`.
 * - PaymentIntents: no `invoice` field.
 */
object ApiVersion {
    /** The first basil release. Every later version keeps these shapes. */
    const val BASIL = "2025-03-31"

    private val FORMAT = Regex("^\\d{4}-\\d{2}-\\d{2}(\\.[a-z]+)?$")

    /** A well-formed version string (`2024-06-20`, `2025-06-30.basil`); anything else is ignored. */
    fun isValid(version: String?): Boolean = version != null && FORMAT.matches(version)

    /** True for basil and every later version. */
    fun isBasil(version: String?): Boolean = isValid(version) && version!!.substring(0, 10) >= BASIL

    /** The version a request is answered in: the one it asked for, or the account default. */
    fun effective(requested: String?): String =
        requested?.trim()?.takeIf { isValid(it) } ?: BuildInfo.STRIPE_API_VERSION
}
