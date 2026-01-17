package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.learning.FCLvNextLearningEpisodeManager.EpisodeOutcome

class FCLvNextLearningAdvisor {
    // ─────────────────────────────────────────────
    // Autonomy phase control
    // ─────────────────────────────────────────────
    private var currentPhase: LearningPhase = LearningPhase.TIMING_ONLY
    private var heightEnableEvidence: Int = 0

    // ─────────────────────────────────────────────
    // Metrics-based profile hysteresis state
    // ─────────────────────────────────────────────

    private var lastMetricsProfile: FCLvNextProfile? = null
    private var metricsEvidenceCount: Int = 0
    private var lastProfileChangeAt: Long = 0L

    private companion object {
        const val METRICS_COOLDOWN_MS = 6 * 60 * 60 * 1000L   // 6 uur
        const val MIN_METRICS_EVIDENCE = 3

        // DEBUG: zet op true om fase NIET automatisch naar TIMING_AND_HEIGHT te laten gaan
        const val FREEZE_PHASE_TO_TIMING_ONLY = false
    }


    private var lastMetrics: LearningMetricsSnapshot? = null

    private val stats = ParameterLearningStats()

    // ─────────────────────────────────────────────
    // Profile learning (fase 2: adviserend)
    // ─────────────────────────────────────────────

    private var lastProfileAdvice: FCLvNextProfileAdvice? = null
    private var profileEvidenceCount: Int = 0

    fun getProfileAdvice(): FCLvNextProfileAdvice? = lastProfileAdvice

    fun getLearningPhase(): LearningPhase = currentPhase

    fun onMetricsSnapshot(snapshot: LearningMetricsSnapshot) {
        lastMetrics = snapshot

        runCatching {
            if (!snapshot.dataQualityOk) return

            var scoreAggressive = 0.0
            var scoreStrict = 0.0

            // SAFETY
            if (snapshot.tbr24 > 4.0 || snapshot.tbt24 > 6.0) {
                scoreStrict += 1.2
            }
            if (snapshot.isNight && snapshot.tbr24 > 2.0) {
                scoreStrict += 0.4
            }

            // UNDER-TREATMENT
            if (snapshot.tar24 > 20.0 && snapshot.tir24 < 70.0) {
                scoreAggressive += 1.0
            }
            if (snapshot.tar7d > 18.0) {
                scoreAggressive += 0.6
            }

            // GOOD CONTROL → BALANCED
            val recommended = when {
                snapshot.tir24 >= 75.0 &&
                    snapshot.tbr24 < 2.0 &&
                    snapshot.tar24 < 20.0 ->
                    FCLvNextProfile.BALANCED

                scoreStrict >= scoreAggressive + 0.6 ->
                    FCLvNextProfile.STRICT

                scoreAggressive >= scoreStrict + 0.6 ->
                    FCLvNextProfile.AGGRESSIVE

                else ->
                    FCLvNextProfile.BALANCED
            }

            val now = snapshot.timestampMillis

            if (recommended == lastMetricsProfile) {
                metricsEvidenceCount++
            } else {
                lastMetricsProfile = recommended
                metricsEvidenceCount = 1
            }

            val cooldownPassed = now - lastProfileChangeAt > METRICS_COOLDOWN_MS

            if (metricsEvidenceCount >= MIN_METRICS_EVIDENCE && cooldownPassed) {
                lastProfileChangeAt = now

                lastProfileAdvice = FCLvNextProfileAdvice(
                    recommended = recommended,
                    confidence = 0.6,
                    reason =
                        "metrics: TIR24=${snapshot.tir24.toInt()}% " +
                            "TAR24=${snapshot.tar24.toInt()}% " +
                            "TBR24=${snapshot.tbr24.toInt()}% " +
                            "night=${snapshot.isNight}",
                    evidenceCount = metricsEvidenceCount
                )
            }
        }

        // ─────────────────────────────────────────────
        // Autonomy gate: allow HEIGHT learning?
        // ─────────────────────────────────────────────
        if (!FREEZE_PHASE_TO_TIMING_ONLY && currentPhase == LearningPhase.TIMING_ONLY) {

            val safeForHeight =
                snapshot.dataQualityOk &&
                    snapshot.tbr24 < 2.0 &&
                    snapshot.tir7d >= 70.0 &&
                    (!snapshot.isNight || snapshot.tbr24 < 1.0)

            if (safeForHeight) {
                heightEnableEvidence++
            } else {
                heightEnableEvidence = 0
            }

            // vereis meerdere stabiele snapshots
            if (heightEnableEvidence >= 3) {
                currentPhase = LearningPhase.TIMING_AND_HEIGHT
            }
        }

    }

    fun onEpisodeOutcome(
        outcome: EpisodeOutcome,
        isNight: Boolean,
        peakBand: Int,
        rescueConfirmed: Boolean,
        mealActive: Boolean
    ) {
        val hints = OutcomeToHintMapper.map(outcome, isNight, peakBand, rescueConfirmed, mealActive)

        for (h in hints) {
            // whitelist/spec gate (future-safe)
            val spec = LearningParameterSpecs.specs[h.parameter] ?: continue
            if (isNight && !spec.nightAllowed) continue

            stats.update(h.parameter, h.direction, isNight)
        }

        // ─────────────────────────────────────────────
        // Profile advice (v1 – deterministic & explainable)
        // ─────────────────────────────────────────────

        var scoreAggressive = 0.0
        var scoreStrict = 0.0
        var evidence = 0

        // 1️⃣ Outcome-based signal
        when (outcome) {
            EpisodeOutcome.TOO_LATE -> {
                scoreAggressive += 1.0
                evidence++
            }
            EpisodeOutcome.OVERSHOOT -> {
                // vaak: te laat begonnen → achteraf teveel
                scoreAggressive += 0.6
                evidence++
            }
            EpisodeOutcome.HYPO_RISK -> {
                scoreStrict += 1.0
                evidence++
            }
            EpisodeOutcome.TOO_STRONG -> {
                scoreStrict += 0.7
                evidence++
            }
            else -> {
                // GOOD_CONTROL, NO_ACTION_NEEDED → geen signaal
            }
        }

        // 2️⃣ Peak severity (alleen relevant bij maaltijd-context)
        if (mealActive && peakBand >= 15) {
            scoreAggressive += 0.8
            evidence++
        } else if (mealActive && peakBand >= 12) {
            scoreAggressive += 0.4
            evidence++
        }

        // 3️⃣ Rescue is extra safety-signaal
        if (rescueConfirmed) {
            scoreStrict += 0.6
            evidence++
        }

        // 4️⃣ Nacht-bias → iets conservatiever adviseren
        if (isNight) {
            scoreStrict += 0.2
        }

        // 5️⃣ Beslis aanbevolen profiel
        val recommendedProfile = when {
            scoreStrict >= scoreAggressive + 0.6 ->
                FCLvNextProfile.STRICT

            scoreAggressive >= scoreStrict + 0.6 ->
                FCLvNextProfile.AGGRESSIVE

            else ->
                FCLvNextProfile.BALANCED
        }

        // Confidence = verschil tussen scores
        val confidence =
            kotlin.math.abs(scoreAggressive - scoreStrict)
                .coerceIn(0.0, 1.0)

        // Verklarende reden (voor UI / status)
        val reason = buildString {
            append("scores: agg=${"%.2f".format(scoreAggressive)} ")
            append("strict=${"%.2f".format(scoreStrict)}. ")
            append("outcome=$outcome. ")
            if (mealActive) append("mealActive. ")
            if (peakBand > 0) append("peakBand=$peakBand. ")
            if (rescueConfirmed) append("rescueConfirmed. ")
            if (isNight) append("nightBias. ")
        }.trim()

        profileEvidenceCount =
            (profileEvidenceCount + evidence).coerceAtMost(50)

        lastProfileAdvice = FCLvNextProfileAdvice(
            recommended = recommendedProfile,
            confidence = confidence,
            reason = reason,
            evidenceCount = profileEvidenceCount
        )
    }

    fun getAdvice(isNight: Boolean): List<LearningAdvice> =
        stats.allAdvice(isNight)

    /**
     * UI-only learning advice.
     *
     * - Toont TIMING-adviezen eerder (lage confidence toegestaan)
     * - HEIGHT-adviezen alleen in TIMING_AND_HEIGHT fase
     * - Heeft GEEN invloed op dosing (adjuster gebruikt getAdvice())
     */
    fun getUiAdvice(isNight: Boolean): List<LearningAdvice> {

        val map =
            if (isNight) stats.exportNightStats()
            else stats.exportDayStats()

        val out = mutableListOf<LearningAdvice>()

        // ✅ toon ALLE parameters uit de specs, ook als ze nog geen stats hebben
        for ((param, spec) in LearningParameterSpecs.specs) {

            val snap = map[param] ?: LearningStatSnapshot(ema = 0.0, count = 0)

            val direction =
                when {
                    snap.ema > 0.05 -> +1
                    snap.ema < -0.05 -> -1
                    else -> 0
                }

            val confidence =
                kotlin.math.abs(snap.ema).coerceIn(0.0, 1.0)

            out += LearningAdvice(
                parameter = param,
                direction = direction,
                confidence = confidence,
                evidenceCount = snap.count,
                isNight = isNight
            )
        }

        return out
    }



    fun getLearningStatus(isNight: Boolean): String {
        val sb = StringBuilder()

        sb.append("🧠 Learning status\n")
        sb.append("─────────────────────\n")

        // parameters
        val paramAdvice = stats.allAdvice(isNight)
        if (paramAdvice.isEmpty()) {
            sb.append("• Parameters: nog geen stabiele adviezen\n")
        } else {
            sb.append("• Parameters:\n")
            paramAdvice
                .sortedByDescending { it.confidence }
                .forEach {
                    val dir = if (it.direction > 0) "↑" else "↓"
                    sb.append(
                        "  - ${it.parameter} $dir " +
                            "conf=${"%.2f".format(it.confidence)} " +
                            "n=${it.evidenceCount}\n"
                    )
                }
        }

        // profiel
        val p = lastProfileAdvice
        if (p == null) {
            sb.append("• Profiel: nog geen advies\n")
        } else {
            sb.append(
                "• Profiel: ${p.recommended} " +
                    "conf=${"%.2f".format(p.confidence)} " +
                    "n=${p.evidenceCount}\n"
            )
        }

        return sb.toString().trimEnd()
    }

    // ─────────────────────────────────────────────
// Persistence support
// ─────────────────────────────────────────────

    fun exportSnapshot(): FCLvNextLearningSnapshot =
        FCLvNextLearningSnapshot(
            schemaVersion = 1,
            dayStats = stats.exportDayStats(),
            nightStats = stats.exportNightStats(),
            profileAdvice = lastProfileAdvice,
            profileEvidenceCount = profileEvidenceCount,
            learningPhase = currentPhase
        )


    fun importSnapshot(snapshot: FCLvNextLearningSnapshot) {
        if (snapshot.schemaVersion != 1) return

        stats.importDayStats(snapshot.dayStats)
        stats.importNightStats(snapshot.nightStats)

        lastProfileAdvice = snapshot.profileAdvice
        profileEvidenceCount = snapshot.profileEvidenceCount

        currentPhase =
            if (FREEZE_PHASE_TO_TIMING_ONLY) LearningPhase.TIMING_ONLY
            else snapshot.learningPhase
    }


}
