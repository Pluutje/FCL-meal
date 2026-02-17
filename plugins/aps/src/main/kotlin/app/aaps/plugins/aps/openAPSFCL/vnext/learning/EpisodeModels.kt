package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

data class Episode(
    val id: Long,
    val startTime: DateTime,
    val endTime: DateTime?,
    val isNight: Boolean,

    val excluded: Boolean,
    val exclusionReason: ExclusionReason?,

    val qualityScore: Double,

    // ⬇⬇⬇ NIEUW ⬇⬇⬇
    val mealIntentActiveAtStart: Boolean,
    val startTrigger: StartTrigger,
    val firstMeaningfulInsulinAt: DateTime?,
    val delayToFirstInsulinMin: Int?,
    val missedIntervention: Boolean
)
enum class StartTrigger {
    INSULIN,
    RISE,
    MEAL_INTENT
}

enum class ExclusionReason {
    RESCUE_CONFIRMED,
    DOWNTREND_LOCKED,
    MANUAL_BOLUS,
    DATA_INSUFFICIENT
}

sealed class EpisodeEvent {
    data class Started(val episode: Episode) : EpisodeEvent()
    data class Finished(val episode: Episode) : EpisodeEvent()
}
