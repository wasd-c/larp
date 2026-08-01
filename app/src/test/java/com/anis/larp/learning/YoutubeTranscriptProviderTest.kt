package com.anis.larp.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeTranscriptProviderTest {
    @Test
    fun parsesCommonYoutubeAddressesAndRawIds() {
        val expected = "dQw4w9WgXcQ"

        assertEquals(expected, parseYoutubeVideoId(expected))
        assertEquals(
            expected,
            parseYoutubeVideoId("https://www.youtube.com/watch?feature=share&v=$expected")
        )
        assertEquals(expected, parseYoutubeVideoId("https://youtu.be/$expected?t=15"))
        assertEquals(expected, parseYoutubeVideoId("https://m.youtube.com/shorts/$expected"))
        assertEquals(expected, parseYoutubeVideoId("youtube.com/embed/$expected"))
        assertEquals(expected, parseYoutubeVideoId("https://youtube.com/live/$expected"))
    }

    @Test
    fun rejectsNonYoutubeAndInsecureAddresses() {
        listOf(
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "http://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.example/watch?v=dQw4w9WgXcQ",
            "not-video"
        ).forEach { value ->
            val result = runCatching { parseYoutubeVideoId(value) }
            assertTrue("Expected rejection for $value", result.isFailure)
        }
    }

    @Test
    fun longTranscriptKeepsBeginningMiddleAndEndWithinBudget() {
        val transcript = (0..999).joinToString(" ") { "word$it" }

        val compacted = compactTranscript(transcript, maxCharacters = 600)

        assertTrue(compacted.length <= 600)
        assertTrue(compacted.startsWith("word0"))
        assertTrue(compacted.contains("word500"))
        assertTrue(compacted.endsWith("word999"))
        assertEquals(2, Regex(Regex.escape("[…]")).findAll(compacted).count())
    }
}
