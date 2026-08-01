package com.anis.larp.model

import android.os.Build
import android.util.Log

enum class AccelerationKind {
    AUTO,
    CPU,
    GPU,
    NPU
}

data class DeviceAccelerationProfile(
    val preferredKind: AccelerationKind,
    val gemmaArtifactFileName: String,
    val label: String,
    val npuRuntimeLabel: String? = null,
    val incompatibleArtifactFileNames: Set<String> = emptySet()
) {
    val hasNpu: Boolean
        get() = preferredKind == AccelerationKind.NPU

    fun supportsArtifact(artifactFileName: String?): Boolean =
        artifactFileName == null || incompatibleArtifactFileNames.none {
            it.equals(artifactFileName, ignoreCase = true)
        }

    companion object {
        fun detect(): DeviceAccelerationProfile {
            val profile = detect(
                socModel = Build.SOC_MODEL.orEmpty(),
                hardware = Build.HARDWARE.orEmpty(),
                board = Build.BOARD.orEmpty()
            )
            val signature = "${Build.SOC_MODEL}|${profile.label}"
            if (signature != lastLoggedSignature) {
                lastLoggedSignature = signature
                if (profile.hasNpu) {
                    Log.i(
                        LOG_TAG,
                        "NPU détecté: SoC=${Build.SOC_MODEL}; " +
                            "hardware=${Build.HARDWARE}; board=${Build.BOARD}; " +
                            "runtime=${profile.npuRuntimeLabel}; préférence=NPU"
                    )
                } else {
                    Log.i(
                        LOG_TAG,
                        "Aucun profil NPU LiteRT connu: SoC=${Build.SOC_MODEL}; " +
                            "préférence=${profile.preferredKind}"
                    )
                }
            }
            return profile
        }

        internal fun detect(
            socModel: String,
            hardware: String,
            board: String
        ): DeviceAccelerationProfile {
            val soc = socModel.lowercase()
            val normalizedHardware = hardware.lowercase()
            val normalizedBoard = board.lowercase()
            val deviceFingerprint = "$soc $normalizedHardware $normalizedBoard"

            return when {
                "sm8850" in deviceFingerprint -> DeviceAccelerationProfile(
                    preferredKind = AccelerationKind.NPU,
                    gemmaArtifactFileName = PromptModelCatalog.GEMMA_4_FILE,
                    label = "NPU Snapdragon SM8850 · HTP v81",
                    npuRuntimeLabel = "Qualcomm QNN / HTP v81",
                    incompatibleArtifactFileNames = setOf(
                        PromptModelCatalog.GEMMA_4_SNAPDRAGON_8_ELITE_FILE
                    )
                )

                "sm8750" in deviceFingerprint -> DeviceAccelerationProfile(
                    preferredKind = AccelerationKind.NPU,
                    gemmaArtifactFileName =
                        PromptModelCatalog.GEMMA_4_SNAPDRAGON_8_ELITE_FILE,
                    label = "NPU Snapdragon 8 Elite",
                    npuRuntimeLabel = "Qualcomm QNN / HTP"
                )

                "tensor g5" in deviceFingerprint ||
                    "tensor_g5" in deviceFingerprint -> DeviceAccelerationProfile(
                    preferredKind = AccelerationKind.NPU,
                    gemmaArtifactFileName =
                        PromptModelCatalog.GEMMA_4_TENSOR_G5_FILE,
                    label = "NPU Google Tensor G5",
                    npuRuntimeLabel = "Google Tensor NPU"
                )

                else -> DeviceAccelerationProfile(
                    preferredKind = AccelerationKind.GPU,
                    gemmaArtifactFileName = PromptModelCatalog.GEMMA_4_FILE,
                    label = "GPU avec repli CPU"
                )
            }
        }

        @Volatile
        private var lastLoggedSignature: String? = null
        private const val LOG_TAG = "LarpLiteRt"
    }
}
