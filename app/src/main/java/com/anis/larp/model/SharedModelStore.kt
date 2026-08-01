package com.anis.larp.model

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.io.OutputStream

data class SharedModelFile(
    val uri: Uri,
    val fileName: String,
    val displayName: String,
    val repository: String?,
    val sizeBytes: Long,
    val isPending: Boolean
)

class SharedModelStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    )

    fun findCompleted(
        repository: String,
        artifactName: String? = null
    ): SharedModelFile? = querySharedModels()
        .asSequence()
        .filter { !it.isPending && it.sizeBytes > 0L }
        .filter { it.repository.equals(repository, ignoreCase = true) }
        .filter { model ->
            artifactName == null ||
                model.fileName.equals(
                    artifactName.substringAfterLast('/'),
                    ignoreCase = true
                )
        }
        .maxByOrNull(SharedModelFile::sizeBytes)

    fun findForDownload(
        repository: String,
        artifactName: String
    ): SharedModelFile? {
        val candidates = querySharedModels()
            .filter { it.repository.equals(repository, ignoreCase = true) }
            .filter {
                it.fileName.equals(
                    artifactName.substringAfterLast('/'),
                    ignoreCase = true
                )
            }
            .map { model ->
                model.copy(sizeBytes = size(model.uri))
            }
        val selected = candidates.maxWithOrNull(
            compareBy<SharedModelFile> { !it.isPending }
                .thenBy(SharedModelFile::sizeBytes)
        ) ?: return null

        candidates
            .filter { it.isPending && it.uri != selected.uri }
            .forEach { duplicate -> delete(duplicate.uri) }
        return selected
    }

    fun createPending(
        repository: String,
        artifactName: String,
        displayName: String
    ): SharedModelFile {
        findForDownload(repository, artifactName)?.let { return it }
        val fileName = safeFileName(artifactName.substringAfterLast('/'))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.TITLE, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MODEL_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(
                MediaStore.DownloadColumns.DOWNLOAD_URI,
                repositorySource(repository)
            )
        }
        val uri = resolver.insert(collection, values)
            ?: throw FileNotFoundException(
                "Android n'a pas pu créer $RELATIVE_DIRECTORY$fileName."
            )
        return SharedModelFile(
            uri = uri,
            fileName = fileName,
            displayName = displayName,
            repository = repository,
            sizeBytes = 0L,
            isPending = true
        )
    }

    fun openOutput(uri: Uri, append: Boolean): OutputStream =
        resolver.openOutputStream(uri, if (append) "wa" else "w")
            ?: throw FileNotFoundException("Le fichier partagé ne peut pas être écrit.")

    fun truncate(uri: Uri) {
        openOutput(uri, append = false).use { }
    }

    fun complete(uri: Uri) {
        val updated = resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null
        )
        check(updated == 1) {
            "Le modèle partagé ne peut pas être finalisé."
        }
    }

    fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    fun size(uri: Uri): Long {
        val descriptorSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            }
        }.getOrNull() ?: -1L
        val mediaStoreSize = queryOne(uri)?.sizeBytes ?: -1L
        return maxOf(descriptorSize, mediaStoreSize, 0L)
    }

    fun isAccessible(uriString: String): Boolean = runCatching {
        val model = queryOne(Uri.parse(uriString))
        model != null && !model.isPending && model.sizeBytes > 0L
    }.getOrDefault(false)

    fun openRead(uriString: String): ParcelFileDescriptor =
        resolver.openFileDescriptor(Uri.parse(uriString), "r")
            ?: throw FileNotFoundException(
                "Le modèle partagé n'est plus accessible. Sélectionnez-le dans Files."
            )

    fun discoveredRecords(): List<PromptModelRecord> {
        val profile = DeviceAccelerationProfile.detect()
        return querySharedModels()
            .asSequence()
            .filter { !it.isPending && it.sizeBytes > 0L }
            .sortedByDescending { model ->
                model.fileName.equals(
                    profile.gemmaArtifactFileName,
                    ignoreCase = true
                )
            }
            .distinctBy { model ->
                model.repository?.let { repository ->
                    "${repository.lowercase()}|${model.fileName.lowercase()}"
                } ?: model.uri.toString()
            }
            .map { model ->
                val isGemma4 = model.repository.equals(
                    PromptModelCatalog.GEMMA_4_REPOSITORY,
                    ignoreCase = true
                ) || model.fileName.contains("gemma-4", ignoreCase = true)
                PromptModelRecord(
                    id = model.repository?.let { repository ->
                        PromptModelCatalog.remoteModelId(repository, model.fileName)
                    }
                        ?: "prompt:shared:${model.uri}",
                    displayName = when {
                        isGemma4 -> PromptModelCatalog.GEMMA_4_DISPLAY_NAME
                        model.displayName.isNotBlank() -> model.displayName
                        else -> model.fileName.substringBeforeLast(".litertlm")
                    },
                    filePath = "",
                    contentUri = model.uri.toString(),
                    source = "Download/Models",
                    repository = model.repository,
                    artifactFileName = model.fileName,
                    sizeBytes = model.sizeBytes,
                    accelerationHint = if (isGemma4) {
                        profile.preferredKind
                    } else {
                        AccelerationKind.AUTO
                    },
                    speculativeDecoding = isGemma4 &&
                        profile.preferredKind != AccelerationKind.NPU
                )
            }
            .toList()
    }

    private fun querySharedModels(): List<SharedModelFile> = buildList {
        resolver.query(
            collection,
            PROJECTION,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(RELATIVE_DIRECTORY),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val sourceIndex = cursor.getColumnIndexOrThrow(
                MediaStore.DownloadColumns.DOWNLOAD_URI
            )
            val pendingIndex = cursor.getColumnIndexOrThrow(
                MediaStore.MediaColumns.IS_PENDING
            )
            while (cursor.moveToNext()) {
                val fileName = cursor.getString(nameIndex).orEmpty()
                if (!fileName.endsWith(".litertlm", ignoreCase = true)) continue
                add(
                    SharedModelFile(
                        uri = ContentUris.withAppendedId(
                            collection,
                            cursor.getLong(idIndex)
                        ),
                        fileName = fileName,
                        displayName = cursor.getString(titleIndex).orEmpty(),
                        repository = repositoryFromSource(
                            cursor.getString(sourceIndex)
                        ),
                        sizeBytes = cursor.getLong(sizeIndex),
                        isPending = cursor.getInt(pendingIndex) != 0
                    )
                )
            }
        }
    }

    private fun queryOne(uri: Uri): SharedModelFile? = resolver.query(
        uri,
        PROJECTION,
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        SharedModelFile(
            uri = uri,
            fileName = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            ).orEmpty(),
            displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            ).orEmpty(),
            repository = repositoryFromSource(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.DownloadColumns.DOWNLOAD_URI
                    )
                )
            ),
            sizeBytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            ),
            isPending = cursor.getInt(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            ) != 0
        )
    }

    private fun repositoryFromSource(source: String?): String? {
        val prefix = "https://huggingface.co/"
        val value = source?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?: return null
        val segments = value.split('/').filter(String::isNotBlank)
        return segments.takeIf { it.size >= 2 }
            ?.take(2)
            ?.joinToString("/")
    }

    private fun repositorySource(repository: String): String =
        "https://huggingface.co/${repository.trim()}"

    private fun safeFileName(value: String): String {
        val stem = value.substringBeforeLast(".litertlm")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(100)
            .ifBlank { "model" }
        return "$stem.litertlm"
    }

    companion object {
        val RELATIVE_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/Models/"
        const val USER_VISIBLE_DIRECTORY = "Download/Models"
        const val MODEL_MIME_TYPE = "application/octet-stream"

        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.DownloadColumns.DOWNLOAD_URI,
            MediaStore.MediaColumns.IS_PENDING
        )
    }
}
