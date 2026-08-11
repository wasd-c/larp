package com.anis.larp.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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

    suspend fun importArtifacts(context: Context, uris: List<Uri>) =
        withContext(Dispatchers.IO) {
            require(uris.size == ARTIFACTS.size) {
                "Sélectionnez exactement le modèle Qwen et son fichier mmproj."
            }
            val resolver = context.contentResolver
            val namedUris = uris.map { uri ->
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val name = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
                require(name.endsWith(".gguf", ignoreCase = true)) {
                    "Sélectionnez uniquement les deux fichiers Qwen au format .gguf."
                }
                name to uri
            }
            val projectors = namedUris.filter { (name, _) ->
                name.contains("mmproj", ignoreCase = true)
            }
            val models = namedUris.filterNot { (name, _) ->
                name.contains("mmproj", ignoreCase = true)
            }
            require(projectors.size == 1 && models.size == 1) {
                "La sélection doit contenir un modèle Qwen et un seul fichier mmproj."
            }
            val store = SharedModelStore(context)
            listOf(
                MODEL_FILE to models.single().second,
                PROJECTOR_FILE to projectors.single().second
            ).forEach { (artifact, uri) ->
                store.importFromUri(
                    sourceUri = uri,
                    repository = REPOSITORY,
                    artifactName = artifact,
                    displayName = "Qwen ASR"
                )
            }
            check(isAvailable(context)) {
                "Les deux fichiers Qwen n'ont pas pu être importés."
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
