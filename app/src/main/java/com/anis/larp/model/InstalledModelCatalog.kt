package com.anis.larp.model

import android.content.Context
import android.speech.tts.TextToSpeech
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class InstalledModelOption(
    val id: String,
    val label: String,
    val description: String
)

data class ModelInventory(
    val ttsModels: List<InstalledModelOption> = emptyList(),
    val promptModels: List<InstalledModelOption> = emptyList(),
    val sttModels: List<InstalledModelOption> = emptyList()
)

class InstalledModelCatalog(
    context: Context,
    private val promptCatalog: PromptModelCatalog
) {
    private val applicationContext = context.applicationContext

    suspend fun load(
        targetLanguage: LearningLanguage,
        nativeLanguageTag: String
    ): ModelInventory = coroutineScope {
        val tts = async {
            runCatching { loadTts(targetLanguage.locale) }.getOrDefault(emptyList())
        }
        val prompt = async {
            runCatching { loadPromptModels(targetLanguage) }.getOrDefault(emptyList())
        }
        val stt = async {
            runCatching {
                loadStt(
                    listOf(
                        speechRecognitionLocaleFor(nativeLanguageTag),
                        targetLanguage.speechRecognitionLocale
                    ).distinctBy(Locale::toLanguageTag)
                )
            }.getOrDefault(emptyList())
        }
        ModelInventory(
            ttsModels = tts.await(),
            promptModels = prompt.await(),
            sttModels = stt.await()
        )
    }

    private suspend fun loadTts(targetLocale: Locale): List<InstalledModelOption> {
        val requestedLocale = textToSpeechLocaleFor(targetLocale.toLanguageTag())
        val initialized = CompletableDeferred<Int>()
        val textToSpeech = withContext(Dispatchers.Main) {
            TextToSpeech(applicationContext) { status ->
                initialized.complete(status)
            }
        }
        return try {
            if (withTimeout(15_000) { initialized.await() } != TextToSpeech.SUCCESS) {
                emptyList()
            } else {
                textToSpeech.voices
                    .orEmpty()
                    .filter { voice ->
                        !voice.isNetworkConnectionRequired &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                            voice.features.orEmpty() &&
                            offlineVoiceLocaleScore(requestedLocale, voice.locale) >= 0
                    }
                    .sortedWith(
                        compareByDescending<android.speech.tts.Voice> {
                            offlineVoiceLocaleScore(requestedLocale, it.locale)
                        }
                            .thenByDescending { it.quality }
                            .thenBy { it.name }
                    )
                    .map { voice ->
                        InstalledModelOption(
                            id = voice.name,
                            label = voice.name,
                            description =
                                "${voice.locale.toLanguageTag()} · hors ligne · qualité ${voice.quality}"
                        )
                    }
            }
        } finally {
            withContext(Dispatchers.Main) {
                textToSpeech.shutdown()
            }
        }
    }

    private suspend fun loadPromptModels(
        targetLanguage: LearningLanguage
    ): List<InstalledModelOption> {
        val localModels = withContext(Dispatchers.IO) {
            promptCatalog.availableModels().map { record ->
                InstalledModelOption(
                    id = record.id,
                    label = record.displayName,
                    description = buildString {
                        append(record.source)
                        append(" · ")
                        append(formatSize(record.sizeBytes))
                        append(" · ")
                        append(
                            when (record.accelerationHint) {
                                AccelerationKind.NPU -> "NPU"
                                AccelerationKind.GPU -> "GPU/CPU"
                                AccelerationKind.CPU -> "CPU"
                                AccelerationKind.AUTO -> "accélération auto"
                            }
                        )
                        if (record.speculativeDecoding) {
                            append(" · MTP spéculatif")
                        }
                    }
                )
            }
        }
        val nanoOption = if (!promptModelSupportsTargetLanguage(
                ModelPreferences.PROMPT_GEMINI_NANO,
                targetLanguage
            )
        ) {
            emptyList()
        } else runCatching {
            val geminiNano = Generation.getClient()
            try {
                if (
                    withTimeout(10_000) { geminiNano.checkStatus() } ==
                    FeatureStatus.AVAILABLE
                ) {
                    listOf(
                        InstalledModelOption(
                            id = ModelPreferences.PROMPT_GEMINI_NANO,
                            label = "Gemini Nano",
                            description = "Android AI Core · installé sur l'appareil"
                        )
                    )
                } else {
                    emptyList()
                }
            } finally {
                geminiNano.close()
            }
        }.getOrDefault(emptyList())
        return nanoOption + localModels
    }

    private suspend fun loadStt(
        requiredLocales: List<Locale>
    ): List<InstalledModelOption> {
        val candidates = listOf(
            Triple(
                ModelPreferences.STT_ML_KIT_ADVANCED,
                SpeechRecognizerOptions.Mode.MODE_ADVANCED,
                "Gemini · avancée"
            ),
            Triple(
                ModelPreferences.STT_ML_KIT_BASIC,
                SpeechRecognizerOptions.Mode.MODE_BASIC,
                "Gemini · basique"
            )
        )
        return buildList {
            if (QwenAsrModel.isAvailable(applicationContext)) {
                add(
                    InstalledModelOption(
                        id = ModelPreferences.STT_QWEN_3_ASR,
                        label = "Qwen",
                        description =
                            "Qwen3-ASR 0.6B · multilingue · hors ligne"
                    )
                )
            }
            candidates.forEach { (id, mode, label) ->
                runCatching {
                    val availableForEveryLocale = requiredLocales.all { locale ->
                        val recognizer = SpeechRecognition.getClient(
                            speechRecognizerOptions {
                                this.locale = locale
                                preferredMode = mode
                            }
                        )
                        try {
                            withTimeout(10_000) { recognizer.checkStatus() } ==
                                FeatureStatus.AVAILABLE
                        } finally {
                            recognizer.close()
                        }
                    }
                    if (availableForEveryLocale) {
                        add(
                            InstalledModelOption(
                                id = id,
                                label = label,
                                description =
                                    requiredLocales.joinToString(" + ") {
                                        it.toLanguageTag()
                                    } + " · sur l'appareil"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "taille inconnue"
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            "%.1f Go".format(gib)
        } else {
            "%.0f Mo".format(bytes / (1024.0 * 1024.0))
        }
    }
}
