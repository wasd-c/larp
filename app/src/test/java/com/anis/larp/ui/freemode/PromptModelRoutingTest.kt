package com.anis.larp.ui.freemode

import com.anis.larp.learning.Exercise
import com.anis.larp.learning.Lesson
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.model.LearningLanguage
import com.anis.larp.model.AccelerationKind
import com.anis.larp.model.DeviceAccelerationProfile
import com.anis.larp.model.PromptModelRecord
import com.anis.larp.model.speechRecognitionLocaleFor
import com.anis.larp.model.requestsLearningLanguageSwitch
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
    fun liteRtSamplerSupportsSingleOutputHeadArtifacts() {
        val sampler = compatibleLiteRtSamplerConfig()

        assertEquals(1, sampler.topK)
        assertEquals(0.95, sampler.topP, 0.0)
        assertEquals(1.0, sampler.temperature, 0.0)
        assertEquals(0, sampler.seed)
    }

    @Test
    fun modelContextKeepsOnlyRecentTurnsWithinTheLocalBudget() {
        val turns = (0 until 6).map { index ->
            ConversationTurn("user-$index", "assistant-$index")
        }

        val recent = compactConversationHistory(turns)
        val bounded = compactConversationHistory(
            listOf(ConversationTurn("u".repeat(4_000), "a".repeat(4_000)))
        )

        assertEquals(listOf("user-2", "user-3", "user-4", "user-5"), recent.map { it.userMessage })
        assertTrue(bounded.sumOf { it.userMessage.length + it.assistantMessage.length } <= 1_600)
    }

    @Test
    fun liteRtFallbackCanBeLimitedToOneAdditionalInference() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            generateVerifiedLearningContentReply(
                kind = LearningContentRequestKind.EXERCISE,
                transcript = "Create an exercise",
                tutorContext = TutorContext(Locale.FRANCE, Locale.US),
                conversationHistory = emptyList(),
                modelLabel = "Gemma",
                maxAttempts = 1
            ) {
                attempts += 1
                "LANGUAGE_TAG: fr-FR\nREPLY: Incomplete"
            }
        }.exceptionOrNull()

        assertEquals(1, attempts)
        assertNotNull(failure)
    }

    @Test
    fun incompatibleArtifactSelectsTheMatchingDeviceVariant() {
        val selected = promptRecord("selected", "generic.litertlm")
        val fallback = promptRecord("fallback", "device-npu.litertlm")
        val profile = DeviceAccelerationProfile(
            preferredKind = AccelerationKind.NPU,
            gemmaArtifactFileName = "device-npu.litertlm",
            label = "Test NPU",
            incompatibleArtifactFileNames = setOf("generic.litertlm")
        )

        assertEquals(
            fallback,
            compatibleArtifactFallback(listOf(selected, fallback), selected, profile)
        )
    }

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
    fun lessonChatPromptUsesTheLessonAsReferenceAndDisablesCreationTools() {
        val prompt = tutorPrompt(
            transcript = "Pourquoi utilise-t-on me llamo ?",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(
                nativeLanguage = Locale.FRANCE,
                targetLanguage = Locale.forLanguageTag("es-ES")
            ),
            lessonContext = LessonChatContext(
                id = "lesson:introductions",
                title = "Se présenter",
                objective = "Dire son nom.",
                content = "Me llamo Ana.",
                topic = "Présentation"
            )
        )

        assertTrue(prompt.contains("BEGIN LESSON lesson:introductions"))
        assertTrue(prompt.contains("Me llamo Ana."))
        assertFalse(prompt.contains("submit_lesson_content"))
        assertTrue(prompt.contains("Learner's current message: Pourquoi"))
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

        assertTrue(prompt.contains("submit_lesson_content"))
        assertFalse(prompt.contains("create_exercise"))
        assertTrue(prompt.contains("builds every exercise step locally"))
    }

    @Test
    fun geminiGetsStructuredLocalCreationActions() {
        val prompt = tutorPrompt(
            transcript = "Crée un exercice de vocabulaire",
            recognitionLocale = Locale.FRANCE,
            tutorContext = TutorContext(Locale.FRANCE, Locale.ENGLISH),
            toolMode = TutorToolMode.TAGGED_ACTIONS
        )

        assertTrue(prompt.contains("ACTION: SUBMIT_LESSON_CONTENT"))
        assertTrue(prompt.contains("ACTION_X1"))
        assertTrue(prompt.contains("ACTION_S2"))
        assertTrue(prompt.contains("ACTION: NONE"))
        assertFalse(prompt.contains("ACTION_CHOICES"))
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
        assertEquals(
            LearningContentRequestKind.EXERCISE,
            requestedLearningContentKind(
                "Apprends-moi à me présenter en chinois."
            )
        )
    }

    @Test
    fun leakedNativeToolCallIsRoutedBackThroughVerifiedCreation() {
        val rawReply =
            "submit_lesson_content{c1:<|\\\">你好<|\\\">,i1:<|\\\">1<|\\\">}<tool_call>"

        assertEquals(
            LearningContentRequestKind.EXERCISE,
            learningContentKindFromRawToolCall(rawReply)
        )
        assertEquals("", sanitizeTextForSpeech(rawReply))
    }

    @Test
    fun explicitSpanishCreationOverridesTheEnglishDefault() = runBlocking {
        val request = "Créer un exercice pour apprendre à me présenter en Espagnol"
        val explicitLanguage = requireNotNull(
            LearningLanguage.explicitlyNamedIn(request)
        )
        val reply = generateVerifiedLearningContentReply(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = request,
            tutorContext = TutorContext(Locale.FRANCE, explicitLanguage.locale),
            conversationHistory = emptyList(),
            modelLabel = "Gemma",
            maxAttempts = 1
        ) {
            """
                ACTION: SUBMIT_LESSON_CONTENT
                ACTION_TOPIC: Presentarse
                ACTION_X1: me llamo
                ACTION_M1: je m'appelle
                ACTION_X2: soy
                ACTION_M2: je suis
                ACTION_X3: vivo
                ACTION_M3: j'habite
                ACTION_S1: Me llamo Ana y vivo aquí.
                ACTION_SM1: Je m'appelle Ana et j'habite ici.
                ACTION_I1: 0,2
                ACTION_S2: Soy Ana y vivo aquí.
                ACTION_SM2: Je suis Ana et j'habite ici.
                ACTION_I2: 1,2
                LANGUAGE_TAG: fr-FR
                REPLY: L'exercice en espagnol est prêt.
            """.trimIndent()
        }

        val action = reply.contentAction as LearningContentAction.CreateLessonContent
        assertEquals("es-ES", action.languageTag)
        assertEquals("es", explicitLanguage.locale.language)
    }

    @Test
    fun explicitFreeModeSwitchNeedsSwitchWording() {
        assertTrue(requestsLearningLanguageSwitch("Je veux apprendre l'espagnol."))
        assertTrue(requestsLearningLanguageSwitch("Passons à l'allemand."))
        assertFalse(requestsLearningLanguageSwitch("Quelle différence entre espagnol et italien ?"))
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
    fun dedicatedCreationPromptRequiresOnlyCompactLinguisticFields() {
        val prompt = learningContentPrompt(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = "Crée un exercice sur les achats.",
            tutorContext = TutorContext(Locale.FRANCE, Locale.US)
        )

        assertTrue(prompt.contains("ACTION: SUBMIT_LESSON_CONTENT"))
        assertTrue(prompt.contains("ACTION_X1:"))
        assertTrue(prompt.contains("ACTION_M3:"))
        assertTrue(prompt.contains("ACTION_S2:"))
        assertFalse(prompt.contains("ACTION_EXPECTED_ANSWER:"))
        assertFalse(prompt.contains("ACTION_CHOICES:"))
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
                    ACTION: SUBMIT_LESSON_CONTENT
                    ACTION_TOPIC: Shopping
                    ACTION_X1: costs
                    ACTION_M1: coûte
                    ACTION_X2: dollars
                    ACTION_M2: dollars
                    ACTION_X3: ten
                    ACTION_M3: dix
                    ACTION_S1: It costs ten dollars.
                    ACTION_SM1: Cela coûte dix dollars.
                    ACTION_I1: 0,1,2
                    ACTION_S2: This costs five dollars.
                    ACTION_SM2: Cela coûte cinq dollars.
                    ACTION_I2: 0,1
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
    fun verifiedCreationRetriesWhenCompactFieldsDecodeToNoUsableContent() = runBlocking {
        var attempts = 0
        val reply = generateVerifiedLearningContentReply(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = "Crée un exercice pour me présenter.",
            tutorContext = TutorContext(Locale.FRANCE, Locale.US),
            conversationHistory = emptyList(),
            modelLabel = "Gemma"
        ) {
            attempts += 1
            if (attempts == 1) {
                """
                    ACTION: SUBMIT_LESSON_CONTENT
                    ACTION_TOPIC: Introductions
                    ACTION_TARGETS: [{badly formatted}]
                    ACTION_SENTENCES: [{badly formatted}]
                    REPLY: Prêt.
                """.trimIndent()
            } else {
                """
                    ACTION: SUBMIT_LESSON_CONTENT
                    ACTION_TOPIC: Introductions
                    ACTION_X1: name
                    ACTION_M1: nom
                    ACTION_X2: live
                    ACTION_M2: habiter
                    ACTION_X3: Paris
                    ACTION_M3: Paris
                    ACTION_S1: My name is Ana.
                    ACTION_SM1: Je m'appelle Ana.
                    ACTION_I1: 0
                    ACTION_S2: I live in Paris.
                    ACTION_SM2: J'habite à Paris.
                    ACTION_I2: 1,2
                    LANGUAGE_TAG: fr-FR
                    REPLY: L'exercice est disponible dans larp.
                """.trimIndent()
            }
        }

        assertEquals(2, attempts)
        val action = reply.contentAction as LearningContentAction.CreateLessonContent
        assertEquals(3, action.content.targets.size)
        assertEquals(2, action.content.sentences.size)
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
        assertTrue(request.contains("Topic: Culture"))
        assertTrue(request.contains("Prompt: How much is it?"))
        assertFalse(request.contains("Instructions: Répondez"))
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

    private fun promptRecord(id: String, artifact: String) = PromptModelRecord(
        id = id,
        displayName = "Gemma 4",
        filePath = "/tmp/$artifact",
        source = "test",
        repository = "litert-community/gemma-4-e2b-it-litert-lm",
        artifactFileName = artifact
    )
}
