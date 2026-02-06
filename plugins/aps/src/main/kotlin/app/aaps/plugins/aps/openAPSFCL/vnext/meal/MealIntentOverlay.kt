package app.aaps.plugins.aps.openAPSFCL.vnext.meal

import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfig
import org.joda.time.DateTime
import kotlin.math.max
import kotlin.math.min

data class MealIntentEffect(
    val active: Boolean,
    val strength: Double,   // 0..1
    val assumeCarbs: Boolean,
    val type: MealIntentType,
    val reason: String
)


private fun lerp(a: Double, b: Double, t: Double): Double =
    a + (b - a) * t.coerceIn(0.0, 1.0)

/**
 * Berekent hoe sterk MealIntent NU nog mag meewegen.
 * - gebruikt Repository (TTL is daar al afgevangen)
 * - strength loopt lineair af tot 0
 */
fun computeMealIntentEffect(now: DateTime): MealIntentEffect {
    val intent = MealIntentRepository.get()
        ?: return MealIntentEffect(
            active = false,
            strength = 0.0,
            assumeCarbs = false,
            type = MealIntentType.NORMAL,
            reason = "MealIntent: none"
        )

    val nowMs = now.millis
    val totalMs = max(1L, intent.validUntil - intent.timestamp)
    val remainingMs = (intent.validUntil - nowMs).coerceAtLeast(0L)

    val strength =
        (remainingMs.toDouble() / totalMs.toDouble())
            .coerceIn(0.0, 1.0)

    return MealIntentEffect(
        active = strength > 0.0,
        strength = strength,
        assumeCarbs = true,   // 🔴 dit is de kern
        type = intent.type,
        reason =
            "MealIntent=${intent.type} strength=${"%.2f".format(strength)}"
    )

}

/**
 * Past ALLEEN timing/detectie-parameters aan.
 * ❌ geen dosis-hoogte
 * ❌ geen maxSMB
 * ❌ geen doseStrengthMul
 */
fun applyMealIntentToConfig(
    base: FCLvNextConfig,
    effect: MealIntentEffect
): FCLvNextConfig {

    if (!effect.active) return base

    val s = effect.strength

    // 🔴 Kern: we vertrouwen BG-stijging volledig als carb
    val detectMul = lerp(1.0, 0.55, s)   // veel sneller detecteren
    val earlyMul  = lerp(1.0, 0.65, s)
    val microMul  = lerp(1.0, 0.65, s)

    // 🔴 Minder remmen
    val iobPowerMul = lerp(1.0, 0.75, s)
    val peakSlopeMul = lerp(1.0, 0.80, s)

    return base.copy(
        // sneller meal detectie
        mealDetectThresholdMul =
            base.mealDetectThresholdMul * detectMul,

        // micro + early eerder
        microRampThresholdMul =
            base.microRampThresholdMul * microMul,

        earlyStage1ThresholdMul =
            base.earlyStage1ThresholdMul * earlyMul,

        // 🔴 agressiever mogen doseren
        commitIobPower =
            base.commitIobPower * iobPowerMul,

        peakSlopeThreshold =
            base.peakSlopeThreshold * peakSlopeMul
    )
}

