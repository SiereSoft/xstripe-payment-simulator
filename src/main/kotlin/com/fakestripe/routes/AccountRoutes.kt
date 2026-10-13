package com.fakestripe.routes

import com.fakestripe.store.Simulator
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /v1/account`: the account the API key belongs to. Integrations call it to check that a
 * configured key is the operator's own account before trusting anything else it answers. The id is
 * `FAKE_STRIPE_ACCOUNT_ID` (default `acct_ssf_local`); the rest is a fully onboarded test account.
 */
fun Route.accountRoutes(sim: Simulator) {
    get("/v1/account") {
        val created = sim.read { it.seedCreatedAt() }
        call.respondStripe(buildJsonObject {
            put("id", sim.accountId)
            put("object", "account")
            put("business_profile", buildJsonObject {
                put("mcc", JsonNull)
                put("name", "Siere Payments Gym")
                put("product_description", JsonNull)
                put("support_email", JsonNull)
                put("support_phone", JsonNull)
                put("support_url", JsonNull)
                put("url", JsonNull)
            })
            put("business_type", JsonNull)
            put("capabilities", buildJsonObject {
                put("card_payments", "active")
                put("transfers", "active")
            })
            put("charges_enabled", true)
            put("controller", buildJsonObject { put("type", "account") })
            put("country", "US")
            put("created", created)
            put("default_currency", "usd")
            put("details_submitted", true)
            put("email", JsonNull)
            put("external_accounts", buildJsonObject {
                put("object", "list")
                put("data", JsonArray(emptyList()))
                put("has_more", false)
                put("total_count", 0)
                put("url", "/v1/accounts/${sim.accountId}/external_accounts")
            })
            put("metadata", buildJsonObject { })
            put("payouts_enabled", true)
            put("settings", buildJsonObject {
                put("dashboard", buildJsonObject {
                    put("display_name", "Siere Payments Gym")
                    put("timezone", "Etc/UTC")
                })
            })
            put("type", "standard")
        })
    }
}

/** The account's creation time: the oldest object in the world, or now in an empty one. */
private fun com.fakestripe.store.DataStore.seedCreatedAt(): Long =
    (customers.values.map { it.created } + products.values.map { it.created }).minOrNull() ?: now()
