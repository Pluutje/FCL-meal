package app.aaps.core.interfaces.meal

enum class MealIntentType {
    SMALL,
    NORMAL,
    LARGE;

    fun uiLabel(): String =
        when (this) {
            MealIntentType.SMALL ->
                "Maaltijd, lager in koolhydraten\n"

            MealIntentType.NORMAL ->
                "Maaltijd, gemiddeld in koolhydraten\n"

            MealIntentType.LARGE ->
                "Maaltijd, hoger in koolhydraten\n"
        }

}
