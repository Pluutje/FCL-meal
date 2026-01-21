package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager.EpisodeOutcome

enum class HeightLearningIntent {
    NONE,
    CONSIDER_INCREASE,
    CONSIDER_DECREASE
}

enum class HeightIntent {
    NONE,
    TOO_WEAK,
    TOO_STRONG
}

object OutcomeToHintMapper {

    /**
     * Huidige learning-fase:
     * - TIMING_ONLY = alleen commit/timing parameters
     * - Hoogte-parameters zijn geparkeerd (zie TODO Fase C.1)
     */
    private val PHASE = LearningPhase.TIMING_ONLY

    fun map(
        outcome: EpisodeOutcome,
        isNight: Boolean,
        peakBand: Int,
        rescueConfirmed: Boolean,
        mealActive: Boolean
    ): List<ParameterHint> {

        // ─────────────────────────────────────────────
        // 0️⃣ SAFETY OVERRIDE (altijd dominant)
        // ─────────────────────────────────────────────
        if (rescueConfirmed) {
            return listOf(
                ParameterHint(LearningParameter.K_DELTA, -1),
                ParameterHint(LearningParameter.K_SLOPE, -1),
                ParameterHint(LearningParameter.K_ACCEL, -1)
            )
        }

        // ─────────────────────────────────────────────
        // Helpers
        // ─────────────────────────────────────────────

        // Alleen timing-parameters toestaan in TIMING_ONLY fase
        fun timingOnly(list: List<ParameterHint>): List<ParameterHint> =
            if (PHASE == LearningPhase.TIMING_ONLY)
                list.filter {
                    when (it.parameter) {
                        LearningParameter.UNCERTAIN_MIN_FRACTION,
                        LearningParameter.UNCERTAIN_MAX_FRACTION,
                        LearningParameter.CONFIRM_MIN_FRACTION,
                        LearningParameter.CONFIRM_MAX_FRACTION,
                        LearningParameter.MIN_COMMIT_DOSE,
                        LearningParameter.COMMIT_IOB_POWER -> true
                        else -> false
                    }
                }
            else list

        // Confidence-weging op basis van episode-sterkte
        val weight = confidenceWeight(
            peakBand = peakBand,
            mealActive = mealActive
        )
        val heightIntent = detectHeightIntent(
            outcome = outcome,
            peakBand = peakBand,
            mealActive = mealActive,
            isNight = isNight,
            rescueConfirmed = rescueConfirmed
        )
// ─────────────────────────────────────────────
// FASE C.1 – observe-only hoogte learning
// (nog GEEN parameterhints genereren)
// ─────────────────────────────────────────────
// heightIntent is bewust NIET gebruikt om hints te maken.
// Logging / analyse gebeurt elders (CSV / status).


        // ─────────────────────────────────────────────
        // Outcome → hints
        // ─────────────────────────────────────────────
        return when (outcome) {

            EpisodeOutcome.TOO_LATE -> {
                // Alleen zinvol bij echte maaltijd / duidelijke stijging
                if (!mealActive && peakBand < 12) {
                    emptyList()
                } else {
                    timingOnly(
                        buildList {
                            addAll(weighted(LearningParameter.UNCERTAIN_MIN_FRACTION, +1, weight))
                            addAll(weighted(LearningParameter.CONFIRM_MIN_FRACTION, +1, weight))
                            addAll(weighted(LearningParameter.COMMIT_IOB_POWER, -1, weight))
                        }
                    )
                }
            }

            EpisodeOutcome.TOO_LATE_EVEN_WITH_EARLY -> {
                // We waren al vroeg bezig, maar nog steeds te laat → agressiever timing
                timingOnly(
                    buildList {
                        addAll(weighted(LearningParameter.UNCERTAIN_MIN_FRACTION, +1, weight))
                        addAll(weighted(LearningParameter.CONFIRM_MIN_FRACTION, +1, weight))
                        addAll(weighted(LearningParameter.UNCERTAIN_MAX_FRACTION, +1, weight))
                        addAll(weighted(LearningParameter.COMMIT_IOB_POWER, -1, weight))
                    }
                )
            }

            EpisodeOutcome.OVERSHOOT -> {
                // Te veel / te snel → timing afremmen
                timingOnly(
                    buildList {
                        addAll(weighted(LearningParameter.UNCERTAIN_MAX_FRACTION, -1, weight))
                        addAll(weighted(LearningParameter.CONFIRM_MAX_FRACTION, -1, weight))
                        addAll(weighted(LearningParameter.MIN_COMMIT_DOSE, -1, weight))
                    }
                )
            }

            EpisodeOutcome.TOO_STRONG -> {
                // Hoogte-probleem → genegeerd in TIMING_ONLY
                if (PHASE == LearningPhase.TIMING_ONLY) {
                    emptyList()
                } else {
                    listOf(
                        ParameterHint(LearningParameter.K_DELTA, -1),
                        ParameterHint(LearningParameter.K_SLOPE, -1),
                        ParameterHint(LearningParameter.K_ACCEL, -1)
                    )
                }
            }

            EpisodeOutcome.HYPO_RISK -> {
                // Hypo-risico mag altijd hoogte verlagen
                listOf(
                    ParameterHint(LearningParameter.K_DELTA, -1),
                    ParameterHint(LearningParameter.K_SLOPE, -1)
                )
            }

            else -> emptyList()
        }
    }

    // ─────────────────────────────────────────────
    // Confidence helpers
    // ─────────────────────────────────────────────

    private fun confidenceWeight(
        peakBand: Int,
        mealActive: Boolean
    ): Int {
        var w = 1
        if (mealActive) w += 1
        if (peakBand >= 14) w += 1
        return w.coerceAtMost(3)
    }

    private fun weighted(
        parameter: LearningParameter,
        direction: Int,
        weight: Int
    ): List<ParameterHint> =
        List(weight) { ParameterHint(parameter, direction) }

    // ─────────────────────────────────────────────
    // TODO FASE C.1 – Hoogte-learning (nog UIT)
    // ─────────────────────────────────────────────
    /*
    Ideeën (nog niet actief):
    - Bij structureel TOO_LATE_EVEN_WITH_EARLY
    - peakBand ≥ 15
    - alleen overdag
    - nooit bij rescueConfirmed
    → kleine +1 op K_DELTA of K_SLOPE
    */

    /**
     * Observe-only hoogte-intent.
     * Wordt gelogd maar nog NIET toegepast (fase C.1).
     */
    fun detectHeightIntent(
        outcome: EpisodeOutcome,
        peakBand: Int,
        mealActive: Boolean,
        isNight: Boolean,
        rescueConfirmed: Boolean
    ): HeightIntent {

        // Veiligheid domineert altijd
        if (rescueConfirmed) return HeightIntent.TOO_STRONG
        if (isNight) return HeightIntent.NONE

        return when (outcome) {
            EpisodeOutcome.TOO_LATE,
            EpisodeOutcome.TOO_LATE_EVEN_WITH_EARLY ->
                if (mealActive && peakBand >= 14)
                    HeightIntent.TOO_WEAK
                else
                    HeightIntent.NONE

            EpisodeOutcome.OVERSHOOT,
            EpisodeOutcome.TOO_STRONG ->
                HeightIntent.TOO_STRONG

            else -> HeightIntent.NONE
        }

    }


}
