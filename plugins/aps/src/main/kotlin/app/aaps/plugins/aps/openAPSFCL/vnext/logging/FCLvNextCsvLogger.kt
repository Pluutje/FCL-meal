package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.os.Environment
import org.joda.time.DateTime
import java.io.File
import java.util.Locale


data class FCLvNextCsvLogRow(

    // ── Context ──
    var ts: DateTime = DateTime.now(),
    var isNight: Boolean = false,
    var bg: Double = 0.0,
    var target: Double = 0.0,

    // ── Trends ──
    var slope: Double = 0.0,
    var accel: Double = 0.0,
    var recentSlope: Double = 0.0,
    var recentDelta5m: Double = 0.0,
    var consistency: Double = 0.0,

    // ── IOB ──
    var iob: Double = 0.0,
    var iobRatio: Double = 0.0,
    var bgZone: String = "",
    var doseAccess: String = "",

    // ── Model ──
    var effectiveISF: Double = 0.0,
    var gain: Double = 0.0,
    var energyBase: Double = 0.0,
    var energyTotal: Double = 0.0,


    var stagnationActive: Boolean = false,
    var stagnationBoost: Double = 0.0,
    var stagnationAccel: Double = 0.0,
    var stagnationAccelLimit: Double = 0.0,

    var rawDose: Double = 0.0,
    var iobFactor: Double = 0.0,
    var normalDose: Double = 0.0,

    // ── Early ──
    var earlyStage: Int = 0,
    var earlyConfidence: Double = 0.0,
    var earlyTargetU: Double = 0.0,

    // ── Decision / phase ──
    var mealState: String = "",
    var commitFraction: Double = 0.0,
    var minutesSinceCommit: Int = -1,

    // ── Peak ──
    var peakState: String = "",
    var predictedPeak: Double = 0.0,
    var peakIobBoost: Double = 1.0,
    var effectiveIobRatio: Double = 0.0,
    var peakBand: Int = 0,
    var peakMaxSlope: Double = 0.0,
    var peakMomentum: Double = 0.0,
    var peakRiseSinceStart: Double = 0.0,
    var peakEpisodeActive: Boolean = false,

    var suppressForPeak: Boolean = false,
    var absorptionActive: Boolean = false,
    var reentrySignal: Boolean = false,
    var decisionReason: String = "",

    // ── Height learning (observe-only) ──
    var heightIntent: String = "NONE",

    // ── Rescue ──
    var pred60: Double = 0.0,
    var rescueState: String = "",
    var rescueConfidence: Double = 0.0,
    var rescueReason: String = "",

    var profielNaam: String = "",
    var mealDetectSpeed: String = "",
    var correctionStyle: String = "",
    var doseDistributionStyle: String = "",

    // ── Execution ──
    var finalDose: Double = 0.0,
    var commandedDose: Double = 0.0,
    var deliveredTotal: Double = 0.0,
    var bolus: Double = 0.0,
    var basalRate: Double = 0.0,

    // ── Reserve ──
    var reserveU: Double = 0.0,
    var reserveAction: String = "NONE",
    var reserveDeltaU: Double = 0.0,
    var reserveAgeMin: Int = -1,

    var shouldDeliver: Boolean = false
)

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

        // ── Height learning (observe-only) ──
        "height_intent",

        // ── Rescue / hypo prevention ──
        "pred60",
        "rescue_state",
        "rescue_confidence",
        "rescue_reason",

        "profielNaam",
        "mealDetectSpeed",
        "correctionStyle",
        "doseDistributionStyle",


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

    fun log(row: FCLvNextCsvLogRow) {
        log(
            ts = row.ts,
            isNight = row.isNight,
            bg = row.bg,
            target = row.target,

            slope = row.slope,
            accel = row.accel,
            recentSlope = row.recentSlope,
            recentDelta5m = row.recentDelta5m,
            consistency = row.consistency,

            iob = row.iob,
            iobRatio = row.iobRatio,
            bgZone = row.bgZone,
            doseAccess = row.doseAccess,

            effectiveISF = row.effectiveISF,
            gain = row.gain,
            energyBase = row.energyBase,
            energyTotal = row.energyTotal,


            stagnationActive = row.stagnationActive,
            stagnationBoost = row.stagnationBoost,
            stagnationAccel = row.stagnationAccel,
            stagnationAccelLimit = row.stagnationAccelLimit,

            rawDose = row.rawDose,
            iobFactor = row.iobFactor,
            normalDose = row.normalDose,

            earlyStage = row.earlyStage,
            earlyConfidence = row.earlyConfidence,
            earlyTargetU = row.earlyTargetU,

            mealState = row.mealState,
            commitFraction = row.commitFraction,
            minutesSinceCommit = row.minutesSinceCommit,

            peakState = row.peakState,
            predictedPeak = row.predictedPeak,
            peakIobBoost = row.peakIobBoost,
            effectiveIobRatio = row.effectiveIobRatio,
            peakBand = row.peakBand,
            peakMaxSlope = row.peakMaxSlope,
            peakMomentum = row.peakMomentum,
            peakRiseSinceStart = row.peakRiseSinceStart,
            peakEpisodeActive = row.peakEpisodeActive,

            suppressForPeak = row.suppressForPeak,
            absorptionActive = row.absorptionActive,
            reentrySignal = row.reentrySignal,
            decisionReason = row.decisionReason,

            heightIntent = row.heightIntent,

            pred60 = row.pred60,
            rescueState = row.rescueState,
            rescueConfidence = row.rescueConfidence,
            rescueReason = row.rescueReason,

            profielNaam = row.profielNaam,
            mealDetectSpeed = row.mealDetectSpeed,
            correctionStyle = row.correctionStyle,
            doseDistributionStyle = row.doseDistributionStyle,

            finalDose = row.finalDose,
            commandedDose = row.commandedDose,
            deliveredTotal = row.deliveredTotal,
            bolus = row.bolus,
            basalRate = row.basalRate,

            reserveU = row.reserveU,
            reserveAction = row.reserveAction,
            reserveDeltaU = row.reserveDeltaU,
            reserveAgeMin = row.reserveAgeMin,

            shouldDeliver = row.shouldDeliver
        )
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

        heightIntent: String,

        // rescue
        pred60: Double,
        rescueState: String,
        rescueConfidence: Double,
        rescueReason: String,

        profielNaam: String,
        mealDetectSpeed: String,
        correctionStyle: String,
        doseDistributionStyle: String,

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
                heightIntent,

                // rescue
                bg1(pred60),
                rescueState,
                t2(rescueConfidence),
                rescueReason.replace(SEP, ","),

                profielNaam,
                mealDetectSpeed,
                correctionStyle,
                doseDistributionStyle,



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
