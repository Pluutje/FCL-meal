package app.aaps.plugins.aps.openAPSFCL.vnext.meal

import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.core.interfaces.meal.MealIntentRepository.PreBolusSnapshot
import app.aaps.core.interfaces.meal.MealIntentRepository.UiStatus
import org.joda.time.DateTime
import org.joda.time.Minutes
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class PreBolusController @Inject constructor() {


    // ===============================
    // Interne state
    // ===============================
    private data class State(
        var active: Boolean = false,
        var mealType: MealIntentType? = null,

        var totalU: Double = 0.0,
        var deliveredU: Double = 0.0,
        var remainingU: Double = 0.0,

        var armedAt: DateTime? = null,
        var validUntil: DateTime? = null,

        // decay bookkeeping
        var decayStarted: Boolean = false,
        var remainingAtDecayStart: Double = 0.0
    )

    private val state = State()

    // ===============================
    // Arm / reset
    // ===============================
    fun arm(
        type: MealIntentType,
        totalU: Double,
        validUntil: DateTime,
        now: DateTime
    ) {
        if (totalU <= 0.0) return

        // voorkom her-armen van exact dezelfde episode
        if (
            state.active &&
            state.mealType == type &&
            state.validUntil == validUntil
        ) return

        state.active = true
        state.mealType = type

        state.totalU = totalU
        state.deliveredU = 0.0
        state.remainingU = totalU

        state.armedAt = now
        state.validUntil = validUntil

        state.decayStarted = false
        state.remainingAtDecayStart = 0.0
    }

    fun reset() {
        state.active = false
        state.mealType = null
        state.totalU = 0.0
        state.deliveredU = 0.0
        state.remainingU = 0.0
        state.armedAt = null
        state.validUntil = null
        state.decayStarted = false
        state.remainingAtDecayStart = 0.0
    }

    // ===============================
    // Geldigheid
    // ===============================
    fun isActive(now: DateTime): Boolean =
        state.active &&
            state.remainingU > 0.0 &&
            !isExpired(now)

    fun isUiActive(now: DateTime): Boolean =
        state.active &&
            state.validUntil != null &&
            !now.isAfter(state.validUntil)

    fun isExpired(now: DateTime): Boolean =
        state.validUntil?.let { now.isAfter(it) } ?: true

    fun stopMealIntent() {

        // 1️⃣ Repository leegmaken
        MealIntentRepository.clear()

        // 2️⃣ Snapshot ook leegmaken
        MealIntentRepository.setPreBolusSnapshot(null)

        // 3️⃣ Interne state volledig resetten
        reset()
    }



    // ===============================
    // Chunking / delivery
    // ===============================
    fun computeChunk(maxSmb: Double): Double =
        if (!isActive(DateTime.now())) 0.0
        else min(state.remainingU, maxSmb)

    fun consumePlannedChunk(chunkU: Double) {
        if (chunkU <= 0.0) return

        state.deliveredU += chunkU
        state.remainingU =
            (state.remainingU - chunkU).coerceAtLeast(0.0)
    }

    // ===============================
    // Decay
    // ===============================
    private fun graceMinutes(type: MealIntentType): Int = when (type) {
        MealIntentType.SNACK  -> 45
        MealIntentType.SMALL  -> 15
        MealIntentType.NORMAL -> 20
        MealIntentType.LARGE  -> 20
    }

    private fun computeDecayFactor(now: DateTime): Double {
        val armedAt = state.armedAt ?: return 0.0
        val validUntil = state.validUntil ?: return 0.0
        val type = state.mealType ?: return 0.0

        if (now.isAfter(validUntil)) return 0.0

        val minutesSince =
            Minutes.minutesBetween(armedAt, now).minutes

        val totalMinutes =
            Minutes.minutesBetween(armedAt, validUntil).minutes
        if (totalMinutes <= 1) return 0.0

        val grace =
            min(graceMinutes(type), totalMinutes - 1)

        // binnen grace: geen decay
        if (minutesSince <= grace) return 1.0

        val decayWindow = totalMinutes - grace
        val t =
            ((minutesSince - grace).toDouble() / decayWindow)
                .coerceIn(0.0, 1.0)

        // kwadratisch: langzaam begin, sneller einde
        return (1.0 - t * t).coerceIn(0.0, 1.0)
    }

    fun applyDecay(now: DateTime) {
        if (!state.active) return
        if (state.remainingU <= 0.0) return

        val decayFactor = computeDecayFactor(now)

        // start decay exact 1×
        if (!state.decayStarted && decayFactor < 1.0) {
            state.decayStarted = true
            state.remainingAtDecayStart = state.remainingU
        }

        if (!state.decayStarted) return

        val decayedRemaining =
            state.remainingAtDecayStart * decayFactor

        // ❗ alleen reduceren
        state.remainingU =
            min(state.remainingU, decayedRemaining)
                .coerceAtLeast(0.0)
    }

    // ===============================
    // Snapshots
    // ===============================
    fun snapshot(now: DateTime): PreBolusSnapshot? {
        if (!isActive(now)) return null
        return buildSnapshot(now)
    }

    fun uiSnapshot(now: DateTime): PreBolusSnapshot? {
        // 🔑 KOPPELING MET DE ENIGE WAARHEID
        val intent = MealIntentRepository.get() ?: return null

        // bestaande UI-logica blijft leidend
        if (!isUiActive(now)) return null

        return buildSnapshot(now)
    }


    private fun buildSnapshot(now: DateTime): PreBolusSnapshot {
        val armedAt = state.armedAt!!
        val validUntil = state.validUntil!!

        val minutesSince =
            Minutes.minutesBetween(armedAt, now).minutes
        val minutesLeft =
            Minutes.minutesBetween(now, validUntil).minutes.coerceAtLeast(0)

        val status =
            when {
                now.isAfter(validUntil) ->
                    UiStatus.EXPIRED
                state.remainingU > 0.0 ->
                    UiStatus.ACTIVE
                else ->
                    UiStatus.DELIVERED
            }

        val snapshot =
            PreBolusSnapshot(
                mealType = state.mealType!!,
                totalU = state.totalU,
                deliveredU = state.deliveredU,
                remainingU = state.remainingU,

                armedAt = armedAt.millis,          // ✅ Long
                validUntil = validUntil.millis,    // ✅ Long

                minutesSinceArmed = minutesSince,
                minutesRemaining = minutesLeft,

                decayFactor = computeDecayFactor(now),
                status = status
            )

        MealIntentRepository.setPreBolusSnapshot(snapshot)

        return snapshot
    }



}
