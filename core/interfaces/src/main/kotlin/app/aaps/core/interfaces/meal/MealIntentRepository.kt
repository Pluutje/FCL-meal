package app.aaps.core.interfaces.meal

import java.util.concurrent.atomic.AtomicReference

object MealIntentRepository {

    data class MealIntent(
        val type: MealIntentType,
        val timestamp: Long,
        val validUntil: Long
    )

    private val current = AtomicReference<MealIntent?>()

    fun set(type: MealIntentType, ttlMinutes: Int) {
        val now = System.currentTimeMillis()
        current.set(
            MealIntent(
                type = type,
                timestamp = now,
                validUntil = now + ttlMinutes * 60_000L
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
