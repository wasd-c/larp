package com.anis.larp.ui.freemode

import com.anis.larp.model.ModelPreferences
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Owns ML Kit recognizer selection and download policy outside the conversation state machine. */
internal class MlKitRecognizerProvider(
    private val onStatusMessage: (String) -> Unit
) {
    suspend fun select(
        locale: Locale,
        selectedModelId: String?
    ): SelectedRecognizer? = when (selectedModelId) {
        ModelPreferences.STT_ML_KIT_ADVANCED -> selectRequired(
            locale = locale,
            mode = SpeechRecognizerOptions.Mode.MODE_ADVANCED,
            label = "Gemini · avancée"
        )
        ModelPreferences.STT_ML_KIT_BASIC -> selectRequired(
            locale = locale,
            mode = SpeechRecognizerOptions.Mode.MODE_BASIC,
            label = "Gemini · basique"
        )
        else -> selectBest(locale)
    }

    private suspend fun selectBest(locale: Locale): SelectedRecognizer? {
        val advanced = create(locale, SpeechRecognizerOptions.Mode.MODE_ADVANCED)
        val advancedStatus = runCatching {
            withTimeout(STATUS_TIMEOUT_MILLIS) { advanced.checkStatus() }
        }.getOrNull()
        if (advancedStatus == FeatureStatus.AVAILABLE) {
            return SelectedRecognizer(advanced, "Gemini · avancée")
        }
        advanced.close()

        val basic = create(locale, SpeechRecognizerOptions.Mode.MODE_BASIC)
        return when (withTimeout(STATUS_TIMEOUT_MILLIS) { basic.checkStatus() }) {
            FeatureStatus.AVAILABLE -> SelectedRecognizer(basic, "Gemini · basique")
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> download(
                recognizer = basic,
                label = "Gemini · basique",
                statusMessage = "Téléchargement du modèle vocal sur l'appareil…"
            )
            else -> {
                basic.close()
                null
            }
        }
    }

    private suspend fun selectRequired(
        locale: Locale,
        mode: Int,
        label: String
    ): SelectedRecognizer? {
        val recognizer = create(locale, mode)
        return when (withTimeout(STATUS_TIMEOUT_MILLIS) { recognizer.checkStatus() }) {
            FeatureStatus.AVAILABLE -> SelectedRecognizer(recognizer, label)
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> download(
                recognizer = recognizer,
                label = label,
                statusMessage = "Téléchargement du modèle STT $label sélectionné…"
            )
            else -> {
                recognizer.close()
                throw IllegalStateException(
                    "Le modèle STT $label sélectionné n'est pas disponible pour " +
                        locale.toLanguageTag() + "."
                )
            }
        }
    }

    private suspend fun download(
        recognizer: SpeechRecognizer,
        label: String,
        statusMessage: String
    ): SelectedRecognizer {
        onStatusMessage(statusMessage)
        val result = withTimeout(DOWNLOAD_TIMEOUT_MILLIS) {
            recognizer.download().first { status ->
                status is DownloadStatus.DownloadCompleted ||
                    status is DownloadStatus.DownloadFailed
            }
        }
        if (result is DownloadStatus.DownloadCompleted) {
            return SelectedRecognizer(recognizer, label)
        }
        recognizer.close()
        throw (result as DownloadStatus.DownloadFailed).e
    }

    private fun create(locale: Locale, mode: Int): SpeechRecognizer =
        SpeechRecognition.getClient(
            speechRecognizerOptions {
                this.locale = locale
                preferredMode = mode
            }
        )

    private companion object {
        const val STATUS_TIMEOUT_MILLIS = 10_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 120_000L
    }
}

internal data class SelectedRecognizer(
    val recognizer: SpeechRecognizer,
    val label: String
)
