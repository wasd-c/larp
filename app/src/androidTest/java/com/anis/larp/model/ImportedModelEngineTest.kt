package com.anis.larp.model

import androidx.test.platform.app.InstrumentationRegistry
import com.anis.larp.ui.freemode.LiteRtReplyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class ImportedModelEngineTest {
    @Test
    fun selectedImportedModelInitializesOnAnAvailableBackend() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ModelPreferences(context)
        val record = PromptModelCatalog(context).find(preferences.promptModelId)
        assumeNotNull(record)
        val generator = LiteRtReplyGenerator(context)

        try {
            val runtime = withTimeout(180_000L) {
                generator.preload(requireNotNull(record)) { }
            }
            println("Imported model runtime: $runtime")
            assertTrue(
                runtime.contains("GPU") ||
                    runtime.contains("CPU") ||
                    runtime.contains("NPU")
            )
        } finally {
            generator.close()
        }
    }
}
