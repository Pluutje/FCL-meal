package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfig
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextContext

/**
 * Fase I Learning Episode Manager
 * - Volledig observerend
 * - Geen invloed op dosing
 * - Geen logging (dat komt in Stap 2)
 */
private const val END_SLOPE_MAX = 0.35         // was 0.2 (te streng)
private const val END_ACCEL_MAX = 0.08         // was 0.0 (te streng)
private const val MAX_EPISODE_MIN = 180        // harde timeout 3 uur

class FCLvNextLearningEpisodeManager {

    // ─────────────────────────────────────────────
    // Outcome labels (publiek, stabiel)
    // ─────────────────────────────────────────────
    enum class EpisodeOutcome {
        GOOD_CONTROL,
        TOO_LATE,
        TOO_LATE_EVEN_WITH_EARLY,
        TOO_STRONG,
        OVERSHOOT,
        HYPO_RISK,
        NO_ACTION_NEEDED
    }

    // ─────────────────────────────────────────────
    // Episode context (intern, persistent)
    // ─────────────────────────────────────────────
    private data class LearningEpisodeContext(
        var active: Boolean = false,
        var startedAt: DateTime? = null,

        // context bij start
        var startBg: Double = 0.0,
        var targetBg: Double = 0.0,
        var isNight: Boolean = false,

        // dynamiek
        var maxSlope: Double = 0.0,
        var maxAccel: Double = 0.0,

        // dosing
        var totalDelivered: Double = 0.0,
        var earlyStageMax: Int = 0,

        // safety
        var rescueTriggered: Boolean = false
    )

    private val episode = LearningEpisodeContext()

    fun isActive(): Boolean = episode.active

    // ─────────────────────────────────────────────
    // Episode start
    // ─────────────────────────────────────────────
    fun maybeStartEpisode(
        ctx: FCLvNextContext,
        peakActive: Boolean,
        earlyStage: Int,
        mealActive: Boolean,
        now: DateTime
    ) {
        if (episode.active) return

        val shouldStart =
            peakActive ||
                earlyStage > 0 ||
                mealActive

        if (shouldStart) {
            episode.active = true
            episode.startedAt = now
            episode.startBg = ctx.input.bgNow
            episode.targetBg = ctx.input.targetBG
            episode.isNight = ctx.input.isNight

            episode.maxSlope = ctx.slope
            episode.maxAccel = ctx.acceleration
            episode.totalDelivered = 0.0
            episode.earlyStageMax = earlyStage
            episode.rescueTriggered = false
        }
    }

    // ─────────────────────────────────────────────
    // Episode update (per cycle)
    // ─────────────────────────────────────────────
    fun updateEpisode(
        ctx: FCLvNextContext,
        earlyStage: Int,
        deliveredNow: Double,
        rescueConfirmed: Boolean
    ) {
        if (!episode.active) return

        episode.maxSlope = maxOf(episode.maxSlope, ctx.slope)
        episode.maxAccel = maxOf(episode.maxAccel, ctx.acceleration)

        episode.totalDelivered += deliveredNow
        episode.earlyStageMax = maxOf(episode.earlyStageMax, earlyStage)

        if (rescueConfirmed) {
            episode.rescueTriggered = true
        }
    }

    // ─────────────────────────────────────────────
    // Episode end condition
    // ─────────────────────────────────────────────
    fun shouldEndEpisode(
        ctx: FCLvNextContext,
        peakActive: Boolean,
        lastCommitAt: DateTime?,
        now: DateTime,
        config: FCLvNextConfig
    ): Boolean {

        if (!episode.active) return false

        val absorptionDone =
            lastCommitAt == null ||
                minutesSince(lastCommitAt, now) > config.absorptionWindowMinutes

        val ageMin = minutesSince(episode.startedAt, now)

        // ✅ timeout: altijd eindigen
        if (ageMin >= MAX_EPISODE_MIN) return true

        // ✅ robuuster “stabilized” criterium (ruisbestendig)
        val stabilized =
            ctx.slope <= END_SLOPE_MAX &&
                kotlin.math.abs(ctx.acceleration) <= END_ACCEL_MAX

        return !peakActive && stabilized && absorptionDone
    }


    // ─────────────────────────────────────────────
    // Outcome classification (deterministisch)
    // ─────────────────────────────────────────────
    fun classifyOutcome(
        ctx: FCLvNextContext,
        predictedPeak: Double,
        peakBand: Int,
        config: FCLvNextConfig
    ): EpisodeOutcome {

        // 1️⃣ Veiligheid
        if (episode.rescueTriggered || ctx.input.bgNow <= 4.4) {
            return EpisodeOutcome.HYPO_RISK
        }

        // 2️⃣ Geen actie nodig
        if (
            episode.totalDelivered < 0.1 &&
            predictedPeak < ctx.input.targetBG + 1.2
        ) {
            return EpisodeOutcome.NO_ACTION_NEEDED
        }

        // 3️⃣ Overshoot
        if (
            predictedPeak < 12.0 &&
            ctx.input.bgNow >= 14.5
        ) {
            return EpisodeOutcome.OVERSHOOT
        }

        // 4️⃣ Te laat
        if (
            peakBand >= 12 &&
            episode.earlyStageMax == 0 &&
            episode.maxSlope >= 1.2
        ) {
            return EpisodeOutcome.TOO_LATE
        }

        // 4️⃣ B  Nog steeds te laat (ook al was er early)

        if (
            peakBand >= 12 &&
            episode.earlyStageMax > 0 &&
            episode.totalDelivered < 0.60 &&     // startwaarde: te weinig vroeg
            episode.maxSlope >= 1.2
        ) {
            return EpisodeOutcome.TOO_LATE_EVEN_WITH_EARLY
        }


        // 5️⃣ Te sterk
        if (
            episode.totalDelivered > 1.2 * config.maxSMB &&
            ctx.input.bgNow < ctx.input.targetBG
        ) {
            return EpisodeOutcome.TOO_STRONG
        }

        return EpisodeOutcome.GOOD_CONTROL
    }

    // ─────────────────────────────────────────────
    // Episode close
    // ─────────────────────────────────────────────
    fun closeEpisode(
        ctx: FCLvNextContext,
        predictedPeak: Double,
        peakBand: Int,
        config: FCLvNextConfig
    ): EpisodeOutcome {

        val outcome = classifyOutcome(ctx, predictedPeak, peakBand, config)
        episode.active = false
        return outcome
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────
    private fun minutesSince(ts: DateTime?, now: DateTime): Int {
        if (ts == null) return Int.MAX_VALUE
        return org.joda.time.Minutes.minutesBetween(ts, now).minutes
    }
}
