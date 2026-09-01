package com.anis.larp.ui.freemode

import com.anis.larp.learning.LearningContentAction
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FreeModeUiStateTest {
    @Test
    fun frenchSpeechOverridesIncorrectUsLocaleFromModel() {
        val reply = parseGeneratedReply(
            rawReply = """
                LANGUAGE_TAG: en-US
                REPLY: Je ne peux pas transcrire l'audio car vous n'avez pas fourni de fichier audio.
            """.trimIndent(),
            fallbackLocale = Locale.US
        )

        assertEquals("fr", reply.locale.language)
        assertEquals("FR", reply.locale.country)
    }

    @Test
    fun englishSpeechOverridesIncorrectFrenchLocaleFromModel() {
        val reply = parseGeneratedReply(
            rawReply = """
                LANGUAGE_TAG: fr-FR
                REPLY: Hello, my name is Ana and I live in Paris.
            """.trimIndent(),
            fallbackLocale = Locale.FRANCE
        )

        assertEquals("en", reply.locale.language)
    }

    @Test
    fun shortFrenchReplyUsesFrenchVoiceLocale() {
        assertEquals(
            "fr",
            localeMatchingSpokenText("Merci !", Locale.US).language
        )
    }

    @Test
    fun pausedConversationRemainsActiveUntilExplicitlyEnded() {
        assertTrue(
            FreeModeUiState(
                phase = SpeechPhase.IDLE,
                conversationActive = true
            ).isActive
        )
    }

    @Test
    fun textComposerWaitsWhileTheModelOrVoiceIsBusy() {
        assertTrue(FreeModeUiState(modelsReady = true).canSendText)
        assertEquals(
            false,
            FreeModeUiState(
                modelsReady = true,
                phase = SpeechPhase.THINKING
            ).canSendText
        )
        assertEquals(
            false,
            FreeModeUiState(
                modelsReady = true,
                phase = SpeechPhase.SPEAKING
            ).canSendText
        )
    }

    @Test
    fun visibleTranscriptCombinesCommittedAndPartialText() {
        val state = FreeModeUiState(
            committedTranscript = "Bonjour",
            partialTranscript = "tout le monde"
        )

        assertEquals("Bonjour tout le monde", state.visibleTranscript)
    }

    @Test
    fun appendTextDoesNotIntroduceExtraWhitespace() {
        assertEquals("Bonjour le monde", appendText(" Bonjour ", " le monde "))
    }

    @Test
    fun generatedReplyUsesNanoLanguageTagInsteadOfDeviceLocale() {
        val reply = parseGeneratedReply(
            rawReply = """
                LANGUAGE_TAG: en-US
                REPLY: That sounds like a great idea.
            """.trimIndent(),
            fallbackLocale = Locale.FRANCE
        )

        assertEquals("That sounds like a great idea.", reply.text)
        assertEquals("en", reply.locale.language)
    }

    @Test
    fun mandarinLanguageTagIsNormalizedForAndroidTtsVoices() {
        val reply = parseGeneratedReply(
            rawReply = """
                LANGUAGE_TAG: cmn-Hans-CN
                REPLY: 很高兴认识你。
            """.trimIndent(),
            fallbackLocale = Locale.ENGLISH
        )

        assertEquals("zh", reply.locale.language)
    }

    @Test
    fun promptScaffoldingIsNeverReturnedAsSpeech() {
        val reply = parseGeneratedReply(
            rawReply = """
                CONVERSATION: none yet
                LANGUAGE_TAG: en-US
                REPLY: Welcome! Let's begin.
            """.trimIndent(),
            fallbackLocale = Locale.FRANCE
        )

        assertEquals("Welcome! Let's begin.", reply.text)
    }

    @Test
    fun scaffoldingOnlyResponseIsRejectedInsteadOfSpoken() {
        try {
            parseGeneratedReply(
                rawReply = "CONVERSATION: none yet",
                fallbackLocale = Locale.FRANCE
            )
            fail("Expected scaffolding-only response to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("aucune réponse"))
        }
    }

    @Test
    fun ttsTextDropsStandaloneLanguageTagsAndConversationMetadata() {
        assertEquals(
            "Welcome! Let's begin.",
            sanitizeTextForSpeech(
                """
                    en-US
                    Conversation: none yet
                    Assistant: Welcome! Let's begin.
                """.trimIndent()
            )
        )
    }

    @Test
    fun ttsTextDropsJsonAndToolProtocolFields() {
        assertEquals(
            "L'exercice est prêt.",
            sanitizeTextForSpeech(
                """
                    {
                      "LANGUAGE_TAG": "fr-FR",
                      "ACTION": "CREATE_EXERCISE",
                      "ACTION_TITLE": "Les salutations",
                      "TOOL_CALL": "create_exercise",
                      "REPLY": "L'exercice est prêt."
                    }
                """.trimIndent()
            )
        )
    }

    @Test
    fun ttsTextRemovesInlineCanonicalLocaleWithoutDamagingNormalProse() {
        assertEquals(
            "I'll answer in English. Keep this conversation going.",
            sanitizeTextForSpeech(
                "I'll answer in en-US English. Keep this conversation going."
            )
        )
    }

    @Test
    fun ttsTextKeepsHyphenatedLearningContent() {
        assertEquals(
            "jeo-neun means 'I' in this sentence.",
            sanitizeTextForSpeech("jeo-neun means 'I' in this sentence.")
        )
    }

    @Test
    fun ttsTextStripsAProtocolLabelEvenWhenItIsInline() {
        assertEquals(
            "Let's practice greetings.",
            sanitizeTextForSpeech("Reply: Conversation: Let's practice greetings.")
        )
    }

    @Test
    fun taggedLessonContentIsParsedButNotIncludedInSpeech() {
        val reply = parseGeneratedReply(
            rawReply = """
                ACTION: SUBMIT_LESSON_CONTENT
                ACTION_TOPIC: Daily routines
                ACTION_TARGETS: goes~va || school~école
                ACTION_SENTENCES: She goes to school.~Elle va à l'école.~0,1~She/goes/to school
                LANGUAGE_TAG: fr-FR
                REPLY: J'ai créé l'exercice dans l'onglet Exercices.
            """.trimIndent(),
            fallbackLocale = Locale.ENGLISH,
            contentLanguageTag = "en-US"
        )

        assertEquals(
            "J'ai créé l'exercice dans l'onglet Exercices.",
            reply.text
        )
        val action = reply.contentAction as LearningContentAction.CreateLessonContent
        assertEquals("en-US", action.languageTag)
        assertEquals("Daily routines", action.content.topic)
        assertEquals(listOf(0, 1), action.content.sentences.single().targetIndexes)
        assertEquals(listOf("She", "goes", "to school"), action.content.sentences.single().chunks)
    }

    @Test
    fun chineseInlinePinyinAndOneBasedIndexesAreNormalized() {
        val action = parseLearningContentAction(
            rawReply = """
                ACTION: SUBMIT_LESSON_CONTENT
                ACTION_TOPIC: Présentations
                ACTION_X1: 你好 (nǐ hǎo)
                ACTION_M1: Bonjour
                ACTION_R1: NONE
                ACTION_X2: 我是 (wǒ shì)
                ACTION_M2: Je suis
                ACTION_R2: NONE
                ACTION_X3: 叫 (jiào)
                ACTION_M3: S'appeler
                ACTION_R3: NONE
                ACTION_S1: 你好，我是张三。
                ACTION_SM1: Bonjour, je suis Zhang San.
                ACTION_I1: 1,2,3
                ACTION_C1: 你好 / 我是张三
                ACTION_S2: 我叫李四。
                ACTION_SM2: Je m'appelle Li Si.
                ACTION_I2: 2,3
                ACTION_C2: 我叫李四
            """.trimIndent(),
            fallbackLanguageTag = "zh-Hans-CN"
        ) as LearningContentAction.CreateLessonContent

        assertEquals(listOf("你好", "我是", "叫"), action.content.targets.map { it.text })
        assertEquals(
            listOf("nǐ hǎo", "wǒ shì", "jiào"),
            action.content.targets.map { it.reading }
        )
        assertEquals(listOf(0, 1), action.content.sentences[0].targetIndexes)
        assertEquals(listOf(2), action.content.sentences[1].targetIndexes)
    }

    @Test
    fun legacyGeneratedExerciseAndLessonProtocolsAreRejected() {
        assertEquals(
            null,
            parseLearningContentAction(
                "ACTION: CREATE_EXERCISE\nACTION_TITLE: Legacy",
                "en-US"
            )
        )
        assertEquals(
            null,
            parseLearningContentAction(
                "ACTION: CREATE_LESSON\nACTION_TITLE: Legacy",
                "en-US"
            )
        )
    }
}
