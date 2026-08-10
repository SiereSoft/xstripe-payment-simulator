package com.fakestripe.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ClockMode(val wireValue: String) {
    @SerialName("free")
    FREE("free"),

    @SerialName("manual")
    MANUAL("manual"),
    ;

    companion object {
        fun parse(value: String): ClockMode? = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        }

        fun defaultFor(scenario: String?): ClockMode =
            if (scenario == null) FREE else MANUAL
    }
}

/** Persisted clock configuration; free mode deliberately stores no wall-clock reading. */
@Serializable
data class SimulatorClockState(
    val mode: ClockMode = ClockMode.FREE,
    var manualTime: Long? = null,
)

/**
 * The sole source of runtime timestamps in a simulated world.
 *
 * Free mode follows the host clock for local compatibility. Manual mode stays
 * fixed until the privileged controller advances it, making episode mutations
 * exactly reproducible.
 */
class SimulatorClock(
    val state: SimulatorClockState,
    private val wallTimeSeconds: () -> Long = { systemTimeSeconds() },
) {
    init {
        require(state.mode != ClockMode.MANUAL || state.manualTime != null) {
            "A manual clock requires a current time."
        }
    }

    val mode: ClockMode get() = state.mode

    fun now(): Long = when (mode) {
        ClockMode.FREE -> wallTimeSeconds()
        ClockMode.MANUAL -> requireNotNull(state.manualTime)
    }

    fun advance(seconds: Long): Long {
        check(mode == ClockMode.MANUAL) { "Only a manual clock can be advanced." }
        val next = Math.addExact(now(), seconds)
        state.manualTime = next
        return next
    }

    companion object {
        private const val MANUAL_BASE = 1_798_761_600L // 2027-01-01T00:00:00Z
        private const val WINDOW_SECONDS = 365L * 86_400L

        fun systemTimeSeconds(): Long = System.currentTimeMillis() / 1000

        fun resetState(mode: ClockMode, seed: Long, scenario: String?): SimulatorClockState =
            when (mode) {
                ClockMode.FREE -> SimulatorClockState(mode = ClockMode.FREE)
                ClockMode.MANUAL -> SimulatorClockState(
                    mode = ClockMode.MANUAL,
                    manualTime = deterministicTime(seed, scenario),
                )
            }

        /** Stable across processes and platforms; deliberately does not use String.hashCode(). */
        fun deterministicTime(seed: Long, scenario: String?): Long {
            var hash = seed xor 0x5EED_C10CL
            (scenario ?: "default").forEach { char ->
                hash = hash * 1_099_511_628_211L xor char.code.toLong()
            }
            return MANUAL_BASE + Math.floorMod(hash, WINDOW_SECONDS)
        }
    }
}
