package com.anis.larp.model

import java.util.Locale

/** Returns -1 for an incompatible voice, otherwise a higher-is-better score. */
internal fun offlineVoiceLocaleScore(requested: Locale, candidate: Locale): Int {
    if (canonicalSpeechLanguage(requested) != canonicalSpeechLanguage(candidate)) return -1

    val requestedScript = speechScript(requested)
    val candidateScript = speechScript(candidate)
    if (
        requestedScript.isNotBlank() &&
        candidateScript.isNotBlank() &&
        requestedScript != candidateScript
    ) return -1

    var score = 100
    if (requested.toLanguageTag().equals(candidate.toLanguageTag(), ignoreCase = true)) {
        score += 100
    }
    if (requestedScript.isNotBlank() && requestedScript == candidateScript) score += 40
    if (
        requested.country.isNotBlank() &&
        requested.country.equals(candidate.country, ignoreCase = true)
    ) score += 20
    if (candidate.country.isBlank()) score += 5
    return score
}

private fun canonicalSpeechLanguage(locale: Locale): String = when (
    locale.language.lowercase(Locale.ROOT)
) {
    "cmn" -> "zh"
    else -> locale.language.lowercase(Locale.ROOT)
}

private fun speechScript(locale: Locale): String {
    if (locale.script.isNotBlank()) return locale.script
    if (canonicalSpeechLanguage(locale) != "zh") return ""
    return when (locale.country.uppercase(Locale.ROOT)) {
        "CN", "SG", "MY" -> "Hans"
        "TW", "HK", "MO" -> "Hant"
        else -> ""
    }
}
