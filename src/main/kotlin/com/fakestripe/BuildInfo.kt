package com.fakestripe

import java.util.Properties

/**
 * Build metadata generated from `build.gradle.kts` (the `generateVersion` task), so the
 * project version has exactly one source: health, the `Server` header, the webhook
 * `User-Agent` and the service info all read [VERSION].
 */
object BuildInfo {
    /** The simulator's name in `/v1/admin/health`. */
    const val SIMULATOR = "stripe"

    /** The Stripe API version whose wire shapes the simulator models. */
    const val STRIPE_API_VERSION = "2024-06-20"

    val VERSION: String = loadVersion()

    /** `fake-stripe/<version>`: the product token used in `Server` and `User-Agent`. */
    val PRODUCT: String get() = "fake-stripe/$VERSION"

    internal fun loadVersion(resource: String = "/fake-stripe-version.properties"): String {
        val stream = BuildInfo::class.java.getResourceAsStream(resource) ?: return "0.0.0-dev"
        return stream.use { input ->
            Properties().apply { load(input) }.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "0.0.0-dev"
        }
    }
}
