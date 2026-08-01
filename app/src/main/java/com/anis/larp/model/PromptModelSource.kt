package com.anis.larp.model

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.system.Os
import java.io.File
import java.io.IOException

class PromptModelSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val sharedStore = SharedModelStore(applicationContext)
    private val runtimeDirectory = File(
        applicationContext.noBackupFilesDir,
        "model-runtime"
    )

    fun open(record: PromptModelRecord): OpenPromptModel {
        record.contentUri?.takeIf(String::isNotBlank)?.let { uri ->
            val descriptor = sharedStore.openRead(uri)
            try {
                check(descriptor.statSize > 0L) {
                    "Le fichier de ${record.displayName} est vide."
                }
                val descriptorPath = "/proc/self/fd/${descriptor.fd}"
                val resolvedPath = runCatching {
                    Os.readlink(descriptorPath)
                }.getOrNull()
                if (
                    resolvedPath?.endsWith(".litertlm", ignoreCase = true) == true &&
                    File(resolvedPath).canRead()
                ) {
                    return OpenPromptModel(
                        key = uri,
                        path = resolvedPath,
                        descriptor = descriptor
                    )
                }
                val runtimeFile = prepareRuntimeCopy(
                    uri = uri,
                    expectedSize = descriptor.statSize,
                    descriptor = descriptor
                )
                return OpenPromptModel(
                    key = uri,
                    path = runtimeFile.absolutePath,
                    descriptor = null
                )
            } catch (error: Throwable) {
                runCatching(descriptor::close)
                throw error
            }
        }

        val file = File(record.filePath)
        require(file.isFile && file.length() > 0L) {
            "Le fichier de ${record.displayName} est introuvable."
        }
        return OpenPromptModel(
            key = file.absolutePath,
            path = file.absolutePath,
            descriptor = null
        )
    }

    private fun prepareRuntimeCopy(
        uri: String,
        expectedSize: Long,
        descriptor: ParcelFileDescriptor
    ): File {
        runtimeDirectory.mkdirs()
        val target = File(
            runtimeDirectory,
            "${uri.hashCode().toUInt().toString(16)}.litertlm"
        )
        if (target.isFile && target.length() == expectedSize) {
            descriptor.close()
            removeOtherRuntimeCopies(target)
            return target
        }

        val reserveBytes = 256L * 1024L * 1024L
        val availableBytes = StatFs(runtimeDirectory.absolutePath).availableBytes
        if (availableBytes < expectedSize + reserveBytes) {
            descriptor.close()
            throw IOException(
                "Espace insuffisant pour préparer le modèle : libérez au moins " +
                    formatBytes(expectedSize + reserveBytes - availableBytes) + "."
            )
        }

        val partial = File(runtimeDirectory, "${target.name}.partial")
        partial.delete()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .buffered(COPY_BUFFER_SIZE)
                .use { input ->
                    partial.outputStream()
                        .buffered(COPY_BUFFER_SIZE)
                        .use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
            check(partial.length() == expectedSize) {
                "La préparation du modèle est incomplète."
            }
            target.delete()
            check(partial.renameTo(target)) {
                "Le modèle préparé ne peut pas être finalisé."
            }
            removeOtherRuntimeCopies(target)
            return target
        } catch (error: Throwable) {
            partial.delete()
            runCatching(descriptor::close)
            throw error
        }
    }

    private fun removeOtherRuntimeCopies(selected: File) {
        runtimeDirectory.listFiles()
            ?.filter { it != selected }
            ?.forEach(File::delete)
    }

    private fun formatBytes(bytes: Long): String =
        "%.1f Go".format(bytes / (1024.0 * 1024.0 * 1024.0))

    companion object {
        private const val COPY_BUFFER_SIZE = 1024 * 1024
    }
}

data class OpenPromptModel(
    val key: String,
    val path: String,
    private val descriptor: ParcelFileDescriptor?
) : AutoCloseable {
    override fun close() {
        descriptor?.close()
    }
}
