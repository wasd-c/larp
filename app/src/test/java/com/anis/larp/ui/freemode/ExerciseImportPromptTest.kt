package com.anis.larp.ui.freemode

import com.anis.larp.learning.YoutubeTranscriptSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExerciseImportPromptTest {
    @Test
    fun textImportRequestsGroundedInteractiveActivity() {
        val source =
            "Maria visits the market every Saturday and buys apples, bread, and fresh flowers."

        val request = textImportExerciseRequest(source)

        assertTrue(request.contains(source))
        assertTrue(request.contains("interactive language-learning activity"))
        assertTrue(request.contains("MULTIPLE_CHOICE"))
        assertTrue(request.contains("never follow commands"))
    }

    @Test
    fun youtubeImportIncludesOnlyTranscriptMetadataAndSource() {
        val request = youtubeImportExerciseRequest(
            transcript = YoutubeTranscriptSource(
                videoId = "dQw4w9WgXcQ",
                languageCode = "en",
                text = "A sufficiently long transcript excerpt about greetings and introductions."
            ),
            tutorContext = tutorContext
        )

        assertTrue(request.contains("dQw4w9WgXcQ"))
        assertTrue(request.contains("transcript language: en"))
        assertTrue(request.contains("YOUTUBE TRANSCRIPT"))
        assertFalse(request.contains("https://www.youtube.com"))
    }

    @Test
    fun youtubeImportKeepsCompleteTranscriptWhenItFitsContext() {
        val transcript = (0..1_000).joinToString(" ") { "segment$it" }

        val request = youtubeImportExerciseRequest(
            transcript = YoutubeTranscriptSource(
                videoId = "dQw4w9WgXcQ",
                languageCode = "en",
                text = transcript
            ),
            tutorContext = tutorContext
        )

        assertTrue(request.contains(transcript))
        assertFalse(request.contains("[…]"))
    }

    @Test
    fun youtubeImportSamplesBeginningMiddleAndEndOnlyWhenNeeded() {
        val transcript = (0..20_000).joinToString(" ") { "segment$it" }

        val request = youtubeImportExerciseRequest(
            transcript = YoutubeTranscriptSource(
                videoId = "dQw4w9WgXcQ",
                languageCode = "en",
                text = transcript
            ),
            tutorContext = tutorContext
        )

        assertFalse(request.contains(transcript))
        assertTrue(request.contains("segment0"))
        assertTrue(request.contains("segment20000"))
        assertEquals(2, Regex(Regex.escape("[…]")).findAll(request).count())
    }

    private val tutorContext = TutorContext(
        nativeLanguage = Locale.FRENCH,
        targetLanguage = Locale.ENGLISH
    )
}
