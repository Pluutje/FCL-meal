package app.aaps.plugins.aps.openAPSFCL.vnext.learning

/**
 * Applies learning-based multipliers to baseline config values.
 *
 * - Never mutates config
 * - Never exceeds spec bounds
 * - Fully deterministic
 */
class FCLvNextLearningAdjuster(
    private val advisor: FCLvNextLearningAdvisor,
    private val phase: LearningPhase = LearningPhase.TIMING_ONLY
){
    /**
     * Returns multiplier for a parameter (default = 1.0)
     */
    fun multiplier(
        parameter: LearningParameter,
        isNight: Boolean
    ): Double {

        val advice = advisor
            .getAdvice(isNight)
            .firstOrNull { it.parameter == parameter }
            ?: return 1.0

        val spec = LearningParameterSpecs.specs[parameter]
            ?: return 1.0

        val domain = spec.domain

        // richting: +1 = omhoog, -1 = omlaag
        // HEIGHT-learning is uitgeschakeld in TIMING_ONLY fase
        if (domain == LearningDomain.HEIGHT && phase == LearningPhase.TIMING_ONLY) {
            return 1.0
        }

// stapgrootte per domein
        val step =
            when (domain) {
                LearningDomain.TIMING -> 0.15
                LearningDomain.HEIGHT -> 0.07
            }

// richting: +1 = omhoog, -1 = omlaag
        val rawMultiplier =
            1.0 + (advice.direction * advice.confidence * step)

// clamp binnen veilige grenzen
        return rawMultiplier.coerceIn(
            spec.minMultiplier,
            spec.maxMultiplier
        )

    }

}
