package com.anis.larp.model

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QwenAsrModel {
    const val SOURCE_REPOSITORY = "Qwen/Qwen3-ASR-0.6B"
    const val REPOSITORY = "ggml-org/Qwen3-ASR-0.6B-GGUF"
    const val MODEL_FILE = "Qwen3-ASR-0.6B-Q8_0.gguf"
    const val PROJECTOR_FILE = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
    const val DOWNLOAD_SIZE_BYTES = 1_019_000_000L
    val ARTIFACTS = listOf(MODEL_FILE, PROJECTOR_FILE)

    fun isAvailable(context: Context): Boolean {
        val store = SharedModelStore(context)
        return ARTIFACTS.all { artifact ->
            store.findCompleted(REPOSITORY, artifact)?.sizeBytes?.let { it > 0L } == true
        }
    }

    suspend fun materializeForRuntime(context: Context, fileName: String): File =
        withContext(Dispatchers.IO) {
            require(fileName in ARTIFACTS) { "Artefact Qwen inconnu : $fileName" }
            val shared = SharedModelStore(context).findCompleted(REPOSITORY, fileName)
                ?: throw IllegalStateException(
                    "Le fichier $fileName n'est pas disponible dans Download/Models."
                )
            val runtimeDirectory = File(context.noBackupFilesDir, "qwen-asr-runtime")
                .apply { mkdirs() }
            val runtimeFile = File(runtimeDirectory, fileName)
            if (runtimeFile.isFile && runtimeFile.length() == shared.sizeBytes) {
                return@withContext runtimeFile
            }
            val partial = File(runtimeDirectory, "$fileName.partial")
            partial.delete()
            context.contentResolver.openInputStream(shared.uri)?.buffered().use { input ->
                requireNotNull(input) { "Android refuse l'accès à ${shared.fileName}." }
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(partial.length() == shared.sizeBytes) {
                partial.delete()
                "La copie locale de $fileName est incomplète."
            }
            if (!partial.renameTo(runtimeFile)) {
                partial.copyTo(runtimeFile, overwrite = true)
                partial.delete()
            }
            runtimeFile
        }
}
