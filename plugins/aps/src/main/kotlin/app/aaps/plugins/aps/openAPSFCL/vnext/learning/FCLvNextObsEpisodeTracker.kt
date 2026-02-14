package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import kotlin.math.max
import kotlin.math.min

class EpisodeTracker {

    // ─────────────────────────────
    // State
    // ─────────────────────────────
    private enum class State { IDLE, ACTIVE }

    private var state: State = State.IDLE
    private var activeEpisode: Episode? = null
    private var nextId: Long = 1L

    // Laatste episode-einde (voor overlap-preventie)
    private var lastEpisodeEndedAt: DateTime? = null

    // End-detectie helpers
    private var iobBelowEndSince: DateTime? = null
    private var lastAnyInsulinAt: DateTime? = null

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
        // START-detectie
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
        // State machine
        // ─────────────────────────────
        return when (state) {

            // ─────────────────────────
            // IDLE → START
            // ─────────────────────────
            State.IDLE -> {
                if (isStartDose && startAllowedByIob) {

                    val retro = findRetroStart(now) ?: now
                    val clamp = lastEpisodeEndedAt

                    // Start nooit vóór vorige episode laten vallen
                    val startAt =
                        if (clamp != null && retro.isBefore(clamp)) clamp else retro

                    val ep = Episode(
                        id = nextId++,
                        startTime = startAt,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp(consistency)
                    )

                    activeEpisode = ep
                    state = State.ACTIVE
                    iobBelowEndSince = null

                    EpisodeEvent.Started(ep)
                } else {
                    null
                }
            }

            // ─────────────────────────
            // ACTIVE → END
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

    private fun resetAll() {
        state = State.IDLE
        activeEpisode = null
        iobBelowEndSince = null
        insulinBuf.clear()
    }

    private fun clamp(x: Double): Double =
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
    }

    // ─────────────────────────────
    // Public state (voor snapshot/UI)
    // ─────────────────────────────
    fun totalEpisodes(): Long = nextId - 1
    fun hasActiveEpisode(): Boolean = activeEpisode != null
    fun activeEpisodeStart(): DateTime? = activeEpisode?.startTime
}
