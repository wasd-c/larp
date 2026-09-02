package com.anis.larp.telemetry

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.anis.larp.BuildConfig
import com.openobserve.android.OpenObserve
import com.openobserve.android.core.configuration.Configuration
import com.openobserve.android.log.Logger
import com.openobserve.android.log.Logs
import com.openobserve.android.log.LogsConfiguration
import com.openobserve.android.ndk.NdkCrashReports
import com.openobserve.android.privacy.TrackingConsent
import com.openobserve.android.rum.GlobalRumMonitor
import com.openobserve.android.rum.Rum
import com.openobserve.android.rum.RumActionType
import com.openobserve.android.rum.RumConfiguration
import com.openobserve.android.rum.RumErrorSource
import com.openobserve.android.rum.tracking.ActivityViewTrackingStrategy
import java.net.URI
import kotlinx.coroutines.CancellationException

object Telemetry {
    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false

    @Volatile
    private var logger: Logger? = null

    val isConfigured: Boolean
        get() = configuration().isValid

    fun initializeIfConsented(context: Context): Boolean {
        if (
            !TelemetryGate.shouldInitialize(
                consent = TelemetryPreferences(context).consent,
                configured = isConfigured
            )
        ) return false
        return initialize(context.applicationContext)
    }

    fun setConsent(
        context: Context,
        consent: TelemetryConsent,
        source: String
    ) {
        require(consent != TelemetryConsent.UNKNOWN)
        val applicationContext = context.applicationContext
        TelemetryPreferences(applicationContext).consent = consent
        when (consent) {
            TelemetryConsent.GRANTED -> {
                if (initialized) {
                    OpenObserve.setTrackingConsent(TrackingConsent.GRANTED)
                    enabled = true
                } else {
                    initialize(applicationContext)
                }
                event(
                    name = "telemetry_consent_granted",
                    attributes = mapOf("consent_source" to source)
                )
            }

            TelemetryConsent.DENIED -> {
                enabled = false
                if (initialized) {
                    OpenObserve.setTrackingConsent(TrackingConsent.NOT_GRANTED)
                }
            }

            TelemetryConsent.UNKNOWN -> Unit
        }
    }

    fun event(name: String, attributes: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val safeName = TelemetryPrivacy.eventName(name)
        val safeAttributes = TelemetryPrivacy.attributes(attributes) +
            ("schema_version" to SCHEMA_VERSION)
        runCatching {
            GlobalRumMonitor.get().addAction(
                RumActionType.CUSTOM,
                safeName,
                safeAttributes
            )
            logger?.i(safeName, attributes = safeAttributes)
        }.onFailure { error ->
            Log.w(TAG, "Unable to record telemetry event $safeName", error)
        }
    }

    fun error(
        operation: String,
        throwable: Throwable,
        attributes: Map<String, Any?> = emptyMap()
    ) {
        if (!enabled) return
        val safeOperation = TelemetryPrivacy.eventName(operation)
        val safeThrowable = TelemetryPrivacy.throwable(throwable)
        val safeAttributes = TelemetryPrivacy.attributes(
            attributes + mapOf(
                "operation" to safeOperation,
                "error_type" to throwable.javaClass.simpleName
            )
        ) + ("schema_version" to SCHEMA_VERSION)
        runCatching {
            GlobalRumMonitor.get().addError(
                safeOperation,
                RumErrorSource.SOURCE,
                safeThrowable,
                safeAttributes
            )
            logger?.e(safeOperation, safeThrowable, safeAttributes)
        }.onFailure { telemetryError ->
            Log.w(TAG, "Unable to record telemetry error $safeOperation", telemetryError)
        }
    }

    suspend fun <T> operation(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        block: suspend () -> T
    ): T {
        if (!enabled) return block()
        val safeName = TelemetryPrivacy.eventName(name)
        val startedAt = SystemClock.elapsedRealtime()
        event("${safeName}_started", attributes)
        return try {
            block().also {
                event(
                    name = "${safeName}_completed",
                    attributes = attributes + mapOf(
                        "duration_ms" to (SystemClock.elapsedRealtime() - startedAt),
                        "result" to "success"
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            event(
                name = "${safeName}_cancelled",
                attributes = attributes + mapOf(
                    "duration_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "result" to "cancelled"
                )
            )
            throw cancellation
        } catch (throwable: Throwable) {
            error(
                operation = safeName,
                throwable = throwable,
                attributes = attributes + mapOf(
                    "duration_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "result" to "failure"
                )
            )
            throw throwable
        }
    }

    private fun initialize(context: Context): Boolean = synchronized(lock) {
        if (initialized) {
            enabled = true
            return@synchronized true
        }
        val config = configuration()
        if (!config.isValid) {
            Log.i(TAG, "Telemetry is disabled because build configuration is incomplete.")
            return@synchronized false
        }

        runCatching {
            val coreConfiguration = Configuration.Builder(
                clientToken = config.clientToken,
                env = BuildConfig.BUILD_TYPE,
                service = SERVICE_NAME
            ).build()
            OpenObserve.initialize(
                context.applicationContext,
                coreConfiguration,
                TrackingConsent.GRANTED
            )
            Rum.enable(
                RumConfiguration.Builder(config.applicationId)
                    .useCustomEndpoint(config.rumEndpoint)
                    .setSessionSampleRate(100f)
                    .setTelemetrySampleRate(0f)
                    .trackUserInteractions()
                    .trackLongTasks(LONG_TASK_THRESHOLD_MILLIS)
                    .trackNonFatalAnrs(true)
                    .trackBackgroundEvents(true)
                    .trackAnonymousUser(false)
                    .collectAccessibility(false)
                    .useViewTrackingStrategy(
                        ActivityViewTrackingStrategy(trackExtras = false)
                    )
                    .build()
            )
            Logs.enable(
                LogsConfiguration.Builder()
                    .useCustomEndpoint(config.logsEndpoint)
                    .build()
            )
            NdkCrashReports.enable()
            logger = Logger.Builder()
                .setName(SERVICE_NAME)
                .setService(SERVICE_NAME)
                .setNetworkInfoEnabled(false)
                .setRemoteLogThreshold(Log.INFO)
                .build()
            PrivacyPreservingCrashHandler.install()
            initialized = true
            enabled = true
            event(
                name = "telemetry_initialized",
                attributes = mapOf(
                    "build_type" to BuildConfig.BUILD_TYPE,
                    "configured" to true
                )
            )
            true
        }.getOrElse { error ->
            enabled = false
            Log.e(TAG, "OpenObserve telemetry initialization failed.", error)
            false
        }
    }

    private fun configuration() = TelemetryConfiguration(
        clientToken = BuildConfig.TELEMETRY_CLIENT_TOKEN.trim(),
        rumEndpoint = BuildConfig.TELEMETRY_RUM_ENDPOINT.trim(),
        logsEndpoint = BuildConfig.TELEMETRY_LOGS_ENDPOINT.trim(),
        applicationId = BuildConfig.TELEMETRY_APPLICATION_ID.trim()
    )

    private data class TelemetryConfiguration(
        val clientToken: String,
        val rumEndpoint: String,
        val logsEndpoint: String,
        val applicationId: String
    ) {
        val isValid: Boolean
            get() = clientToken.isNotBlank() &&
                applicationId.isNotBlank() &&
                rumEndpoint.isSecureEndpoint() &&
                logsEndpoint.isSecureEndpoint()

        private fun String.isSecureEndpoint(): Boolean = runCatching {
            val parsed = URI(this)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private const val TAG = "LarpTelemetry"
    private const val SERVICE_NAME = "larp-android"
    private const val SCHEMA_VERSION = 1
    private const val LONG_TASK_THRESHOLD_MILLIS = 250L
}

private object PrivacyPreservingCrashHandler {
    private var installed = false

    fun install() {
        if (installed) return
        val openObserveHandler = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            openObserveHandler.uncaughtException(
                thread,
                TelemetryPrivacy.throwable(throwable)
            )
        }
        installed = true
    }
}
