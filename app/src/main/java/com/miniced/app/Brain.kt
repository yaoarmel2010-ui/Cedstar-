package com.miniced.app

/**
 * Repli local (hors-ligne), utilisé uniquement si aucune clé API Claude n'est
 * configurée. Dès qu'une clé est renseignée, MainActivity utilise ClaudeClient
 * (voir ce fichier) qui, lui, sait faire de vraies recherches web et te
 * reprendre si tu te trompes.
 */
object Brain {

    fun process(userSpeech: String): String {
        val text = userSpeech.lowercase()

        return when {
            "bonjour" in text || "salut" in text ->
                "Salut ! Configure ta clé API Claude pour que je devienne vraiment utile."

            else ->
                "J'ai bien entendu : « $userSpeech ». Configure ta clé API Claude " +
                "(bouton en haut) pour que je puisse vraiment te répondre et faire " +
                "des recherches."
        }
    }
}
