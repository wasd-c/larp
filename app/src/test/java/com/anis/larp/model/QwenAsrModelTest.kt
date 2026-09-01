package com.anis.larp.model

import com.anis.larp.ui.freemode.QwenSpeechRecognizer
import com.anis.larp.ui.freemode.sanitizeQwenTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenAsrModelTest {
    @Test
    fun `qwen download is an atomic model and projector pair`() {
        assertEquals(
            listOf(QwenAsrModel.MODEL_FILE, QwenAsrModel.PROJECTOR_FILE),
            QwenAsrModel.ARTIFACTS
        )
        assertTrue(QwenAsrModel.ARTIFACTS.all { it.endsWith(".gguf") })
    }

    @Test
    fun `qwen runtime uses the official ggml conversion of requested source`() {
        assertEquals("Qwen/Qwen3-ASR-0.6B", QwenAsrModel.SOURCE_REPOSITORY)
        assertEquals("ggml-org/Qwen3-ASR-0.6B-GGUF", QwenAsrModel.REPOSITORY)
    }

    @Test
    fun `android duplicate suffix still matches the requested artifact`() {
        assertTrue(
            artifactFileNameMatches(
                "Qwen3-ASR-0.6B-Q8_0 (1).gguf",
                QwenAsrModel.MODEL_FILE
            )
        )
    }

    @Test
    fun `llama server idle slots log means qwen is ready`() {
        assertTrue(
            QwenSpeechRecognizer.isServerReadyLog(
                "0.02.046.210 I srv update_slots: all slots are idle"
            )
        )
        assertTrue(
            QwenSpeechRecognizer.isServerReadyLog(
                "server is listening on http://127.0.0.1:8080"
            )
        )
    }

    @Test
    fun `qwen protocol metadata is not returned as spoken text`() {
        assertEquals(
            "Good.",
            sanitizeQwenTranscript("language French<asr_text>Good.")
        )
        assertEquals(
            "Good morning",
            sanitizeQwenTranscript("<ASR_TEXT>Good morning<|endoftext|>")
        )
        assertEquals("Good", sanitizeQwenTranscript("Good"))
        assertEquals(
            "",
            sanitizeQwenTranscript(
                "Transcrire l'audio à texte (langue: Française)."
            )
        )
        assertEquals(
            "",
            sanitizeQwenTranscript(
                "Transcribe audio to text (language: French)"
            )
        )
    }
}
