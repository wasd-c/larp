package com.anis.larp.model

import java.util.Locale

enum class LearningLanguage(
    val languageTag: String,
    val displayName: String,
    val nativeName: String,
    private val speechRecognitionTag: String = languageTag
) {
    ENGLISH("en-US", "Anglais", "English"),
    SPANISH("es-ES", "Espagnol", "Español"),
    KOREAN("ko-KR", "Coréen", "한국어"),
    SIMPLIFIED_CHINESE(
        "zh-Hans-CN",
        "Chinois simplifié",
        "简体中文",
        speechRecognitionTag = "cmn-Hans-CN"
    );

    val locale: Locale
        get() = Locale.forLanguageTag(languageTag)

    val speechRecognitionLocale: Locale
        get() = Locale.forLanguageTag(speechRecognitionTag)

    companion object {
        fun fromLanguageTag(tag: String?): LearningLanguage =
            entries.firstOrNull {
                Locale.forLanguageTag(it.languageTag).language ==
                    Locale.forLanguageTag(tag.orEmpty()).language
            } ?: ENGLISH
    }
}

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
