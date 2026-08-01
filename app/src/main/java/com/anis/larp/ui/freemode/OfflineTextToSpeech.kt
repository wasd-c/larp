package com.anis.larp.ui.freemode

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

class OfflineTextToSpeech(context: Context) {
    private val initialization = CompletableDeferred<Int>()
    private val textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            initialization.complete(status)
        }
    }

    suspend fun speak(
        text: String,
        requestedLocale: Locale,
        selectedVoiceName: String? = null
    ): Voice {
        val spokenText = sanitizeTextForSpeech(text)
        if (spokenText.isBlank()) {
            throw IllegalArgumentException(
                "Le modèle n'a produit aucun texte utile à prononcer."
            )
        }
        if (initialization.await() != TextToSpeech.SUCCESS) {
            throw IllegalStateException("Le moteur de synthèse vocale n'a pas pu démarrer.")
        }

        val offlineVoices = textToSpeech.voices
            .orEmpty()
            .filter { voice ->
                voice.locale.language == requestedLocale.language &&
                    !voice.isNetworkConnectionRequired
            }
        val bestOfflineVoice = if (selectedVoiceName != null) {
            offlineVoices.firstOrNull { it.name == selectedVoiceName }
                ?: throw IllegalStateException(
                    "La voix TTS sélectionnée n'est plus disponible pour " +
                        requestedLocale.displayLanguage + "."
                )
        } else {
            offlineVoices
            .maxByOrNull { voice ->
                voice.quality
            }
        }
            ?: throw IllegalStateException(
                "Aucune voix hors ligne n'est installée pour ${requestedLocale.displayLanguage}."
            )

        if (textToSpeech.setVoice(bestOfflineVoice) == TextToSpeech.ERROR) {
            throw IllegalStateException(
                "La voix hors ligne ${bestOfflineVoice.name} n'a pas pu être sélectionnée."
            )
        }

        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            textToSpeech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resume(bestOfflineVoice)
                        }
                    }

                    @Deprecated("Deprecated by the Android framework")
                    override fun onError(id: String?) {
                        resumeWithSpeechError(id)
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        resumeWithSpeechError(id)
                    }

                    private fun resumeWithSpeechError(id: String?) {
                        if (id == utteranceId && continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("La synthèse vocale a échoué.")
                            )
                        }
                    }
                }
            )
            val result = textToSpeech.speak(
                spokenText,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                utteranceId
            )
            if (result == TextToSpeech.ERROR && continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("La synthèse vocale n'a pas pu commencer.")
                )
            }
            continuation.invokeOnCancellation {
                textToSpeech.stop()
            }
        }
        return bestOfflineVoice
    }

    fun stop() {
        textToSpeech.stop()
    }

    fun close() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }
}
