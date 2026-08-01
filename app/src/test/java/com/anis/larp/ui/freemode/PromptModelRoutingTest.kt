package com.anis.larp.ui.freemode

import com.anis.larp.learning.Exercise
import com.anis.larp.learning.Lesson
import com.anis.larp.model.LearningLanguage
import com.anis.larp.model.speechRecognitionLocaleFor
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptModelRoutingTest {
    @Test
    fun knownPromptModelsUseCompactRuntimeLabels() {
        assertEquals("Gemini", knownModelLabel("Gemini Nano"))
        assertEquals(
            "Gemma",
            knownModelLabel(
                displayName = "Gemma 4",
                repository = "litert-community/gemma-4-e2b-it-litert-lm"
            )
        )
        assertEquals("Le modèle", knownModelLabel("Mon modèle"))
    }

    @Test
    fun tutorPromptUsesPersistedNativeAndTargetLanguages() {
        val prompt = tutorPrompt(
            transcript = "Hola",
            recognitionLocale = Locale.forLanguageTag("es-ES"),
            tutorContext = TutorContext(
                nativeLanguage = Locale.FRENCH,
                targetLanguage = Locale.forLanguageTag("ko-KR")
            )
        )

        assertTrue(prompt.contains("native language is fr"))
        assertTrue(prompt.contains("learning ko-KR"))
        assertTrue(prompt.contains("Learner's current message: Hola"))
    }

    @Test
    fun simplifiedChineseUsesMlKitAndAndroidSpecificLanguageTags() {
        assertEquals(
            "zh-Hans-CN",
            LearningLanguage.SIMPLIFIED_CHINESE.locale.toLanguageTag()
        )
        assertEquals(
            "cmn-Hans-CN",
            LearningLanguage.SIMPLIFIED_CHINESE
                .speechRecognitionLocale
                .toLanguageTag()
        )
    }

    @Test
    fun speechRecognitionUsesThePersistedNativeLanguage() {
        assertEquals(
            "fr-FR",
            speechRecognitionLocaleFor("fr-FR").toLanguageTag()
        )
        assertEquals(
            "cmn-Hans-CN",
            speechRecognitionLocaleFor("zh-CN").toLanguageTag()
        )
    }

    @Test
    fun tutorPromptKeepsPriorConversationTurns() {
        val prompt = tutorPrompt(
            transcript = "Et ensuite ?",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(Locale.FRANCE, Locale.ENGLISH),
            conversationHistory = listOf(
                ConversationTurn(
                    userMessage = "Bonjour",
                    assistantMessage = "Hello!"
                )
            )
        )

        assertTrue(prompt.contains("LEARNER: Bonjour"))
        assertTrue(prompt.contains("TUTOR: Hello!"))
        assertTrue(prompt.contains("Learner's current message: Et ensuite ?"))
    }

    @Test
    fun firstTurnDoesNotContainSpokenConversationSentinel() {
        val prompt = tutorPrompt(
            transcript = "Bonjour",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(Locale.FRANCE, Locale.ENGLISH)
        )

        assertFalse(prompt.contains("CONVERSATION: none yet"))
        assertFalse(prompt.contains("Previous turns:"))
    }

    @Test
    fun gemmaIsExplicitlyAwareOfNativeCreationTools() {
        val prompt = tutorPrompt(
            transcript = "Crée une leçon sur les salutations",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(Locale.FRANCE, Locale.ENGLISH),
            toolMode = TutorToolMode.NATIVE
        )

        assertTrue(prompt.contains("create_exercise"))
        assertTrue(prompt.contains("create_lesson"))
        assertTrue(prompt.contains("call exactly the matching tool"))
    }

    @Test
    fun geminiGetsStructuredLocalCreationActions() {
        val prompt = tutorPrompt(
            transcript = "Crée un exercice de vocabulaire",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(Locale.FRANCE, Locale.ENGLISH),
            toolMode = TutorToolMode.TAGGED_ACTIONS
        )

        assertTrue(prompt.contains("ACTION: CREATE_EXERCISE"))
        assertTrue(prompt.contains("ACTION: CREATE_LESSON"))
        assertTrue(prompt.contains("ACTION: NONE"))
        assertTrue(prompt.contains("Présentations, Famille, Routine"))
        assertTrue(prompt.contains("Société, Avenir"))
        assertFalse(prompt.contains("1. Présentations"))
    }

    @Test
    fun explicitFrenchExerciseRequestUsesVerifiedCreationPath() {
        assertEquals(
            LearningContentRequestKind.EXERCISE,
            requestedLearningContentKind(
                "D'accord. Je te laisse créer exercice sur le passé composé."
            )
        )
        assertEquals(
            LearningContentRequestKind.LESSON,
            requestedLearningContentKind("Prépare-moi une leçon sur les salutations.")
        )
    }

    @Test
    fun shortCreationFollowUpUsesRecentConversationTopic() {
        assertEquals(
            LearningContentRequestKind.EXERCISE,
            requestedLearningContentKind(
                transcript = "Oui, crée-le maintenant.",
                conversationHistory = listOf(
                    ConversationTurn(
                        userMessage = "Je veux travailler chez un commerçant.",
                        assistantMessage = "Voulez-vous un exercice de conversation ?"
                    )
                )
            )
        )
    }

    @Test
    fun mentioningExistingExerciseDoesNotCreateAnotherOne() {
        assertNull(requestedLearningContentKind("Corrige ma réponse à cet exercice."))
        assertNull(requestedLearningContentKind("How do I answer this exercise?"))
    }

    @Test
    fun dedicatedCreationPromptRequiresCompleteSaveableFields() {
        val prompt = learningContentPrompt(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = "Crée un exercice sur les achats.",
            tutorContext = TutorContext(Locale.FRANCE, Locale.US)
        )

        assertTrue(prompt.contains("ACTION: CREATE_EXERCISE"))
        assertTrue(prompt.contains("ACTION_EXPECTED_ANSWER:"))
        assertTrue(prompt.contains("ACTION_EXERCISE_TYPE:"))
        assertTrue(prompt.contains("ACTION_CHOICES:"))
        assertTrue(prompt.contains("ACTION_LANGUAGE_TAG: en-US"))
        assertTrue(prompt.contains("Do not ask another question"))
    }

    @Test
    fun verifiedCreationRetriesUntilModelProvidesPersistableAction() = runBlocking {
        var attempts = 0
        val reply = generateVerifiedLearningContentReply(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = "Crée-moi un exercice sur les achats.",
            tutorContext = TutorContext(Locale.FRANCE, Locale.US),
            conversationHistory = emptyList(),
            modelLabel = "Gemma"
        ) {
            attempts += 1
            if (attempts == 1) {
                "LANGUAGE_TAG: fr-FR\nREPLY: Je vais créer un exercice."
            } else {
                """
                    ACTION: CREATE_EXERCISE
                    ACTION_TITLE: Shopping conversation
                    ACTION_INSTRUCTIONS: Répondez au commerçant en anglais.
                    ACTION_PROMPT: How much does this cost?
                    ACTION_EXPECTED_ANSWER: It costs ten dollars.
                    ACTION_EXERCISE_TYPE: FREE_RESPONSE
                    ACTION_CHOICES: NONE
                    ACTION_LANGUAGE_TAG: en-US
                    LANGUAGE_TAG: fr-FR
                    REPLY: L'exercice est disponible dans larp.
                """.trimIndent()
            }
        }

        assertEquals(2, attempts)
        assertNotNull(reply.contentAction)
        assertEquals("L'exercice est disponible dans larp.", reply.text)
    }

    @Test
    fun creationClaimsAreRecognizedOnlyWhenTheyAssertLocalWork() {
        assertTrue(
            claimsUnverifiedContentCreation(
                "Okay, I have created an exercise for you."
            )
        )
        assertTrue(
            claimsUnverifiedContentCreation(
                "L'exercice est en cours de création."
            )
        )
        assertFalse(
            claimsUnverifiedContentCreation(
                "Would you like me to explain this exercise?"
            )
        )
    }

    @Test
    fun exerciseRemixPromptIncludesOriginalAndLearnerDirections() {
        val request = exerciseRemixRequest(
            exercise = Exercise(
                id = "exercise:test",
                title = "At the market",
                instructions = "Répondez au vendeur.",
                prompt = "How much is it?",
                expectedAnswer = "It is ten euros.",
                languageTag = "en-US",
                createdAtMillis = 1L
            ),
            guidance = "Make it harder and focus on bargaining."
        )

        assertTrue(request.contains("Make it harder and focus on bargaining."))
        assertTrue(request.contains("Title: At the market"))
        assertTrue(request.contains("Prompt: How much is it?"))
        assertTrue(request.contains("language tag en-US"))
        assertTrue(request.contains("complete standalone exercise"))
    }

    @Test
    fun lessonRemixPromptIncludesFullMultilineLesson() {
        val request = lessonRemixRequest(
            lesson = Lesson(
                id = "lesson:test",
                title = "Greetings",
                objective = "Saluer naturellement.",
                content = "Hello means bonjour.\nGood evening means bonsoir.",
                languageTag = "en-US",
                createdAtMillis = 1L
            ),
            guidance = "Add examples for a formal dinner."
        )

        assertTrue(request.contains("Add examples for a formal dinner."))
        assertTrue(request.contains("Hello means bonjour.\nGood evening means bonsoir."))
        assertTrue(request.contains("complete standalone lesson"))
    }
}
