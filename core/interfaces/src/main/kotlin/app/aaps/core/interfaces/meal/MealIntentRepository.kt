package app.aaps.core.interfaces.meal

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

    private val current = AtomicReference<MealIntent?>()

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
