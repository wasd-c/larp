package com.anis.larp.ui.freemode

import java.text.Normalizer
import java.util.Locale

/**
 * Keeps ASR transport prompts out of learner-visible text.
 *
 * The pinned llama-server turns the transcription request into the internal
 * prompt `Transcribe audio to text (language: ...)`. On quiet or unusable
 * audio, a generative ASR model can echo or translate that prompt instead of
 * returning speech. It is protocol data, not something the learner said.
 */
internal fun sanitizeRecognizedSpeech(rawText: String): String {
    val text = rawText
        .replace(Regex("<\\|[^>]+\\|>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return text.takeUnless(::isAsrTransportPromptEcho).orEmpty()
}

private fun isAsrTransportPromptEcho(text: String): Boolean {
    if (text.isBlank() || text.length > MAX_ASR_PROMPT_LENGTH) return false
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

    return TRANSCRIPTION_VERB.containsMatchIn(normalized) &&
        AUDIO_WORD.containsMatchIn(normalized) &&
        TEXT_WORD.containsMatchIn(normalized) &&
        LANGUAGE_QUALIFIER.containsMatchIn(normalized)
}

private val COMBINING_MARKS = Regex("""\p{M}+""")
private val TRANSCRIPTION_VERB = Regex(
    """\b(?:transcri\p{L}*|trascri\p{L}*|transkrib\p{L}*)\b"""
)
private val AUDIO_WORD = Regex("""\baudio\b""")
private val TEXT_WORD = Regex("""\b(?:text\p{L}*|texto|testo|tekst)\b""")
private val LANGUAGE_QUALIFIER = Regex(
    """\((?:language|langue|idioma|sprache|lingua|taal)\s*[:：][^)]+\)"""
)
private const val MAX_ASR_PROMPT_LENGTH = 180
