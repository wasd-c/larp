package com.anis.larp.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anis.larp.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val catalog = PromptModelCatalog(appContext)
    private val sharedStore = SharedModelStore(appContext)
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    @Volatile
    private var activeDownloadUri: Uri? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = inputData.getString(KEY_REPOSITORY)?.trim().orEmpty()
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
        val displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty()
        val requestedFile = inputData.getString(KEY_REQUESTED_FILE)
            ?.takeIf(String::isNotBlank)
        val acceleration = runCatching {
            AccelerationKind.valueOf(
                inputData.getString(KEY_ACCELERATION).orEmpty()
            )
        }.getOrDefault(AccelerationKind.AUTO)
        val speculativeDecoding =
            inputData.getBoolean(KEY_SPECULATIVE_DECODING, false)

        if (
            !REPOSITORY_PATTERN.matches(repository) ||
            modelId.isBlank() ||
            displayName.isBlank()
        ) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Dépôt Hugging Face invalide.")
            )
        }

        createNotificationChannel()
        setForeground(createForegroundInfo(displayName, 0, null))

        try {
            val reusableModel = sharedStore.findCompleted(
                repository = repository,
                artifactName = requestedFile
            )
            val artifactName = requestedFile
                ?: reusableModel?.fileName
                ?: resolveLiteRtModelFile(repository)
            require(artifactName.endsWith(".litertlm", ignoreCase = true)) {
                "Ce dépôt ne contient aucun modèle .litertlm compatible."
            }
            val sharedModel = reusableModel ?: download(
                repository = repository,
                artifactName = artifactName,
                displayName = displayName
            )
            val record = PromptModelRecord(
                id = modelId,
                displayName = displayName,
                filePath = "",
                contentUri = sharedModel.uri.toString(),
                source = "Hugging Face · ${SharedModelStore.USER_VISIBLE_DIRECTORY}",
                repository = repository,
                artifactFileName = artifactName,
                sizeBytes = sharedModel.sizeBytes,
                accelerationHint = acceleration,
                speculativeDecoding = speculativeDecoding
            )
            catalog.add(record)
            showFinishedNotification(
                displayName = displayName,
                message =
                    "Modèle prêt dans ${SharedModelStore.USER_VISIBLE_DIRECTORY}"
            )
            Result.success(
                workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_FILE_PATH to "",
                    KEY_FILE_URI to sharedModel.uri.toString()
                )
            )
        } catch (cancellation: CancellationException) {
            activeDownloadUri?.let(sharedStore::delete)
            activeDownloadUri = null
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: "Le téléchargement du modèle a échoué."
            if (isStopped) {
                activeDownloadUri?.let(sharedStore::delete)
                activeDownloadUri = null
                Result.failure(workDataOf(KEY_ERROR to "Téléchargement annulé."))
            } else if (error is IOException && runAttemptCount < 3) {
                Result.retry()
            } else {
                activeDownloadUri?.let(sharedStore::delete)
                activeDownloadUri = null
                showFinishedNotification(displayName, message, failed = true)
                Result.failure(workDataOf(KEY_ERROR to message))
            }
        }
    }

    private suspend fun download(
        repository: String,
        artifactName: String,
        displayName: String
    ): SharedModelFile {
        val sharedModel = sharedStore.createPending(
            repository = repository,
            artifactName = artifactName,
            displayName = displayName
        )
        if (!sharedModel.isPending && sharedModel.sizeBytes > 0L) {
            return sharedModel
        }
        activeDownloadUri = sharedModel.uri
        var existingBytes = sharedStore.size(sharedModel.uri)
        val encodedArtifact = artifactName
            .split("/")
            .joinToString("/") { encodePathSegment(it) }
        val modelUrl = URI(
            "https://huggingface.co/$repository/resolve/main/$encodedArtifact?download=true"
        ).toURL()
        val remoteBytes = resolveRemoteSize(modelUrl)
        if (remoteBytes > 0L && existingBytes == remoteBytes) {
            return publishCompletedDownload(
                sharedModel = sharedModel,
                displayName = displayName,
                sizeBytes = existingBytes
            )
        }
        if (remoteBytes > 0L && existingBytes > remoteBytes) {
            sharedStore.truncate(sharedModel.uri)
            existingBytes = 0L
        }
        val connection = (modelUrl.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "larp-android/1.0")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                val totalBytes = connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                if (existingBytes > 0L && totalBytes == existingBytes) {
                    return publishCompletedDownload(
                        sharedModel = sharedModel,
                        displayName = displayName,
                        sizeBytes = existingBytes
                    )
                }
                sharedStore.truncate(sharedModel.uri)
                throw IOException(
                    "La reprise du téléchargement a été refusée; elle va redémarrer proprement."
                )
            }
            if (responseCode !in 200..299) {
                throw IOException(
                    "Hugging Face a répondu avec le code $responseCode."
                )
            }
            val canResume =
                responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val downloadedBeforeRequest = if (canResume) existingBytes else 0L
            val responseBytes = connection.contentLengthLong.coerceAtLeast(0L)
            val totalBytes = when {
                remoteBytes > 0L -> remoteBytes
                responseBytes > 0L -> downloadedBeforeRequest + responseBytes
                else -> 0L
            }
            ensureStorageAvailable(
                requiredBytes = (totalBytes - downloadedBeforeRequest)
                    .coerceAtLeast(0L)
            )

            var downloadedSize = downloadedBeforeRequest
            connection.inputStream.buffered().use { input ->
                sharedStore.openOutput(
                    uri = sharedModel.uri,
                    append = canResume
                ).buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var downloaded = downloadedBeforeRequest
                    var lastReportedAt = 0L
                    while (true) {
                        if (isStopped) {
                            throw IOException("Téléchargement annulé.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastReportedAt >= PROGRESS_INTERVAL_MILLIS) {
                            reportProgress(displayName, downloaded, totalBytes)
                            lastReportedAt = now
                        }
                    }
                    output.flush()
                    downloadedSize = downloaded
                }
            }

            if (totalBytes > 0L && downloadedSize != totalBytes) {
                throw IOException(
                    "Téléchargement incomplet ($downloadedSize sur $totalBytes octets)."
                )
            }
            return publishCompletedDownload(
                sharedModel = sharedModel,
                displayName = displayName,
                sizeBytes = downloadedSize
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun publishCompletedDownload(
        sharedModel: SharedModelFile,
        displayName: String,
        sizeBytes: Long
    ): SharedModelFile {
        sharedStore.complete(sharedModel.uri)
        activeDownloadUri = null
        val publishedSize = sharedStore.size(sharedModel.uri)
            .takeIf { it > 0L }
            ?: sizeBytes
        reportProgress(displayName, publishedSize, publishedSize)
        return sharedModel.copy(
            sizeBytes = publishedSize,
            isPending = false
        )
    }

    private fun resolveRemoteSize(modelUrl: java.net.URL): Long {
        val connection = (modelUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "larp-android/1.0")
        }
        return try {
            connection.connect()
            if (connection.responseCode in 200..299) {
                connection.contentLengthLong.coerceAtLeast(0L)
            } else {
                0L
            }
        } catch (_: IOException) {
            0L
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun reportProgress(
        displayName: String,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val percent = if (totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        setProgress(
            workDataOf(
                KEY_PROGRESS_PERCENT to percent,
                KEY_DOWNLOADED_BYTES to downloadedBytes,
                KEY_TOTAL_BYTES to totalBytes
            )
        )
        setForeground(
            createForegroundInfo(
                displayName = displayName,
                percent = percent,
                totalBytes = totalBytes.takeIf { it > 0L }
            )
        )
    }

    private fun resolveLiteRtModelFile(repository: String): String {
        val apiUrl = URI("https://huggingface.co/api/models/$repository").toURL()
        val connection = (apiUrl.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "larp-android/1.0")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException(
                    "Le dépôt Hugging Face est introuvable ou nécessite une autorisation."
                )
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val siblings = JSONObject(response).optJSONArray("siblings")
                ?: throw IOException("Le dépôt ne contient aucun fichier.")
            buildList {
                for (index in 0 until siblings.length()) {
                    val fileName = siblings.getJSONObject(index)
                        .optString("rfilename")
                    if (fileName.endsWith(".litertlm", ignoreCase = true)) {
                        add(fileName)
                    }
                }
            }
                .minByOrNull(::artifactPreferenceScore)
                ?: throw IOException(
                    "Ce dépôt ne contient aucun modèle .litertlm compatible."
                )
        } finally {
            connection.disconnect()
        }
    }

    private fun artifactPreferenceScore(fileName: String): Int {
        val lower = fileName.lowercase()
        val platformPenalty = listOf(
            "web",
            "intel",
            "qualcomm",
            "tensor",
            "ios"
        ).count(lower::contains) * 10_000
        return platformPenalty + fileName.length
    }

    private fun ensureStorageAvailable(requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val sharedVolume = applicationContext.getExternalFilesDir(null)
            ?: applicationContext.filesDir
        val available = StatFs(sharedVolume.absolutePath).availableBytes
        val reserve = 256L * 1024L * 1024L
        if (available < requiredBytes + reserve) {
            throw IOException(
                "Espace insuffisant : libérez au moins " +
                    formatBytes(requiredBytes + reserve - available) + "."
            )
        }
    }

    private fun createForegroundInfo(
        displayName: String,
        percent: Int,
        totalBytes: Long?
    ): ForegroundInfo {
        val cancelIntent: PendingIntent =
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Téléchargement de $displayName")
            .setContentText(
                if (totalBytes == null) {
                    "Connexion à Hugging Face…"
                } else {
                    "$percent % · ${formatBytes(totalBytes)}"
                }
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, totalBytes == null)
            .addAction(0, "Annuler", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        val notification = builder.build()
        return ForegroundInfo(
            notificationId(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun showFinishedNotification(
        displayName: String,
        message: String,
        failed: Boolean = false
    ) {
        notificationManager.notify(
            notificationId(),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(
                    if (failed) {
                        "Téléchargement de $displayName interrompu"
                    } else {
                        "$displayName est prêt"
                    }
                )
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Téléchargements de modèles",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Progression des modèles utilisables hors ligne dans larp"
            }
        )
    }

    private fun notificationId(): Int =
        NOTIFICATION_ID_BASE + (id.hashCode() and 0x0FFF)

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            "%.1f Go".format(gib)
        } else {
            "%.0f Mo".format(bytes / (1024.0 * 1024.0))
        }
    }

    companion object {
        const val KEY_REPOSITORY = "repository"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_REQUESTED_FILE = "requested_file"
        const val KEY_ACCELERATION = "acceleration"
        const val KEY_SPECULATIVE_DECODING = "speculative_decoding"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_FILE_URI = "file_uri"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID_BASE = 4_200
        private const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_INTERVAL_MILLIS = 750L
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val REPOSITORY_PATTERN =
            Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    }
}
