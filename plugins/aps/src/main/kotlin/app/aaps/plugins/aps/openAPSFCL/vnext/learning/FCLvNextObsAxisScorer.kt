package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stap 3B: AxisScorer (uitgebreid)
 *
 * Input:
 * - Episode (tijd/kwaliteit/exclusion)
 * - FCLvNextObsEpisodeSummary (feiten: peak/nadir/timeAbove10/firstInsulin/predPeakError/rebound)
 *
 * Output:
 * - AxisObservation per axis: TIMING / HEIGHT / PERSISTENCE
 *
 * Kernregels (volgens jouw wensen):
 * - TIMING "LATE" is pas een probleem als de werkelijke piek > 10 mmol/L.
 *   Een late eerste dosis maar géén hoge piek => TIMING=OK.
 * - HEIGHT primair op werkelijke piek (peakBg). predictedPeak mismatch alleen als tag.
 * - Exclusions:
 *   - episode.excluded => geen learning (we returnen 1 UNKNOWN + reason als trace)
 */

enum class Axis {
    TIMING,
    HEIGHT,
    PERSISTENCE
}

enum class AxisOutcome {
    OK,

    // TIMING
    EARLY,
    LATE,
    LATE_PEAK_INTERVENTION,

    // HEIGHT
    TOO_HIGH,      // peak boven grens (10)
    TOO_STRONG,    // hypo/nadir onder grens of sterke overshoot naar beneden
    TOO_WEAK,      // (nog beperkt bruikbaar zonder carbs/meal truth)

    // PERSISTENCE
    TOO_SHORT,     // lang boven 10 => te weinig/te kort volgehouden
    TOO_LONG,      // te lang doorgezet => nadir te laag / rebound na hypo (proxy)

    UNKNOWN
}

data class AxisObservation(
    val episodeId: Long,
    val axis: Axis,
    val outcome: AxisOutcome,
    val signalStrength: Double,   // 0..1 (duidelijkheid van dit label)
    val reason: String,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Alle thresholds expliciet in config zodat jij later veilig kunt tunen.
 */
data class FCLvNextObsAxisScorerConfig(

    // Targets / zones
    val highBgThresholdMmol: Double = 10.0,
    val hypoThresholdMmol: Double = 3.9,

    // TIMING:
    // Alleen labelen als peak > highBgThreshold.
    val timingLateMinMinutesToFirstInsulin: Int = 20,

    // EARLY / TOO_LONG / TOO_STRONG proxies:
    // - EARLY: eerste meaningful insulin erg snel + nadir laag / of nadir < hypoThreshold
    val earlyMaxMinutesToFirstInsulin: Int = 8,
    val earlyNadirSafetyMmol: Double = 4.2,     // iets boven hypo, conservatief

    // PERSISTENCE TOO_SHORT:
    // - lang boven 10 => te kort / te zwak doorgezet
    val persistenceTooShort_TimeAbove10Min: Int = 45,

    // PERSISTENCE TOO_LONG / TOO_STRONG:
    // - nadir onder hypoThreshold => TOO_STRONG
    // - nadir in [hypoThreshold..earlyNadirSafety] + reboundDetected => TOO_LONG (proxy)
    val tooLongReboundRequiresLowNadir: Boolean = true,

    // predicted mismatch (tag-only)
    val predictedPeakMismatchAbsMmol: Double = 2.0
)

class FCLvNextObsAxisScorer(
    private val cfg: FCLvNextObsAxisScorerConfig = FCLvNextObsAxisScorerConfig()
) {

    fun score(
        episode: Episode,
        summary: FCLvNextObsEpisodeSummary
    ): List<AxisObservation> {

        // Excluded episodes: geen learning labels
        if (episode.excluded) {
            return listOf(
                AxisObservation(
                    episodeId = episode.id,
                    axis = Axis.HEIGHT,
                    outcome = AxisOutcome.UNKNOWN,
                    signalStrength = 0.0,
                    reason = "EXCLUDED: ${episode.exclusionReason}"
                )
            )
        }

        val quality = clamp01(episode.qualityScore)

        val peak = summary.peakBg
        val nadir = summary.nadirBg
        val minutesToFirst = summary.minutesToFirstInsulin
        val timeAbove10 = summary.timeAbove10Min

        val tags = mutableMapOf<String, String>()

        // predicted mismatch tag (debug)
        if (summary.predictedPeakAtStart != null && peak != null) {
            val errAbs = abs(summary.predictedPeakAtStart - peak)
            if (errAbs >= cfg.predictedPeakMismatchAbsMmol) {
                tags["PRED_MISMATCH"] =
                    "absErr=${fmt(errAbs)} pred=${fmt(summary.predictedPeakAtStart)} act=${fmt(peak)}"
            }
        }
        if (summary.peakPredictionError != null) {
            tags["PRED_ERR"] = "act-pred=${fmt(summary.peakPredictionError)}"
        }

        val timing = scoreTiming(
            episode = episode,
            summary = summary,
            quality = quality,
            peakBg = peak,
            minutesToFirstInsulin = minutesToFirst,
            nadirBg = nadir,
            tags = tags
        )
        val timingLateOrPeak =
            timing.outcome == AxisOutcome.LATE ||
                timing.outcome == AxisOutcome.LATE_PEAK_INTERVENTION

        val timingIsLatePeak =
            timing.outcome == AxisOutcome.LATE_PEAK_INTERVENTION



        val height = scoreHeight(
            episode = episode,
            quality = quality,
            peakBg = peak,
            nadirBg = nadir,
            timeBelowTargetMin = summary.timeBelowTargetMin,
            timeBelowHypoMin = summary.timeBelowHypoMin,
            durationMin = summary.durationMin,
            timingLateOrPeak = timingLateOrPeak,
            timingIsLatePeak = timingIsLatePeak,
            tags = tags
        )


        val persistence = scorePersistence(
            episode = episode,
            quality = quality,
            peakBg = peak,
            nadirBg = nadir,
            timeAbove10Min = timeAbove10,
            reboundDetected = summary.reboundDetected,
            tags = tags
        )

        return listOf(timing, height, persistence)
    }

    // ─────────────────────────────────────────────
    // TIMING (nu met EARLY-proxy)
    // ─────────────────────────────────────────────
    private fun scoreTiming(
        episode: Episode,
        summary: FCLvNextObsEpisodeSummary,
        quality: Double,
        peakBg: Double?,
        minutesToFirstInsulin: Int?,
        nadirBg: Double?,
        tags: Map<String, String>
    ): AxisObservation {

        if (peakBg == null) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "TIMING unknown: peakBg missing",
                tags = tags
            )
        }

        // ─────────────────────────────
        // 1️⃣ MISSED INTERVENTION → altijd LATE
        // ─────────────────────────────
        if (episode.missedIntervention) {
            val peakSignal = signalFromPeak(peakBg)
            val strength = clamp01(0.60 * quality + 0.40 * peakSignal)

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.LATE,
                signalStrength = strength,
                reason = "TIMING LATE: missedIntervention=true peak=${fmt(peakBg)}",
                tags = tags
            )
        }

        // ─────────────────────────────
        // 2️⃣ Peak niet hoog → timing OK
        // ─────────────────────────────
        if (peakBg <= cfg.highBgThresholdMmol) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.OK,
                signalStrength = clamp01(0.65 * quality),
                reason = "TIMING OK: peak<=${fmt(cfg.highBgThresholdMmol)}",
                tags = tags
            )
        }

        // ─────────────────────────────
        // 3️⃣ Geen insulin geregistreerd
        // ─────────────────────────────
        if (minutesToFirstInsulin == null) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.LATE,
                signalStrength = clamp01(0.70 * quality + 0.30 * signalFromPeak(peakBg)),
                reason = "TIMING LATE: no meaningful insulin",
                tags = tags
            )
        }

        // ─────────────────────────────
        // 4️⃣ EARLY
        // ─────────────────────────────
        val early =
            minutesToFirstInsulin <= cfg.earlyMaxMinutesToFirstInsulin &&
                nadirBg != null &&
                nadirBg <= cfg.earlyNadirSafetyMmol

        if (early) {
            val depth = clamp01((cfg.earlyNadirSafetyMmol - nadirBg!!) / 1.0)
            val strength = clamp01(0.55 * quality + 0.45 * depth)

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.EARLY,
                signalStrength = strength,
                reason = "TIMING EARLY: minToFirst=${minutesToFirstInsulin}",
                tags = tags
            )
        }

        // ─────────────────────────────
// 5️⃣ LATE_PEAK_INTERVENTION
// Insuline rond of na peak bij hoge piek
// ─────────────────────────────

        val phase = summary.phaseOfFirstInsulin

        if (peakBg > cfg.highBgThresholdMmol &&
            phase != null &&
            phase >= 0.8
        ) {
            val phaseExcess = clamp01((phase - 0.8) / 0.6)
            val peakSignal = signalFromPeak(peakBg)

            val strength =
                clamp01(
                    0.50 * quality +
                        0.30 * peakSignal +
                        0.20 * phaseExcess
                )

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.TIMING,
                outcome = AxisOutcome.LATE_PEAK_INTERVENTION,
                signalStrength = strength,
                reason = "TIMING LATE_PEAK: phase=${fmt(phase)} peak=${fmt(peakBg)}",
                tags = tags
            )
        }


        // ─────────────────────────────
        // 5️⃣ LATE grens (mealIntent strenger)
        // ─────────────────────────────
        val lateThreshold =
            if (episode.mealIntentActiveAtStart)
                cfg.timingLateMinMinutesToFirstInsulin - 5   // strenger bij mealIntent
            else
                cfg.timingLateMinMinutesToFirstInsulin

        val late = minutesToFirstInsulin >= lateThreshold
        val outcome = if (late) AxisOutcome.LATE else AxisOutcome.OK

        val latenessStrength =
            if (!late) 0.30
            else clamp01((minutesToFirstInsulin - lateThreshold).toDouble() / 30.0)

        val strength =
            clamp01(
                0.55 * quality +
                    0.25 * signalFromPeak(peakBg) +
                    0.20 * latenessStrength
            )

        return AxisObservation(
            episodeId = episode.id,
            axis = Axis.TIMING,
            outcome = outcome,
            signalStrength = strength,
            reason = "TIMING ${outcome.name}: minToFirst=${minutesToFirstInsulin} threshold=$lateThreshold",
            tags = tags
        )
    }


    // ─────────────────────────────────────────────
    // HEIGHT (nu met TOO_STRONG / TOO_WEAK placeholders)
    // ─────────────────────────────────────────────
    private fun scoreHeight(
        episode: Episode,
        quality: Double,
        peakBg: Double?,
        nadirBg: Double?,
        timeBelowTargetMin: Int,
        timeBelowHypoMin: Int,
        durationMin: Int,
        timingLateOrPeak: Boolean,
        timingIsLatePeak: Boolean,
        tags: Map<String, String>
    ): AxisObservation {


        if (peakBg == null) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "HEIGHT unknown: peakBg missing",
                tags = tags
            )
        }

        // TOO_STRONG
        if (nadirBg != null && nadirBg < cfg.hypoThresholdMmol) {
            val severity = clamp01((cfg.hypoThresholdMmol - nadirBg) / 1.0)
            val hypoTimeFactor =
                clamp01(timeBelowHypoMin.toDouble() / 20.0)   // 20m = zwaar signaal
            val targetTimeFactor =
                clamp01(timeBelowTargetMin.toDouble() / 40.0) // 40m = structureel te laag
            val strength =
                clamp01(
                    0.45 * quality +
                        0.25 * severity +
                        0.20 * hypoTimeFactor +
                        0.10 * targetTimeFactor
                )


            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.TOO_STRONG,
                signalStrength = strength,
                reason = "HEIGHT TOO_STRONG: nadir=${fmt(nadirBg)}",
                tags = tags
            )
        }

        // Mild overshoot: langdurig onder target maar niet hypo
        if (timeBelowTargetMin >= 30 && (nadirBg == null || nadirBg >= cfg.hypoThresholdMmol)) {

            val undershootRatio =
                clamp01(timeBelowTargetMin.toDouble() / durationMin)

            val strength =
                clamp01(
                    0.50 * quality +
                        0.50 * undershootRatio
                )

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.TOO_STRONG,
                signalStrength = strength,
                reason = "HEIGHT TOO_STRONG: prolonged TBT=${timeBelowTargetMin}m",
                tags = tags
            )
        }


        // TOO_HIGH

        if (peakBg > cfg.highBgThresholdMmol) {
            val overshoot = max(0.0, peakBg - cfg.highBgThresholdMmol)
            val overshootStrength = clamp01(overshoot / 4.0)

            var base = 0.60 * quality + 0.40 * overshootStrength

            // 🔗 Interactie: als insulin pas laat / rond de piek komt,
            // dan is de oorzaak waarschijnlijk TIMING i.p.v. totale HEIGHT.
            if (timingLateOrPeak) {
                base *= 0.72   // ~28% dempen
            }

            // Als het specifiek LATE_PEAK_INTERVENTION is, nog iets sterker dempen.
            if (timingIsLatePeak) {
                base *= 0.85   // extra demping
            }

            val strength =
                if (episode.missedIntervention)
                    clamp01(base * 1.15)
                else
                    clamp01(base)

            val reasonExtra =
                when {
                    timingIsLatePeak -> " (timing late-peak)"
                    timingLateOrPeak -> " (timing late)"
                    else -> ""
                }

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.TOO_HIGH,
                signalStrength = strength,
                reason = "HEIGHT TOO_HIGH$reasonExtra: peak=${fmt(peakBg)}",
                tags = tags
            )
        }


        return AxisObservation(
            episodeId = episode.id,
            axis = Axis.HEIGHT,
            outcome = AxisOutcome.OK,
            signalStrength = clamp01(0.65 * quality),
            reason = "HEIGHT OK",
            tags = tags
        )
    }


    // ─────────────────────────────────────────────
    // PERSISTENCE (nu met TOO_LONG proxy)
    // ─────────────────────────────────────────────
    private fun scorePersistence(
        episode: Episode,
        quality: Double,
        peakBg: Double?,
        nadirBg: Double?,
        timeAbove10Min: Int,
        reboundDetected: Boolean,
        tags: Map<String, String>
    ): AxisObservation {

        if (peakBg == null) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "PERSISTENCE unknown: peakBg missing",
                tags = tags
            )
        }

        // Als peak niet hoog was: persistence probleemloos (voor nu)
        if (peakBg <= cfg.highBgThresholdMmol) {
            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.OK,
                signalStrength = clamp01(0.65 * quality),
                reason = "PERSISTENCE OK: peak<=${fmt(cfg.highBgThresholdMmol)}",
                tags = tags
            )
        }

        // TOO_LONG proxy:
        // Als er rebound is én nadir laag-ish => mogelijk te lang doorgezet (of te agressief na peak)
        val lowEnoughForTooLong =
            if (!cfg.tooLongReboundRequiresLowNadir) true
            else (nadirBg != null && nadirBg <= cfg.earlyNadirSafetyMmol)

        if (reboundDetected && lowEnoughForTooLong) {
            val depth =
                if (nadirBg == null) 0.5
                else clamp01((cfg.earlyNadirSafetyMmol - nadirBg) / 1.0)

            val strength = clamp01(0.55 * quality + 0.45 * depth)

            return AxisObservation(
                episodeId = episode.id,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.TOO_LONG,
                signalStrength = strength,
                reason = "PERSISTENCE TOO_LONG: reboundDetected=true nadir=${nadirBg?.let { fmt(it) } ?: "?"}",
                tags = tags
            )
        }

        // TOO_SHORT:
        val tooShort = timeAbove10Min >= cfg.persistenceTooShort_TimeAbove10Min
        val outcome = if (tooShort) AxisOutcome.TOO_SHORT else AxisOutcome.OK

        val aboveStrength =
            if (!tooShort) 0.30
            else clamp01((timeAbove10Min - cfg.persistenceTooShort_TimeAbove10Min).toDouble() / 60.0)

        val strength =
            clamp01(
                0.55 * quality +
                    0.25 * signalFromPeak(peakBg) +
                    0.20 * aboveStrength
            )

        return AxisObservation(
            episodeId = episode.id,
            axis = Axis.PERSISTENCE,
            outcome = outcome,
            signalStrength = strength,
            reason = "PERSISTENCE ${outcome.name}: timeAbove10=${timeAbove10Min}m (tooShort>=${cfg.persistenceTooShort_TimeAbove10Min}m) rebound=$reboundDetected",
            tags = tags
        )
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────
    private fun clamp01(x: Double): Double = min(1.0, max(0.0, x))

    private fun signalFromPeak(peak: Double): Double {
        val overshoot = max(0.0, peak - cfg.highBgThresholdMmol)
        return clamp01(overshoot / 4.0)
    }

    private fun fmt(x: Double): String = String.format("%.2f", x)
}
