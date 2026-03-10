package app.aaps.core.graph.meal

import app.aaps.core.interfaces.meal.MealIntentType

data class MealIntentVisualStyle(
    val backgroundColor: Int,
    val textColor: Int,
    val alpha: Int
)

fun styleFor(
    type: MealIntentType,
    alpha: Int
): MealIntentVisualStyle =
    when (type) {
        MealIntentType.SNACK ->
            MealIntentVisualStyle(
                backgroundColor = (0xFF81C784.toInt() and 0x00FFFFFF) or (alpha shl 24),
                textColor = 0xFFFFFFFF.toInt(),
                alpha = alpha
            )

        MealIntentType.SMALL ->
            MealIntentVisualStyle(
                backgroundColor = (0xFF64B5F6.toInt() and 0x00FFFFFF) or (alpha shl 24),
                textColor = 0xFFFFFFFF.toInt(),
                alpha = alpha
            )

        MealIntentType.NORMAL ->
            MealIntentVisualStyle(
                backgroundColor = (0xFFFFB74D.toInt() and 0x00FFFFFF) or (alpha shl 24),
                textColor = 0xFF000000.toInt(),
                alpha = alpha
            )

        MealIntentType.LARGE ->
            MealIntentVisualStyle(
                backgroundColor = (0xFFE57373.toInt() and 0x00FFFFFF) or (alpha shl 24),
                textColor = 0xFFFFFFFF.toInt(),
                alpha = alpha
            )
    }
