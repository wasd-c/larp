package com.anis.larp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseModelRoutingTest {
    @Test
    fun androidTtsAndMlKitReceiveTheirOwnChineseLocaleFamilies() {
        assertEquals("cmn-Hans-CN", speechRecognitionLocaleFor("zh-CN").toLanguageTag())
        assertEquals("zh-Hans-CN", textToSpeechLocaleFor("cmn-Hans-CN").toLanguageTag())
        assertEquals(
            LearningLanguage.SIMPLIFIED_CHINESE,
            LearningLanguage.fromLanguageTag("cmn-Hans-CN")
        )
    }

    @Test
    fun simplifiedChineseRejectsTraditionalOfflineVoices() {
        val requested = java.util.Locale.forLanguageTag("zh-Hans-CN")

        assertTrue(
            offlineVoiceLocaleScore(
                requested,
                java.util.Locale.forLanguageTag("zh-CN")
            ) >= 0
        )
        assertEquals(
            -1,
            offlineVoiceLocaleScore(
                requested,
                java.util.Locale.forLanguageTag("zh-Hant-TW")
            )
        )
    }

    @Test
    fun geminiNanoIsNotOfferedAsAValidatedChineseTutor() {
        assertFalse(
            promptModelSupportsTargetLanguage(
                ModelPreferences.PROMPT_GEMINI_NANO,
                LearningLanguage.SIMPLIFIED_CHINESE
            )
        )
        assertTrue(
            promptModelSupportsTargetLanguage(
                "prompt:huggingface:litert-community/gemma-4-e2b-it-litert-lm",
                LearningLanguage.SIMPLIFIED_CHINESE
            )
        )
    }
}
