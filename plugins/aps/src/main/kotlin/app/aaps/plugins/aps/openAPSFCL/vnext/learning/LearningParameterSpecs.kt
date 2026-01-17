package app.aaps.plugins.aps.openAPSFCL.vnext.learning

enum class LearningDomain {
    TIMING,
    HEIGHT
}

data class LearningParameterSpec(
    val key: LearningParameter,
    val uiLabel: String,
    val minMultiplier: Double,
    val maxMultiplier: Double,
    val nightAllowed: Boolean = true,
    val domain: LearningDomain
)

object LearningParameterSpecs {

    val specs: Map<LearningParameter, LearningParameterSpec> = mapOf(

        // ─────────────────────────────────────────────
        // Core dynamics → HEIGHT
        // ─────────────────────────────────────────────
        LearningParameter.K_DELTA to LearningParameterSpec(
            key = LearningParameter.K_DELTA,
            uiLabel = "BG-afwijking",
            minMultiplier = 0.85,
            maxMultiplier = 1.15,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        ),

        LearningParameter.K_SLOPE to LearningParameterSpec(
            key = LearningParameter.K_SLOPE,
            uiLabel = "BG-stijgsnelheid",
            minMultiplier = 0.80,
            maxMultiplier = 1.20,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        ),

        LearningParameter.K_ACCEL to LearningParameterSpec(
            key = LearningParameter.K_ACCEL,
            uiLabel = "BG-versnelling",
            minMultiplier = 0.80,
            maxMultiplier = 1.20,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        ),

        // ─────────────────────────────────────────────
        // Commit shaping → TIMING
        // ─────────────────────────────────────────────
        LearningParameter.COMMIT_IOB_POWER to LearningParameterSpec(
            key = LearningParameter.COMMIT_IOB_POWER,
            uiLabel = "IOB-remming",
            minMultiplier = 0.70,
            maxMultiplier = 1.40,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        LearningParameter.MIN_COMMIT_DOSE to LearningParameterSpec(
            key = LearningParameter.MIN_COMMIT_DOSE,
            uiLabel = "Min. correctie",
            minMultiplier = 0.70,
            maxMultiplier = 1.30,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        LearningParameter.UNCERTAIN_MIN_FRACTION to LearningParameterSpec(
            key = LearningParameter.UNCERTAIN_MIN_FRACTION,
            uiLabel = "Onzeker min %",
            minMultiplier = 0.85,
            maxMultiplier = 1.15,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        LearningParameter.UNCERTAIN_MAX_FRACTION to LearningParameterSpec(
            key = LearningParameter.UNCERTAIN_MAX_FRACTION,
            uiLabel = "Onzeker max %",
            minMultiplier = 0.85,
            maxMultiplier = 1.15,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        LearningParameter.CONFIRM_MIN_FRACTION to LearningParameterSpec(
            key = LearningParameter.CONFIRM_MIN_FRACTION,
            uiLabel = "Bevestig min %",
            minMultiplier = 0.85,
            maxMultiplier = 1.15,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        LearningParameter.CONFIRM_MAX_FRACTION to LearningParameterSpec(
            key = LearningParameter.CONFIRM_MAX_FRACTION,
            uiLabel = "Bevestig max %",
            minMultiplier = 0.85,
            maxMultiplier = 1.15,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        // ─────────────────────────────────────────────
        // Absorption / pre-peak → HEIGHT
        // ─────────────────────────────────────────────
        LearningParameter.ABSORPTION_DOSE_FACTOR to LearningParameterSpec(
            key = LearningParameter.ABSORPTION_DOSE_FACTOR,
            uiLabel = "Absorptie-reductie",
            minMultiplier = 0.50,
            maxMultiplier = 1.30,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        ),

        LearningParameter.PRE_PEAK_BUNDLE_FACTOR to LearningParameterSpec(
            key = LearningParameter.PRE_PEAK_BUNDLE_FACTOR,
            uiLabel = "Pre-piek bundel",
            minMultiplier = 0.70,
            maxMultiplier = 1.20,
            nightAllowed = true,
            domain = LearningDomain.TIMING
        ),

        // ─────────────────────────────────────────────
        // Peak shaping → HEIGHT
        // ─────────────────────────────────────────────
        LearningParameter.PEAK_MOMENTUM_GAIN to LearningParameterSpec(
            key = LearningParameter.PEAK_MOMENTUM_GAIN,
            uiLabel = "Piek-momentum",
            minMultiplier = 0.70,
            maxMultiplier = 1.40,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        ),

        LearningParameter.PEAK_RISE_GAIN to LearningParameterSpec(
            key = LearningParameter.PEAK_RISE_GAIN,
            uiLabel = "Piek-stijging",
            minMultiplier = 0.70,
            maxMultiplier = 1.30,
            nightAllowed = true,
            domain = LearningDomain.HEIGHT
        )
    )
}

