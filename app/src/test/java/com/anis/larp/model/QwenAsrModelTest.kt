package com.anis.larp.model

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
}
