package com.anis.larp.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExercisePlanTest {
    @Test
    fun malformedGeneratedPlanIsRepairedToTheFixedTwoWordFourGapContract() {
        val repaired = normalizeExercisePlan(
            requested = ExercisePlan(
                words = listOf(
                    LearnedWord(
                        text = "airport",
                        pronunciation = "",
                        definition = "",
                        gapSentence = "No gap here",
                        distractors = listOf("airport", "hotel"),
                        recallPrompt = "",
                        recallAnswer = ""
                    )
                ),
                hardPrompt = "",
                hardAnswer = "",
                finalSentence = "Only ___ gap",
                finalAnswers = listOf("airport")
            ),
            prompt = "Where is the airport terminal?",
            expectedAnswer = "The terminal is ahead.",
            choices = listOf("terminal", "hotel")
        )

        assertEquals(2, repaired.words.size)
        assertTrue(repaired.words.all { it.gapSentence.countGaps() == 1 })
        assertTrue(repaired.words.all { it.distractors.size == 2 })
        assertEquals(4, repaired.finalSentence.countGaps())
        assertEquals(4, repaired.finalAnswers.size)
        assertTrue(repaired.words.all { learned ->
            repaired.finalAnswers.any { it.equals(learned.text, ignoreCase = true) }
        })
    }
}
