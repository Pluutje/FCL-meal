package app.aaps.core.interfaces.meal

enum class MealIntentType {
    SMALL,
    NORMAL,
    LARGE,
    SNACK;   // 👈 NIEUW

    fun uiLabel(): String =
        when (this) {
            SMALL ->
                "Maaltijd, lager in koolhydraten\n"

            NORMAL ->
                "Maaltijd, gemiddeld in koolhydraten\n"

            LARGE ->
                "Maaltijd, hoger in koolhydraten\n"

            SNACK ->
                "Langdurig snacken of borrelen\n" +
                    "Geeft FCL vNext langer extra kracht\n"
        }


}
