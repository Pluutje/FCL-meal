package app.aaps.core.interfaces.meal

enum class MealIntentType {
    SMALL,
    NORMAL,
    LARGE,
    SNACK;   // 👈 NIEUW

    fun uiLabel(): String =
        when (this) {
            SMALL ->
                "Kleine maaltijd, lager in koolhydraten\n"

            NORMAL ->
                "Normale maaltijd, gemiddeld in koolhydraten\n"

            LARGE ->
                "Grote maaltijd, hoger in koolhydraten\n"

            SNACK ->
                "Langdurig snacken of borrelen\n" +
                    "Geeft FCL vNext langer extra kracht\n"
        }

    // 1️⃣ Beschrijving bij invoeren
    fun uiDescription(): String =
        when (this) {
            SMALL ->
                "Kleine maaltijd, lager in koolhydraten\n"

            NORMAL ->
                "Normale maaltijd, gemiddeld in koolhydraten\n"

            LARGE ->
                "Grote maaltijd, hoger in koolhydraten\n"

            SNACK ->
                "Langdurig snacken of borrelen\n" +
                    "Geeft FCL vNext langer extra kracht\n"
        }

    // 2️⃣ Titel van popup bij tik op gekleurde balk
    fun popupTitle(): String =
        when (this) {
            SMALL  -> "Kleine maaltijd"
            NORMAL -> "Normale maaltijd"
            LARGE  -> "Grote maaltijd"
            SNACK  -> "Snackmoment"
        }

    // 3️⃣ Korte tekst in de gekleurde balk
    fun bandLabel(): String =
        when (this) {
            SMALL  -> "Klein"
            NORMAL -> "Normaal"
            LARGE  -> "Groot"
            SNACK  -> "Snack"
        }


}
