package com.anis.larp.ui.freemode

import com.anis.larp.learning.LearningContentAction
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FreeModeUiStateTest {
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
    fun taggedExerciseActionIsParsedButNotIncludedInSpeech() {
        val reply = parseGeneratedReply(
            rawReply = """
                ACTION: CREATE_EXERCISE
                ACTION_TITLE: Present simple
                ACTION_INSTRUCTIONS: Complétez la phrase.
                ACTION_PROMPT: She ___ to school every day.
                ACTION_EXPECTED_ANSWER: She goes to school every day.
                ACTION_EXERCISE_TYPE: FILL_BLANK
                ACTION_CHOICES: NONE
                ACTION_DIFFICULTY: BEGINNER
                ACTION_TOPIC: Daily routines
                ACTION_LANGUAGE_TAG: en-US
                LANGUAGE_TAG: fr-FR
                REPLY: J'ai créé l'exercice dans l'onglet Exercices.
            """.trimIndent(),
            fallbackLocale = Locale.ENGLISH
        )

        assertEquals(
            "J'ai créé l'exercice dans l'onglet Exercices.",
            reply.text
        )
        val action = reply.contentAction as LearningContentAction.CreateExercise
        assertEquals("Present simple", action.title)
        assertEquals("en-US", action.languageTag)
        assertEquals(com.anis.larp.learning.ExerciseType.FILL_BLANK, action.type)
        assertEquals(
            com.anis.larp.learning.ExerciseDifficulty.BEGINNER,
            action.difficulty
        )
        assertEquals("Routine", action.topic)
    }

    @Test
    fun taggedMultipleChoiceActionParsesInteractiveChoices() {
        val action = parseLearningContentAction(
            rawReply = """
                ACTION: CREATE_EXERCISE
                ACTION_TITLE: At the café
                ACTION_INSTRUCTIONS: Choisissez la réponse polie.
                ACTION_PROMPT: What would you say when ordering coffee?
                ACTION_EXPECTED_ANSWER: Could I have a coffee, please?
                ACTION_EXERCISE_TYPE: MULTIPLE_CHOICE
                ACTION_CHOICES: Give coffee. || Could I have a coffee, please? || Coffee now.
                ACTION_LANGUAGE_TAG: en-US
            """.trimIndent(),
            fallbackLanguageTag = "en-US"
        ) as LearningContentAction.CreateExercise

        assertEquals(
            com.anis.larp.learning.ExerciseType.MULTIPLE_CHOICE,
            action.type
        )
        assertEquals(3, action.choices.size)
        assertTrue(action.choices.contains(action.expectedAnswer))
    }

    @Test
    fun olderExerciseActionWithoutInteractiveMetadataStillCreatesFreeResponse() {
        val action = parseLearningContentAction(
            rawReply = """
                ACTION: CREATE_EXERCISE
                ACTION_TITLE: At the shop
                ACTION_INSTRUCTIONS: Répondez au commerçant.
                ACTION_PROMPT: Ask politely for a blue shirt.
                ACTION_EXPECTED_ANSWER: Do you have this shirt in blue, please?
                ACTION_LANGUAGE_TAG: en-US
            """.trimIndent(),
            fallbackLanguageTag = "en-US"
        ) as LearningContentAction.CreateExercise

        assertEquals(com.anis.larp.learning.ExerciseType.FREE_RESPONSE, action.type)
        assertTrue(action.choices.isEmpty())
    }

    @Test
    fun multipleChoiceAddsReferenceAnswerWhenModelFormatsItSeparately() {
        val action = parseLearningContentAction(
            rawReply = """
                ACTION: CREATE_EXERCISE
                ACTION_TITLE: Polite shopping
                ACTION_INSTRUCTIONS: Choisissez la phrase polie.
                ACTION_PROMPT: Which sentence is polite?
                ACTION_EXPECTED_ANSWER: Could I try this on, please?
                ACTION_EXERCISE_TYPE: multiple choice
                ACTION_CHOICES: ["I try this.", "Give it to me."]
                ACTION_LANGUAGE_TAG: en-US
            """.trimIndent(),
            fallbackLanguageTag = "en-US"
        ) as LearningContentAction.CreateExercise

        assertEquals(com.anis.larp.learning.ExerciseType.MULTIPLE_CHOICE, action.type)
        assertTrue(action.choices.contains(action.expectedAnswer))
    }

    @Test
    fun taggedLessonActionSupportsEscapedParagraphs() {
        val reply = parseGeneratedReply(
            rawReply = """
                ACTION: CREATE_LESSON
                ACTION_TITLE: Greetings
                ACTION_OBJECTIVE: Greet someone naturally.
                ACTION_CONTENT: Hello means bonjour.\\nGood evening means bonsoir.
                ACTION_LANGUAGE_TAG: en-US
                LANGUAGE_TAG: fr-FR
                REPLY: La leçon est prête.
            """.trimIndent(),
            fallbackLocale = Locale.ENGLISH
        )

        val action = reply.contentAction as LearningContentAction.CreateLesson
        assertEquals(
            "Hello means bonjour.\nGood evening means bonsoir.",
            action.content
        )
    }

    @Test
    fun taggedLessonActionSupportsNaturalMultilineContent() {
        val reply = parseGeneratedReply(
            rawReply = """
                ACTION: CREATE_LESSON
                ACTION_TITLE: Greeting a shopkeeper
                ACTION_OBJECTIVE: Savoir saluer et demander un produit.
                ACTION_CONTENT: Start by saying “Hello”.
                Ask politely: “Do you have this in blue?”

                Finish with “Thank you, goodbye.”
                ACTION_LANGUAGE_TAG: en-US
                LANGUAGE_TAG: fr-FR
                REPLY: La leçon est prête.
            """.trimIndent(),
            fallbackLocale = Locale.ENGLISH
        )

        val action = reply.contentAction as LearningContentAction.CreateLesson
        assertEquals(
            "Start by saying “Hello”.\n" +
                "Ask politely: “Do you have this in blue?”\n\n" +
                "Finish with “Thank you, goodbye.”",
            action.content
        )
    }
}
