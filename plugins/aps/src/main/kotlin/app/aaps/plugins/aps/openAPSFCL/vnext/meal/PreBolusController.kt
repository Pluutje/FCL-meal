package app.aaps.plugins.aps.openAPSFCL.vnext.meal

import app.aaps.core.interfaces.meal.MealIntentType
import org.joda.time.DateTime
import org.joda.time.Minutes

/**
 * Beheert een expliciet ingestelde pre-bolus:
 * - wordt ge-armed bij MealIntent
 * - blijft actief tot validUntil of remainingU == 0
 * - levert in chunks (maxSMB)
 *
 * GEEN BG / IOB / safety kennis.
 * Dat blijft volledig in FCLvNext.
 */
data class PreBolusSnapshot(
    val mealType: MealIntentType,
    val totalU: Double,
    val deliveredU: Double,
    val remainingU: Double,
    val armedAt: DateTime,
    val validUntil: DateTime,
    val minutesSinceArmed: Int,
    val minutesRemaining: Int
)


class PreBolusController {

    // ===============================
    // Interne state
    // ===============================
    private data class PreBolusState(
        var active: Boolean = false,
        var mealType: MealIntentType? = null,

        var totalU: Double = 0.0,
        var remainingU: Double = 0.0,

        var armedAt: DateTime? = null,      // 👈 TOEVOEGEN
        var lastFireAt: DateTime? = null,

        // absolute expiry (leidend)
        var validUntil: DateTime? = null
    )


    private val state = PreBolusState()

    // ===============================
    // Arm / reset
    // ===============================

    /**
     * Arm met absolute geldigheid (validUntil).
     * TTL wordt upstream bepaald (UI) en in repository al vertaald naar validUntil.
     */
    fun arm(
        type: MealIntentType,
        preBolusU: Double,
        validUntil: DateTime,
        now: DateTime
    ) {
        if (preBolusU <= 0.0) return

        state.active = true
        state.mealType = type
        state.totalU = preBolusU
        state.remainingU = preBolusU
        state.armedAt = now              // 👈 TOEVOEGEN
        state.validUntil = validUntil
        state.lastFireAt = null

    }

    fun reset() {
        state.active = false
        state.mealType = null
        state.totalU = 0.0
        state.remainingU = 0.0
        state.armedAt = null        // 👈 TOEVOEGEN
        state.validUntil = null
        state.lastFireAt = null
    }

    // ===============================
    // Status / geldigheid
    // ===============================

    fun isExpired(now: DateTime): Boolean {
        val until = state.validUntil ?: return true
        return now.isAfter(until)
    }

    fun isActive(now: DateTime): Boolean =
        state.active &&
            state.remainingU > 0.0 &&
            !isExpired(now)

    fun remainingU(): Double = state.remainingU

    fun mealType(): MealIntentType? = state.mealType

    fun validUntil(): DateTime? = state.validUntil

    // ===============================
    // Trigger & chunking
    // ===============================

    /**
     * FCLvNext bepaalt of er een stijging is.
     * Deze controller beslist alleen of hij
     * dan MAG reageren.
     */
    fun shouldTrigger(
        riseDetected: Boolean,
        now: DateTime
    ): Boolean {
        if (!isActive(now)) return false
        return riseDetected
    }

    /**
     * Berekent hoeveel we NU zouden willen geven,
     * begrensd door maxSMB.
     */
    fun computeChunk(maxSmb: Double): Double {
        if (state.remainingU <= 0.0) return 0.0
        return minOf(state.remainingU, maxSmb)
    }



    fun consumePlannedChunk(chunkU: Double, now: DateTime) {
        if (chunkU <= 0.0) return

        state.remainingU =
            (state.remainingU - chunkU).coerceAtLeast(0.0)

        state.lastFireAt = now


    }

    fun cleanupIfExpired(now: DateTime) {
        val until = state.validUntil ?: return
        if (now.isAfter(until)) {
            reset()
        }
    }



    // ===============================
    // Debug / logging
    // ===============================

    fun debugString(now: DateTime): String {
        if (!isActive(now)) return "PREBOLUS: inactive"

        val until = state.validUntil
        val minsLeft =
            if (until != null) Minutes.minutesBetween(now, until).minutes.coerceAtLeast(0)
            else 0

        return "PREBOLUS: ${state.mealType} " +
            "remaining=${"%.2f".format(state.remainingU)}U " +
            "validFor=${minsLeft}m"
    }

    fun snapshot(now: DateTime): PreBolusSnapshot? {
        if (!isActive(now)) return null

        val armedAt = state.armedAt ?: return null
        val validUntil = state.validUntil ?: return null

        val deliveredU = (state.totalU - state.remainingU).coerceAtLeast(0.0)

        val minutesSince =
            Minutes.minutesBetween(armedAt, now).minutes

        val minutesLeft =
            Minutes.minutesBetween(now, validUntil).minutes.coerceAtLeast(0)

        return PreBolusSnapshot(
            mealType = state.mealType ?: return null,
            totalU = state.totalU,
            deliveredU = deliveredU,
            remainingU = state.remainingU,
            armedAt = armedAt,
            validUntil = validUntil,
            minutesSinceArmed = minutesSince,
            minutesRemaining = minutesLeft
        )
    }

    fun uiSnapshot(now: DateTime): PreBolusSnapshot? {
        val validUntil = state.validUntil ?: return null
        if (now.isAfter(validUntil)) return null

        val armedAt = state.armedAt ?: return null

        val deliveredU = (state.totalU - state.remainingU).coerceAtLeast(0.0)

        val minutesSince =
            Minutes.minutesBetween(armedAt, now).minutes

        val minutesLeft =
            Minutes.minutesBetween(now, validUntil).minutes.coerceAtLeast(0)

        return PreBolusSnapshot(
            mealType = state.mealType ?: return null,
            totalU = state.totalU,
            deliveredU = deliveredU,
            remainingU = state.remainingU,
            armedAt = armedAt,
            validUntil = validUntil,
            minutesSinceArmed = minutesSince,
            minutesRemaining = minutesLeft
        )
    }


}
