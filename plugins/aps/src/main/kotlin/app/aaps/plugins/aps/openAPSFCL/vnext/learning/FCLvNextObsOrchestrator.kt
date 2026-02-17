package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey

/**
 * FCLvNextObsOrchestrator
 *
 * Verbindt:
 * - EpisodeTracker (stap 1)
 * - EpisodeSummarizer (stap 3A)
 * - AxisScorer (stap 3B)
 * - ConfidenceAccumulator (stap 4)
 * - AdviceEmitter (stap 5)
 *
 * Deze klasse is de ENIGE die je vanuit determineBasal hoeft aan te roepen.
 */
class FCLvNextObsOrchestrator(
    private val prefs: Preferences,
    private val episodeTracker: EpisodeTracker,
    private val summarizer: FCLvNextObsEpisodeSummarizer,
    private val axisScorer: FCLvNextObsAxisScorer,
    private val confidenceAccumulator: FCLvNextObsConfidenceAccumulator,
    private val adviceEmitter: FCLvNextObsAdviceEmitter,
) {

    /**
     * Deze methode roep je 1× per determineBasal cycle aan.
     *
     * @return eventueel een AdviceBundle (of null als er niets te melden is)
     */
    private var lastSnapshot: FCLvNextObsSnapshot? = null
    private var lastDeliveryConfidence: Double = 1.0
    private var learningStore: FCLvNextObsLearningStore? = null
//    private var lastFinishedEpisode: Episode? = null
    private val finishedEpisodes: ArrayDeque<Episode> = ArrayDeque()

    private var lastDeliveryGateStatus: DeliveryGateStatus? = null
    private val deliveryGate = FCLvNextObsInsulinDeliveryGate()

    private var lastProfile: String? = null
    private var lastMealDetect: String? = null
    private var lastCorrectionStyle: String? = null


    fun onFiveMinuteTick(
        now: DateTime,
        isNight: Boolean,
        peakActive: Boolean,
        mealSignalActive: Boolean,
        prePeakCommitWindow: Boolean,
        rescueConfirmed: Boolean,
        downtrendLocked: Boolean,

        bgMmol: Double,
        targetMmol: Double,
        currentIob: Double,

        slope: Double,
        acceleration: Double,
        deltaToTarget: Double,
        consistency: Double,

        predictedPeakAtStart: Double?,
        deliveryConfidence: Double,

        commandedU: Double,
        maxBolusU: Double,
        manualBolusDetected: Boolean
    ): FCLvNextObsAdviceBundle? {

        checkForSettingChanges()

        lastDeliveryConfidence = deliveryConfidence.coerceIn(0.0, 1.0)
        // ─────────────────────────────────────────────
        // 💉 Insulin delivery gate (UI-only observatie)
        // ─────────────────────────────────────────────
        val gateCheck = deliveryGate.recordCycle(
            now = now,
            commandedU = commandedU,
            currentIob = currentIob,
            phase = "DELIVER"
        )

        lastDeliveryGateStatus =
            DeliveryGateStatus(
                confidence = gateCheck.confidenceMultiplier,
                ok = gateCheck.ok,
                reason = gateCheck.reason
            )




        // ─────────────────────────────────────────────
// Bolus hook -> forceMealConfirm
// Start episode zodra FCL daadwerkelijk doseert boven X% van maxBolus
// ─────────────────────────────────────────────
        val pct = BOLUS_TRIGGER_PCT
        val bolusTriggerThresholdU = (maxBolusU * pct).coerceAtLeast(MIN_TRIGGER_U)
        val forceMealConfirm =
            commandedU.isFinite() &&
                maxBolusU.isFinite() &&
                commandedU >= bolusTriggerThresholdU


        val event = episodeTracker.onFiveMinuteTick(
            now = now,
            isNight = isNight,

            mealIntentActive = mealSignalActive,

            bgMmol = bgMmol,
            currentIob = currentIob,

            commandedU = commandedU,
            maxBolusU = maxBolusU,

            consistency = consistency
        )



        rebuildSnapshot(now)


        // Alleen iets doen bij einde van episode
        if (event !is EpisodeEvent.Finished) return null

        val episode = event.episode

        val endReason =
            when {
                episode.excluded && episode.exclusionReason != null ->
                    "Gestopt: ${episode.exclusionReason}"

                episode.endTime != null ->
                    "Gestopt: insuline-effect voorbij"

                else ->
                    null
            }


        finishedEpisodes.addFirst(episode)
        while (finishedEpisodes.size > MAX_STORED_EPISODES) {
            finishedEpisodes.removeLast()
        }

        // Excluded episodes tellen niet mee voor learning
        if (episode.excluded) return null

        // 1️⃣ Samenvatten (feiten)
        val summary =
            summarizer.summarize(
                episode = episode,
                predictedPeakAtStart = predictedPeakAtStart
            )

        // 2️⃣ Scoren langs assen
        val observations =
            axisScorer.score(
                episode = episode,
                summary = summary
            )

        // 3️⃣ Confidence opbouwen
        confidenceAccumulator.ingestEpisode(
            now = now,
            isNight = isNight,
            observations = observations,
            deliveryConfidence = deliveryConfidence
        )

        // 💾 Persist learning state (crash-safe)
        try {
            learningStore?.save(confidenceAccumulator, now)
        } catch (_: Throwable) {
            // learning mag NOOIT crashen
        }

        // 4️⃣ Adviezen genereren (indien structureel)
        val topSignals =
            confidenceAccumulator.getTopSignals(now)

        if (topSignals.isEmpty()) {
            // debug bundle teruggeven zodat je ziet dat pipeline draait
            val snap = confidenceAccumulator.buildSnapshot(now)
            val debug = buildString {
                append("[OBS] Episode #${episode.id} finished. Buckets:\n")
                for ((axis, list) in snap.perAxis) {
                    val top = list.firstOrNull()
                    if (top != null) {
                        append(" - $axis: top=${top.outcome} conf=${"%.2f".format(top.confidence)} n=${top.supportCount}\n")
                    } else {
                        append(" - $axis: (no evidence)\n")
                    }
                }
            }.trim()

            return FCLvNextObsAdviceBundle(
                createdAt = now,
                advices = emptyList(),
                debugSummary = debug
            )
        }



        return adviceEmitter.emit(
            now = now,
            topSignals = topSignals
        )
    }

    private fun checkForSettingChanges() {

        val currentProfile =
            prefs.get(StringKey.fcl_vnext_profile)

        val currentMealDetect =
            prefs.get(StringKey.fcl_vnext_meal_detect_speed)

        val currentCorrection =
            prefs.get(StringKey.fcl_vnext_correction_style)

        if (lastProfile == null) {
            lastProfile = currentProfile
            lastMealDetect = currentMealDetect
            lastCorrectionStyle = currentCorrection
            return
        }

        if (currentProfile != lastProfile) {
            resetAxis(Axis.HEIGHT)  // ✅ Gebruik de nieuwe resetAxis met save
            lastProfile = currentProfile
        }

        if (currentMealDetect != lastMealDetect) {
            resetAxis(Axis.TIMING)
            lastMealDetect = currentMealDetect
        }

        if (currentCorrection != lastCorrectionStyle) {
            resetAxis(Axis.PERSISTENCE)
            lastCorrectionStyle = currentCorrection
        }
    }


    private fun rebuildSnapshot(now: DateTime) {
        val axisSnapshots =
            Axis.entries.map { axis ->
                confidenceAccumulator.buildAxisSnapshot(
                    now = now,
                    axis = axis,
                    emitThreshold = confidenceAccumulator.emitThreshold()
                )
            }

        val total = episodeTracker.totalEpisodes()
        val totalEvidence = axisSnapshots.sumOf { it.episodesSeen }

        val snapshotStatus =
            when {
                totalEvidence == 0 -> SnapshotStatus.INIT
                axisSnapshots.any { it.status == AxisStatus.STRUCTURAL_SIGNAL } ->
                    SnapshotStatus.SIGNAL_PRESENT
                else ->
                    SnapshotStatus.OBSERVING
            }



        val last = finishedEpisodes.firstOrNull()

        val lastEndReason =
            when {
                last?.excluded == true && last.exclusionReason != null ->
                    "Gestopt: ${last.exclusionReason}"

                last?.endTime != null ->
                    "Gestopt: insuline-effect voorbij"

                else -> null
            }

        lastSnapshot =
            FCLvNextObsSnapshot(
                createdAt = now,
                totalEpisodes = total,
                activeEpisode = episodeTracker.hasActiveEpisode(),
                activeEpisodeStartedAt = episodeTracker.activeEpisodeStart(),
                deliveryConfidence = lastDeliveryConfidence,
                status = snapshotStatus,
                axes = axisSnapshots,

                recentEpisodes = finishedEpisodes.toList(),

                lastEpisodeStart = last?.startTime,
                lastEpisodeEnd = last?.endTime,
                lastEpisodeEndReason = lastEndReason,

                deliveryGateStatus = lastDeliveryGateStatus
            )

    }


    fun getCurrentSnapshot(): FCLvNextObsSnapshot? = lastSnapshot

    fun attachLearningStore(store: FCLvNextObsLearningStore) {
        this.learningStore = store
    }

    fun getLastFinishedEpisode(): Episode? = finishedEpisodes.firstOrNull()
    fun getRecentFinishedEpisodes(): List<Episode> = finishedEpisodes.toList()

    fun resetAxis(axis: Axis) {
        confidenceAccumulator.resetAxis(axis)

        // ✅ DIRECT persistent state overschrijven met lege evidence
        // Dit voorkomt dat oude data terugkomt bij volgende restore
        try {
            learningStore?.save(confidenceAccumulator, DateTime.now())
            android.util.Log.i("FCLvNextObs", "Reset and saved empty state for axis: $axis")
        } catch (_: Throwable) {
            // Fail-safe: logging mag niet crashen
            android.util.Log.e("FCLvNextObs", "Failed to save after reset for axis: $axis")
        }
    }


    companion object {
        // bv. 0.20 = start episode als commandedU >= 20% van maxBolus
        private const val BOLUS_TRIGGER_PCT = 0.2
        // safety: als maxBolus heel klein is, wil je niet op 0.01U triggeren
        private const val MIN_TRIGGER_U = 0.15
        private const val MAX_STORED_EPISODES = 7
    }


}
