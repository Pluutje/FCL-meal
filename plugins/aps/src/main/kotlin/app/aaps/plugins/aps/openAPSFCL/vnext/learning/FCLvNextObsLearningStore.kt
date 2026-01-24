package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import com.google.gson.Gson
import org.joda.time.DateTime

/**
 * Persistent storage voor OBS learning state.
 *
 * - Bewaart alleen structurele learning (evidence)
 * - Versie-gebonden: semantische wijzigingen → reset
 * - Volledig crash-safe (fail = negeren)
 */

// ─────────────────────────────────────────────
// 🔹 Serializable modellen
// ─────────────────────────────────────────────

data class StoredEvidence(
    val atMillis: Long,
    val episodeId: Long,
    val axis: Axis,
    val outcome: AxisOutcome,
    val strength: Double,
    val weight: Double
)

data class StoredLearningState(
    val version: String,
    val savedAtMillis: Long,
    val evidence: List<StoredEvidence>
)

// ─────────────────────────────────────────────
// 🔹 Store
// ─────────────────────────────────────────────

class FCLvNextObsLearningStore(
    private val prefs: Preferences
) {

    private val gson = Gson()

    /**
     * StringKey gedefinieerd in StringKey.kt
     */
    private val KEY = StringKey.fcl_vnext_obs_learning_state

    /**
     * ⚠️ Verhoog deze string als:
     * - AxisScorer logica wijzigt
     * - betekenis van AxisOutcome wijzigt
     * - delivery weighting semantisch wijzigt
     */
    private val VERSION = "v1"

    /**
     * Bewaar huidige learning-state
     */
    fun save(
        accumulator: FCLvNextObsConfidenceAccumulator,
        now: DateTime
    ) {
        try {
            val state = accumulator.exportState(
                version = VERSION,
                now = now
            )
            prefs.put(KEY, gson.toJson(state))
        } catch (_: Throwable) {
            // 🔒 learning mag NOOIT een crash veroorzaken
        }
    }

    /**
     * Herstel learning-state (indien aanwezig & compatible)
     */
    fun restore(
        accumulator: FCLvNextObsConfidenceAccumulator
    ) {
        val json = prefs.get(KEY) ?: return

        try {
            val state =
                gson.fromJson(json, StoredLearningState::class.java)

            if (state.version != VERSION) return

            accumulator.importState(state)
        } catch (_: Throwable) {
            // corrupt / oud → negeren
        }
    }

    /**
     * Volledig resetten van OBS learning
     * (bijv. bij profielwijziging of grote update)
     */
    fun clear() {
        try {
            prefs.remove(KEY)
        } catch (_: Throwable) {
            // safe no-op
        }
    }
}
