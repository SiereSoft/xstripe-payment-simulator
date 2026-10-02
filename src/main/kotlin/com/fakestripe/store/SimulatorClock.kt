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

/**
 * Persisted clock configuration; free mode deliberately stores no wall-clock reading.
 *
 * Manual time is held as whole seconds ([manualTime], the field older snapshots carry) plus the
 * millisecond remainder ([manualMillis]), so the control protocol's millisecond times round-trip
 * exactly while Stripe objects keep their Unix-second timestamps.
 */
@Serializable
data class SimulatorClockState(
    val mode: ClockMode = ClockMode.FREE,
    var manualTime: Long? = null,
    var manualMillis: Int = 0,
) {
    /** Manual time in epoch milliseconds, or null in free mode. */
    val manualTimeMs: Long?
        get() = manualTime?.let { it * 1000 + manualMillis }

    fun setManualTimeMs(ms: Long) {
        manualTime = Math.floorDiv(ms, 1000L)
        manualMillis = Math.floorMod(ms, 1000L).toInt()
    }
}

/**
 * The sole source of runtime timestamps in a simulated world.
 *
 * Free mode follows the host clock for local compatibility. Manual mode stays fixed until the
 * privileged controller moves it, making episode mutations exactly reproducible. While the
 * scheduler applies an item that fell due at an earlier instant, [override] pins "now" to that
 * instant so the objects it creates carry the time the work was due.
 */
class SimulatorClock(
    val state: SimulatorClockState,
    private val wallTimeSeconds: () -> Long = SYSTEM_SECONDS,
) {
    init {
        require(state.mode != ClockMode.MANUAL || state.manualTime != null) {
            "A manual clock requires a current time."
        }
    }

    /** Transient "now" (epoch milliseconds) used while settling due work; never persisted. */
    @Volatile
    var override: Long? = null

    val mode: ClockMode get() = state.mode

    /** Current simulated time in epoch milliseconds. */
    fun nowMs(): Long = override ?: when (mode) {
        ClockMode.FREE -> wallTimeMs()
        ClockMode.MANUAL -> requireNotNull(state.manualTimeMs)
    }

    /** Current simulated time in Unix seconds (Stripe's timestamp unit). */
    fun now(): Long = Math.floorDiv(nowMs(), 1000L)

    /** The host clock as this world sees it, in epoch milliseconds. */
    fun wallTimeMs(): Long =
        if (wallTimeSeconds === SYSTEM_SECONDS) System.currentTimeMillis() else wallTimeSeconds() * 1000

    /** Move a manual clock to [ms] (epoch milliseconds). */
    fun setMs(ms: Long) {
        check(mode == ClockMode.MANUAL) { "Only a manual clock can be moved." }
        state.setManualTimeMs(ms)
    }

    companion object {
        private const val MANUAL_BASE = 1_798_761_600L // 2027-01-01T00:00:00Z
        private const val WINDOW_SECONDS = 365L * 86_400L

        /** The real host clock; identity-compared so millisecond precision is kept for it. */
        val SYSTEM_SECONDS: () -> Long = { System.currentTimeMillis() / 1000 }

        fun resetState(
            mode: ClockMode,
            seed: Long,
            scenario: String?,
            startMs: Long? = null,
        ): SimulatorClockState =
            when (mode) {
                ClockMode.FREE -> SimulatorClockState(mode = ClockMode.FREE)
                ClockMode.MANUAL -> SimulatorClockState(mode = ClockMode.MANUAL).apply {
                    setManualTimeMs(startMs ?: (deterministicTime(seed, scenario) * 1000))
                }
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
