package com.anis.larp.ui.freemode

import androidx.compose.runtime.Immutable
import java.util.Locale

enum class SpeechPhase {
    IDLE,
    PREPARING,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

enum class CreatedLearningContentKind {
    EXERCISE,
    LESSON
}

enum class ChatMessageAuthor {
    LEARNER,
    TUTOR
}

@Immutable
data class TutorChatMessage(
    val author: ChatMessageAuthor,
    val text: String
)

@Immutable
data class CreatedLearningContent(
    val id: String,
    val kind: CreatedLearningContentKind,
    val title: String,
    val description: String,
    val topic: String? = null,
    val difficulty: String? = null
)

@Immutable
data class FreeModeUiState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val committedTranscript: String = "",
    val partialTranscript: String = "",
    val locale: Locale = Locale.getDefault(),
    val targetLocale: Locale = Locale.ENGLISH,
    val recognitionMode: String? = null,
    val statusMessage: String? = null,
    val aiReply: String = "",
    val replyLocale: Locale? = null,
    val promptModelName: String? = null,
    val promptAcceleration: String? = null,
    val modelsReady: Boolean = false,
    val modelReadinessError: String? = null,
    val conversationActive: Boolean = false,
    val thinkingWord: String? = null,
    val createdContent: CreatedLearningContent? = null,
    val chatMessages: List<TutorChatMessage> = emptyList()
) {
    val isActive: Boolean
        get() = conversationActive ||
            (phase != SpeechPhase.IDLE && phase != SpeechPhase.ERROR)

    val visibleTranscript: String
        get() = listOf(committedTranscript, partialTranscript)
            .filter(String::isNotBlank)
            .joinToString(separator = " ")
            .trim()

    val canSendText: Boolean
        get() = modelsReady &&
            phase != SpeechPhase.THINKING &&
            phase != SpeechPhase.SPEAKING
}
