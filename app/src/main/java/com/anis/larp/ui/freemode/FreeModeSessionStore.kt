package com.anis.larp.ui.freemode

import android.content.Context
import com.anis.larp.learning.LearningContentAction
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class FreeModeSessionMetadata(
    val nativeLanguageTag: String,
    val targetLanguageTag: String,
    val promptModelId: String,
    val promptModelLabel: String,
    val sttModelId: String,
    val ttsVoiceName: String,
    val appVersion: String,
    val device: String,
    val androidSdk: Int
)

/**
 * Append-only, local diagnostic history for Libre conversations.
 *
 * Each conversation has its own JSONL file so a process interruption cannot
 * erase earlier turns. Raw microphone audio is never stored.
 */
class FreeModeSessionStore(
    private val sessionDirectory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val activeMarker = File(sessionDirectory, ACTIVE_MARKER_FILE)
    private val lock = Any()

    fun beginOrResume(metadata: FreeModeSessionMetadata): String = synchronized(lock) {
        sessionDirectory.mkdirs()
        val existingId = readActiveSessionId()
        if (existingId != null) {
            append(
                existingId,
                event("session_resumed")
                    .put("configuration", metadata.toJson())
            )
            return@synchronized existingId
        }

        val sessionId = "${nowMillis()}-${safeId(newId())}"
        val started = event("session_started")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("sessionId", sessionId)
            .put("mode", "Libre")
            .put("audioRecorded", false)
            .put("configuration", metadata.toJson())
        sessionFile(sessionId).writeText(started.toString() + "\n")
        writeActiveSessionId(sessionId)
        sessionId
    }

    fun recordRecognitionReady(localeTag: String, mode: String) {
        appendToActive(
            event("recognition_ready")
                .put("locale", localeTag)
                .put("mode", mode)
        )
    }

    fun recordUserUtterance(
        text: String,
        localeTag: String,
        recognitionMode: String?
    ) {
        appendToActive(
            event("user_utterance")
                .put("text", text)
                .put("locale", localeTag)
                .put("recognitionMode", recognitionMode)
        )
    }

    fun recordAssistantReply(reply: GeneratedReply, ttsVoiceName: String?) {
        appendToActive(
            event("assistant_reply")
                .put("text", reply.text)
                .put("locale", reply.locale.toLanguageTag())
                .put("model", reply.modelName)
                .put("acceleration", reply.acceleration)
                .put("ttsVoice", ttsVoiceName ?: "automatic_offline")
        )
    }

    fun recordToolAction(action: LearningContentAction) {
        val payload = event("tool_action")
        when (action) {
            is LearningContentAction.CreateExercise -> payload
                .put("tool", "create_exercise")
                .put("title", action.title)
                .put("instructions", action.instructions)
                .put("prompt", action.prompt)
                .put("expectedAnswer", action.expectedAnswer)
                .put("exerciseType", action.type.wireValue)
                .put("difficulty", action.difficulty.wireValue)
                .put("topic", action.topic)
                .put("choices", org.json.JSONArray(action.choices))
                .put("languageTag", action.languageTag)

            is LearningContentAction.CreateLesson -> payload
                .put("tool", "create_lesson")
                .put("title", action.title)
                .put("objective", action.objective)
                .put("content", action.content)
                .put("topic", action.topic)
                .put("languageTag", action.languageTag)
        }
        appendToActive(payload)
    }

    fun recordModelReady(modelLabel: String, runtime: String?) {
        appendToActive(
            event("model_ready")
                .put("model", modelLabel)
                .put("runtime", runtime)
        )
    }

    fun recordTtsCompleted(voiceName: String?) {
        appendToActive(
            event("tts_completed")
                .put("voice", voiceName ?: "automatic_offline")
        )
    }

    fun recordPause(reason: String) {
        appendToActive(event("session_paused").put("reason", reason))
    }

    fun recordError(stage: String, error: Throwable) {
        val causeChain = generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .map { cause ->
                JSONObject()
                    .put("class", cause::class.java.name)
                    .put("message", cause.message)
            }
            .toList()
        val stack = error.stackTrace
            .take(MAX_STACK_FRAMES)
            .map(StackTraceElement::toString)
        appendToActive(
            event("error")
                .put("stage", stage)
                .put("class", error::class.java.name)
                .put("message", error.message)
                .put("causes", JSONArray(causeChain))
                .put("stack", JSONArray(stack))
        )
    }

    fun endExplicitly(reason: String = "user_requested") {
        synchronized(lock) {
            val sessionId = readActiveSessionId() ?: return
            append(sessionId, event("session_ended").put("reason", reason))
            activeMarker.delete()
        }
    }

    fun activeSessionId(): String? = synchronized(lock) {
        readActiveSessionId()
    }

    fun sessionFiles(): List<File> = synchronized(lock) {
        sessionDirectory.listFiles()
            ?.filter { it.isFile && it.extension == SESSION_EXTENSION }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
    }

    fun diagnosticDirectory(): File = sessionDirectory

    private fun appendToActive(payload: JSONObject) {
        synchronized(lock) {
            val sessionId = readActiveSessionId() ?: return
            append(sessionId, payload)
        }
    }

    private fun append(sessionId: String, payload: JSONObject) {
        sessionFile(sessionId).appendText(payload.toString() + "\n")
    }

    private fun event(type: String): JSONObject = JSONObject()
        .put("eventId", UUID.randomUUID().toString())
        .put("timestampMillis", nowMillis())
        .put("type", type)

    private fun sessionFile(sessionId: String): File =
        File(sessionDirectory, "$sessionId.$SESSION_EXTENSION")

    private fun readActiveSessionId(): String? {
        val sessionId = activeMarker
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.takeIf { SAFE_SESSION_ID.matches(it) }
            ?: return null
        return sessionId.takeIf { sessionFile(it).isFile }
    }

    private fun writeActiveSessionId(sessionId: String) {
        val temporary = File(sessionDirectory, "$ACTIVE_MARKER_FILE.partial")
        temporary.delete()
        temporary.writeText(sessionId)
        activeMarker.delete()
        check(temporary.renameTo(activeMarker)) {
            "La session Libre active ne peut pas être enregistrée."
        }
    }

    private fun safeId(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_-]+"), "-")
        .trim('-')
        .take(80)
        .ifBlank { UUID.randomUUID().toString() }

    private fun FreeModeSessionMetadata.toJson(): JSONObject = JSONObject()
        .put("nativeLanguageTag", nativeLanguageTag)
        .put("targetLanguageTag", targetLanguageTag)
        .put("promptModelId", promptModelId)
        .put("promptModelLabel", promptModelLabel)
        .put("sttModelId", sttModelId)
        .put("ttsVoiceName", ttsVoiceName)
        .put("appVersion", appVersion)
        .put("device", device)
        .put("androidSdk", androidSdk)

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val ACTIVE_MARKER_FILE = ".active-session"
        private const val SESSION_EXTENSION = "jsonl"
        private const val MAX_CAUSE_DEPTH = 5
        private const val MAX_STACK_FRAMES = 24
        private val SAFE_SESSION_ID = Regex("^[A-Za-z0-9_-]+$")

        @Volatile
        private var instance: FreeModeSessionStore? = null

        fun getInstance(context: Context): FreeModeSessionStore =
            instance ?: synchronized(this) {
                instance ?: FreeModeSessionStore(
                    File(
                        context.applicationContext.filesDir,
                        "diagnostics/free_mode_sessions"
                    )
                ).also { instance = it }
            }
    }
}
