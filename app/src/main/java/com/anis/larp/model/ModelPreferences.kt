package com.anis.larp.model

import android.content.Context

class ModelPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var onboardingComplete: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        private set(value) {
            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()
        }

    var nativeLanguageTag: String
        get() = preferences.getString(KEY_NATIVE_LANGUAGE, null)
            ?: java.util.Locale.getDefault().toLanguageTag()
        set(value) {
            preferences.edit().putString(KEY_NATIVE_LANGUAGE, value).apply()
        }

    var targetLanguage: LearningLanguage
        get() = LearningLanguage.fromLanguageTag(
            preferences.getString(KEY_TARGET_LANGUAGE, null)
        )
        set(value) {
            preferences.edit().putString(KEY_TARGET_LANGUAGE, value.languageTag).apply()
        }

    var promptModelId: String
        get() = preferences.getString(KEY_PROMPT_MODEL, null) ?: PROMPT_GEMINI_NANO
        set(value) {
            preferences.edit().putString(KEY_PROMPT_MODEL, value).apply()
        }

    var ttsVoiceName: String?
        get() = preferences.getString(KEY_TTS_VOICE, null)
        set(value) {
            preferences.edit().putString(KEY_TTS_VOICE, value).apply()
        }

    var sttModelId: String?
        get() = preferences.getString(KEY_STT_MODEL, null)
        set(value) {
            preferences.edit().putString(KEY_STT_MODEL, value).apply()
        }

    fun completeOnboarding(
        nativeLanguageTag: String,
        targetLanguage: LearningLanguage,
        promptModelId: String,
        sttModelId: String
    ) {
        preferences.edit()
            .putString(KEY_NATIVE_LANGUAGE, nativeLanguageTag)
            .putString(KEY_TARGET_LANGUAGE, targetLanguage.languageTag)
            .putString(KEY_PROMPT_MODEL, promptModelId)
            .putString(KEY_STT_MODEL, sttModelId)
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
        onboardingComplete = true
    }

    companion object {
        const val PROMPT_GEMINI_NANO = "prompt:gemini-nano"
        const val STT_ML_KIT_ADVANCED = "stt:ml-kit-advanced"
        const val STT_ML_KIT_BASIC = "stt:ml-kit-basic"
        const val STT_QWEN_3_ASR = "stt:qwen3-asr-0.6b"

        private const val PREFERENCES_NAME = "larp_models"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_NATIVE_LANGUAGE = "native_language"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_PROMPT_MODEL = "prompt_model"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_STT_MODEL = "stt_model"
    }
}
