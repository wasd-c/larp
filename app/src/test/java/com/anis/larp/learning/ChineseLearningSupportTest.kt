package com.anis.larp.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseLearningSupportTest {
    private val chinese = LessonContent(
        topic = "Restaurant",
        targets = listOf(
            LearningTarget("你好", "bonjour", "nǐ hǎo"),
            LearningTarget("谢谢", "merci", "xiè xie"),
            LearningTarget("水", "eau", "shuǐ")
        ),
        sentences = listOf(
            LearningSentence("你好，我想喝水。", "Bonjour, je voudrais boire de l'eau.", listOf(0, 2)),
            LearningSentence("谢谢你的水。", "Merci pour ton eau.", listOf(1, 2))
        )
    )

    @Test
    fun chineseContentRequiresPinyinAndAcceptsToneMarkedReadings() {
        assertEquals(
            LessonContentValidation.Valid,
            LessonContentValidator().validate(chinese, "zh-Hans-CN")
        )

        val missingPinyin = chinese.copy(
            targets = chinese.targets.mapIndexed { index, target ->
                if (index == 0) target.copy(reading = null) else target
            }
        )
        val result = LessonContentValidator().validate(missingPinyin, "zh-Hans-CN")

        assertTrue(result is LessonContentValidation.Invalid)
        assertTrue(
            (result as LessonContentValidation.Invalid).reasons.any {
                "pinyin" in it
            }
        )
    }

    @Test
    fun repairCreatesUsefulTargetAwareChunksForUnspacedSentences() {
        val repaired = LessonContentValidator().repair(chinese, "zh-Hans-CN")

        repaired.sentences.forEach { sentence ->
            assertTrue(sentence.chunks.size >= 2)
            assertTrue(chunksRepresent(sentence.chunks, sentence.text))
        }
        assertTrue(repaired.sentences.first().chunks.any { it.startsWith("你好") })
        assertTrue("水。" in repaired.sentences.first().chunks)
    }

    @Test
    fun aWholeSentenceTargetStillProducesMoreThanOneReorderBlock() {
        val sentence = LearningSentence("你好", "bonjour", listOf(0))

        assertEquals(
            listOf("你", "好"),
            learningChunks(sentence, listOf(LearningTarget("你好", "bonjour", "nǐ hǎo")))
        )
    }

    @Test
    fun longChineseSentencesStayWithinTheReorderCardLimit() {
        val sentence = LearningSentence("我今天想和朋友一起学习中文", "Je veux étudier.", emptyList())
        val chunks = learningChunks(sentence, emptyList())

        assertTrue(chunks.size in 2..MAX_LEARNING_CHUNKS)
        assertTrue(chunksRepresent(chunks, sentence.text))
    }

    @Test
    fun compiledChineseReorderStepsAreNonTrivialAndDeterministic() {
        val repaired = LessonContentValidator().repair(chinese, "zh-Hans-CN")
        val configuration = LessonConfiguration(seed = 27)

        val first = DeterministicLessonCompiler().compile(repaired, configuration)
        val second = DeterministicLessonCompiler().compile(repaired, configuration)
        val reorders = first.filterIsInstance<LessonStep.ReorderChunks>()

        assertEquals(first, second)
        assertTrue(reorders.isNotEmpty())
        assertTrue(reorders.all { it.shuffledChunks.size >= 2 })
    }

    @Test
    fun chineseAnswerMatchingIgnoresAsrWhitespaceAndTerminalPunctuation() {
        val normalizer = DefaultAnswerNormalizer()

        assertEquals(
            normalizer.normalize("你好。", "zh-Hans-CN"),
            normalizer.normalize("你 好", "zh-Hans-CN")
        )
    }

    @Test
    fun longChineseSentencesAreMeasuredByCharactersInsteadOfSpaces() {
        val tooLong = chinese.copy(
            sentences = listOf(
                chinese.sentences.first().copy(
                    text = "我".repeat(MAX_DENSE_SENTENCE_UNITS + 1) + "你好水",
                    targetIndexes = listOf(0, 2)
                )
            )
        )
        val result = LessonContentValidator().validate(tooLong, "zh-Hans-CN")

        assertTrue(result is LessonContentValidation.Invalid)
        assertTrue(
            (result as LessonContentValidation.Invalid).reasons.any {
                "too long" in it
            }
        )
    }

    @Test
    fun chineseTargetDistractorsNeverFallBackToEnglish() {
        val distractors = LocalDistractorProvider().targetTextsFor(0, chinese, 3)

        assertEquals(3, distractors.size)
        assertTrue(distractors.all(::containsHanCharacters))
        assertFalse(distractors.any { it == "other" || it == "another" })
    }

    @Test
    fun legacyWordOrderAlsoSegmentsChineseWithoutSpaces() {
        val definition = normalizeGeneratedExerciseDefinition(
            typeValue = "WORD_ORDER",
            expectedAnswer = "我想喝水",
            choicesValue = null
        )

        assertEquals(ExerciseType.WORD_ORDER, definition.type)
        assertEquals(listOf("我", "想", "喝", "水"), definition.choices)
    }

    @Test
    fun legacyFallbackKeepsSingleHanCharactersInsteadOfFrenchPlaceholders() {
        val plan = fallbackExercisePlan(
            prompt = "请说中文",
            expectedAnswer = "我想喝水"
        )

        assertTrue(plan.words.all { containsHanCharacters(it.text) })
    }
}
