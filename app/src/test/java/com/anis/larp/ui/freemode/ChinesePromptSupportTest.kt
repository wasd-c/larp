package com.anis.larp.ui.freemode

import com.anis.larp.learning.LearningContentAction
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinesePromptSupportTest {
    @Test
    fun chineseCreationPromptRequiresPinyinChunksAndApprovedTopic() {
        val prompt = learningContentPrompt(
            kind = LearningContentRequestKind.EXERCISE,
            transcript = "Crée un exercice de chinois au restaurant.",
            tutorContext = TutorContext(
                nativeLanguage = Locale.FRANCE,
                targetLanguage = Locale.forLanguageTag("zh-Hans-CN")
            )
        )

        assertTrue(prompt.contains("ACTION_R1:"))
        assertTrue(prompt.contains("ACTION_C2:"))
        assertTrue(prompt.contains("Hanyu Pinyin with tone marks"))
        assertTrue(prompt.contains("ACTION_TOPIC must be exactly one of"))
        assertTrue(prompt.contains("Restaurant"))
    }

    @Test
    fun taggedChineseContentPersistsPinyinAndSentenceChunks() {
        val action = parseLearningContentAction(
            rawReply = """
                ACTION: SUBMIT_LESSON_CONTENT
                ACTION_TOPIC: Restaurant
                ACTION_X1: 你好
                ACTION_M1: bonjour
                ACTION_R1: nǐ hǎo
                ACTION_X2: 谢谢
                ACTION_M2: merci
                ACTION_R2: xiè xie
                ACTION_X3: 水
                ACTION_M3: eau
                ACTION_R3: shuǐ
                ACTION_S1: 你好，我想喝水。
                ACTION_SM1: Bonjour, je voudrais boire de l'eau.
                ACTION_I1: 0,2
                ACTION_C1: 你好/我/想/喝/水。
                ACTION_S2: 谢谢你的水。
                ACTION_SM2: Merci pour ton eau.
                ACTION_I2: 1,2
                ACTION_C2: 谢谢/你/的/水。
                LANGUAGE_TAG: fr-FR
                REPLY: L'exercice est prêt.
            """.trimIndent(),
            fallbackLanguageTag = "zh-Hans-CN"
        ) as LearningContentAction.CreateLessonContent

        assertEquals("zh-Hans-CN", action.languageTag)
        assertEquals("nǐ hǎo", action.content.targets.first().reading)
        assertEquals(
            listOf("你好", "我", "想", "喝", "水。"),
            action.content.sentences.first().chunks
        )
    }

    @Test
    fun qwenReceivesItsCanonicalChineseLanguageHint() {
        assertEquals("Chinese", qwenLanguageHint(Locale.forLanguageTag("cmn-Hans-CN")))
        assertEquals("French", qwenLanguageHint(Locale.FRANCE))
    }
}
