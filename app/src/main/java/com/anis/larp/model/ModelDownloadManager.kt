package com.anis.larp.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

class ModelDownloadManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val sharedStore = SharedModelStore(applicationContext)

    fun enqueue(
        repository: String,
        displayName: String,
        requestedFileName: String? = null,
        accelerationKind: AccelerationKind = AccelerationKind.AUTO,
        speculativeDecoding: Boolean = false
    ): DownloadRequest {
        val normalizedRepository = repository.trim()
        val modelId = PromptModelCatalog.remoteModelId(
            normalizedRepository,
            requestedFileName
        )
        val reusableModel = sharedStore.findCompleted(
            repository = normalizedRepository,
            artifactName = requestedFileName
        )
        val input = Data.Builder()
            .putString(ModelDownloadWorker.KEY_REPOSITORY, normalizedRepository)
            .putString(ModelDownloadWorker.KEY_MODEL_ID, modelId)
            .putString(ModelDownloadWorker.KEY_DISPLAY_NAME, displayName)
            .putString(ModelDownloadWorker.KEY_REQUESTED_FILE, requestedFileName)
            .putString(
                ModelDownloadWorker.KEY_ACCELERATION,
                accelerationKind.name
            )
            .putBoolean(
                ModelDownloadWorker.KEY_SPECULATIVE_DECODING,
                speculativeDecoding
            )
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (reusableModel == null) {
                            NetworkType.CONNECTED
                        } else {
                            NetworkType.NOT_REQUIRED
                        }
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInputData(input)
            .addTag(DOWNLOAD_TAG)
            .addTag(modelId)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(modelId),
            ExistingWorkPolicy.KEEP,
            request
        )
        return DownloadRequest(modelId = modelId, workId = request.id)
    }

    fun enqueueGemma4(): DownloadRequest {
        val profile = DeviceAccelerationProfile.detect()
        return enqueue(
            repository = PromptModelCatalog.GEMMA_4_REPOSITORY,
            displayName = PromptModelCatalog.GEMMA_4_DISPLAY_NAME,
            requestedFileName = profile.gemmaArtifactFileName,
            accelerationKind = profile.preferredKind,
            speculativeDecoding =
                profile.preferredKind != AccelerationKind.NPU
        )
    }

    data class DownloadRequest(
        val modelId: String,
        val workId: UUID
    )

    companion object {
        const val DOWNLOAD_TAG = "larp-prompt-model-download"

        private fun uniqueWorkName(modelId: String): String =
            "larp-model-${modelId.hashCode().toUInt()}"
    }
}
