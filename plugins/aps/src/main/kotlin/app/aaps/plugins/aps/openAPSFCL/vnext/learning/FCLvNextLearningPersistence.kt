package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistente opslag van learning state in Preferences als JSON.
 * - load bij init
 * - save bij episode-einde (caller beslist wanneer)
 */
class FCLvNextLearningPersistence(
    private val preferences: Preferences
) {
    private val key = StringKey.fcl_vnext_learning_snapshot_json

    fun loadInto(advisor: FCLvNextLearningAdvisor) {
        val raw = preferences.get(key)
        if (raw.isNullOrBlank()) return

        runCatching {
            val snap = fromJson(raw)
            advisor.importSnapshot(snap)

            saveFrom(advisor)
        }.onFailure {
            // Fail-safe: corrupte JSON? Dan niet crashen.
            // Optioneel: reset key zodat je “schone start” hebt.
            // preferences.put(key, "")
        }
    }

    fun saveFrom(advisor: FCLvNextLearningAdvisor) {
        val snap = advisor.exportSnapshot()
        val raw = toJson(snap)
        preferences.put(key, raw)
    }

    // ─────────────────────────────────────────────
    // JSON encode/decode
    // ─────────────────────────────────────────────

    private fun toJson(s: FCLvNextLearningSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", s.schemaVersion)

        root.put("dayStats", statsToJson(s.dayStats))
        root.put("nightStats", statsToJson(s.nightStats))

        root.put("profileEvidenceCount", s.profileEvidenceCount)

        root.put("learningPhase", s.learningPhase.name)

        if (s.profileAdvice != null) {
            val pa = JSONObject()
            pa.put("recommended", s.profileAdvice.recommended.name)
            pa.put("confidence", s.profileAdvice.confidence)
            pa.put("reason", s.profileAdvice.reason)
            pa.put("evidenceCount", s.profileAdvice.evidenceCount)
            root.put("learningPhase", s.learningPhase.name)
            root.put("profileAdvice", pa)
        } else {
            root.put("profileAdvice", JSONObject.NULL)
        }

        return root.toString()
    }

    private fun fromJson(raw: String): FCLvNextLearningSnapshot {
        val root = JSONObject(raw)
        val ver = root.optInt("schemaVersion", 1)

        val phaseRaw = root.optString("learningPhase", LearningPhase.TIMING_ONLY.name)

        val learningPhase =
            when (phaseRaw) {
                "TIMING_AND_HIGHT" -> LearningPhase.TIMING_AND_HEIGHT // legacy typo fix
                else -> runCatching { LearningPhase.valueOf(phaseRaw) }
                    .getOrElse { LearningPhase.TIMING_ONLY }
            }


        val dayStats = jsonToStats(root.optJSONObject("dayStats"))
        val nightStats = jsonToStats(root.optJSONObject("nightStats"))

        val profEvidence = root.optInt("profileEvidenceCount", 0)

        val profileAdviceObj = root.opt("profileAdvice")
        val profileAdvice =
            if (profileAdviceObj == null || profileAdviceObj == JSONObject.NULL) {
                null
            } else {
                val pa = profileAdviceObj as JSONObject
                FCLvNextProfileAdvice(
                    recommended = FCLvNextProfile.valueOf(pa.getString("recommended")),
                    confidence = pa.optDouble("confidence", 0.0),
                    reason = pa.optString("reason", ""),
                    evidenceCount = pa.optInt("evidenceCount", 0)
                )
            }

        return FCLvNextLearningSnapshot(
            schemaVersion = ver,
            dayStats = dayStats,
            nightStats = nightStats,
            profileAdvice = profileAdvice,
            profileEvidenceCount = profEvidence,
            learningPhase = learningPhase
        )
    }

    private fun statsToJson(map: Map<LearningParameter, LearningStatSnapshot>): JSONObject {
        val obj = JSONObject()
        for ((param, snap) in map) {
            val s = JSONObject()
            s.put("ema", snap.ema)
            s.put("count", snap.count)
            obj.put(param.name, s)
        }
        return obj
    }

    private fun jsonToStats(obj: JSONObject?): Map<LearningParameter, LearningStatSnapshot> {
        if (obj == null) return emptyMap()

        val out = HashMap<LearningParameter, LearningStatSnapshot>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            runCatching {
                val p = LearningParameter.valueOf(k)
                val s = obj.getJSONObject(k)
                out[p] = LearningStatSnapshot(
                    ema = s.optDouble("ema", 0.0),
                    count = s.optInt("count", 0)
                )
            }
        }
        return out
    }
}
