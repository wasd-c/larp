package com.anis.larp.ui.onboarding

import com.anis.larp.model.ModelPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingModelRoutingTest {
    @Test
    fun androidOnboardingUsesBroadlySupportedBasicSpeechRecognition() {
        assertEquals(
            ModelPreferences.STT_ML_KIT_BASIC,
            AsrChoice.ANDROID.onboardingModelId()
        )
        assertEquals(
            ModelPreferences.STT_QWEN_3_ASR,
            AsrChoice.QWEN.onboardingModelId()
        )
    }
}
