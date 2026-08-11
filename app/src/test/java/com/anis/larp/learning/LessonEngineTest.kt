package com.anis.larp.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonEngineTest {
    private val content = LessonContent(
        topic = "commander au restaurant",
        targets = listOf(
            LearningTarget("water", "eau"),
            LearningTarget("bread", "pain"),
            LearningTarget("please", "s'il vous plaît")
        ),
        sentences = listOf(
            LearningSentence(
                text = "Water and bread, please.",
                meaning = "De l'eau et du pain, s'il vous plaît.",
                targetIndexes = listOf(0, 1, 2),
                chunks = listOf("Water", "and bread", "please")
            )
        )
    )

    @Test
    fun threeTargetsCompileToTenLocalStepsAndEachTargetRepeats() {
        val steps = compiler().compile(content, LessonConfiguration(seed = 7))
        assertEquals(10, steps.size)
        content.targets.indices.forEach { index ->
            assertTrue("target $index should repeat", appearances(steps, index) >= 2)
        }
    }

    @Test
    fun assistanceDecreasesTowardTheEnd() {
        val steps = compiler().compile(content, LessonConfiguration(seed = 7))
        assertTrue(steps.take(3).map { it.assistance }.average() > steps.takeLast(3).map { it.assistance }.average())
    }

    @Test
    fun distractorsNeverContainTheCorrectNormalizedAnswer() {
        val provider = LocalDistractorProvider(
            recentlyLearned = listOf(LearningTarget("WATER!", "EAU.") )
        )
        val meanings = provider.meaningsFor(0, content, 3)
        val texts = provider.targetTextsFor(0, content, 3)
        val normalizer = DefaultAnswerNormalizer()
        assertFalse(meanings.any { normalizer.normalize(it, "fr") == "eau" })
        assertFalse(texts.any { normalizer.normalize(it, "en") == "water" })
    }

    @Test
    fun shuffleIsReproducibleWithFixedSeed() {
        val configuration = LessonConfiguration(seed = 42)
        assertEquals(compiler().compile(content, configuration), compiler().compile(content, configuration))
    }

    @Test
    fun invalidTargetIndexIsRejected() {
        val invalid = content.copy(sentences = listOf(content.sentences.single().copy(targetIndexes = listOf(9))))
        val result = LessonContentValidator().validate(invalid, "en")
        assertTrue(result is LessonContentValidation.Invalid)
        assertTrue((result as LessonContentValidation.Invalid).reasons.any { "missing target 9" in it })
    }

    @Test
    fun compilerIsPureAndNeedsNeitherLlmNorCompose() {
        val result = DeterministicLessonCompiler().compile(content, LessonConfiguration(desiredStepCount = 8))
        assertEquals(8, result.size)
    }

    @Test
    fun disablingAudioRemovesAudioSteps() {
        val steps = compiler().compile(content, LessonConfiguration(enableListening = false))
        assertFalse(steps.any {
            it is LessonStep.AudioRecognition || it is LessonStep.ListenSentence ||
                (it is LessonStep.SentenceMeaningChoice && it.playAudio)
        })
    }

    @Test
    fun disablingMicrophoneReplacesSpeakingSteps() {
        val steps = compiler().compile(content, LessonConfiguration(enableSpeaking = false))
        assertFalse(steps.any { it is LessonStep.SpeakSentence })
        assertTrue(steps.any { it is LessonStep.TypeAnswer })
    }

    @Test
    fun previousErrorSelectsTheFocusedReviewTarget() {
        val steps = compiler().compile(
            content,
            LessonConfiguration(previousErrorTargetIndexes = setOf(1))
        )
        assertTrue(steps.any { it is LessonStep.AudioRecognition && it.targetIndex == 1 })
    }

    @Test
    fun cachedContentCanBeDecodedAndRecompiled() {
        val restored = LessonContentCodec.decode(LessonContentCodec.encode(content))
        assertEquals(content, restored)
        assertEquals(
            compiler().compile(content, LessonConfiguration(seed = 3)),
            compiler().compile(restored, LessonConfiguration(seed = 3))
        )
    }

    @Test
    fun trivialRepairDropsImpossibleReferencesWithoutAnotherGeneration() {
        val malformed = content.copy(
            sentences = listOf(content.sentences.single().copy(targetIndexes = listOf(0, 99)))
        )
        val repaired = LessonContentValidator().repair(malformed, "en")
        assertEquals(listOf(0, 1, 2), repaired.sentences.single().targetIndexes)
    }

    private fun compiler() = DeterministicLessonCompiler()

    private fun appearances(steps: List<LessonStep>, targetIndex: Int): Int = steps.count { step ->
        when (step) {
            is LessonStep.IntroduceTarget -> step.targetIndex == targetIndex
            is LessonStep.MeaningChoice -> step.targetIndex == targetIndex
            is LessonStep.AudioRecognition -> step.targetIndex == targetIndex
            is LessonStep.MatchTargets -> targetIndex in step.targetIndexes
            is LessonStep.ClozeSentence -> step.missingTargetIndex == targetIndex
            else -> false
        }
    }
}
