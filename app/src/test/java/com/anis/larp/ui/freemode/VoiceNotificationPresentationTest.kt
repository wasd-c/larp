package com.anis.larp.ui.freemode

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNotificationPresentationTest {
    @Test
    fun listeningShowsTheConfiguredNativeLanguage() {
        val presentation = voiceNotificationPresentation(
            FreeModeUiState(
                phase = SpeechPhase.LISTENING,
                locale = Locale.FRENCH,
                conversationActive = true
            )
        )

        assertEquals("larp vous écoute", presentation.title)
        assertEquals("Parlez en français", presentation.text)
    }

    @Test
    fun thinkingUsesKnownModelLabel() {
        val presentation = voiceNotificationPresentation(
            FreeModeUiState(
                phase = SpeechPhase.THINKING,
                promptModelName = "Gemma",
                conversationActive = true
            )
        )

        assertEquals("larp prépare sa réponse", presentation.title)
        assertEquals("Gemma réfléchit…", presentation.text)
    }

    @Test
    fun thinkingFallsBackToGenericModelLabel() {
        val presentation = voiceNotificationPresentation(
            FreeModeUiState(
                phase = SpeechPhase.THINKING,
                conversationActive = true
            )
        )

        assertEquals("Le modèle réfléchit…", presentation.text)
    }
}
