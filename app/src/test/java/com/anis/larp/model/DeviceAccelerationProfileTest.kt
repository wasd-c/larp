package com.anis.larp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccelerationProfileTest {
    @Test
    fun sm8850UsesQualcommHtpV81NpuProfile() {
        val profile = DeviceAccelerationProfile.detect(
            socModel = "SM8850",
            hardware = "qcom",
            board = "canoe"
        )

        assertEquals(AccelerationKind.NPU, profile.preferredKind)
        assertEquals("Qualcomm QNN / HTP v81", profile.npuRuntimeLabel)
        assertEquals(
            PromptModelCatalog.GEMMA_4_FILE,
            profile.gemmaArtifactFileName
        )
        assertTrue(profile.hasNpu)
        assertTrue(
            !profile.supportsArtifact(
                PromptModelCatalog.GEMMA_4_SNAPDRAGON_8_ELITE_FILE
            )
        )
        assertTrue(profile.supportsArtifact(PromptModelCatalog.GEMMA_4_FILE))
    }

    @Test
    fun unknownSocKeepsGpuFallbackProfile() {
        val profile = DeviceAccelerationProfile.detect(
            socModel = "unknown",
            hardware = "generic",
            board = "generic"
        )

        assertEquals(AccelerationKind.GPU, profile.preferredKind)
        assertEquals(null, profile.npuRuntimeLabel)
    }
}
