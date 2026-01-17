package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager.EpisodeOutcome

object OutcomeToHintMapper {

    private val PHASE = LearningPhase.TIMING_ONLY

    fun map(
        outcome: EpisodeOutcome,
        isNight: Boolean,
        peakBand: Int,
        rescueConfirmed: Boolean,
        mealActive: Boolean
    ): List<ParameterHint> {

        // 0) SAFETY always dominates (hoogte omlaag)
        if (rescueConfirmed) {
            return listOf(
                ParameterHint(LearningParameter.K_DELTA, -1),
                ParameterHint(LearningParameter.K_SLOPE, -1),
                ParameterHint(LearningParameter.K_ACCEL, -1)
            )
        }

        // helper: alleen timing parameters toestaan in TIMING_ONLY fase
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

        return when (outcome) {

            EpisodeOutcome.TOO_LATE -> {
                // Alleen zinvol bij echte meal/high dynamiek
                if (!mealActive && peakBand < 12) emptyList()
                else timingOnly(
                    listOf(
                        // eerder / sneller committen
                        ParameterHint(LearningParameter.UNCERTAIN_MIN_FRACTION, +1),
                        ParameterHint(LearningParameter.CONFIRM_MIN_FRACTION, +1),

                        // iets minder IOB-remming in commit (eerder durven)
                        ParameterHint(LearningParameter.COMMIT_IOB_POWER, -1)
                    )
                )
            }

            EpisodeOutcome.TOO_LATE_EVEN_WITH_EARLY -> {
                // We waren al vroeg bezig, maar nog niet voldoende → timing agressiever
                timingOnly(
                    listOf(
                        // Eerder & steviger starten
                        ParameterHint(LearningParameter.UNCERTAIN_MIN_FRACTION, +1),
                        ParameterHint(LearningParameter.CONFIRM_MIN_FRACTION, +1),

                        // Sneller durven opschalen
                        ParameterHint(LearningParameter.UNCERTAIN_MAX_FRACTION, +1),

                        // Minder IOB-remming → eerder echte doses
                        ParameterHint(LearningParameter.COMMIT_IOB_POWER, -1)
                    )
                )
            }


            EpisodeOutcome.OVERSHOOT -> {
                // Overshoot is vaak "te laat gestart en dan te veel erachteraan".
                // In timing-only: juist eerder maar gecontroleerder.
                timingOnly(
                    listOf(
                        ParameterHint(LearningParameter.UNCERTAIN_MAX_FRACTION, -1),
                        ParameterHint(LearningParameter.CONFIRM_MAX_FRACTION, -1),
                        ParameterHint(LearningParameter.MIN_COMMIT_DOSE, -1)
                    )
                )
            }

            EpisodeOutcome.TOO_STRONG -> {
                // Te sterk: dit is hoogte, maar in TIMING_ONLY fase doen we hier niets.
                if (PHASE == LearningPhase.TIMING_ONLY) emptyList()
                else listOf(
                    ParameterHint(LearningParameter.K_DELTA, -1),
                    ParameterHint(LearningParameter.K_SLOPE, -1),
                    ParameterHint(LearningParameter.K_ACCEL, -1)
                )
            }

            EpisodeOutcome.HYPO_RISK -> {
                // Hypo-risico = hoogte omlaag (mag, ook in timing-only)
                listOf(
                    ParameterHint(LearningParameter.K_DELTA, -1),
                    ParameterHint(LearningParameter.K_SLOPE, -1)
                )
            }

            else -> emptyList()
        }
    }

}
