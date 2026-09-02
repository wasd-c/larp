package com.anis.larp.telemetry

internal object TelemetryPrivacy {
    private val allowedAttributeKeys = setOf(
        "acceleration",
        "app_version",
        "attempt",
        "available",
        "build_type",
        "cached",
        "category",
        "configured",
        "consent_source",
        "content_kind",
        "count",
        "delivery",
        "destination",
        "duration_ms",
        "elapsed_ms",
        "error_type",
        "from",
        "hints_used",
        "language",
        "level",
        "mistakes",
        "model_id",
        "model_provider",
        "operation",
        "permission",
        "phase",
        "progress_percent",
        "rating",
        "result",
        "schema_version",
        "screen",
        "size_bytes",
        "source",
        "status",
        "stt_engine",
        "to",
        "tts_engine"
    )

    fun eventName(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_')
        .take(MAX_EVENT_NAME_LENGTH)
        .ifBlank { "unknown_event" }

    fun attributes(values: Map<String, Any?>): Map<String, Any> = buildMap {
        values.forEach { (key, rawValue) ->
            if (key !in allowedAttributeKeys || rawValue == null) return@forEach
            val value = when (rawValue) {
                is Boolean -> rawValue
                is Byte, is Short, is Int, is Long, is Float, is Double -> rawValue
                is Enum<*> -> rawValue.name.lowercase().take(MAX_STRING_VALUE_LENGTH)
                else -> rawValue.toString().take(MAX_STRING_VALUE_LENGTH)
            }
            put(key, value)
        }
    }

    fun throwable(value: Throwable): Throwable = SanitizedTelemetryException(
        originalType = value.javaClass.name.take(MAX_STRING_VALUE_LENGTH)
    ).also { sanitized ->
        sanitized.stackTrace = value.stackTrace
    }

    private class SanitizedTelemetryException(originalType: String) :
        RuntimeException("Reported exception type: $originalType")

    private const val MAX_EVENT_NAME_LENGTH = 80
    private const val MAX_STRING_VALUE_LENGTH = 96
}
