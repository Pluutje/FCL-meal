package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.os.Environment
import org.joda.time.DateTime
import java.io.File
import java.util.Locale

object FCLvNextCsvLogger {

    private const val FILE_NAME = "FCLvNext_Log.csv"
    private const val MAX_DAYS = 5
    private const val MAX_LINES = MAX_DAYS * 288  // 5 min ticks

    private const val SEP = ";"

    private val header = listOf(
        // ── Context ──
        "ts",
        "isNight",
        "bg_mmol",
        "target_mmol",
        "delta_target",
        "iob",
        "iob_ratio",
        "bg_zone",
        "dose_access",

        // ── Trends ──
        "slope",
        "accel",
        "recent_slope",
        "recent_delta5m",
        "consistency",

        // ── Model ──
        "effective_isf",
        "gain",
        "energy_base",
        "energy_total",
        "kDeltaBase",
        "kSlopeBase",
        "kAccelBase",
        "kDeltaEff",
        "kSlopeEff",
        "kAccelEff",
        "stagnation_active",
        "stagnation_boost",
        "stagnation_accel",
        "stagnation_accel_limit",
        "raw_dose",
        "iob_factor",
        "normal_dose",

        // ── Early ──
        "early_stage",
        "early_confidence",
        "early_target_u",

        // ── Decision / phase ──
        "meal_state",
        "commit_fraction",
        "commit_base_min",
        "commit_multiplier",
        "commit_effective_min",
        "minutes_since_commit",

        // ── Peak prediction ──
        "peak_state",
        "predicted_peak",
        "peak_iob_boost",
        "effective_iob_ratio",
        "peak_band",
        "peak_max_slope",
        "peak_momentum",
        "peak_rise_since_start",
        "peak_episode_active",
        "suppress_for_peak",
        "absorption_active",
        "reentry_signal",
        "decision_reason",

        // ── Rescue / hypo prevention ──
        "pred60",
        "rescue_state",
        "rescue_confidence",
        "rescue_reason",

        // ── Execution ──
        "final_dose",
        "commanded_dose",
        "delivered_total",
        "bolus",
        "basal_u_h",

        // ── Reserve pool ──
        "reserve_u",
        "reserve_action",
        "reserve_delta_u",
        "reserve_age_min",

        "should_deliver"
    ).joinToString(SEP)

    private fun getFile(): File {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            "Documents/AAPS/ANALYSE"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun log(
        ts: DateTime = DateTime.now(),
        isNight: Boolean,
        bg: Double,
        target: Double,

        // trends
        slope: Double,
        accel: Double,
        recentSlope: Double,
        recentDelta5m: Double,
        consistency: Double,

        // IOB
        iob: Double,
        iobRatio: Double,
        bgZone: String,
        doseAccess: String,

        // model
        effectiveISF: Double,
        gain: Double,
        energyBase: Double,
        energyTotal: Double,

        // learning (core)
        kDeltaBase: Double,
        kSlopeBase: Double,
        kAccelBase: Double,
        kDeltaEff: Double,
        kSlopeEff: Double,
        kAccelEff: Double,

        stagnationActive: Boolean,
        stagnationBoost: Double,
        stagnationAccel: Double,
        stagnationAccelLimit: Double,

        rawDose: Double,
        iobFactor: Double,
        normalDose: Double,

        // early
        earlyStage: Int,
        earlyConfidence: Double,
        earlyTargetU: Double,

        // phase / decision
        mealState: String,
        commitFraction: Double,
        commitBaseMin: Double,
        commitMultiplier: Double,
        commitEffectiveMin: Double,
        minutesSinceCommit: Int,

        // peak
        peakState: String,
        predictedPeak: Double,
        peakIobBoost: Double,
        effectiveIobRatio: Double,
        peakBand: Int,
        peakMaxSlope: Double,
        peakMomentum: Double,
        peakRiseSinceStart: Double,
        peakEpisodeActive: Boolean,

        suppressForPeak: Boolean,
        absorptionActive: Boolean,
        reentrySignal: Boolean,
        decisionReason: String,

        // rescue
        pred60: Double,
        rescueState: String,
        rescueConfidence: Double,
        rescueReason: String,

        // execution
        finalDose: Double,
        commandedDose: Double,
        deliveredTotal: Double,
        bolus: Double,
        basalRate: Double,

        // reserve
        reserveU: Double,
        reserveAction: String,
        reserveDeltaU: Double,
        reserveAgeMin: Int,

        shouldDeliver: Boolean
    ) {
        try {
            val file = getFile()

            val line = listOf(
                ts.toString("yyyy-MM-dd HH:mm:ss"),
                isNight,

                // BG
                bg1(bg),
                bg1(target),

                // delta
                d2(bg - target),

                // IOB
                u2(iob),
                u2(iobRatio),
                bgZone,
                doseAccess,

                // trends
                t2(slope),
                a2(accel),
                t2(recentSlope),
                t2(recentDelta5m),
                t2(consistency),

                // model
                bg2(effectiveISF),
                u2(gain),
                e2(energyBase),
                e2(energyTotal),
                u2(kDeltaBase),
                u2(kSlopeBase),
                u2(kAccelBase),
                u2(kDeltaEff),
                u2(kSlopeEff),
                u2(kAccelEff),
                stagnationActive,
                e2(stagnationBoost),
                a2(stagnationAccel),
                a2(stagnationAccelLimit),
                u2(rawDose),
                u2(iobFactor),
                u2(normalDose),

                // early
                earlyStage,
                t2(earlyConfidence),
                u2(earlyTargetU),

                // phase
                mealState,
                t2(commitFraction),
                t2(commitBaseMin),
                t2(commitMultiplier),
                t2(commitEffectiveMin),
                minutesSinceCommit,

                // peak
                peakState,
                bg1(predictedPeak),
                u2(peakIobBoost),
                u2(effectiveIobRatio),
                peakBand,
                t2(peakMaxSlope),
                t2(peakMomentum),
                bg1(peakRiseSinceStart),
                peakEpisodeActive,
                suppressForPeak,
                absorptionActive,
                reentrySignal,
                decisionReason.replace(SEP, ","),

                // rescue
                bg1(pred60),
                rescueState,
                t2(rescueConfidence),
                rescueReason.replace(SEP, ","),

                // execution
                u2(finalDose),
                u2(commandedDose),
                u2(deliveredTotal),
                u2(bolus),
                u2(basalRate),

                // reserve
                u2(reserveU),
                reserveAction,
                u2(reserveDeltaU),
                reserveAgeMin,

                shouldDeliver
            ).joinToString(SEP)

            if (!file.exists() || file.length() == 0L) {
                file.writeText(header + "\n" + line + "\n")
                return
            }

            val lines = file.readLines().toMutableList()
            val body = lines.drop(1).toMutableList()

            body.add(0, line)

            val trimmed =
                if (body.size > MAX_LINES) body.take(MAX_LINES) else body

            file.writeText(header + "\n")
            file.appendText(trimmed.joinToString("\n") + "\n")

        } catch (_: Exception) {
            // logging mag NOOIT FCL blokkeren
        }
    }

    // formatting helpers
    private fun bg1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun bg2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun d2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun u2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun a2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun e2(x: Double)  = String.format(Locale.US, "%.2f", x)
    private fun t2(x: Double)  = String.format(Locale.US, "%.2f", x)
}
