package com.anis.larp.telemetry

import android.content.Context

enum class TelemetryConsent {
    UNKNOWN,
    GRANTED,
    DENIED
}

internal object TelemetryGate {
    fun shouldInitialize(
        consent: TelemetryConsent,
        configured: Boolean
    ): Boolean = configured && consent == TelemetryConsent.GRANTED

    fun shouldShowConsent(
        consent: TelemetryConsent,
        configured: Boolean,
        skipped: Boolean
    ): Boolean = configured && !skipped && consent == TelemetryConsent.UNKNOWN
}

class TelemetryPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var consent: TelemetryConsent
        get() = preferences.getString(KEY_CONSENT, null)
            ?.let { stored ->
                TelemetryConsent.entries.firstOrNull { it.name == stored }
            }
            ?: TelemetryConsent.UNKNOWN
        set(value) {
            preferences.edit().putString(KEY_CONSENT, value.name).apply()
        }

    companion object {
        private const val PREFERENCES_NAME = "larp_telemetry"
        private const val KEY_CONSENT = "tracking_consent"
    }
}
