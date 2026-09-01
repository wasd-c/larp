package com.anis.larp.ui.freemode

import com.anis.larp.model.ModelPreferences
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
        ModelPreferences.STT_ML_KIT_ADVANCED -> selectAdvancedOrBasic(locale)
        ModelPreferences.STT_ML_KIT_BASIC -> selectRequired(
            locale = locale,
            mode = SpeechRecognizerOptions.Mode.MODE_BASIC,
            label = "Gemini · basique",
            modelId = ModelPreferences.STT_ML_KIT_BASIC
        )
        else -> selectBest(locale)
    }

    private suspend fun selectAdvancedOrBasic(locale: Locale): SelectedRecognizer = try {
        selectRequired(
            locale = locale,
            mode = SpeechRecognizerOptions.Mode.MODE_ADVANCED,
            label = "Gemini · avancée",
            modelId = ModelPreferences.STT_ML_KIT_ADVANCED
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        onStatusMessage(
            "Reconnaissance avancée indisponible · passage au modèle Android basique…"
        )
        selectRequired(
            locale = locale,
            mode = SpeechRecognizerOptions.Mode.MODE_BASIC,
            label = "Gemini · basique",
            modelId = ModelPreferences.STT_ML_KIT_BASIC
        )
    }

    private suspend fun selectBest(locale: Locale): SelectedRecognizer? {
        val advanced = create(locale, SpeechRecognizerOptions.Mode.MODE_ADVANCED)
        val advancedStatus = try {
            withTimeout(STATUS_TIMEOUT_MILLIS) { advanced.checkStatus() }
        } catch (cancellation: CancellationException) {
            advanced.close()
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (advancedStatus == FeatureStatus.AVAILABLE) {
            return SelectedRecognizer(
                advanced,
                "Gemini · avancée",
                ModelPreferences.STT_ML_KIT_ADVANCED
            )
        }
        advanced.close()

        val basic = create(locale, SpeechRecognizerOptions.Mode.MODE_BASIC)
        return try {
            when (withTimeout(STATUS_TIMEOUT_MILLIS) { basic.checkStatus() }) {
                FeatureStatus.AVAILABLE -> SelectedRecognizer(
                    basic,
                    "Gemini · basique",
                    ModelPreferences.STT_ML_KIT_BASIC
                )
                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING -> download(
                    recognizer = basic,
                    label = "Gemini · basique",
                    modelId = ModelPreferences.STT_ML_KIT_BASIC,
                    statusMessage = "Téléchargement du modèle vocal sur l'appareil…"
                )
                else -> {
                    basic.close()
                    null
                }
            }
        } catch (error: Throwable) {
            basic.close()
            throw error
        }
    }

    private suspend fun selectRequired(
        locale: Locale,
        mode: Int,
        label: String,
        modelId: String
    ): SelectedRecognizer {
        val recognizer = create(locale, mode)
        return try {
            when (withTimeout(STATUS_TIMEOUT_MILLIS) { recognizer.checkStatus() }) {
                FeatureStatus.AVAILABLE -> SelectedRecognizer(recognizer, label, modelId)
                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING -> download(
                    recognizer = recognizer,
                    label = label,
                    modelId = modelId,
                    statusMessage = "Téléchargement du modèle STT $label sélectionné…"
                )
                else -> throw IllegalStateException(
                    "Le modèle STT $label sélectionné n'est pas disponible pour " +
                        locale.toLanguageTag() + "."
                )
            }
        } catch (error: Throwable) {
            recognizer.close()
            throw error
        }
    }

    private suspend fun download(
        recognizer: SpeechRecognizer,
        label: String,
        modelId: String,
        statusMessage: String
    ): SelectedRecognizer {
        onStatusMessage(statusMessage)
        val result = try {
            withTimeout(DOWNLOAD_TIMEOUT_MILLIS) {
                recognizer.download().first { status ->
                    status is DownloadStatus.DownloadCompleted ||
                        status is DownloadStatus.DownloadFailed
                }
            }
        } catch (error: Throwable) {
            recognizer.close()
            throw error
        }
        if (result is DownloadStatus.DownloadCompleted) {
            return SelectedRecognizer(recognizer, label, modelId)
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
    val label: String,
    val modelId: String
)
