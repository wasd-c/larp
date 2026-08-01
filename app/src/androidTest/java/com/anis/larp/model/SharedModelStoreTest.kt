package com.anis.larp.model

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedModelStoreTest {
    @Test
    fun sharedModelIsDiscoverableAndReadableThroughLiteRtPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedModelStore(context)
        val suffix = System.nanoTime().toString()
        val repository = "larp-test/probe-$suffix"
        val artifactName = "storage-probe-$suffix.litertlm"
        val bytes = "shared-model-probe".toByteArray()
        val pending = store.createPending(
            repository = repository,
            artifactName = artifactName,
            displayName = "Storage probe"
        )

        try {
            store.openOutput(pending.uri, append = false).use { output ->
                output.write(bytes)
            }
            assertEquals(bytes.size.toLong(), store.size(pending.uri))
            store.complete(pending.uri)

            val shared = requireNotNull(
                store.findCompleted(repository, artifactName)
            )
            assertEquals(bytes.size.toLong(), shared.sizeBytes)
            assertTrue(
                store.discoveredRecords().any { record ->
                    record.contentUri == shared.uri.toString() &&
                        record.source == SharedModelStore.USER_VISIBLE_DIRECTORY
                }
            )
            val restoredRecord = PromptModelCatalog(context).find(
                PromptModelCatalog.remoteModelId(repository, artifactName)
            )
            assertEquals(shared.uri.toString(), restoredRecord?.contentUri)
            assertEquals(artifactName, restoredRecord?.artifactFileName)

            val source = PromptModelSource(context)
            source.open(
                PromptModelRecord(
                    id = PromptModelCatalog.remoteModelId(repository, artifactName),
                    displayName = "Storage probe",
                    filePath = "",
                    contentUri = shared.uri.toString(),
                    source = SharedModelStore.USER_VISIBLE_DIRECTORY,
                    repository = repository,
                    sizeBytes = shared.sizeBytes
                )
            ).use { opened ->
                assertTrue(opened.path.endsWith(".litertlm"))
                assertTrue(File(opened.path).isFile)
                assertArrayEquals(bytes, File(opened.path).readBytes())
            }
        } finally {
            store.delete(pending.uri)
            PromptModelCatalog(context).availableModels()
        }
    }
}
