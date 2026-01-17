package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import kotlin.math.abs

class ParameterLearningStats {

    private data class Stat(
        var ema: Double = 0.0,
        var count: Int = 0
    )

    // apart per mode: DAY/NIGHT
    private val dayStats = mutableMapOf<LearningParameter, Stat>()
    private val nightStats = mutableMapOf<LearningParameter, Stat>()

    fun update(parameter: LearningParameter, direction: Int, isNight: Boolean) {
        val map = if (isNight) nightStats else dayStats
        val s = map.getOrPut(parameter) { Stat() }

        val spec = LearningParameterSpecs.specs[parameter]

        val alpha =
            when (spec?.domain) {
                LearningDomain.TIMING -> 0.18   // sneller leren
                LearningDomain.HEIGHT -> 0.08   // trager, veiliger
                else -> 0.12
            }

        s.ema = (1 - alpha) * s.ema + alpha * direction
        s.count++
    }

    fun getAdvice(parameter: LearningParameter, isNight: Boolean): LearningAdvice? {
        val map = if (isNight) nightStats else dayStats
        val s = map[parameter] ?: return null

        val confidence = abs(s.ema).coerceIn(0.0, 1.0)

        // gates tegen ruis
        if (s.count < 4) return null
        if (confidence < 0.22) return null

        return LearningAdvice(
            parameter = parameter,
            direction = if (s.ema > 0) +1 else -1,
            confidence = confidence,
            evidenceCount = s.count,
            isNight = isNight
        )
    }

    fun allAdvice(isNight: Boolean): List<LearningAdvice> {
        val keys = (if (isNight) nightStats else dayStats).keys
        return keys.mapNotNull { getAdvice(it, isNight) }
    }

    // ─────────────────────────────────────────────
    // Persistence support
    // ─────────────────────────────────────────────

    fun exportDayStats(): Map<LearningParameter, LearningStatSnapshot> =
        dayStats.mapValues { (_, s) ->
            LearningStatSnapshot(
                ema = s.ema,
                count = s.count
            )
        }

    fun exportNightStats(): Map<LearningParameter, LearningStatSnapshot> =
        nightStats.mapValues { (_, s) ->
            LearningStatSnapshot(
                ema = s.ema,
                count = s.count
            )
        }

    fun importDayStats(snapshot: Map<LearningParameter, LearningStatSnapshot>) {
        dayStats.clear()
        snapshot.forEach { (param, snap) ->
            dayStats[param] = Stat(
                ema = snap.ema,
                count = snap.count
            )
        }
    }

    fun importNightStats(snapshot: Map<LearningParameter, LearningStatSnapshot>) {
        nightStats.clear()
        snapshot.forEach { (param, snap) ->
            nightStats[param] = Stat(
                ema = snap.ema,
                count = snap.count
            )
        }
    }

}
