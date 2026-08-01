package com.anis.larp.learning

import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.tool
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningContentToolSetTest {
    @Test
    fun gemmaReceivesBothCreationToolSchemas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = tool(
            LearningContentToolSet(
                LearningContentRepository.getInstance(context)
            )
        )
        val manager = ToolManager(listOf(provider))
        val descriptions = requireNotNull(
            ToolManager::class.java
                .getDeclaredMethod("getToolsDescription")
                .invoke(manager)
        ).toString()

        assertTrue(descriptions.contains("create_exercise"))
        assertTrue(descriptions.contains("create_lesson"))
    }

    @Test
    fun generatedExerciseActionIsPersistedAndObservable() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(testContext.cacheDir, "learning-content-save-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val contentFile = File(directory, "learning_content.json")
        val repository = LearningContentRepository.createForTests(contentFile)

        try {
            repository.execute(
                LearningContentAction.CreateExercise(
                    title = "Shopping conversation",
                    instructions = "Répondez au commerçant en anglais.",
                    prompt = "How much does this cost?",
                    expectedAnswer = "It costs ten dollars.",
                    languageTag = "en-US",
                    type = ExerciseType.MULTIPLE_CHOICE,
                    choices = listOf(
                        "It costs five dollars.",
                        "It costs ten dollars.",
                        "It costs twenty dollars."
                    )
                )
            )

            assertEquals(1, repository.state.value.exercises.size)
            assertEquals(
                "Shopping conversation",
                repository.state.value.exercises.single().title
            )
            val saved = JSONObject(contentFile.readText())
                .getJSONArray("exercises")
                .getJSONObject(0)
            assertEquals("How much does this cost?", saved.getString("prompt"))
            assertEquals("MULTIPLE_CHOICE", saved.getString("exerciseType"))
            assertEquals(3, saved.getJSONArray("choices").length())

            val reloaded = LearningContentRepository.createForTests(contentFile)
                .state.value.exercises.single()
            assertEquals(ExerciseType.MULTIPLE_CHOICE, reloaded.type)
            assertEquals("It costs ten dollars.", reloaded.choices[1])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun archiveKeepsExerciseContentAndPersistsArchiveTimestamp() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(testContext.cacheDir, "learning-content-archive-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val contentFile = File(directory, "learning_content.json")
        val repository = LearningContentRepository.createForTests(contentFile)

        try {
            val exercise = repository.createExercise(
                title = "At the market",
                instructions = "Répondez au vendeur.",
                prompt = "How much is it?",
                expectedAnswer = "It is ten euros.",
                languageTag = "en-US"
            )

            repository.archiveExercise(exercise.id)

            val archived = repository.state.value.exercises.single()
            assertEquals("How much is it?", archived.prompt)
            assertTrue(requireNotNull(archived.archivedAtMillis) > 0L)
            val saved = JSONObject(contentFile.readText())
                .getJSONArray("exercises")
                .getJSONObject(0)
            assertEquals("At the market", saved.getString("title"))
            assertTrue(saved.getLong("archivedAtMillis") > 0L)
        } finally {
            directory.deleteRecursively()
        }
    }
}
