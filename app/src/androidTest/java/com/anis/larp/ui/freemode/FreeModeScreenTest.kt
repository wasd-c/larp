package com.anis.larp.ui.freemode

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anis.larp.ui.LarpApp
import com.anis.larp.ui.theme.LarpTheme
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.ExerciseDifficulty
import com.anis.larp.learning.ExerciseCompletion
import com.anis.larp.learning.ExercisePlan
import com.anis.larp.learning.LearnedWord
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.Lesson
import java.io.File
import java.util.Locale
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FreeModeScreenTest {

    // The v2 rule hangs with the Compose runtime required by Material 3 alpha17.
    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createComposeRule()

    private fun showFreeMode() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                LarpApp(
                    animationsEnabled = false,
                    skipOnboarding = true
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun tenStepPlan(): ExercisePlan = ExercisePlan(
        words = listOf(
            LearnedWord(
                text = "goes",
                pronunciation = "ɡoʊz",
                definition = "Forme de go à la troisième personne.",
                gapSentence = "She ___ to school every day.",
                distractors = listOf("going", "gone"),
                recallPrompt = "Complétez : He ___ home.",
                recallAnswer = "goes"
            ),
            LearnedWord(
                text = "school",
                pronunciation = "skuːl",
                definition = "École.",
                gapSentence = "The children are at ___.",
                distractors = listOf("teacher", "book"),
                recallPrompt = "Écrivez le lieu où les élèves étudient.",
                recallAnswer = "school"
            )
        ),
        hardPrompt = "Write a sentence using goes and school.",
        hardAnswer = "She goes to school.",
        finalSentence = "She ___ to ___ every ___ ___.",
        finalAnswers = listOf("goes", "school", "single", "day")
    )

    @Test
    fun repositoryPersistsOnlyApprovedTopicsForExercisesAndLessons() {
        val cacheDirectory = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
        val contentFile = File(
            cacheDirectory,
            "learning-topic-test-${System.nanoTime()}.json"
        )
        try {
            val repository = LearningContentRepository.createForTests(contentFile)
            val exercise = repository.createExercise(
                title = "At the airport",
                instructions = "Enregistrez vos bagages.",
                prompt = "Where is the check-in desk?",
                expectedAnswer = "It is near the entrance.",
                languageTag = "en-US",
                topic = "A made-up travel label"
            )
            val lesson = repository.createLesson(
                title = "Rain and sunshine",
                objective = "Parler du temps.",
                content = "It is raining today.",
                languageTag = "en-US",
                topic = "Something invented"
            )

            assertEquals("Aéroport", exercise.topic)
            assertEquals("Météo", lesson.topic)
            repository.completeExercise(
                id = exercise.id,
                mistakes = 2,
                elapsedMillis = 42_000L,
                hintsUsed = 1
            )
            repository.rateExercise(exercise.id, 4)
            val reloaded = LearningContentRepository.createForTests(contentFile)
                .state.value.exercises.single()
            assertEquals(2, reloaded.plan.words.size)
            assertEquals(2, reloaded.completion?.mistakes)
            assertEquals(42_000L, reloaded.completion?.elapsedMillis)
            assertEquals(1, reloaded.completion?.hintsUsed)
            assertEquals(4, reloaded.completion?.difficultyRating)
            val persisted = contentFile.readText()
            org.junit.Assert.assertFalse(persisted.contains("made-up"))
            org.junit.Assert.assertFalse(persisted.contains("Something invented"))
        } finally {
            contentFile.delete()
            File(contentFile.parentFile, "${contentFile.name}.partial").delete()
        }
    }

    @Test
    fun freeModeShowsInitialStateAndSelectedDestination() {
        showFreeMode()

        composeRule.onNodeWithText("larp").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation_Apprendre").assertIsSelected()
        composeRule.onNodeWithText("Commencer à parler").assertIsDisplayed()
    }

    @Test
    fun tabsAndNestedDictionaryNavigate() {
        showFreeMode()

        composeRule.onNodeWithTag("navigation_Exercices").performClick()
        composeRule.onNodeWithText("Exercices").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation_Exercices").assertIsSelected()

        composeRule.onNodeWithTag("navigation_Leçons").performClick()
        composeRule.onNodeWithText("Leçons").assertIsDisplayed()
        composeRule.onNodeWithTag("navigation_Leçons").assertIsSelected()

        composeRule.onNodeWithTag("navigation_Profil").performClick()
        composeRule.onNodeWithTag("profile_dictionary").performClick()
        composeRule.onNodeWithText("Dictionnaire").assertIsDisplayed()
        composeRule.onNodeWithTag("dictionary_back").performClick()
        composeRule.onNodeWithText("Profil").assertIsDisplayed()
    }

    @Test
    fun modelSettingsOpenFromTheVoiceScreen() {
        showFreeMode()

        composeRule.onNodeWithTag("open_model_settings").performClick()
        composeRule.onNodeWithText("Modèles").assertIsDisplayed()
        composeRule.onNodeWithText("Voix, conversation et écoute").assertIsDisplayed()
        composeRule.onNodeWithTag("model_settings_back").performClick()
        composeRule.onNodeWithText("larp").assertIsDisplayed()
    }

    @Test
    fun primaryActionShowsLiveTranscriptionAndStops() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf(FreeModeUiState(locale = Locale.FRANCE))
            }
            LarpTheme(dynamicColor = false) {
                FreeModeScreen(
                    uiState = state,
                    animationsEnabled = false,
                    onPrimaryAction = {
                        state = if (state.isActive) {
                            state.copy(
                                phase = SpeechPhase.IDLE,
                                conversationActive = false,
                                committedTranscript = state.visibleTranscript,
                                partialTranscript = ""
                            )
                        } else {
                            state.copy(
                                phase = SpeechPhase.LISTENING,
                                conversationActive = true,
                                partialTranscript = "Bonjour depuis le microphone",
                                recognitionMode = "Basique"
                            )
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("Commencer à parler").performClick()
        composeRule.onNodeWithText("Terminer la conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Écoute en cours").assertIsDisplayed()
        composeRule
            .onNodeWithText("Bonjour depuis le microphone")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Langue : fr-FR · Mode Basique").assertIsDisplayed()

        composeRule.onNodeWithText("Terminer la conversation").performClick()
        composeRule.onNodeWithText("Commencer à parler").assertIsDisplayed()
        composeRule.onNodeWithText("Prêt à transcrire").assertIsDisplayed()
        composeRule
            .onNodeWithText("Bonjour depuis le microphone")
            .assertIsDisplayed()
    }

    @Test
    fun thinkingUsesExpressiveSingleWordStatus() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                FreeModeScreen(
                    uiState = FreeModeUiState(
                        phase = SpeechPhase.THINKING,
                        thinkingWord = "Cerebrating",
                        conversationActive = true
                    ),
                    onPrimaryAction = {},
                    animationsEnabled = false
                )
            }
        }

        composeRule.onNodeWithText("Cerebrating").assertIsDisplayed()
    }

    @Test
    fun createdExerciseSheetOpensInOneClick() {
        var openedId: String? = null
        val created = CreatedLearningContent(
            id = "exercise:created",
            kind = CreatedLearningContentKind.EXERCISE,
            title = "Voyage en train",
            description = "Choisissez les phrases utiles à la gare.",
            topic = "Transports",
            difficulty = "Intermédiaire"
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                FreeModeScreen(
                    uiState = FreeModeUiState(createdContent = created),
                    onPrimaryAction = {},
                    onOpenCreatedContent = { openedId = it.id },
                    animationsEnabled = false
                )
            }
        }

        composeRule.onNodeWithText("Voyage en train").assertIsDisplayed()
        composeRule.onNodeWithTag("created_content_difficulty").assertIsDisplayed()
        composeRule.onNodeWithText("Intermédiaire").assertIsDisplayed()
        composeRule.onNodeWithTag("created_content_topic").assertIsDisplayed()
        composeRule.onNodeWithText("Transports").assertIsDisplayed()
        composeRule.onNodeWithTag("open_created_content").performClick()
        composeRule.runOnIdle { assertEquals(created.id, openedId) }
    }

    @Test
    fun createdExerciseOpenRequestIsConsumedBeforeReturningToTheTab() {
        val exercise = Exercise(
            id = "exercise:one-shot",
            title = "Demander son chemin",
            instructions = "Posez une question polie.",
            prompt = "How do I get to the station?",
            expectedAnswer = "Go straight ahead.",
            languageTag = "en-US",
            createdAtMillis = 1L,
            topic = "Directions"
        )
        var showExercises by mutableStateOf(true)
        var requestedId by mutableStateOf<String?>(exercise.id)
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                if (showExercises) {
                    com.anis.larp.ui.ExercisesScreen(
                        exercises = listOf(exercise),
                        requestedOpenId = requestedId,
                        onRequestedOpenHandled = { requestedId = null }
                    )
                } else {
                    androidx.compose.material3.Text("Libre")
                }
            }
        }

        composeRule.onNodeWithText("How do I get to the station?")
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, requestedId)
            showExercises = false
        }
        composeRule.onNodeWithText("Libre").assertIsDisplayed()
        composeRule.runOnIdle { showExercises = true }
        composeRule.waitForIdle()

        assertEquals(
            0,
            composeRule.onAllNodesWithText("How do I get to the station?")
                .fetchSemanticsNodes().size
        )
        composeRule.onNodeWithText("Demander son chemin").assertIsDisplayed()
    }

    @Test
    fun pullingDownExerciseLibraryRevealsSearch() {
        val exercise = Exercise(
            id = "exercise:search",
            title = "Restaurant",
            instructions = "Commandez poliment.",
            prompt = "Order a meal.",
            expectedAnswer = "Could I have the menu, please?",
            languageTag = "en-US",
            createdAtMillis = 1L
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(exercises = listOf(exercise))
            }
        }

        composeRule.onNodeWithTag("content_library_list").performTouchInput {
            swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 500)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_search").assertIsDisplayed()
    }

    @Test
    fun exerciseDetailChipReturnsToFilteredLibraryAndCanBeDismissed() {
        val beginner = Exercise(
            id = "exercise:beginner",
            title = "Premiers pas",
            instructions = "Présentez-vous.",
            prompt = "Introduce yourself.",
            expectedAnswer = "My name is Alex.",
            languageTag = "en-US",
            createdAtMillis = 1L,
            difficulty = ExerciseDifficulty.BEGINNER,
            topic = "Présentation"
        )
        val advanced = Exercise(
            id = "exercise:advanced",
            title = "Débat avancé",
            instructions = "Défendez votre opinion.",
            prompt = "Debate the topic.",
            expectedAnswer = "I would argue that…",
            languageTag = "en-US",
            createdAtMillis = 2L,
            difficulty = ExerciseDifficulty.ADVANCED,
            topic = "Débat"
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(beginner, advanced)
                )
            }
        }

        composeRule.onNodeWithText("Premiers pas").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Débutant").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library_search").assertIsDisplayed()
        composeRule.onNodeWithTag("active_filter_difficulty").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Débat avancé")
                .fetchSemanticsNodes().size
        )

        composeRule.onNodeWithContentDescription("Retirer le filtre Débutant")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Débat avancé").assertIsDisplayed()
    }

    @Test
    fun futureActionsAreDisabled() {
        showFreeMode()

        composeRule.onNodeWithTag("resume_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("write_action").assertIsNotEnabled()
        composeRule
            .onNodeWithText("Disponible dans une prochaine étape")
            .assertIsDisplayed()
    }

    @Test
    fun nanoReplyAndItsSpeechLanguageAreVisible() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                FreeModeScreen(
                    uiState = FreeModeUiState(
                        phase = SpeechPhase.SPEAKING,
                        conversationActive = true,
                        committedTranscript = "Can we continue in French?",
                        aiReply = "Bien sûr, continuons en français.",
                        replyLocale = Locale.FRANCE
                    ),
                    animationsEnabled = false,
                    onPrimaryAction = {}
                )
            }
        }

        composeRule.onNodeWithText("larp répond").assertIsDisplayed()
        composeRule.onNodeWithText("larp · fr-FR").assertIsDisplayed()
        composeRule
            .onNodeWithText("Bien sûr, continuons en français.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Terminer la conversation").assertIsDisplayed()
    }

    @Test
    fun generatedExerciseStartsTheFixedTenStepLearningFlow() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(
                        Exercise(
                            id = "exercise:test",
                            title = "Le présent simple",
                            instructions = "Complétez la phrase.",
                            prompt = "She ___ to school.",
                            expectedAnswer = "She goes to school.",
                            languageTag = "en-US",
                            createdAtMillis = 1L,
                            plan = tenStepPlan()
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Le présent simple").performClick()
        composeRule.onNodeWithText("Étape 1 sur 10").assertIsDisplayed()
        composeRule.onNodeWithText("Apprenez ce mot").assertIsDisplayed()
        composeRule.onNodeWithText("ɡoʊz").assertIsDisplayed()
        composeRule.onNodeWithText("Continuer").performClick()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("word_answer_goes").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("word_answer_goes").performTextInput("goes")
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("word_answer_goes").performImeAction()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Correct!").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("Glissez le bon mot dans la phrase").assertIsDisplayed()
    }

    @Test
    fun voicedAnswerUsesPronunciationFeedback() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(
                        Exercise(
                            id = "exercise:voice-feedback",
                            title = "Prononciation",
                            instructions = "Prononcez le mot.",
                            prompt = "Say goes.",
                            expectedAnswer = "goes",
                            languageTag = "en-US",
                            createdAtMillis = 1L,
                            plan = tenStepPlan()
                        )
                    ),
                    onRecognizeAnswer = { "goes" }
                )
            }
        }

        composeRule.onNodeWithText("Prononciation").performClick()
        composeRule.onNodeWithText("Continuer").performClick()
        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNodeWithText("Répondre")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Bonne prononciation!").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("Glissez le bon mot dans la phrase").assertIsDisplayed()
    }

    @Test
    fun leavingAnExerciseInProgressRequiresConfirmation() {
        val exercise = Exercise(
            id = "exercise:guard-exit",
            title = "Routine quotidienne",
            instructions = "Apprenez deux mots.",
            prompt = "Practice.",
            expectedAnswer = "goes",
            languageTag = "en-US",
            createdAtMillis = 1L,
            plan = tenStepPlan()
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(exercises = listOf(exercise))
            }
        }

        composeRule.onNodeWithText("Routine quotidienne").performClick()
        composeRule.onNodeWithText("Continuer").performClick()
        composeRule.onNodeWithContentDescription("Retour").performClick()
        composeRule.onNodeWithText("Quitter l’exercice ?").assertIsDisplayed()
        composeRule.onNodeWithText("Continuer l’exercice").performClick()
        composeRule.onNodeWithText("Étape 2 sur 10").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Retour").performClick()
        composeRule.onNodeWithText("Quitter").performClick()
        composeRule.onNodeWithText("Exercices").assertIsDisplayed()
        composeRule.onNodeWithText("Routine quotidienne").assertIsDisplayed()
    }

    @Test
    fun wrongAnswerStaysVisibleAndNextOnlyReturnsThroughUnlockedSteps() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(
                        Exercise(
                            id = "exercise:unlock-history",
                            title = "Navigation guidée",
                            instructions = "Répondez puis revenez en arrière.",
                            prompt = "Practice.",
                            expectedAnswer = "goes",
                            languageTag = "en-US",
                            createdAtMillis = 1L,
                            plan = tenStepPlan()
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Navigation guidée").performClick()
        composeRule.onNodeWithText("Continuer").performClick()
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("word_answer_goes").performTextInput("wrong")
        composeRule.onNodeWithTag("word_answer_goes").performImeAction()
        composeRule.onNodeWithText("Pas tout à fait. Réessayez.").assertIsDisplayed()
        composeRule.onNodeWithText("Suivant").assertIsNotEnabled()

        composeRule.onNodeWithTag("word_answer_goes").performTextClearance()
        composeRule.onNodeWithTag("word_answer_goes").performTextInput("goes")
        composeRule.onNodeWithTag("word_answer_goes").performImeAction()
        composeRule.onNodeWithText("Glissez le bon mot dans la phrase").assertIsDisplayed()
        composeRule.onNodeWithText("Précédent").performClick()
        composeRule.onNodeWithText("Étape 2 sur 10").assertIsDisplayed()
        composeRule.onNodeWithText("Suivant").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Glissez le bon mot dans la phrase").assertIsDisplayed()
    }

    @Test
    fun fixedTenStepExerciseCompletesWithSummaryAndDifficultyRating() {
        var completion: Triple<Int, Long, Int>? = null
        var rating: Int? = null
        val exercise = Exercise(
            id = "exercise:complete-flow",
            title = "Routine scolaire",
            instructions = "Apprenez deux mots liés.",
            prompt = "Practice school routines.",
            expectedAnswer = "She goes to school.",
            languageTag = "en-US",
            createdAtMillis = 1L,
            plan = tenStepPlan()
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(exercise),
                    onComplete = { _, mistakes, elapsed, hints ->
                        completion = Triple(mistakes, elapsed, hints)
                    },
                    onRateDifficulty = { _, value -> rating = value }
                )
            }
        }

        composeRule.onNodeWithText("Routine scolaire").performClick()
        composeRule.onNodeWithText("Continuer").performClick()
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("word_answer_goes").performTextInput("goes")
        composeRule.onNodeWithTag("word_answer_goes").performImeAction()
        composeRule.onNodeWithText("goes").performClick()
        composeRule.onNodeWithText("Vérifier").performClick()
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("recall_answer").performTextInput("goes")
        composeRule.onNodeWithTag("recall_answer").performImeAction()

        composeRule.onNodeWithText("Continuer").performClick()
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("word_answer_school").performTextInput("school")
        composeRule.onNodeWithTag("word_answer_school").performImeAction()
        composeRule.onNodeWithText("school").performClick()
        composeRule.onNodeWithText("Vérifier").performClick()
        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("recall_answer").performTextInput("school")
        composeRule.onNodeWithTag("recall_answer").performImeAction()

        composeRule.onNodeWithText("Répondre").performClick()
        composeRule.onNodeWithTag("recall_answer")
            .performTextInput("She goes to school.")
        composeRule.onNodeWithTag("recall_answer").performImeAction()
        composeRule.onNodeWithTag("final_typed_0").performTextInput("goes")
        composeRule.onNodeWithTag("final_typed_1").performTextInput("school")
        composeRule.onNodeWithText("single").performClick()
        composeRule.onNodeWithText("day").performClick()
        composeRule.onNodeWithText("Vérifier")
            .assertIsEnabled()
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Bravo !").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Erreurs : 0").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Indices utilisés", substring = true)
                .fetchSemanticsNodes().size
        )
        composeRule.runOnIdle {
            assertEquals(0, completion?.first)
            assertEquals(0, completion?.third)
        }
        composeRule.onNodeWithContentDescription("4 étoiles")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(4, rating) }
    }

    @Test
    fun legacyExerciseTypeStillUsesTheFixedTenStepPattern() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(
                        Exercise(
                            id = "exercise:multiple-choice",
                            title = "Commander poliment",
                            instructions = "Choisissez la formule la plus polie.",
                            prompt = "What would you say at a café?",
                            expectedAnswer = "Could I have a coffee, please?",
                            languageTag = "en-US",
                            createdAtMillis = 1L,
                            plan = tenStepPlan()
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Commander poliment").performClick()
        composeRule.onNodeWithText("Étape 1 sur 10").assertIsDisplayed()
        composeRule.onNodeWithText("Apprenez ce mot").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("exercise_choice_1")
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun unfinishedExercisesAreListedBeforeVisuallyCompletedExercises() {
        val unfinished = Exercise(
            id = "exercise:unfinished",
            title = "À commencer",
            instructions = "Nouvel exercice.",
            prompt = "Practice.",
            expectedAnswer = "Answer.",
            languageTag = "en-US",
            createdAtMillis = 2L,
            plan = tenStepPlan()
        )
        val completed = unfinished.copy(
            id = "exercise:completed",
            title = "Déjà terminé",
            completion = ExerciseCompletion(
                completedAtMillis = 3L,
                mistakes = 1,
                elapsedMillis = 90_000L,
                hintsUsed = 0,
                difficultyRating = 3
            )
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(completed, unfinished)
                )
            }
        }

        composeRule.onNodeWithText("À faire").assertIsDisplayed()
        composeRule.onNodeWithText("Terminés").assertIsDisplayed()
        val unfinishedTop = composeRule.onNodeWithText("À commencer")
            .fetchSemanticsNode().boundsInRoot.top
        val completedTop = composeRule.onNodeWithText("Déjà terminé")
            .fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(unfinishedTop < completedTop)
        composeRule.onNodeWithContentDescription("Déjà terminé terminé")
            .assertIsDisplayed()
    }

    @Test
    fun completedExerciseWordsAppearInDictionaryWithProvenance() {
        var openedExerciseId: String? = null
        val completed = Exercise(
            id = "exercise:dictionary",
            title = "Le présent simple",
            instructions = "Apprenez deux mots.",
            prompt = "Practice.",
            expectedAnswer = "She goes to school.",
            languageTag = "en-US",
            createdAtMillis = 1L,
            plan = tenStepPlan(),
            completion = ExerciseCompletion(
                completedAtMillis = 1_700_000_000_000L,
                mistakes = 0,
                elapsedMillis = 60_000L,
                hintsUsed = 0
            )
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ProfileScreen(
                    dictionaryOpen = true,
                    onOpenDictionary = {},
                    onCloseDictionary = {},
                    exercises = listOf(completed),
                    onOpenExercise = { openedExerciseId = it }
                )
            }
        }

        composeRule.onNodeWithText("goes").performClick()
        composeRule.onNodeWithText("Date learned:", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Revenir à l'exercice").performClick()
        composeRule.runOnIdle {
            assertEquals(completed.id, openedExerciseId)
        }
    }

    @Test
    fun exerciseHeaderImportMenuCreatesFromText() {
        var importedText: String? = null
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = emptyList(),
                    onImportText = { importedText = it }
                )
            }
        }

        composeRule.onNodeWithTag("exercise_import_menu").performClick()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
        composeRule.onNodeWithTag("import_exercise_text").performClick()
        composeRule.onNodeWithTag("exercise_import_text_input")
            .performTextInput("A long enough passage to make a useful language learning exercise.")
        composeRule.onNodeWithTag("confirm_exercise_import").performClick()

        composeRule.runOnIdle {
            assertEquals(
                "A long enough passage to make a useful language learning exercise.",
                importedText
            )
        }
    }

    @Test
    fun exerciseHeaderImportMenuCreatesFromYoutubeLink() {
        var importedUrl: String? = null
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = emptyList(),
                    onImportYoutube = { importedUrl = it }
                )
            }
        }

        composeRule.onNodeWithTag("exercise_import_menu").performClick()
        composeRule.onNodeWithTag("import_exercise_youtube").performClick()
        composeRule.onNodeWithTag("exercise_import_youtube_input")
            .performTextInput("https://youtu.be/dQw4w9WgXcQ")
        composeRule.onNodeWithTag("confirm_exercise_import").performClick()

        composeRule.runOnIdle {
            assertEquals("https://youtu.be/dQw4w9WgXcQ", importedUrl)
        }
    }

    @Test
    fun generatedLessonOpensWithObjectiveAndContent() {
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.LessonsScreen(
                    lessons = listOf(
                        Lesson(
                            id = "lesson:test",
                            title = "Les salutations",
                            objective = "Saluer naturellement.",
                            content = "Hello signifie bonjour.",
                            languageTag = "en-US",
                            createdAtMillis = 1L
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Les salutations").performClick()
        composeRule.onNodeWithText("Saluer naturellement.").assertIsDisplayed()
        composeRule.onNodeWithText("Hello signifie bonjour.").assertIsDisplayed()
    }

    @Test
    fun swipingExerciseRightRevealsArchiveAction() {
        var archivedId: String? = null
        val exercise = Exercise(
            id = "exercise:swipe",
            title = "Shopping",
            instructions = "Répondez au vendeur.",
            prompt = "How much is it?",
            expectedAnswer = "It is ten euros.",
            languageTag = "en-US",
            createdAtMillis = 1L
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.ExercisesScreen(
                    exercises = listOf(exercise),
                    onArchive = { archivedId = it.id }
                )
            }
        }

        val row = composeRule.onNodeWithTag("exercise_${exercise.id}")
        val content = composeRule.onNodeWithTag("content_exercise_${exercise.id}")
        val initialBounds = content.fetchSemanticsNode().boundsInRoot
        row.performTouchInput {
            swipe(
                start = center,
                end = center.copy(x = center.x + width * 0.15f),
                durationMillis = 300
            )
        }
        composeRule.waitForIdle()
        val revealedBounds = content.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(
            initialBounds.width * 0.24f,
            revealedBounds.left - initialBounds.left,
            initialBounds.width * 0.03f
        )
        composeRule.onNodeWithTag("archive_exercise_${exercise.id}")
            .performClick()

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(exercise.id, archivedId)
        }
    }

    @Test
    fun swipingLessonLeftCollectsPromptAndRequestsRemix() {
        var receivedPrompt: String? = null
        val lesson = Lesson(
            id = "lesson:swipe",
            title = "Greetings",
            objective = "Saluer naturellement.",
            content = "Hello signifie bonjour.",
            languageTag = "en-US",
            createdAtMillis = 1L
        )
        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                com.anis.larp.ui.LessonsScreen(
                    lessons = listOf(lesson),
                    onRemix = { _, guidance -> receivedPrompt = guidance }
                )
            }
        }

        val row = composeRule.onNodeWithTag("lesson_${lesson.id}")
        val content = composeRule.onNodeWithTag("content_lesson_${lesson.id}")
        val initialBounds = content.fetchSemanticsNode().boundsInRoot
        row.performTouchInput {
            swipe(
                start = center,
                end = center.copy(x = center.x - width * 0.12f),
                durationMillis = 300
            )
        }
        composeRule.waitForIdle()
        val revealedBounds = content.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(
            -(initialBounds.width * 0.20f),
            revealedBounds.left - initialBounds.left,
            initialBounds.width * 0.03f
        )
        composeRule.onNodeWithTag("remix_lesson_${lesson.id}")
            .performClick()
        composeRule.onNodeWithTag("remix_prompt_${lesson.id}")
            .performTextInput("Ajoute une situation au restaurant.")
        composeRule.onNodeWithTag("confirm_remix_${lesson.id}")
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            receivedPrompt == "Ajoute une situation au restaurant."
        }
    }
}
