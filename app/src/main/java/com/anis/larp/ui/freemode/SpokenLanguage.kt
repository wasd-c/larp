package com.anis.larp.ui.freemode

import java.util.Locale

/** Local, deterministic guard against a model emitting the wrong BCP-47 tag. */
internal fun localeMatchingSpokenText(text: String, requestedLocale: Locale): Locale {
    if (text.codePoints().anyMatch { codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        }
    ) return Locale.SIMPLIFIED_CHINESE
    if (text.any { it.code in 0xAC00..0xD7AF }) return Locale.KOREA

    val words = WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    if (words.isEmpty()) return requestedLocale
    val scores = mapOf(
        "fr" to words.count(FRENCH::contains),
        "en" to words.count(ENGLISH::contains),
        "es" to words.count(SPANISH::contains)
    ).toMutableMap()
    if (text.any { it in FRENCH_DIACRITICS }) scores["fr"] = scores.getValue("fr") + 2
    if (text.any { it in SPANISH_DIACRITICS }) scores["es"] = scores.getValue("es") + 2

    val winner = scores.maxByOrNull { it.value } ?: return requestedLocale
    val runnerUp = scores.filterKeys { it != winner.key }.maxOfOrNull { it.value } ?: 0
    val distinctiveShortReply = words.size <= 4 && words.any {
        it in SHORT_DISTINCTIVE.getValue(winner.key)
    }
    val confident = winner.value >= 3 && winner.value >= runnerUp + 2
    if (!confident && !distinctiveShortReply) return requestedLocale
    if (winner.key == requestedLocale.language) return requestedLocale
    return when (winner.key) {
        "fr" -> Locale.FRANCE
        "es" -> Locale.forLanguageTag("es-ES")
        "en" -> Locale.US
        else -> requestedLocale
    }
}

private val WORD = Regex("[\\p{L}’']+")
private const val FRENCH_DIACRITICS = "àâçéèêëîïôùûüÿœæÀÂÇÉÈÊËÎÏÔÙÛÜŸŒÆ"
private const val SPANISH_DIACRITICS = "áéíóúüñ¿¡ÁÉÍÓÚÜÑ"

private val FRENCH = setOf(
    "je", "j'", "tu", "il", "elle", "nous", "vous", "ne", "n'", "pas", "peux",
    "pouvez", "pour", "avec", "car", "une", "des", "du", "le", "la", "les", "est",
    "suis", "êtes", "ai", "avez", "votre", "bonjour", "merci", "exercice", "leçon",
    "créé", "prêt", "prête", "dans", "audio", "fichier", "donner", "transcrire",
    "d'accord", "voici", "cela", "cette", "maintenant", "encore", "très", "bien",
    "parfait", "bravo", "désolé", "désolée", "comprends", "répondre", "réponse", "phrase"
)
private val ENGLISH = setOf(
    "i", "you", "he", "she", "we", "they", "the", "this", "that", "is", "are", "am",
    "can", "cannot", "can't", "please", "hello", "thanks", "thank", "exercise", "lesson",
    "ready", "with", "your", "my", "name", "from", "live", "audio", "file", "give"
)
private val SPANISH = setOf(
    "yo", "tú", "usted", "él", "ella", "nosotros", "ustedes", "el", "la", "los", "las",
    "es", "soy", "está", "puedo", "puede", "por", "con", "hola", "gracias", "ejercicio",
    "lección", "listo", "lista", "archivo", "audio", "dar"
)
private val SHORT_DISTINCTIVE = mapOf(
    "fr" to setOf(
        "bonjour", "merci", "salut", "oui", "désolé", "désolée", "d'accord", "bravo", "parfait"
    ),
    "en" to setOf("hello", "thanks", "yes", "sorry"),
    "es" to setOf("hola", "gracias", "sí", "perdón")
)
