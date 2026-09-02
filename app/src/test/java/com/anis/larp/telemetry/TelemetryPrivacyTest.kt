package com.anis.larp.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPrivacyTest {
    @Test
    fun attributesRejectLearningContentAndUnknownFields() {
        val sanitized = TelemetryPrivacy.attributes(
            mapOf(
                "model_id" to "prompt:gemma",
                "duration_ms" to 42L,
                "text" to "private learner message",
                "transcript" to "private voice transcript",
                "question" to "private lesson question",
                "url" to "https://example.test/private",
                "unexpected" to "private"
            )
        )

        assertEquals("prompt:gemma", sanitized["model_id"])
        assertEquals(42L, sanitized["duration_ms"])
        assertFalse(sanitized.containsKey("text"))
        assertFalse(sanitized.containsKey("transcript"))
        assertFalse(sanitized.containsKey("question"))
        assertFalse(sanitized.containsKey("url"))
        assertFalse(sanitized.containsKey("unexpected"))
    }

    @Test
    fun eventNamesAreBoundedAndMachineReadable() {
        val sanitized = TelemetryPrivacy.eventName(
            " Réponse vocale reçue / Voice reply received "
        )

        assertEquals("r_ponse_vocale_re_ue_voice_reply_received", sanitized)
        assertTrue(sanitized.length <= 80)
    }

    @Test
    fun stringAttributesAreBounded() {
        val sanitized = TelemetryPrivacy.attributes(
            mapOf("model_provider" to "x".repeat(200))
        )

        assertEquals(96, (sanitized["model_provider"] as String).length)
    }

    @Test
    fun exceptionMessagesAreRemovedButTechnicalStackIsRetained() {
        val original = IllegalStateException("private learner message")
        original.stackTrace = arrayOf(
            StackTraceElement("com.anis.larp.Test", "run", "Test.kt", 42)
        )

        val sanitized = TelemetryPrivacy.throwable(original)

        assertFalse(sanitized.message.orEmpty().contains("private learner message"))
        assertTrue(sanitized.message.orEmpty().contains("IllegalStateException"))
        assertArrayEquals(original.stackTrace, sanitized.stackTrace)
    }

    @Test
    fun telemetryInitializationRequiresConfigurationAndExplicitGrant() {
        TelemetryConsent.entries.forEach { consent ->
            assertEquals(
                consent == TelemetryConsent.GRANTED,
                TelemetryGate.shouldInitialize(consent, configured = true)
            )
            assertFalse(TelemetryGate.shouldInitialize(consent, configured = false))
        }
    }

    @Test
    fun firstLaunchPromptOnlyAppearsForConfiguredUndecidedBuilds() {
        assertTrue(
            TelemetryGate.shouldShowConsent(
                consent = TelemetryConsent.UNKNOWN,
                configured = true,
                skipped = false
            )
        )
        assertFalse(
            TelemetryGate.shouldShowConsent(
                consent = TelemetryConsent.GRANTED,
                configured = true,
                skipped = false
            )
        )
        assertFalse(
            TelemetryGate.shouldShowConsent(
                consent = TelemetryConsent.UNKNOWN,
                configured = false,
                skipped = false
            )
        )
        assertFalse(
            TelemetryGate.shouldShowConsent(
                consent = TelemetryConsent.UNKNOWN,
                configured = true,
                skipped = true
            )
        )
    }
}
