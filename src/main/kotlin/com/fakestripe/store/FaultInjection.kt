package com.fakestripe.store

import kotlinx.serialization.Serializable

/**
 * The pre-protocol shape of a scenario-seeded fault plan.
 *
 * Since 0.2.0 scenario faults are ordinary named faults (`com.fakestripe.control.Fault`); this
 * shape is still exported as `fault_injection` in `/v1/admin/state` for verifiers written against
 * 0.1.x, and a state file from 0.1.x that carries one is migrated to a named fault on load.
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
