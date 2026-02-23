package app.aaps.plugins.aps.openAPSFCL.vnext.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// ============================================================
// SECTIE 1: Data classes en Enums
// ============================================================

/**
 * Input snapshot voor curve analyse
 */
data class BgCurveSnapshot(
    val bgNow: Double,              // mmol/L
    val iob: Double,                // U
    val slope: Double?,             // mmol/L/uur (macro trend)
    val delta5m: Double?,           // mmol/L/5min (fast lane)
    val acceleration: Double,       // mmol/L/uur²
    val predictedPeak: Double?      // FCL core predicted peak (optioneel)
)

/**
 * Fase van de BG curve - bepaalt welke projectie relevant is
 */
enum class CurvePhase(
    val emoji: String,
    val descriptionNl: String,
    val showProjection: ProjectionType
) {
    RISING_ACCELERATING("📈", "Stijgend (versnellend)", ProjectionType.PEAK),
    RISING_STEADY("📈", "Stijgend (gelijkmatig)", ProjectionType.PEAK),
    RISING_DECELERATING("📈", "Stijgend (vertragend)", ProjectionType.PEAK),
    PLATEAU_TOP("⏸️", "Plateau (top)", ProjectionType.NONE),
    FALLING_ACCELERATING("📉", "Dalend (versnellend)", ProjectionType.TROUGH),
    FALLING_STEADY("📉", "Dalend (gelijkmatig)", ProjectionType.TROUGH),
    FALLING_DECELERATING("📉", "Dalend (vertragend)", ProjectionType.TROUGH),
    PLATEAU_BOTTOM("⏸️", "Plateau (bodem)", ProjectionType.NONE),
    UNKNOWN("❓", "Onbekend", ProjectionType.NONE);

    val isRising: Boolean get() = showProjection == ProjectionType.PEAK
    val isFalling: Boolean get() = showProjection == ProjectionType.TROUGH
    val isPlateau: Boolean get() = this == PLATEAU_TOP || this == PLATEAU_BOTTOM
}

enum class ProjectionType { PEAK, TROUGH, NONE }

enum class PlateauStability { STABLE, LIGHTLY_VARYING, TRANSITION }

enum class HypoRisk { NONE, LOW, MEDIUM, HIGH, SEVERE }

/**
 * Resultaat van een projectie (piek of dal)
 */
data class ProjectionResult(
    val value: Double,              // mmol/L
    val minutesUntil: Int?,         // minuten tot bereiking
    val confidence: Double          // 0.0 - 1.0
)

/**
 * Informatie over een plateau fase
 */
data class PlateauInfo(
    val stability: PlateauStability,
    val isAtTop: Boolean,
    val estimatedDurationMin: Int? = null
)

/**
 * Complete analyse resultaat
 */
data class CurveAnalysis(
    val phase: CurvePhase,
    val peakProjection: ProjectionResult?,
    val troughProjection: ProjectionResult?,
    val plateauInfo: PlateauInfo?,
    val trendDescription: String,
    val hypoRisk: HypoRisk
)

// ============================================================
// SECTIE 2: Kern Analyzer
// ============================================================

class BgCurveAnalyzer {

    // Thresholds - kunnen later naar preferences verplaatst worden
    companion object Thresholds {
        const val SLOPE_RISING = 0.15
        const val SLOPE_FALLING = -0.15
        const val SLOPE_STABLE_RANGE = 0.15
        const val ACCEL_ACCELERATING = 0.12
        const val ACCEL_DECELERATING = -0.05
        const val ACCEL_STABLE = 0.05
        const val PLATEAU_PROXIMITY = 0.3
        const val MAX_PROJECTION_HOURS = 2.0
        const val MIN_PROJECTION_HOURS = 0.1
    }

    /**
     * Hoofdfunctie: analyseer snapshot en geef complete curve analyse
     */
    fun analyze(snapshot: BgCurveSnapshot): CurveAnalysis {
        val phase = determinePhase(snapshot)

        return CurveAnalysis(
            phase = phase,
            peakProjection = if (phase.isRising) calculatePeakProjection(snapshot) else null,
            troughProjection = if (phase.isFalling) calculateTroughProjection(snapshot) else null,
            plateauInfo = if (phase.isPlateau) analyzePlateau(snapshot, phase) else null,
            trendDescription = generateTrendDescription(phase, snapshot),
            hypoRisk = assessHypoRisk(snapshot, phase)
        )
    }

    // ---------------------------------------------------------
    // Fase detectie
    // ---------------------------------------------------------

    private fun determinePhase(s: BgCurveSnapshot): CurvePhase {
        val slope = s.slope ?: 0.0
        val delta = s.delta5m ?: 0.0
        val accel = s.acceleration

        // Eerst: plateau detectie (heeft prioriteit)
        val nearPredictedPeak = s.predictedPeak?.let {
            abs(s.bgNow - it) < PLATEAU_PROXIMITY
        } ?: false

        val slopeNearZero = abs(slope) < SLOPE_STABLE_RANGE
        val accelNearZero = abs(accel) < ACCEL_STABLE

        // Top plateau detectie
        if ((nearPredictedPeak || (slopeNearZero && slope > -0.1)) &&
            s.bgNow > 6.0 && accelNearZero) {
            return CurvePhase.PLATEAU_TOP
        }

        // Bottom plateau (stabiel laag)
        if (slopeNearZero && accelNearZero && s.bgNow < 5.5) {
            return CurvePhase.PLATEAU_BOTTOM
        }

        // Stijgende fasen - check beide lanes
        val macroRising = slope > SLOPE_RISING
        val fastRising = delta > 0.03

        if (macroRising || (fastRising && slope > -0.2)) {
            return when {
                accel > ACCEL_ACCELERATING -> CurvePhase.RISING_ACCELERATING
                accel < ACCEL_DECELERATING -> CurvePhase.RISING_DECELERATING
                else -> CurvePhase.RISING_STEADY
            }
        }

        // Dalende fasen
        val macroFalling = slope < SLOPE_FALLING
        val fastFalling = delta < -0.03

        if (macroFalling || (fastFalling && slope < 0.2)) {
            return when {
                accel < -ACCEL_ACCELERATING -> CurvePhase.FALLING_ACCELERATING
                accel > abs(ACCEL_DECELERATING) -> CurvePhase.FALLING_DECELERATING
                else -> CurvePhase.FALLING_STEADY
            }
        }

        return CurvePhase.UNKNOWN
    }

    // ---------------------------------------------------------
    // Projectie berekeningen
    // ---------------------------------------------------------

    private fun calculatePeakProjection(s: BgCurveSnapshot): ProjectionResult? {
        val macro = s.slope ?: 0.0
        val fast = (s.delta5m ?: 0.0) * 12.0
        val effSlope = max(macro, fast * 0.8)

        if (effSlope <= 0.05) return null

        // Gebruik FCL's predictedPeak als beschikbaar en betrouwbaar
        val peakValue = s.predictedPeak?.takeIf { it > s.bgNow + 0.3 }
            ?: calculateBallisticPeak(s.bgNow, effSlope, s.acceleration)
            ?: return null

        val minutes = estimateTimeToTarget(s.bgNow, peakValue, effSlope, s.acceleration)
        val confidence = calculateConfidence(s, isRising = true)

        return ProjectionResult(peakValue, minutes, confidence)
    }

    private fun calculateTroughProjection(s: BgCurveSnapshot): ProjectionResult? {
        val slope = s.slope ?: return null
        if (slope >= -0.05) return null

        val troughValue = calculateBallisticTrough(s) ?: return null

        val minutes = estimateTimeToTarget(s.bgNow, troughValue, slope, s.acceleration)
        val confidence = calculateConfidence(s, isRising = false)

        return ProjectionResult(troughValue, minutes, confidence)
    }

    private fun calculateBallisticPeak(bg: Double, slope: Double, accel: Double): Double? {
        // Tijd tot top wanneer slope = 0: t = -v/a
        val hoursToPeak = if (accel < -0.02) {
            (-slope / accel).coerceIn(MIN_PROJECTION_HOURS, MAX_PROJECTION_HOURS)
        } else {
            0.6  // Default 36 minuten bij constante snelheid
        }

        // Ballistische formule: s = v*t + 0.5*a*t²
        val rise = slope * hoursToPeak + 0.5 * accel * hoursToPeak * hoursToPeak
        val peak = bg + rise

        return peak.takeIf { it > bg + 0.2 }
    }

    private fun calculateBallisticTrough(s: BgCurveSnapshot): Double? {
        val slope = s.slope ?: return null
        val accel = s.acceleration

        // Tijd tot bodem
        val hoursToTrough = if (accel > 0.02) {
            // Vertragende daling: -slope/accel (slope is negatief, accel positief)
            (-slope / accel).coerceIn(0.2, 1.5)
        } else {
            1.0  // Default 60 minuten
        }

        // Basis projectie
        var drop = slope * hoursToTrough + 0.5 * accel * hoursToTrough * hoursToTrough

        // IOB impact: hoger IOB = potentieel dieper dal
        val iobImpact = (s.iob * 0.3).coerceAtMost(1.5)
        drop -= iobImpact  // Versterkt het dal (drop is negatief)

        val trough = (s.bgNow + drop).coerceAtLeast(2.5)
        return trough.takeIf { it < s.bgNow - 0.2 }
    }

    private fun estimateTimeToTarget(
        current: Double,
        target: Double,
        slope: Double,
        accel: Double
    ): Int? {
        val distance = target - current

        return if (abs(accel) > 0.01) {
            // Kwadratische oplossing: 0.5*a*t² + s*t - d = 0
            val discriminant = slope * slope + 2 * accel * distance
            if (discriminant < 0) return null

            val t = when {
                accel > 0 && distance > 0 -> (-slope + sqrt(discriminant)) / accel  // Stijgend versnellend
                accel < 0 && distance > 0 -> (-slope + sqrt(discriminant)) / accel  // Stijgend vertragend
                accel > 0 && distance < 0 -> (-slope - sqrt(discriminant)) / accel  // Dalend vertragend
                else -> (-slope + sqrt(discriminant)) / accel
            }

            (t * 60).toInt().coerceAtLeast(5).takeIf { it < 120 }
        } else {
            // Lineair
            ((distance / slope) * 60).toInt().coerceAtLeast(5).takeIf { it < 120 }
        }
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private fun analyzePlateau(s: BgCurveSnapshot, phase: CurvePhase): PlateauInfo {
        val slopeAbs = abs(s.slope ?: 0.0)
        val accelAbs = abs(s.acceleration)

        val stability = when {
            slopeAbs < 0.1 && accelAbs < 0.05 -> PlateauStability.STABLE
            slopeAbs < 0.2 -> PlateauStability.LIGHTLY_VARYING
            else -> PlateauStability.TRANSITION
        }

        return PlateauInfo(
            stability = stability,
            isAtTop = phase == CurvePhase.PLATEAU_TOP
        )
    }

    private fun calculateConfidence(s: BgCurveSnapshot, isRising: Boolean): Double {
        // Consistentie tussen macro en fast lane
        val laneConsistency = when {
            s.slope == null || s.delta5m == null -> 0.5
            abs(s.slope - s.delta5m * 12) < 0.5 -> 0.9
            abs(s.slope - s.delta5m * 12) < 1.0 -> 0.7
            else -> 0.5
        }

        // Lagere confidence bij extreme acceleratie (onvoorspelbaar)
        val accelFactor = 1.0 - (abs(s.acceleration) / 0.5).coerceAtMost(1.0) * 0.3

        // Hogere confidence bij duidelijke richting
        val directionClarity = if (isRising) {
            (s.slope ?: 0.0) / 0.5.coerceAtMost(1.0)
        } else {
            abs(s.slope ?: 0.0) / 0.5.coerceAtMost(1.0)
        }

        return (laneConsistency * 0.5 + accelFactor * 0.3 + directionClarity * 0.2)
            .coerceIn(0.3, 0.95)
    }

    private fun generateTrendDescription(phase: CurvePhase, s: BgCurveSnapshot): String {
        val slopeVal = abs(s.slope ?: 0.0)
        val slopeFmt = "%.1f".format(slopeVal)

        return when (phase) {
            CurvePhase.RISING_ACCELERATING -> "Snel stijgend (versnellend),\n +$slopeFmt mmol/uur"
            CurvePhase.RISING_STEADY -> "Steady stijgend, +$slopeFmt mmol/uur"
            CurvePhase.RISING_DECELERATING -> "Stijgt nog maar remt af (nadert top)"
            CurvePhase.PLATEAU_TOP -> "Op of nabij maximum niveau"
            CurvePhase.FALLING_ACCELERATING -> "Snel dalend (versnellend),\n -$slopeFmt mmol/uur"
            CurvePhase.FALLING_STEADY -> "Steady dalend, -$slopeFmt mmol/uur"
            CurvePhase.FALLING_DECELERATING -> "Daalt nog maar remt af (nadert bodem)"
            CurvePhase.PLATEAU_BOTTOM -> "Stabiel laag niveau"
            CurvePhase.UNKNOWN -> "Trend onduidelijk - wachten op meer data"
        }
    }

    private fun assessHypoRisk(s: BgCurveSnapshot, phase: CurvePhase): HypoRisk {
        // Directe hypo check
        if (s.bgNow < 3.5) return HypoRisk.SEVERE
        if (s.bgNow < 4.0) return HypoRisk.HIGH

        // Projected risk bij dalende fase
        if (phase.isFalling) {
            val projected = calculateBallisticTrough(s)
                ?: (s.bgNow + (s.slope ?: 0.0) * 0.5)

            return when {
                projected < 3.0 -> HypoRisk.SEVERE
                projected < 3.5 -> HypoRisk.HIGH
                projected < 4.0 -> HypoRisk.MEDIUM
                projected < 4.5 -> HypoRisk.LOW
                else -> HypoRisk.NONE
            }
        }

        // Preventieve waarschuwing bij hoog IOB na plateau top
        if (phase == CurvePhase.PLATEAU_TOP && s.iob > 1.5 && s.bgNow < 7.0) {
            return HypoRisk.LOW
        }

        return HypoRisk.NONE
    }
}

// ============================================================
// SECTIE 3: UI Helpers
// ============================================================

/**
 * Helper object voor UI-weergave van analyse resultaten
 */
object CurveAnalysisFormatter {

    fun formatForStatus(analysis: CurveAnalysis): String {
        val sb = StringBuilder()

        // Fase
        sb.append("• Fase: ${analysis.phase.emoji} ${analysis.phase.descriptionNl}\n")

        // Projectie (indien van toepassing)
        when {
            analysis.peakProjection != null -> {
                formatProjection(sb, "Verwacht maximum", analysis.peakProjection, isPeak = true)
            }
            analysis.troughProjection != null -> {
                formatProjection(sb, "Verwacht minimum", analysis.troughProjection, isPeak = false)
                // Hypo warning bij dal
                if (analysis.hypoRisk >= HypoRisk.MEDIUM) {
                    sb.append("  ${formatHypoWarning(analysis.hypoRisk)}\n")
                }
            }
            analysis.plateauInfo != null -> {
                formatPlateau(sb, analysis.plateauInfo)
            }
        }

        // Trend beschrijving
        sb.append("• Trend: ${analysis.trendDescription}\n")

        return sb.toString()
    }

    private fun formatProjection(
        sb: StringBuilder,
        label: String,
        proj: ProjectionResult,
        isPeak: Boolean
    ) {
        sb.append("• $label: ${"%.1f".format(proj.value)} mmol/L")

        proj.minutesUntil?.let { minutes ->
            sb.append("\n  (over ~${minutes} min)")
        }

        sb.append(" ${confidenceEmoji(proj.confidence)}\n")
    }

    private fun formatPlateau(sb: StringBuilder, info: PlateauInfo) {
        val stability = when (info.stability) {
            PlateauStability.STABLE -> "stabiel"
            PlateauStability.LIGHTLY_VARYING -> "licht wisselend"
            PlateauStability.TRANSITION -> "in overgang"
        }
        val location = if (info.isAtTop) "maximum" else "bodem"
        sb.append("• Plateau: $stability op $location niveau\n")
    }

    private fun formatHypoWarning(risk: HypoRisk): String {
        return when (risk) {
            HypoRisk.SEVERE -> "🚨 Kritiek hypo risico!"
            HypoRisk.HIGH -> "⚠️ Hoog hypo risico"
            HypoRisk.MEDIUM -> "⚡ Matig hypo risico - monitor"
            HypoRisk.LOW -> "ℹ️ Laag hypo risico"
            HypoRisk.NONE -> ""
        }
    }

    private fun confidenceEmoji(conf: Double): String = when {
        conf >= 0.8 -> "🟢"
        conf >= 0.6 -> "🟡"
        else -> "🟠"
    }
}