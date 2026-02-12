package app.aaps.core.interfaces.meal

import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference

object MealIntentRepository {

    data class MealIntent(
        val type: MealIntentType,
        val timestamp: Long,
        val validUntil: Long,
        // 👇 NIEUW (alleen relevant voor SNACK)
        val preBolusU: Double? = null,
        val customTtlMinutes: Int? = null
    )

    data class PreBolusSnapshot(
        val mealType: MealIntentType,

        // doses
        val totalU: Double,
        val deliveredU: Double,
        val remainingU: Double,

        // tijdstempels (ENIGE waarheid = millis)
        val armedAt: Long,
        val validUntil: Long,

        // afgeleid
        val minutesSinceArmed: Int,
        val minutesRemaining: Int,

        // verval
        val decayFactor: Double,

        // UI-status
        val status: UiStatus
    )


    enum class UiStatus {
        ACTIVE,        // loopt nog (remainingU > 0)
        DELIVERED,     // alles afgegeven, TTL loopt nog
        EXPIRED        // TTL voorbij
    }

    private val current = AtomicReference<MealIntent?>()

    private val preBolusSnapshot =
        AtomicReference<PreBolusSnapshot?>()

    fun setPreBolusSnapshot(snapshot: PreBolusSnapshot?) {
        preBolusSnapshot.set(snapshot)
    }

    fun getPreBolusSnapshot(): PreBolusSnapshot? =
        preBolusSnapshot.get()


    fun set(
        type: MealIntentType,
        ttlMinutes: Int,
        preBolusU: Double? = null
    ) {
        val now = System.currentTimeMillis()

        current.set(
            MealIntent(
                type = type,
                timestamp = now,
                validUntil = now + ttlMinutes * 60_000L,
                preBolusU =
                    if (type == MealIntentType.SNACK) preBolusU else null,
                customTtlMinutes =
                    if (type == MealIntentType.SNACK) ttlMinutes else null
            )
        )
    }



    fun get(): MealIntent? {
        val intent = current.get() ?: return null
        return if (System.currentTimeMillis() <= intent.validUntil) intent else null
    }

    fun clear() {
        current.set(null)
    }
}
