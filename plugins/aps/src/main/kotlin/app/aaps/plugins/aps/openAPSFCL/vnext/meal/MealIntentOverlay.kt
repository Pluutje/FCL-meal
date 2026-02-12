package app.aaps.plugins.aps.openAPSFCL.vnext.meal

import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfig
import org.joda.time.DateTime
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

data class MealIntentEffect(
    val active: Boolean,
    val strength: Double,            // gate 0..1 (TTL × assist × decay)
    val assistStrength: Double,      // 0..1
    val strengthMultiplier: Double,  // user instelling 0.75–1.25
    val assumeCarbs: Boolean,
    val type: MealIntentType,
    val reason: String
)

private fun lerp(a: Double, b: Double, t: Double): Double =
    a + (b - a) * t.coerceIn(0.0, 1.0)

@Singleton
class MealIntentOverlay @Inject constructor(
    private val prefs: Preferences,
    private val mealTddProvider: MealTddProvider
) {

    /**
     * Expected "maaltijd insuline schaal" op basis van Average TDD.
     *
     * Vuistregel:
     * - 50% TDD is basaal, 50% maaltijd/correctie.
     * - Maaltijddeel ≈ 0.5 * TDD
     * - Gemiddeld 3 maaltijden/dag → per maaltijd ≈ (0.5*TDD)/3 ≈ 0.167*TDD
     *
     * Snack moet lager liggen.
     */
    private fun expectedFromTdd(type: MealIntentType, avgTdd: Double, maxSmb: Double): Double {
        // fallback als TDD niet beschikbaar
        val safeTdd = if (avgTdd > 0.0) avgTdd else (maxSmb * 6.0)

        val expected = when (type) {
            MealIntentType.SNACK  -> safeTdd * 0.06   // snack ~ 6% van TDD (bij jou: 25u → 1.5u)
            MealIntentType.SMALL  -> safeTdd * 0.12
            MealIntentType.NORMAL -> safeTdd * 0.17   // ≈ 1/6 van TDD (bij jou: 25u → 4.25u)
            MealIntentType.LARGE  -> safeTdd * 0.25
        }

        // nooit lager dan 1×maxSMB (zodat coverage niet “te agressief” wordt bij lage expected)
        return expected.coerceAtLeast(maxSmb)
    }

    /**
     * Berekent MealIntent effect (gate + userMul).
     *
     * Inputs:
     * - preBolusU: “verwachte” prebolus volgens jouw knobs (of snack-preBolus uit intent)
     * - decayFactor: van PreBolusController.snapshot(now)?.decayFactor
     */
    fun computeEffect(
        now: DateTime,
        maxSmb: Double,
        preBolusU: Double,
        decayFactor: Double
    ): MealIntentEffect {

        val intent = MealIntentRepository.get()
            ?: return MealIntentEffect(
                active = false,
                strength = 0.0,
                assistStrength = 0.0,
                strengthMultiplier = 1.0,
                assumeCarbs = false,
                type = MealIntentType.NORMAL,
                reason = "MealIntent: none"
            )

        // 1) TTL strength
        val nowMs = now.millis
        val totalMs = max(1L, intent.validUntil - intent.timestamp)
        val remainingMs = (intent.validUntil - nowMs).coerceAtLeast(0L)
        val ttlStrength = (remainingMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0)

        // 2) Avg TDD (cached)
        val avgTdd = mealTddProvider.getAverageTdd(now)

        // 3) Expected scale (TDD-based)
        val expected = expectedFromTdd(intent.type, avgTdd, maxSmb)

        // 4) Coverage → assist
        val coverage =
            if (expected > 0.0) (preBolusU / expected).coerceIn(0.0, 1.2) else 0.0

        val assistStrength =
            (1.0 - coverage).coerceIn(0.0, 1.0)

        // 5) User multiplier per meal type
        val userMul =
            when (intent.type) {
                MealIntentType.SMALL ->
                    prefs.get(DoubleKey.fcl_vnext_meal_strength_small)
                MealIntentType.NORMAL ->
                    prefs.get(DoubleKey.fcl_vnext_meal_strength_normal)
                MealIntentType.LARGE ->
                    prefs.get(DoubleKey.fcl_vnext_meal_strength_large)
                MealIntentType.SNACK ->
                    prefs.get(DoubleKey.fcl_vnext_meal_strength_snack)
            }.coerceIn(0.75, 1.25)

        // 6) gate
        val gate = (ttlStrength * assistStrength * decayFactor).coerceIn(0.0, 1.0)

        return MealIntentEffect(
            active = gate > 0.0,
            strength = gate,
            assistStrength = assistStrength,
            strengthMultiplier = userMul,
            assumeCarbs = true,
            type = intent.type,
            reason =
                "MealIntent=${intent.type} gate=${"%.2f".format(gate)} " +
                    "ttl=${"%.2f".format(ttlStrength)} " +
                    "decay=${"%.2f".format(decayFactor)} " +
                    "cov=${"%.2f".format(coverage)} " +
                    "exp=${"%.2f".format(expected)} " +
                    "tdd=${"%.1f".format(avgTdd)} " +
                    "mul=${"%.2f".format(userMul)}"
        )
    }

    /**
     * Past timing + strength toe (met gate decay).
     */
    fun applyToConfig(
        base: FCLvNextConfig,
        effect: MealIntentEffect
    ): FCLvNextConfig {

        if (!effect.active) return base

        val gate = effect.strength

        // Timing milder
        val detectMul = lerp(1.0, 0.75, gate)
        val earlyMul  = lerp(1.0, 0.80, gate)
        val microMul  = lerp(1.0, 0.80, gate)

        val iobPowerMul = lerp(1.0, 0.90, gate)
        val peakSlopeMul = lerp(1.0, 0.90, gate)

        // Strength scaling (gate + userMul)
        val mealDoseMul = lerp(1.0, effect.strengthMultiplier, gate)

        return base.copy(
            mealDetectThresholdMul = base.mealDetectThresholdMul * detectMul,
            microRampThresholdMul = base.microRampThresholdMul * microMul,
            earlyStage1ThresholdMul = base.earlyStage1ThresholdMul * earlyMul,
            commitIobPower = base.commitIobPower * iobPowerMul,
            peakSlopeThreshold = base.peakSlopeThreshold * peakSlopeMul,

            // strength
            doseStrengthMul = base.doseStrengthMul * mealDoseMul,
            maxCommitFractionMul = base.maxCommitFractionMul * mealDoseMul,
            microDoseMul = base.microDoseMul * mealDoseMul
        )
    }
}
