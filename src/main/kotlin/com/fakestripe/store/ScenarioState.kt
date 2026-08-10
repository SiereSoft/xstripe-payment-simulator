package com.fakestripe.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Controller-only metadata that makes a seeded scenario verifiable and renderable. */
@Serializable
data class ScenarioState(
    val id: String,
    val instructionContext: JsonObject,
    val verifierContext: JsonObject,
)
