package com.anis.larp.ui.freemode

import androidx.test.platform.app.InstrumentationRegistry
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.model.ModelPreferences
import com.anis.larp.model.PromptModelCatalog
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test

class ImportedModelCreationTest {
    @Ignore("Manual device verification; requires the user's selected external model")
    @Test
    fun selectedImportedModelGeneratesCompleteExerciseRemix() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ModelPreferences(context)
        assumeTrue(
            "This verification needs a selected imported LiteRT model.",
            preferences.promptModelId != ModelPreferences.PROMPT_GEMINI_NANO
        )
        val record = PromptModelCatalog(context).find(preferences.promptModelId)
        assumeNotNull(record)
        val generator = LiteRtReplyGenerator(context)
        val original = Exercise(
            id = "exercise:remix-test",
            title = "At the shop",
            instructions = "Répondez au commerçant en anglais.",
            prompt = "Ask how much a shirt costs.",
            expectedAnswer = "How much does this shirt cost?",
            languageTag = "en-US",
            createdAtMillis = 1L
        )

        try {
            val reply = generator.generateReply(
                record = requireNotNull(record),
                transcript = exerciseRemixRequest(
                    original,
                    "Make it more difficult and add a polite negotiation."
                ),
                recognitionLocale = Locale.FRANCE,
                tutorContext = TutorContext(
                    nativeLanguage = Locale.FRANCE,
                    targetLanguage = Locale.US
                ),
                requestedContentKind = LearningContentRequestKind.EXERCISE,
                onPreparingModel = {}
            )

            assertTrue(reply.contentAction is LearningContentAction.CreateExercise)
            val action = reply.contentAction as LearningContentAction.CreateExercise
            assertTrue(action.title.isNotBlank())
            assertTrue(action.instructions.isNotBlank())
            assertTrue(action.prompt.isNotBlank())
            assertTrue(action.expectedAnswer.isNotBlank())
        } finally {
            generator.close()
        }
    }

    @Ignore("Manual device verification; requires the user's selected external model")
    @Test
    fun selectedImportedModelGeneratesCompleteExerciseAction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ModelPreferences(context)
        assumeTrue(
            "This verification needs a selected imported LiteRT model.",
            preferences.promptModelId != ModelPreferences.PROMPT_GEMINI_NANO
        )
        val record = PromptModelCatalog(context).find(preferences.promptModelId)
        assumeNotNull(record)
        val generator = LiteRtReplyGenerator(context)

        try {
            val reply = generator.generateReply(
                record = requireNotNull(record),
                transcript = "Crée-moi un exercice simple pour commander dans un magasin.",
                recognitionLocale = Locale.FRANCE,
                tutorContext = TutorContext(
                    nativeLanguage = Locale.FRANCE,
                    targetLanguage = Locale.US
                ),
                requestedContentKind = LearningContentRequestKind.EXERCISE,
                onPreparingModel = {}
            )

            assertTrue(reply.contentAction is LearningContentAction.CreateExercise)
            val action = reply.contentAction as LearningContentAction.CreateExercise
            assertTrue(action.title.isNotBlank())
            assertTrue(action.instructions.isNotBlank())
            assertTrue(action.prompt.isNotBlank())
            assertTrue(action.expectedAnswer.isNotBlank())
        } finally {
            generator.close()
        }
    }

    @Ignore("Manual device verification; requires the user's selected external model")
    @Test
    fun selectedImportedModelGeneratesCompleteLessonAction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ModelPreferences(context)
        assumeTrue(
            "This verification needs a selected imported LiteRT model.",
            preferences.promptModelId != ModelPreferences.PROMPT_GEMINI_NANO
        )
        val record = PromptModelCatalog(context).find(preferences.promptModelId)
        assumeNotNull(record)
        val generator = LiteRtReplyGenerator(context)

        try {
            val reply = generator.generateReply(
                record = requireNotNull(record),
                transcript = "Prépare une leçon sur les salutations dans un magasin.",
                recognitionLocale = Locale.FRANCE,
                tutorContext = TutorContext(
                    nativeLanguage = Locale.FRANCE,
                    targetLanguage = Locale.US
                ),
                requestedContentKind = LearningContentRequestKind.LESSON,
                onPreparingModel = {}
            )

            assertTrue(reply.contentAction is LearningContentAction.CreateLesson)
            val action = reply.contentAction as LearningContentAction.CreateLesson
            assertTrue(action.title.isNotBlank())
            assertTrue(action.objective.isNotBlank())
            assertTrue(action.content.isNotBlank())
        } finally {
            generator.close()
        }
    }
}
