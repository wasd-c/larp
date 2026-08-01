package com.anis.larp.ui.freemode

import java.util.Locale

/**
 * Removes model protocol fields from text before it reaches Android TTS.
 *
 * Prompt models are asked for a structured response, but small local models can
 * occasionally echo that structure. Keep this as a final output boundary so a
 * future prompt or parser change cannot make metadata audible.
 */
internal fun sanitizeTextForSpeech(rawText: String): String {
    val spokenLines = rawText.lineSequence()
        .mapNotNull(::sanitizeSpeechLine)
        .toList()

    return spokenLines
        .joinToString("\n")
        .replace(conversationSentinelRegex, "")
        .replace(audibleProtocolLabelRegex, "")
        .replace(canonicalLanguageTagRegex, "")
        .replace(spacesBeforePunctuationRegex, "$1")
        .replace(repeatedHorizontalWhitespaceRegex, " ")
        .replace(excessBlankLinesRegex, "\n")
        .trim(' ', '\t', '\n', ',', ';', ':', '-', '—', '–')
}

private fun sanitizeSpeechLine(rawLine: String): String? {
    var line = rawLine
        .trim()
        .removePrefix("- ")
        .removePrefix("* ")
        .trim()

    if (
        line.isBlank() ||
        line == "```" ||
        line == "{" ||
        line == "}" ||
        line.equals("<the text to speak>", ignoreCase = true)
    ) {
        return null
    }

    val field = parseProtocolField(line)
    if (field != null) {
        if (field.key in SPOKEN_VALUE_FIELDS) {
            line = field.value
        } else if (field.key in SILENT_FIELDS || field.key.startsWith("ACTION_") ||
            field.key.startsWith("TOOL_")
        ) {
            // Some models flatten several requested fields onto one line. Keep
            // only an explicit reply value if one follows the silent field.
            line = inlineReplyFieldRegex.find(field.value)?.groupValues?.get(2)
                ?: return null
        }
    }

    line = line
        .replace(leadingReplyFieldRegex, "")
        .replace(leadingLanguageTagRegex, "")
        .trimJsonValue()

    if (line.matches(standaloneLanguageTagRegex)) return null
    if (line.equals("none yet", ignoreCase = true)) return null
    if (line.equals("none", ignoreCase = true)) return null
    if (line.equals("null", ignoreCase = true)) return null
    if (line.equals("n/a", ignoreCase = true)) return null

    return line.takeIf(String::isNotBlank)
}

private data class ProtocolField(
    val key: String,
    val value: String
)

private fun parseProtocolField(line: String): ProtocolField? {
    val separator = line.indexOf(':')
    if (separator <= 0) return null

    val key = line.substring(0, separator)
        .trim(' ', '\t', '"', '\'', '`', '*', '_')
        .replace('-', '_')
        .replace(' ', '_')
        .uppercase(Locale.ROOT)
    return ProtocolField(
        key = key,
        value = line.substring(separator + 1).trim()
    )
}

private fun String.trimJsonValue(): String {
    var value = trim().trimEnd(',').trim()
    if (
        value.length >= 2 &&
        ((value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\''))
    ) {
        value = value.substring(1, value.length - 1).trim()
    }
    return value
}

private val SPOKEN_VALUE_FIELDS = setOf(
    "REPLY",
    "RESPONSE",
    "ANSWER",
    "ASSISTANT",
    "REPONSE",
    "RÉPONSE"
)

private val SILENT_FIELDS = setOf(
    "LANGUAGE_TAG",
    "LANGUAGE",
    "LANGUE",
    "LOCALE",
    "CONVERSATION",
    "CONVERSATION_HISTORY",
    "CONVERSATION_SO_FAR",
    "HISTORY",
    "PREVIOUS_TURN",
    "PREVIOUS_TURNS",
    "TRANSCRIPT",
    "USER",
    "LEARNER",
    "LEARNER'S_CURRENT_MESSAGE",
    "TUTOR",
    "LARP",
    "SYSTEM",
    "MODEL",
    "ACCELERATION",
    "ACTION",
    "TOOL",
    "TOOL_CALL",
    "RETURN_EXACTLY_THIS_FORMAT"
)

private val inlineReplyFieldRegex = Regex(
    """(?i)(?:^|\s)(REPLY|RESPONSE|ANSWER|ASSISTANT|REPONSE|RÉPONSE)\s*:\s*(.+)$"""
)

private val leadingReplyFieldRegex = Regex(
    """(?i)^\s*["'`*]*(?:REPLY|RESPONSE|ANSWER|ASSISTANT|REPONSE|RÉPONSE)["'`*]*\s*:\s*"""
)

private val standaloneLanguageTagRegex = Regex(
    """^[\[("']?$speechLanguageTagPattern[\])"']?[.!,:;]?$"""
)

private val leadingLanguageTagRegex = Regex(
    """^\s*[\[("']?$speechLanguageTagPattern[\])"']?\s*(?:[|:—–]\s*|-(?=\s)\s*)?"""
)

// Match canonical BCP-47 tags (en-US, fr-FR, zh-Hans-CN, cmn-Hans-CN)
// anywhere in otherwise natural text without confusing romanized teaching
// content such as "jeo-neun" for a locale tag.
private val canonicalLanguageTagRegex = Regex(
    """(?<![\p{L}\p{N}])$speechLanguageTagPattern(?![\p{L}\p{N}])"""
)

private const val speechLanguageTagPattern =
    "(?:" +
        "(?i:en-US|es-ES|ko-KR|zh-CN|zh-Hans-CN|cmn-Hans-CN)" +
        "|[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-\\d{3})" +
        ")"

private val conversationSentinelRegex = Regex(
    """(?i)\b(?:CONVERSATION|CONVERSATION_HISTORY|HISTORY)\s*:\s*(?:none yet|none|null|n/a)\b[.,;:]?"""
)
private val audibleProtocolLabelRegex = Regex(
    """(?i)\b(?:LANGUAGE_TAG|LANGUAGE|LANGUE|LOCALE|CONVERSATION(?:_HISTORY|\s+SO\s+FAR)?|HISTORY|PREVIOUS_TURNS?|TRANSCRIPT|ACTION(?:_[A-Z_]+)?|TOOL(?:_[A-Z_]+)?|MODEL|ACCELERATION|REPLY|RESPONSE|ANSWER|ASSISTANT|USER|LEARNER|TUTOR|SYSTEM|LARP)\s*:\s*"""
)
private val spacesBeforePunctuationRegex = Regex("""\s+([,.!?;:])""")
private val repeatedHorizontalWhitespaceRegex = Regex("""[\t ]{2,}""")
private val excessBlankLinesRegex = Regex("""\n{2,}""")
