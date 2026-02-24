package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.*
import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.plugins.aps.openAPSFCL.vnext.meal.PreBolusController
import app.aaps.plugins.aps.openAPSFCL.vnext.ui.BgCurveAnalyzer
import app.aaps.plugins.aps.openAPSFCL.vnext.ui.BgCurveSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.ui.CurveAnalysisFormatter

private const val UI_EPISODES_TO_SHOW = 5

data class FclUiSnapshot(
    val bgNow: Double,
    val iob: Double,
    val delta5m: Double?,
    val slopeHr: Double?,
    val predictedPeak: Double?
)


class FCLvNextStatusFormatter(
    private val prefs: Preferences,
    private val mealIntentRepository: MealIntentRepository,
    private val preBolusController: PreBolusController
){


    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }
    private fun profileLabel(value: String): String =
        when (value) {
            "VERY_STRICT" -> "\uD83D\uDEE1\uFE0F Zeer voorzichtig"
            "STRICT"      -> "\uD83E\uDDEF Voorzichtig"
            "BALANCED"    -> "⚖\uFE0F Gebalanceerd"
            "AGGRESSIVE"   -> "\uD83D\uDE80 Actief"
            "VERY_AGGRESSIVE"  -> "\uD83D\uDD25 Zeer actief"
            else          -> value
        }

    private fun mealDetectLabel(value: String): String =
        when (value) {
            "VERY_SLOW"  -> "\uD83D\uDC22 Zeer laat"
            "SLOW"       -> "\uD83D\uDC0C Laat"
            "MODERATE"   -> "⚖\uFE0F Normaal"
            "FAST"       -> "⚡ Snel"
            "VERY_FAST" -> "\uD83D\uDEA8 Zeer snel"
            else         -> value
        }
    private fun mealLabel(value: String): String =
        when (value) {
            "VERY_CONSERVATIVE"  -> "\uD83D\uDED1 Zeer voorzichtig"
            "CONSERVATIVE"       -> "\uD83D\uDC22 Voorzichtig"
            "BALANCED"   -> "⚖\uFE0F Gebalanceerd"
            "ANTICIPATORYT"       -> "⚡ Anticiperend"
            "AGGRESSIVE" -> "\uD83D\uDE80 Agressief"
            else         -> value
        }

    private fun correctionStyleLabel(value: String): String =
        when (value) {
            "VERY_CAUTIOUS" -> "\uD83D\uDED1 Zeer terughoudend"
            "CAUTIOUS"      -> "\uD83E\uDDEF Voorzichtig"
            "NORMAL"        -> "⚖\uFE0F Normaal"
            "PERSISTENT"    -> "\uD83D\uDD01 Vasthoudend"
            "VERY_PERSISTENT" -> "\uD83D\uDD02 Zeer vasthoudend"
            else            -> value
        }

    private fun doseDistributionLabel(value: String): String =
        when (value) {
            "VERY_SMOOTH"        -> "\uD83C\uDF0A Ultra smooth"
            "SMOOTH"      -> "\uD83E\uDEE7 Smooth"
            "BALANCED"        -> "⚖\uFE0F Balanced"
            "PULSED" -> "\uD83D\uDD28 Pulsed"
            "VERY_PULSED" -> "⚡ Ultra pulsed"
            else            -> value
        }

    private fun mealIntentLabel(type: MealIntentType): String =
        when (type) {
            MealIntentType.SMALL  -> "Kleine maaltijd"
            MealIntentType.NORMAL -> "Normale maaltijd"
            MealIntentType.LARGE  -> "Grote maaltijd"
            MealIntentType.SNACK  -> "Snack / Borrel"
            else                  -> "—"
        }




    private fun extractProfileAdviceLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 14 && t.substring(0, 14) == "PROFILE ADVICE:") {
                return t
            }
        }
        return null
    }

    private fun extractProfileReasonLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 15 && t.substring(0, 15) == "PROFILE REASON:") {
                return t
            }
        }
        return null
    }

    private fun extractPersistLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val out = ArrayList<String>()
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 7 && t.substring(0, 7) == "PERSIST") {
                out.add(t)
            }
        }
        return out
    }

    private fun buildMealIntentBlock(): String? {
        val now = DateTime.now()

        val snapshot = preBolusController.uiSnapshot(now)
            ?: return null

        return """
🍽️ MAALTIJD-INTENT
─────────────────────
• Type        : ${mealIntentLabel(snapshot.mealType)}
• Status      : ${if (snapshot.remainingU > 0.0) "Bolus loopt nog" else "Bolus afgegeven"}
• Pre-bolus   : ${"%.2f".format(snapshot.totalU)} U
• Gegeven     : ${"%.2f".format(snapshot.deliveredU)} U
• Resterend   : ${"%.2f".format(snapshot.remainingU)} U
• Gestart     : ${snapshot.minutesSinceArmed} min geleden
• Geldig tot  : ${DateTime(snapshot.validUntil).toString("HH:mm")}
  (nog ${snapshot.minutesRemaining} min)
• Verval      : ${"%.2f".format(snapshot.decayFactor)}

""".trimIndent()
    }


    /**
     * Maak statusText compacter:
     * - toont eerst profiel + learning advice (als aanwezig)
     * - daarna eventueel de rest van statusText (optioneel, compact)
     */
    private val curveAnalyzer = BgCurveAnalyzer()

    private fun buildFclBlock(
        advice: FCLvNextAdvice?,
        ui: FclUiSnapshot,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean
    ): String {

        if (advice == null) return "Geen FCL advies"

        val sb = StringBuilder()
        sb.append("🧠 FCL vNext\n")
        sb.append("─────────────────────\n")

        // Basis gegevens
        sb.append("📈 Situatie\n")
        sb.append("─────────────────────\n")
        sb.append("• Glucose: ${"%.1f".format(ui.bgNow)} mmol/L\n")
        sb.append("• IOB: ${"%.2f".format(ui.iob)} U\n")

        ui.delta5m?.let {
            sb.append("• Verandering (5m): ${"%.2f".format(it)} mmol/L\n")
        }

        // ⭐ KERN: Curve analyse via aparte module
        val snapshot = BgCurveSnapshot(
            bgNow = ui.bgNow,
            iob = ui.iob,
            slope = ui.slopeHr,
            delta5m = ui.delta5m,
            acceleration = advice.secondDerivative,
            predictedPeak = ui.predictedPeak
        )

        val analysis = curveAnalyzer.analyze(snapshot)
        sb.append(CurveAnalysisFormatter.formatForStatus(analysis))

// ─────────────────────────────
// 🔎 FCL core status (belangrijker dan curve)
// ─────────────────────────────

        advice.peakState?.let { state ->
            val uitleg = when (state) {
                "IDLE" -> "Geen actieve stijging"
                "WATCHING" -> "Sterke stijging actief"
                "CONFIRMED" -> "Piek bevestigd – afremming verwacht"
                else -> state
            }
            sb.append("\n")
            sb.append("• FCL piekstatus: $uitleg\n")
        }

        advice.predictedPeak?.let {
            sb.append("• Verwachte FCL-piek: ${"%.1f".format(it)} mmol/L\n")
        }

        sb.append("\n")

        // Advies sectie (bestaande logica, verkort)
        sb.append("💉 Advies\n")
        sb.append("─────────────────────\n")
        if (!shouldDeliver || (bolusAmount == 0.0 && basalRate == 0.0)) {
            sb.append("• Geen extra insuline nodig\n")
        } else {
            val total = bolusAmount + (basalRate * (5.0 / 60.0))
            sb.append("• Extra insuline nu: ${"%.2f".format(total)} U\n")
        }

        sb.append("\n⏳ Timing\n")
        sb.append("─────────────────────\n")
        sb.append(if (shouldDeliver) "• Toediening nu\n" else "• Geen toediening\n")

        return sb.toString().trimEnd()
    }



    private fun humanLearningStatus(status: SnapshotStatus): String =
        when (status) {
            SnapshotStatus.INIT ->
                "⏳ Initialiseren"
            SnapshotStatus.OBSERVING ->
                "👀 Observerend"
            SnapshotStatus.SIGNAL_PRESENT ->
                "🧠 Lerend"
        }




    private fun minutesBetween(a: DateTime, b: DateTime): Long =
        (b.millis - a.millis) / 60000

    private fun buildDeliveryGateBlock(
        snapshot: FCLvNextObsSnapshot?
    ): String {

        val gate = snapshot?.deliveryGateStatus
            ?: return """
💉 INSULINE CONTROLE
─────────────────────
Nog geen delivery-analyse
""".trimIndent()

        val status =
            if (gate.confidence >= 0.9) "🟢 OK"
            else if (gate.confidence >= 0.6) "🟡 Onzeker"
            else "🔴 Onbetrouwbaar"

        return """
💉 INSULINE CONTROLE
─────────────────────
• Status      : $status
• Confidence  : ${"%.2f".format(gate.confidence)}
${gate.reason?.let { "• Opmerking   : $it" } ?: ""}
""".trimIndent()
    }



    private data class AxisRecommendation(
        val axis: Axis,
        val current: String,
        val recommended: String
    )
    private fun buildAxisRecommendation(
        axis: AxisSnapshot
    ): AxisRecommendation? {

        if (axis.status == AxisStatus.NO_DIRECTION) return null
        if (axis.dominantOutcome == null) return null
        if (axis.dominantConfidence < 0.45) return null
        if (axis.episodesSeen < 5) return null

        return when (axis.axis) {

            Axis.TIMING -> {
                val current = prefs.get(StringKey.fcl_vnext_meal_detect_speed)

                val recommended = when (axis.dominantOutcome.name) {
                    "LATE" -> nextFaster(current)
                    "EARLY" -> nextSlower(current)
                    else -> null
                }

                recommended?.let {
                    AxisRecommendation(Axis.TIMING, current, it)
                }
            }

            Axis.HEIGHT -> {
                val current = prefs.get(StringKey.fcl_vnext_profile)

                val recommended = when (axis.dominantOutcome.name) {
                    "TOO_STRONG" -> nextMoreConservative(current)
                    "TOO_WEAK" -> nextMoreAggressive(current)
                    else -> null
                }

                recommended?.let {
                    AxisRecommendation(Axis.HEIGHT, current, it)
                }
            }

            Axis.PERSISTENCE -> {
                val current = prefs.get(StringKey.fcl_vnext_correction_style)

                val recommended = when (axis.dominantOutcome.name) {
                    "TOO_SHORT" -> nextMorePersistent(current)
                    "TOO_LONG" -> nextMoreCautious(current)
                    else -> null
                }

                recommended?.let {
                    AxisRecommendation(Axis.PERSISTENCE, current, it)
                }
            }

            else -> null
        }
    }
    private fun nextFaster(current: String) = when (current) {
        "VERY_SLOW" -> "SLOW"
        "SLOW" -> "MODERATE"
        "MODERATE" -> "FAST"
        "FAST" -> "VERY_FAST"
        else -> null
    }

    private fun nextSlower(current: String) = when (current) {
        "VERY_FAST" -> "FAST"
        "FAST" -> "MODERATE"
        "MODERATE" -> "SLOW"
        "SLOW" -> "VERY_SLOW"
        else -> null
    }

    private fun nextMoreConservative(current: String) = when (current) {
        "VERY_AGGRESSIVE" -> "AGGRESSIVE"
        "AGGRESSIVE" -> "BALANCED"
        "BALANCED" -> "STRICT"
        "STRICT" -> "VERY_STRICT"
        else -> null
    }

    private fun nextMoreAggressive(current: String) = when (current) {
        "VERY_STRICT" -> "STRICT"
        "STRICT" -> "BALANCED"
        "BALANCED" -> "AGGRESSIVE"
        "AGGRESSIVE" -> "VERY_AGGRESSIVE"
        else -> null
    }

    private fun nextMorePersistent(current: String) = when (current) {
        "VERY_CAUTIOUS" -> "CAUTIOUS"
        "CAUTIOUS" -> "NORMAL"
        "NORMAL" -> "PERSISTENT"
        "PERSISTENT" -> "VERY_PERSISTENT"
        else -> null
    }

    private fun nextMoreCautious(current: String) = when (current) {
        "VERY_PERSISTENT" -> "PERSISTENT"
        "PERSISTENT" -> "NORMAL"
        "NORMAL" -> "CAUTIOUS"
        "CAUTIOUS" -> "VERY_CAUTIOUS"
        else -> null
    }


    fun buildLearningSnapshotBlock(
        snapshot: FCLvNextObsSnapshot?
    ): String {

        if (snapshot == null) {
            return """
📚 LEARNING
─────────────────────
Nog geen observaties beschikbaar
""".trimIndent()
        }

        val sb = StringBuilder()

        sb.append("📚 LEARNING\n")
        sb.append("─────────────────────\n")
        sb.append("• Status : ${humanLearningStatus(snapshot.status)}\n")
        sb.append("• Episodes : ${snapshot.totalEpisodes}\n")

        if (snapshot.activeEpisode && snapshot.activeEpisodeStartedAt != null) {
            val mins = minutesBetween(snapshot.activeEpisodeStartedAt, snapshot.createdAt)
            sb.append("• Actieve episode : ${mins} min\n")
        }

        // ─────────────────────────────
        // COMPACT ADVIES BLOK
        // ─────────────────────────────

        val activeSignals = snapshot.axes
            .filter { it.dominantOutcome != null }
            .sortedByDescending { it.dominantConfidence }

        if (activeSignals.isNotEmpty()) {
            sb.append("\n📊 Signaalopbouw\n")
            sb.append("─────────────────────\n")

            activeSignals.take(3).forEach { axis ->
                val pct = (axis.dominantConfidence * 100).toInt()
                val outcome =
                    axis.dominantOutcome?.name ?: "—"

                sb.append(
                    "${axis.axis.name.padEnd(12)} : $outcome ($pct%)\n"
                )
            }
        }


        val recommendations =
            snapshot.axes.mapNotNull { buildAxisRecommendation(it) }

        if (recommendations.isNotEmpty()) {

            sb.append("\n📌 ADVIES\n")
            sb.append("─────────────────────\n")

            recommendations.forEach { rec ->

                val axisSnapshot =
                    snapshot.axes.firstOrNull { it.axis == rec.axis }

                val confidence =
                    axisSnapshot?.dominantConfidence ?: 0.0

                val confidencePct = (confidence * 100).toInt()

                val strengthLabel = when {
                    confidence >= 0.75 -> "🟢 Sterk"
                    confidence >= 0.60 -> "🟡 Matig"
                    confidence >= 0.45 -> "🟠 Zwak"
                    else -> "⚪ Onzeker"
                }


                val outcomeLabel =
                    when (axisSnapshot?.dominantOutcome?.name) {
                        "TOO_STRONG" -> "Te sterk"
                        "TOO_WEAK" -> "Te zwak"
                        "LATE" -> "Te laat"
                        "LATE_PEAK_INTERVENTION" -> "Te laat (rond piek)"
                        "EARLY" -> "Te vroeg"
                        "TOO_SHORT" -> "Te kort"
                        "TOO_LONG" -> "Te lang"
                        else -> axisSnapshot?.dominantOutcome?.name ?: "—"
                    }

                val currentHuman =
                    when (rec.axis) {
                        Axis.HEIGHT ->
                            profileLabel(rec.current)
                        Axis.TIMING ->
                            mealDetectLabel(rec.current)
                        Axis.PERSISTENCE ->
                            correctionStyleLabel(rec.current)
                        else ->
                            rec.current
                    }

                val newHuman =
                    when (rec.axis) {
                        Axis.HEIGHT ->
                            profileLabel(rec.recommended)
                        Axis.TIMING ->
                            mealDetectLabel(rec.recommended)
                        Axis.PERSISTENCE ->
                            correctionStyleLabel(rec.recommended)
                        else ->
                            rec.recommended
                    }

                sb.append(
                    "${rec.axis.name.padEnd(12)} : " +
                        "$outcomeLabel ($confidencePct%) " +
                        "$strengthLabel\n" +
                        "                 $currentHuman → $newHuman\n"
                )

            }
        }


        return sb.toString().trimEnd()
    }





    fun buildStatus(
        isNight: Boolean,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        ui: FclUiSnapshot,
        activityLog: String?,
        resistanceLog: String?,
        metricsText: String?,
        learningSnapshot: FCLvNextObsSnapshot?
    ): String {

        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${DateTime.now().toString("HH:mm:ss")}
• Advies actief: ${if (shouldDeliver) "JA" else "NEE"}
• Bolus: ${"%.2f".format(bolusAmount)} U
• Basaal: ${"%.2f".format(basalRate)} U/h

🧪 LAATSTE DOSIS
─────────────────────
${formatDeliveryHistory(advice?.let { deliveryHistory.toList() })}
""".trimIndent()

        val fclCore = buildFclBlock(
            advice = advice,
            ui = ui,
            bolusAmount = bolusAmount,
            basalRate = basalRate,
            shouldDeliver = shouldDeliver
        )


        val mealIntentBlock = buildMealIntentBlock()


        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
${activityLog ?: "Geen activiteitdata"}
""".trimIndent()

        val resistanceStatus = """
🧬 AUTO-SENS
─────────────────────
${resistanceLog ?: "Geen resistentie-log"}
""".trimIndent()

        val metricsStatus = """
            
📊 GLUCOSE STATISTIEKEN
─────────────────────
${metricsText ?: "Nog geen data"}
""".trimIndent()


        return """
════════════════════════
 🧠 FCL meal V4 v2.0.1
 
════════════════════════
• Height (sterkte)     : ${profileLabel(prefs.get(StringKey.fcl_vnext_profile))}
• Timing (reactietijd) : ${mealDetectLabel(prefs.get(StringKey.fcl_vnext_meal_detect_speed))}
• Maaltijd behandeling : ${mealLabel(prefs.get(StringKey.fcl_vnext_meal_handling_style))}
• Persistentie        : ${correctionStyleLabel(prefs.get(StringKey.fcl_vnext_correction_style))}
• Insulineverdeling    : ${doseDistributionLabel(prefs.get(StringKey.fcl_vnext_dose_distribution_style))}


$coreStatus

${mealIntentBlock ?: ""}

$fclCore

$activityStatus

$resistanceStatus

${buildLearningSnapshotBlock(learningSnapshot)}

${buildDeliveryGateBlock(learningSnapshot)}

$metricsStatus
""".trimIndent()
    }
}
