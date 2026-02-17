package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import kotlin.math.max
import kotlin.math.min

class EpisodeTracker {

    // ─────────────────────────────
    // State
    // ─────────────────────────────
    private enum class State { IDLE, PENDING_RISE, ACTIVE }

    private var state: State = State.IDLE
    private var activeEpisode: Episode? = null
    private var nextId: Long = 1L

    // Laatste episode-einde (voor overlap-preventie)
    private var lastEpisodeEndedAt: DateTime? = null

    // End-detectie helpers
    private var iobBelowEndSince: DateTime? = null
    private var lastAnyInsulinAt: DateTime? = null

    // Pending rise/mealIntent start
    private var pendingStartAt: DateTime? = null
    private var pendingMealIntent: Boolean = false
    private var pendingTrigger: StartTrigger? = null

    // ─────────────────────────────
    // BG buffer (voor retro-start)
    // ─────────────────────────────
    private data class BgTick(
        val time: DateTime,
        val bg: Double
    )

    private val bgBuf: ArrayDeque<BgTick> = ArrayDeque()

    // ─────────────────────────────
    // Insuline buffer (recent history)
    // ─────────────────────────────
    private data class InsulinTick(
        val time: DateTime,
        val u: Double
    )

    private val insulinBuf: ArrayDeque<InsulinTick> = ArrayDeque()

    // ─────────────────────────────
    // Main entry (5-min tick)
    // ─────────────────────────────
    fun onFiveMinuteTick(
        now: DateTime,
        isNight: Boolean,
        mealIntentActive: Boolean,  // ✅ nieuw

        bgMmol: Double,
        currentIob: Double,

        commandedU: Double,
        maxBolusU: Double,

        consistency: Double
    ): EpisodeEvent? {

        // ─────────────────────────────
        // 1) Buffers bijwerken
        // ─────────────────────────────
        updateBgBuffer(now, bgMmol)
        updateInsulinBuffer(now, commandedU)

        val insulinThisTick =
            commandedU.isFinite() && commandedU > 0.0

        // ─────────────────────────────
        // START-detectie (insuline)
        // ─────────────────────────────
        val startThresholdU =
            max(MIN_TRIGGER_U, maxBolusU * START_MIN_BOOST_FRAC)

        val isStartDose =
            insulinThisTick &&
                commandedU >= startThresholdU &&
                commandedU >= ABSOLUTE_START_MIN_U

        val startAllowedByIob =
            currentIob <= START_IOB_MAX

        // ─────────────────────────────
        // RISE-detectie (glycemische fase)
        // ─────────────────────────────
        val riseDetected = detectRise(now)

        // ─────────────────────────────
        // State machine
        // ─────────────────────────────
        return when (state) {

            // ─────────────────────────
            // IDLE
            // ─────────────────────────
            State.IDLE -> {

                // 1) MealIntent of Rise → pending-start (wachten op insulin)
                if (mealIntentActive || riseDetected) {
                    state = State.PENDING_RISE
                    pendingStartAt = now
                    pendingMealIntent = mealIntentActive
                    pendingTrigger =
                        if (mealIntentActive) StartTrigger.MEAL_INTENT
                        else StartTrigger.RISE
                    return null
                }

                // 2) Direct start op meaningful insulin
                if (isStartDose && startAllowedByIob) {

                    val retro = findRetroStart(now) ?: now
                    val clamp = lastEpisodeEndedAt

                    val startAt =
                        if (clamp != null && retro.isBefore(clamp)) clamp else retro

                    val ep = Episode(
                        id = nextId++,
                        startTime = startAt,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp01(consistency),

                        mealIntentActiveAtStart = mealIntentActive,
                        startTrigger = StartTrigger.INSULIN,
                        firstMeaningfulInsulinAt = now,
                        delayToFirstInsulinMin = 0,
                        missedIntervention = false
                    )

                    activeEpisode = ep
                    state = State.ACTIVE
                    iobBelowEndSince = null

                    return EpisodeEvent.Started(ep)
                }

                null
            }

            // ─────────────────────────
            // PENDING_RISE (rise/mealIntent gezien, maar nog geen insulin)
            // ─────────────────────────
            State.PENDING_RISE -> {

                val startAt = pendingStartAt ?: now
                val waited = minutesBetween(startAt, now)

                // Zodra insulin start: episode wordt ACTIVE
                if (isStartDose) {
                    val delay = minutesBetween(startAt, now)
                    val clamp = lastEpisodeEndedAt

                    val safeStartAt =
                        if (clamp != null && startAt.isBefore(clamp)) clamp else startAt

                    val ep = Episode(
                        id = nextId++,
                        startTime = safeStartAt,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp01(consistency),

                        mealIntentActiveAtStart = pendingMealIntent,
                        startTrigger = pendingTrigger ?: StartTrigger.RISE,
                        firstMeaningfulInsulinAt = now,
                        delayToFirstInsulinMin = delay,
                        missedIntervention = false
                    )

                    activeEpisode = ep
                    state = State.ACTIVE
                    iobBelowEndSince = null

                    // pending reset
                    pendingStartAt = null
                    pendingMealIntent = false
                    pendingTrigger = null

                    return EpisodeEvent.Started(ep)
                }

                // Timeout → “gemiste interventie”: we sluiten meteen een episode af
                if (waited >= PENDING_TIMEOUT_MIN) {
                    val clamp = lastEpisodeEndedAt
                    val safeStartAt =
                        if (clamp != null && startAt.isBefore(clamp)) clamp else startAt

                    val ep = Episode(
                        id = nextId++,
                        startTime = safeStartAt,
                        endTime = now,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp01(consistency),

                        mealIntentActiveAtStart = pendingMealIntent,
                        startTrigger = pendingTrigger ?: StartTrigger.RISE,
                        firstMeaningfulInsulinAt = null,
                        delayToFirstInsulinMin = null,
                        missedIntervention = true
                    )

                    // terug naar IDLE
                    state = State.IDLE
                    activeEpisode = null
                    iobBelowEndSince = null

                    // pending reset
                    pendingStartAt = null
                    pendingMealIntent = false
                    pendingTrigger = null

                    lastEpisodeEndedAt = now

                    return EpisodeEvent.Finished(ep)
                }

                null
            }

            // ─────────────────────────
            // ACTIVE → END (jouw bestaande logica, 1-op-1 overgenomen)
            // ─────────────────────────
            State.ACTIVE -> {
                val ep = activeEpisode ?: return null

                val noRecentInsulin =
                    lastAnyInsulinAt?.let {
                        minutesBetween(it, now) >= END_NO_INSULIN_MIN
                    } ?: true

                if (currentIob < IOB_END_THRESHOLD && noRecentInsulin) {

                    if (iobBelowEndSince == null) {
                        iobBelowEndSince = now
                    }

                    if (minutesBetween(iobBelowEndSince!!, now) >= END_STABLE_MIN) {

                        val finished = ep.copy(endTime = now)

                        resetAll()
                        lastEpisodeEndedAt = now

                        return EpisodeEvent.Finished(finished)
                    }

                } else {
                    iobBelowEndSince = null
                }

                null
            }
        }
    }

    // ─────────────────────────────
    // Helpers
    // ─────────────────────────────
    private fun updateBgBuffer(now: DateTime, bg: Double) {
        if (!bg.isFinite()) return
        bgBuf.addLast(BgTick(now, bg))
        while (bgBuf.size > BG_BUFFER_TICKS) {
            bgBuf.removeFirst()
        }
    }

    private fun updateInsulinBuffer(now: DateTime, u: Double) {
        if (u.isFinite() && u > 0.0) {
            insulinBuf.addLast(InsulinTick(now, u))
            lastAnyInsulinAt = now
        }

        while (
            insulinBuf.isNotEmpty() &&
            minutesBetween(insulinBuf.first().time, now) > INSULIN_WINDOW_MIN
        ) {
            insulinBuf.removeFirst()
        }
    }

    private fun findRetroStart(now: DateTime): DateTime? {
        if (bgBuf.size < 4) return null

        val window =
            bgBuf.filter {
                minutesBetween(it.time, now) in 10..90
            }

        if (window.isEmpty()) return null

        return window.minByOrNull { it.bg }?.time
    }

    /**
     * Detecteer een "stijgingsfase" los van insulin.
     * Heel simpel en tunable: stijging >= 0.8 mmol in ~15-30 min.
     */
    private fun detectRise(now: DateTime): Boolean {
        if (bgBuf.size < 6) return false

        val last = bgBuf.last()

        val ref = bgBuf.firstOrNull {
            minutesBetween(it.time, now) in 15..30
        } ?: return false

        val delta = last.bg - ref.bg
        return delta >= RISE_DELTA_MMOL
    }

    private fun resetAll() {
        state = State.IDLE
        activeEpisode = null
        iobBelowEndSince = null
        insulinBuf.clear()

        // pending ook resetten
        pendingStartAt = null
        pendingMealIntent = false
        pendingTrigger = null
    }

    private fun clamp01(x: Double): Double =
        min(1.0, max(0.0, x))

    private fun minutesBetween(a: DateTime, b: DateTime): Int =
        ((b.millis - a.millis) / 60000L).toInt()

    // ─────────────────────────────
    // Constants
    // ─────────────────────────────
    companion object {
        private const val INSULIN_WINDOW_MIN = 25
        private const val BG_BUFFER_TICKS = 40

        private const val MIN_TRIGGER_U = 0.15
        private const val ABSOLUTE_START_MIN_U = 0.20
        private const val START_MIN_BOOST_FRAC = 0.20
        private const val START_IOB_MAX = 0.30

        private const val IOB_END_THRESHOLD = 0.25
        private const val END_NO_INSULIN_MIN = 30
        private const val END_STABLE_MIN = 20

        // nieuw:
        private const val PENDING_TIMEOUT_MIN = 40
        private const val RISE_DELTA_MMOL = 0.8
    }

    // ─────────────────────────────
    // Public state (voor snapshot/UI)
    // ─────────────────────────────
    fun totalEpisodes(): Long = nextId - 1
    fun hasActiveEpisode(): Boolean = activeEpisode != null
    fun activeEpisodeStart(): DateTime? = activeEpisode?.startTime
}
