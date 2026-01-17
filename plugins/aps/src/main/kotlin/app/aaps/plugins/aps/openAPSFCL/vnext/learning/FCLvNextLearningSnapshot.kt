package app.aaps.plugins.aps.openAPSFCL.vnext.learning

/**
 * Persistente learning snapshot
 * Wordt opgeslagen / geladen als geheel
 */
data class FCLvNextLearningSnapshot(
    val schemaVersion: Int = 1,

    val dayStats: Map<LearningParameter, LearningStatSnapshot>,
    val nightStats: Map<LearningParameter, LearningStatSnapshot>,

    val profileAdvice: FCLvNextProfileAdvice?,
    val profileEvidenceCount: Int,
    val learningPhase: LearningPhase
)

/**
 * Snapshot van 1 parameter-stat
 */
data class LearningStatSnapshot(
    val ema: Double,
    val count: Int
)
