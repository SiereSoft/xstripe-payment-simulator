package com.fakestripe.store

import kotlinx.serialization.Serializable

/**
 * Controller-owned deterministic fault plan attached to a seeded scenario.
 *
 * Actor routes may consume a matching plan, but actors cannot configure or reset it. Keeping the
 * counter in the persisted world makes a lost-response episode reproducible and auditable across
 * simulator restarts.
 */
@Serializable
data class FaultInjectionState(
    val id: String,
    val operation: String,
    val phase: String,
    val responseStatus: Int,
    val responseCode: String,
    var remaining: Int,
    var injectedCount: Int = 0,
)
