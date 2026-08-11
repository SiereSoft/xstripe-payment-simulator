package com.fakestripe.util

import com.fakestripe.error.StripeException
import java.net.URI

/**
 * Validation for caller-supplied redirect targets (`success_url`, `cancel_url`,
 * `return_url`).
 *
 * These strings end up in two dangerous places: a `Location:` header on a 303, and
 * a `<form action="...">` on a hosted page. Without a scheme check, a caller could
 * pass `javascript:alert(1)` and get script execution on the checkout origin, or
 * any absolute URL and get an unconditional open redirect.
 *
 * Real Stripe requires these to be http(s) URLs, so rejecting anything else also
 * moves us *closer* to Stripe's behaviour rather than away from it.
 */
object RedirectUrl {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Returns [value] unchanged when it is a usable http(s) redirect target,
     * otherwise throws a Stripe-shaped 400.
     *
     * Relative URLs are accepted: they cannot carry a scheme, so they cannot
     * express `javascript:` and they cannot leave the origin.
     */
    fun validate(value: String, param: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw StripeException.invalidRequest("`$param` must not be blank.", param = param)
        }

        // Control characters would allow header injection on the 303 response.
        if (trimmed.any { it.isISOControl() }) {
            throw StripeException.invalidRequest(
                "`$param` must not contain control characters.", param = param,
            )
        }

        val scheme = runCatching { URI(trimmed).scheme }.getOrElse {
            throw StripeException.invalidRequest("`$param` is not a valid URL.", param = param)
        }

        // No scheme at all => relative URL => same origin, safe.
        if (scheme == null) return trimmed

        if (scheme.lowercase() !in ALLOWED_SCHEMES) {
            throw StripeException.invalidRequest(
                "`$param` must be an http or https URL (got scheme `$scheme`).", param = param,
            )
        }
        return trimmed
    }

    /** Same as [validate] but tolerates a null/absent value. */
    fun validateOptional(value: String?, param: String): String? =
        value?.let { validate(it, param) }
}
