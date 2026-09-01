package com.anis.larp.model

import java.text.Normalizer
import java.util.Locale

enum class LearningLanguage(
    val languageTag: String,
    val displayName: String,
    val nativeName: String,
    private val speechRecognitionTag: String = languageTag
) {
    ENGLISH("en-US", "Anglais", "English"),
    SPANISH("es-ES", "Espagnol", "Español"),
    FRENCH("fr-FR", "Français", "Français"),
    GERMAN("de-DE", "Allemand", "Deutsch"),
    ITALIAN("it-IT", "Italien", "Italiano"),
    PORTUGUESE("pt-PT", "Portugais", "Português"),
    JAPANESE("ja-JP", "Japonais", "日本語"),
    KOREAN("ko-KR", "Coréen", "한국어"),
    SIMPLIFIED_CHINESE(
        "zh-Hans-CN",
        "Chinois simplifié",
        "简体中文",
        speechRecognitionTag = "cmn-Hans-CN"
    ),
    ARABIC("ar-SA", "Arabe", "العربية"),
    DUTCH("nl-NL", "Néerlandais", "Nederlands"),
    RUSSIAN("ru-RU", "Russe", "Русский");

    val locale: Locale
        get() = Locale.forLanguageTag(languageTag)

    val speechRecognitionLocale: Locale
        get() = Locale.forLanguageTag(speechRecognitionTag)

    companion object {
        fun fromLanguageTag(tag: String?): LearningLanguage =
            entries.firstOrNull {
                val requestedLanguage = Locale.forLanguageTag(tag.orEmpty()).language
                    .let { language -> if (language == "cmn") "zh" else language }
                Locale.forLanguageTag(it.languageTag).language == requestedLanguage
            } ?: ENGLISH

        /**
         * Finds a language explicitly named by the learner. This is deliberately
         * deterministic: the model receives the resolved locale instead of being
         * trusted to infer and persist a language tag on its own.
         */
        fun explicitlyNamedIn(message: String): LearningLanguage? {
            val normalized = message.normalizedLanguageRequest()
            return LANGUAGE_ALIASES.entries.firstNotNullOfOrNull { (language, aliases) ->
                language.takeIf {
                    aliases.any { alias ->
                        Regex("(?:^|[^a-z])${Regex.escape(alias)}(?:$|[^a-z])")
                            .containsMatchIn(normalized)
                    }
                }
            }
        }
    }
}

/** True only for wording that asks to switch the ongoing learning language. */
fun requestsLearningLanguageSwitch(message: String): Boolean {
    if (LearningLanguage.explicitlyNamedIn(message) == null) return false
    val normalized = message.normalizedLanguageRequest()
    return LANGUAGE_SWITCH_MARKERS.any { it.containsMatchIn(normalized) }
}

private fun String.normalizedLanguageRequest(): String =
    Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

private val LANGUAGE_SWITCH_MARKERS = listOf(
    Regex("""\b(?:apprendre|apprenons|etudier|etudions|pratiquer|pratique|pratiquons|parler|parlons|discuter|discute|discutons|conversation|passer|passons|changer|change|bascule|mets|met|switch|learn|study|practice|speak|talk)\b"""),
    Regex("""\b(?:je veux|j aimerais|i want|i d like|quiero)\b""")
)

private val LANGUAGE_ALIASES = linkedMapOf(
    LearningLanguage.ENGLISH to listOf("anglais", "english", "ingles"),
    LearningLanguage.SPANISH to listOf("espagnol", "espagnole", "spanish", "espanol"),
    LearningLanguage.FRENCH to listOf("francais", "french", "frances"),
    LearningLanguage.GERMAN to listOf("allemand", "allemande", "german", "deutsch"),
    LearningLanguage.ITALIAN to listOf("italien", "italienne", "italian", "italiano"),
    LearningLanguage.PORTUGUESE to listOf("portugais", "portugaise", "portuguese", "portugues"),
    LearningLanguage.JAPANESE to listOf(
        "japonais",
        "japonaise",
        "japanese",
        "nihongo",
        "日本語"
    ),
    LearningLanguage.KOREAN to listOf("coreen", "coreenne", "korean", "hangugo", "한국어"),
    LearningLanguage.SIMPLIFIED_CHINESE to listOf(
        "chinois",
        "chinoise",
        "mandarin",
        "chinese",
        "中文"
    ),
    LearningLanguage.ARABIC to listOf("arabe", "arabic", "العربية"),
    LearningLanguage.DUTCH to listOf("neerlandais", "neerlandaise", "dutch", "nederlands"),
    LearningLanguage.RUSSIAN to listOf("russe", "russian", "русский")
)

data class NativeLanguageChoice(
    val languageTag: String,
    val displayName: String
)

fun commonNativeLanguages(deviceLocale: Locale): List<NativeLanguageChoice> {
    val candidates = listOf(
        deviceLocale,
        Locale.ENGLISH,
        Locale.FRENCH,
        Locale.forLanguageTag("es-ES"),
        Locale.forLanguageTag("ko-KR"),
        Locale.SIMPLIFIED_CHINESE
    )
    return candidates
        .distinctBy { it.language }
        .map { locale ->
            NativeLanguageChoice(
                languageTag = locale.toLanguageTag(),
                displayName = locale.displayNameIn(locale)
            )
        }
}

fun Locale.displayNameIn(displayLocale: Locale = Locale.getDefault()): String {
    val name = getDisplayName(displayLocale).ifBlank { toLanguageTag() }
    return name.replaceFirstChar { character ->
        if (character.isLowerCase()) {
            character.titlecase(displayLocale)
        } else {
            character.toString()
        }
    }
}

fun speechRecognitionLocaleFor(languageTag: String): Locale {
    val locale = Locale.forLanguageTag(languageTag)
    return if (locale.language == "zh" || locale.language == "cmn") {
        Locale.forLanguageTag("cmn-Hans-CN")
    } else {
        locale
    }
}

/** Android TTS uses the zh family, while ML Kit speech recognition uses cmn. */
fun textToSpeechLocaleFor(languageTag: String): Locale {
    val locale = Locale.forLanguageTag(languageTag)
    return if (locale.language == "zh" || locale.language == "cmn") {
        Locale.forLanguageTag("zh-Hans-CN")
    } else {
        locale
    }
}
