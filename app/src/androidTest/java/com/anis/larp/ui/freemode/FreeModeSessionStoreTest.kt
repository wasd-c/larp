package com.anis.larp.ui.freemode

import androidx.test.platform.app.InstrumentationRegistry
import com.anis.larp.learning.LearningContentAction
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModeSessionStoreTest {
    @Test
    fun sessionSurvivesStoreRecreationAndEndsOnlyExplicitly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "session-store-test").apply {
            deleteRecursively()
            mkdirs()
        }
        var timestamp = 1_000L
        val metadata = FreeModeSessionMetadata(
            nativeLanguageTag = "fr-FR",
            targetLanguageTag = "en-US",
            promptModelId = "prompt:test",
            promptModelLabel = "Gemma",
            sttModelId = "stt:test",
            ttsVoiceName = "voice:test",
            appVersion = "test",
            device = "test device",
            androidSdk = 36
        )

        try {
            val firstStore = FreeModeSessionStore(
                sessionDirectory = directory,
                nowMillis = { timestamp++ },
                newId = { "session-test" }
            )
            val sessionId = firstStore.beginOrResume(metadata)
            firstStore.recordRecognitionReady("fr-FR", "Avancé")
            firstStore.recordUserUtterance("Bonjour", "fr-FR", "Avancé")
            firstStore.recordToolAction(
                LearningContentAction.CreateExercise(
                    title = "Salutations",
                    instructions = "Répondez.",
                    prompt = "Say hello",
                    expectedAnswer = "Hello",
                    languageTag = "en-US"
                )
            )
            firstStore.recordAssistantReply(
                reply = GeneratedReply(
                    text = "Hello!",
                    locale = Locale.US,
                    modelName = "Gemma",
                    acceleration = "GPU"
                ),
                ttsVoiceName = "voice:test"
            )
            firstStore.recordError(
                stage = "test_stage",
                error = IllegalStateException("diagnostic probe")
            )

            val restoredStore = FreeModeSessionStore(
                sessionDirectory = directory,
                nowMillis = { timestamp++ },
                newId = { "must-not-be-used" }
            )
            assertEquals(sessionId, restoredStore.beginOrResume(metadata))
            assertEquals(sessionId, restoredStore.activeSessionId())
            restoredStore.recordPause("test_pause")

            val eventsBeforeEnd = readEvents(restoredStore.sessionFiles().single())
            assertFalse(eventsBeforeEnd.any { it.getString("type") == "session_ended" })
            restoredStore.endExplicitly()
            assertNull(restoredStore.activeSessionId())

            val events = readEvents(restoredStore.sessionFiles().single())
            assertEquals("session_started", events.first().getString("type"))
            assertFalse(events.first().getBoolean("audioRecorded"))
            assertTrue(events.any { it.getString("type") == "session_resumed" })
            assertTrue(events.any { it.getString("type") == "user_utterance" })
            assertTrue(events.any { it.optString("tool") == "create_exercise" })
            assertTrue(events.any { it.optString("acceleration") == "GPU" })
            assertTrue(events.any { it.optString("stage") == "test_stage" })
            assertEquals("session_ended", events.last().getString("type"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readEvents(file: File): List<JSONObject> =
        file.readLines()
            .filter(String::isNotBlank)
            .map(::JSONObject)
}
