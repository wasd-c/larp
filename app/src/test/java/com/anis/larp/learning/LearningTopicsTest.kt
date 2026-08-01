package com.anis.larp.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTopicsTest {
    @Test
    fun approvedVocabularyContainsExactlyTheRequestedUnnumberedTags() {
        assertEquals(40, LearningTopics.tags.size)
        assertEquals("Présentations", LearningTopics.tags.first())
        assertEquals("Avenir", LearningTopics.tags.last())
        assertTrue(LearningTopics.tags.none { it.any(Char::isDigit) })
    }

    @Test
    fun generatedAliasesAreMappedToApprovedTags() {
        assertEquals("Routine", LearningTopics.choose("Daily routines"))
        assertEquals("Aéroport", LearningTopics.choose("Airport travel"))
        assertEquals("Jeux vidéo", LearningTopics.choose("Gaming"))
        assertEquals("Restaurant", LearningTopics.choose("invented label", "Ordering from a menu"))
    }

    @Test
    fun freeFormValuesAreAlwaysConvertedToApprovedTags() {
        val exerciseTopic = LearningTopics.choose(
            requested = "Totally random travel label",
            context = "At the airport, where is the check-in desk?"
        )
        val lessonTopic = LearningTopics.choose(
            requested = "Whatever the model invented",
            context = "Rain and sunshine. It is raining today."
        )

        assertEquals("Aéroport", exerciseTopic)
        assertEquals("Météo", lessonTopic)
        assertTrue(exerciseTopic in LearningTopics.tags)
        assertTrue(lessonTopic in LearningTopics.tags)
    }
}
