package com.anis.larp.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PromptModelRecord(
    val id: String,
    val displayName: String,
    val filePath: String,
    val contentUri: String? = null,
    val source: String,
    val repository: String? = null,
    val artifactFileName: String? = null,
    val sizeBytes: Long = 0L,
    val accelerationHint: AccelerationKind = AccelerationKind.AUTO,
    val speculativeDecoding: Boolean = false
)

class PromptModelCatalog(private val context: Context) {
    private val applicationContext = context.applicationContext
    private val catalogFile = File(applicationContext.filesDir, CATALOG_FILE)
    private val sharedStore = SharedModelStore(applicationContext)

    fun availableModels(): List<PromptModelRecord> = synchronized(FILE_LOCK) {
        val discovered = sharedStore.discoveredRecords()
        (readCatalog() + discovered)
            .associateBy(PromptModelRecord::id)
            .values
            .map(::normalizeKnownModel)
            .filter { record ->
                when {
                    !record.contentUri.isNullOrBlank() ->
                        isContentUriAccessible(record.contentUri)
                    else -> File(record.filePath).isFile &&
                        File(record.filePath).length() > 0L
                }
            }
            .toList()
            .also(::writeCatalog)
    }

    fun find(modelId: String): PromptModelRecord? =
        availableModels().firstOrNull { it.id == modelId }

    fun add(record: PromptModelRecord) = synchronized(FILE_LOCK) {
        val records = readCatalog()
            .filterNot { it.id == record.id }
            .plus(record)
        writeCatalog(records)
    }

    private fun isContentUriAccessible(uriString: String): Boolean =
        runCatching {
            applicationContext.contentResolver
                .openFileDescriptor(Uri.parse(uriString), "r")
                ?.use { it.statSize > 0L }
                ?: false
        }.getOrDefault(false)

    suspend fun importModel(uri: Uri): PromptModelRecord = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Modèle importé.litertlm"

        require(displayName.endsWith(".litertlm", ignoreCase = true)) {
            "Sélectionnez un modèle LiteRT-LM au format .litertlm."
        }
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val sizeBytes = resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
        require(sizeBytes > 0L) { "Le fichier sélectionné est vide." }
        PromptModelRecord(
            id = "prompt:file:${shortHash(uri.toString())}",
            displayName = displayName.substringBeforeLast(".litertlm"),
            filePath = "",
            contentUri = uri.toString(),
            source = if (uri.authority == MediaStoreAuthority) {
                SharedModelStore.USER_VISIBLE_DIRECTORY
            } else {
                "Fichier partagé"
            },
            artifactFileName = displayName,
            sizeBytes = sizeBytes,
            accelerationHint = AccelerationKind.AUTO,
            speculativeDecoding = false
        ).let(::normalizeKnownModel)
            .also(::add)
    }

    private fun normalizeKnownModel(record: PromptModelRecord): PromptModelRecord {
        val isGemma4 = record.repository?.contains(
            "gemma-4",
            ignoreCase = true
        ) == true || record.displayName.contains(
            "gemma-4",
            ignoreCase = true
        )
        if (!isGemma4) return record

        val profile = DeviceAccelerationProfile.detect()
        return record.copy(
            displayName = GEMMA_4_DISPLAY_NAME,
            repository = record.repository ?: GEMMA_4_REPOSITORY,
            accelerationHint = profile.preferredKind,
            speculativeDecoding = profile.preferredKind != AccelerationKind.NPU
        )
    }

    private fun readCatalog(): List<PromptModelRecord> {
        if (!catalogFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(catalogFile.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PromptModelRecord(
                            id = item.getString("id"),
                            displayName = item.getString("displayName"),
                            filePath = item.optString("filePath"),
                            contentUri = item.optString("contentUri")
                                .ifBlank { null },
                            source = item.getString("source"),
                            repository = item.optString("repository").ifBlank { null },
                            artifactFileName = item.optString("artifactFileName")
                                .ifBlank { null },
                            sizeBytes = item.optLong("sizeBytes"),
                            accelerationHint = runCatching {
                                AccelerationKind.valueOf(
                                    item.optString("accelerationHint", "AUTO")
                                )
                            }.getOrDefault(AccelerationKind.AUTO),
                            speculativeDecoding =
                                item.optBoolean("speculativeDecoding", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCatalog(records: List<PromptModelRecord>) {
        catalogFile.parentFile?.mkdirs()
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("displayName", record.displayName)
                    .put("filePath", record.filePath)
                    .put("contentUri", record.contentUri)
                    .put("source", record.source)
                    .put("repository", record.repository)
                    .put("artifactFileName", record.artifactFileName)
                    .put("sizeBytes", record.sizeBytes)
                    .put("accelerationHint", record.accelerationHint.name)
                    .put("speculativeDecoding", record.speculativeDecoding)
            )
        }
        val temporaryFile = File(catalogFile.parentFile, "${catalogFile.name}.partial")
        temporaryFile.writeText(array.toString())
        if (!temporaryFile.renameTo(catalogFile)) {
            temporaryFile.copyTo(catalogFile, overwrite = true)
            temporaryFile.delete()
        }
    }

    companion object {
        const val GEMMA_4_REPOSITORY =
            "litert-community/gemma-4-e2b-it-litert-lm"
        const val GEMMA_4_FILE = "gemma-4-E2B-it.litertlm"
        const val GEMMA_4_TENSOR_G5_FILE =
            "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
        const val GEMMA_4_SNAPDRAGON_8_ELITE_FILE =
            "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
        const val GEMMA_4_DISPLAY_NAME = "Gemma 4"
        const val GEMMA_4_SIZE_BYTES = 2_588_147_712L

        private const val CATALOG_FILE = "prompt_models.json"
        private const val MediaStoreAuthority = "media"
        private val FILE_LOCK = Any()

        fun remoteModelId(
            repository: String,
            artifactName: String? = null
        ): String = buildString {
            append("prompt:huggingface:")
            append(repository.trim().lowercase())
            artifactName?.takeIf(String::isNotBlank)?.let { artifact ->
                append(":artifact:")
                append(shortHash(artifact.trim().lowercase()))
            }
        }

        private fun shortHash(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
    }
}
