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

    fun enqueueQwenAsr(): DownloadRequest {
        val requests = QwenAsrModel.ARTIFACTS.mapIndexed { index, artifact ->
            val reusableModel = sharedStore.findCompleted(
                repository = QwenAsrModel.REPOSITORY,
                artifactName = artifact
            )
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
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
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_REPOSITORY, QwenAsrModel.REPOSITORY)
                        .putString(ModelDownloadWorker.KEY_MODEL_ID, ModelPreferences.STT_QWEN_3_ASR)
                        .putString(
                            ModelDownloadWorker.KEY_DISPLAY_NAME,
                            "Qwen ASR (${index + 1}/${QwenAsrModel.ARTIFACTS.size})"
                        )
                        .putString(ModelDownloadWorker.KEY_REQUESTED_FILE, artifact)
                        .putBoolean(ModelDownloadWorker.KEY_ADD_TO_PROMPT_CATALOG, false)
                        .build()
                )
                .addTag(DOWNLOAD_TAG)
                .addTag(QWEN_ASR_DOWNLOAD_TAG)
                .addTag(ModelPreferences.STT_QWEN_3_ASR)
                .build()
        }
        var continuation = workManager.beginUniqueWork(
            uniqueWorkName(ModelPreferences.STT_QWEN_3_ASR),
            ExistingWorkPolicy.KEEP,
            requests.first()
        )
        requests.drop(1).forEach { request ->
            continuation = continuation.then(request)
        }
        continuation.enqueue()
        return DownloadRequest(
            modelId = ModelPreferences.STT_QWEN_3_ASR,
            workId = requests.last().id
        )
    }

    data class DownloadRequest(
        val modelId: String,
        val workId: UUID
    )

    companion object {
        const val DOWNLOAD_TAG = "larp-prompt-model-download"
        const val QWEN_ASR_DOWNLOAD_TAG = "larp-qwen-asr-download"

        private fun uniqueWorkName(modelId: String): String =
            "larp-model-${modelId.hashCode().toUInt()}"
    }
}
