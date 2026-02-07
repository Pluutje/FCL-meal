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

        // 🔒 voorkom her-armen van dezelfde episode
        if (
            state.active &&
            state.mealType == type &&
            state.validUntil == validUntil
        ) return

        state.active = true
        state.mealType = type
        state.totalU = preBolusU
        state.remainingU = preBolusU
        state.armedAt = now
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

    // Logisch actief: kan nog insuline leveren
    fun isActive(now: DateTime): Boolean =
        state.active &&
            state.remainingU > 0.0 &&
            !isExpired(now)

    // UI actief: episode loopt nog (TTL)
    fun isUiActive(now: DateTime): Boolean =
        state.active &&
            state.validUntil != null &&
            !now.isAfter(state.validUntil)


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

    /**
     * Laat prebolus langzaam aflopen als hij niet (volledig) wordt afgegeven.
     *
     * - Eerste gracePeriodMin minuten: geen decay
     * - Daarna niet-lineaire afbouw richting TTL
     * - Beperkt ALLEEN remainingU (nooit verhogen)
     */
    private fun graceMinutes(type: MealIntentType): Int = when (type) {
        MealIntentType.SNACK  -> 45
        MealIntentType.SMALL  -> 30
        MealIntentType.NORMAL -> 40
        MealIntentType.LARGE  -> 50
    }


    fun applyDecay(now: DateTime) {
        if (!state.active) return

        val armedAt = state.armedAt ?: return
        val validUntil = state.validUntil ?: return
        val type = state.mealType ?: return

        if (now.isAfter(validUntil)) return

        val minutesSinceArmed =
            Minutes.minutesBetween(armedAt, now).minutes
        if (minutesSinceArmed < 0) return

        val totalMinutes =
            Minutes.minutesBetween(armedAt, validUntil).minutes
        if (totalMinutes <= 1) return

        // 🟢 Grace per maaltijdtype, maar nooit ≥ TTL
        val graceMin =
            minOf(
                graceMinutes(type),
                totalMinutes - 1
            ).coerceAtLeast(0)

        // ⏳ Binnen grace: geen decay
        if (minutesSinceArmed <= graceMin) return

        val decayWindow = totalMinutes - graceMin
        if (decayWindow <= 0) return

        val t =
            ((minutesSinceArmed - graceMin).toDouble() / decayWindow)
                .coerceIn(0.0, 1.0)

        // 🔻 Kwadratische afname: langzaam begin, sneller einde
        val decayFactor = 1.0 - (t * t)

        val maxAllowedRemaining =
            (state.totalU * decayFactor).coerceAtLeast(0.0)

        // ❗ Alleen reduceren, nooit verhogen
        state.remainingU =
            minOf(state.remainingU, maxAllowedRemaining)
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
        if (!isUiActive(now)) return null
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
