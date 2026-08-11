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
    fun compactDecoderAcceptsJsonArraysFromSmallModels() {
        val content = decodeLessonContent(
            topic = "Introductions",
            targets = """[{"t":"name","m":"nom"},{"text":"live","meaning":"habiter"}]""",
            sentences = """[{"s":"My name is Ana and I live here.","m":"Je m'appelle Ana et j'habite ici.","i":[0,1],"c":["My name is Ana","and I live here"]}]"""
        )

        assertEquals(listOf("name", "live"), content.targets.map { it.text })
        assertEquals(listOf(0, 1), content.sentences.single().targetIndexes)
        assertEquals(2, content.sentences.single().chunks.size)
    }

    @Test
    fun gemmaReceivesOnlyTheCompactLessonContentSchema() {
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

        assertTrue(descriptions.contains("submit_lesson_content"))
        assertTrue(descriptions.contains("\"t\""))
        assertTrue(descriptions.contains("\"x1\""))
        assertTrue(descriptions.contains("\"m3\""))
        assertTrue(descriptions.contains("\"s2\""))
        assertTrue(!descriptions.contains("\"distractors\""))
        assertTrue(!descriptions.contains("\"instructions\""))
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
    fun compactToolCreatesOneCachedPackAndTenLocalSteps() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(testContext.cacheDir, "compact-lesson-tool-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val contentFile = File(directory, "learning_content.json")
        val repository = LearningContentRepository.createForTests(contentFile)
        try {
            val result = LearningContentToolSet(
                repository = repository,
                targetLanguageTag = "en-US"
            ).submitLessonContent(
                t = "At the restaurant",
                x1 = "water", m1 = "eau",
                x2 = "bread", m2 = "pain",
                x3 = "please", m3 = "s'il vous plaît",
                s1 = "Water, please.", sm1 = "De l'eau, s'il vous plaît.", i1 = "0,2",
                s2 = "Water and bread, please.",
                sm2 = "De l'eau et du pain, s'il vous plaît.",
                i2 = "0,1,2",
                c2 = "Water/and bread/please"
            )

            assertEquals("10", result["steps"])
            val created = repository.state.value.exercises.single()
            assertEquals(3, requireNotNull(created.lessonContent).targets.size)
            assertEquals(10, created.compiledSteps.size)
            val restored = LearningContentRepository.createForTests(contentFile)
                .state.value.exercises.single()
            assertEquals(created.lessonContent, restored.lessonContent)
            assertEquals(created.compiledSteps, restored.compiledSteps)
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
