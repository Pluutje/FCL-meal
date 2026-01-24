package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

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
    private var lastFinishedEpisode: Episode? = null


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
        deliveryConfidence: Double
    ): FCLvNextObsAdviceBundle? {

        lastDeliveryConfidence = deliveryConfidence.coerceIn(0.0, 1.0)
        rebuildSnapshot(now)

        val event = episodeTracker.onFiveMinuteTick(
            now = now,
            isNight = isNight,

            peakActive = peakActive,
            mealSignalActive = mealSignalActive,
            prePeakCommitWindow = prePeakCommitWindow,

            rescueConfirmed = rescueConfirmed,
            downtrendLocked = downtrendLocked,

            bgMmol = bgMmol,
            targetMmol = targetMmol,
            currentIob = currentIob,

            slope = slope,
            acceleration = acceleration,
            deltaToTarget = deltaToTarget,
            consistency = consistency
        )


        // Alleen iets doen bij einde van episode
        if (event !is EpisodeEvent.Finished) return null

        val episode = event.episode

        lastFinishedEpisode = episode

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

    private fun rebuildSnapshot(now: DateTime) {
        val axisSnapshots =
            Axis.entries.map { axis ->
                confidenceAccumulator.buildAxisSnapshot(
                    now = now,
                    axis = axis,
                    emitThreshold = confidenceAccumulator.emitThreshold()
                )
            }

        val snapshotStatus =
            if (axisSnapshots.any { it.status == AxisStatus.STRUCTURAL_SIGNAL })
                SnapshotStatus.SIGNAL_PRESENT
            else
                SnapshotStatus.OBSERVING

        val last = lastFinishedEpisode

        lastSnapshot =
            FCLvNextObsSnapshot(
                createdAt = now,
                totalEpisodes = episodeTracker.totalEpisodes(),
                activeEpisode = episodeTracker.hasActiveEpisode(),
                activeEpisodeStartedAt = episodeTracker.activeEpisodeStart(),
                deliveryConfidence = lastDeliveryConfidence,
                status = snapshotStatus,
                axes = axisSnapshots,
                lastEpisodeStart = last?.startTime,
                lastEpisodeEnd = last?.endTime
            )
    }


    fun getCurrentSnapshot(): FCLvNextObsSnapshot? = lastSnapshot

    fun attachLearningStore(store: FCLvNextObsLearningStore) {
        this.learningStore = store
    }

    fun getLastFinishedEpisode(): Episode? = lastFinishedEpisode

}
