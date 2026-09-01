package com.anis.larp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.LearningContentAction
import com.anis.larp.learning.LearningContentRepository
import com.anis.larp.learning.LearningSentence
import com.anis.larp.learning.LearningTarget
import com.anis.larp.learning.LessonContent
import com.anis.larp.ui.theme.LarpTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChineseLearningIntegrationTest {
    @Suppress("DEPRECATION")
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chineseTargetRendersWithPinyinAndKeepsItsSpeechLocale() {
        val content = chineseContent()
        val exercise = Exercise(
            id = "exercise:chinese-integration",
            title = "Au restaurant",
            instructions = "Apprenez trois expressions utiles.",
            prompt = content.sentences.first().text,
            expectedAnswer = content.sentences.first().text,
            languageTag = "zh-Hans-CN",
            createdAtMillis = 1L,
            lessonContent = content
        )
        var spokenText: String? = null
        var spokenLanguageTag: String? = null

        composeRule.setContent {
            LarpTheme(dynamicColor = false) {
                ExercisesScreen(
                    exercises = listOf(exercise),
                    requestedOpenId = exercise.id,
                    onSpeakWord = { text, languageTag ->
                        spokenText = text
                        spokenLanguageTag = languageTag
                    }
                )
            }
        }

        composeRule.onNodeWithText("你好").assertIsDisplayed()
        composeRule.onNodeWithText("nǐ hǎo").assertIsDisplayed()
        composeRule.onNodeWithTag("speak_你好").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { spokenText != null }

        composeRule.runOnIdle {
            assertEquals("你好", spokenText)
            assertEquals("zh-Hans-CN", spokenLanguageTag)
        }
    }

    @Test
    fun chineseContentSurvivesTheLocalRepositoryRoundTrip() {
        val cacheDirectory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val contentFile = File(cacheDirectory, "chinese-content-${System.nanoTime()}.json")
        try {
            val repository = LearningContentRepository.createForTests(contentFile)
            repository.execute(
                LearningContentAction.CreateLessonContent(
                    content = chineseContent(),
                    languageTag = "zh-Hans-CN"
                )
            )

            val restored = LearningContentRepository.createForTests(contentFile)
                .state.value.exercises.single()

            assertEquals("zh-Hans-CN", restored.languageTag)
            assertEquals("你好", restored.lessonContent?.targets?.first()?.text)
            assertEquals("nǐ hǎo", restored.lessonContent?.targets?.first()?.reading)
            assertEquals(
                listOf("你好", "我", "想", "喝", "水。"),
                restored.lessonContent?.sentences?.first()?.chunks
            )
        } finally {
            contentFile.delete()
            File(contentFile.parentFile, "${contentFile.name}.partial").delete()
        }
    }

    private fun chineseContent() = LessonContent(
        topic = "Restaurant",
        targets = listOf(
            LearningTarget("你好", "bonjour", "nǐ hǎo"),
            LearningTarget("谢谢", "merci", "xiè xie"),
            LearningTarget("水", "eau", "shuǐ")
        ),
        sentences = listOf(
            LearningSentence(
                text = "你好，我想喝水。",
                meaning = "Bonjour, je voudrais boire de l'eau.",
                targetIndexes = listOf(0, 2),
                chunks = listOf("你好", "我", "想", "喝", "水。")
            ),
            LearningSentence(
                text = "谢谢你的水。",
                meaning = "Merci pour ton eau.",
                targetIndexes = listOf(1, 2),
                chunks = listOf("谢谢", "你", "的", "水。")
            )
        )
    )
}
