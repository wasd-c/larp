package com.anis.larp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuggingFaceModelReferenceTest {
    @Test
    fun parsesRepositoryIdentifier() {
        assertEquals(
            HuggingFaceModelReference("litert-community/gemma-4-E2B-it-litert-lm"),
            parseHuggingFaceModelReference(
                "litert-community/gemma-4-E2B-it-litert-lm"
            )
        )
    }

    @Test
    fun parsesQualcommBlobUrlAndKeepsExactArtifact() {
        assertEquals(
            HuggingFaceModelReference(
                repository = "litert-community/gemma-4-E2B-it-litert-lm",
                requestedFileName =
                    "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
            ),
            parseHuggingFaceModelReference(
                "https://huggingface.co/litert-community/" +
                    "gemma-4-E2B-it-litert-lm/blob/main/" +
                    "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
            )
        )
    }

    @Test
    fun parsesResolveUrlWithDownloadQuery() {
        assertEquals(
            "models/custom model.litertlm",
            parseHuggingFaceModelReference(
                "https://huggingface.co/owner/repo/resolve/main/" +
                    "models/custom%20model.litertlm?download=true"
            )?.requestedFileName
        )
    }

    @Test
    fun rejectsPagesWhichDoNotIdentifyMainLitertModel() {
        assertNull(
            parseHuggingFaceModelReference(
                "https://huggingface.co/owner/repo/blob/dev/model.litertlm"
            )
        )
        assertNull(
            parseHuggingFaceModelReference(
                "https://example.com/owner/repo/blob/main/model.litertlm"
            )
        )
        assertNull(
            parseHuggingFaceModelReference(
                "https://huggingface.co/owner/repo/blob/main/readme.md"
            )
        )
    }

    @Test
    fun differentArtifactsFromSameRepositoryHaveDifferentModelIds() {
        assertNotEquals(
            PromptModelCatalog.remoteModelId("owner/repo", "generic.litertlm"),
            PromptModelCatalog.remoteModelId("owner/repo", "qualcomm.litertlm")
        )
    }
}
