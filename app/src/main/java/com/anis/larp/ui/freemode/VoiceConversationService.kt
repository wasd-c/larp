package com.anis.larp.ui.freemode

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.anis.larp.MainActivity
import com.anis.larp.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VoiceConversationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: VoiceConversationController
    private lateinit var notificationManager: NotificationManager
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        controller = VoiceConversationController.getInstance(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startConversation()
            ACTION_STOP -> stopConversation()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (controller.state.value.conversationActive) {
            controller.suspendFromService()
        }
        notificationJob?.cancel()
        notificationJob = null
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startConversation() {
        try {
            if (!isForeground) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(controller.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                isForeground = true
            }
            acquireWakeLock()
            observeConversationState()
            controller.startFromService()
        } catch (error: Throwable) {
            controller.reportServiceFailure(error)
            releaseWakeLock()
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            stopSelf()
        }
    }

    private fun stopConversation() {
        controller.stopFromService()
        notificationJob?.cancel()
        notificationJob = null
        releaseWakeLock()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun observeConversationState() {
        if (notificationJob?.isActive == true) return
        notificationJob = serviceScope.launch {
            controller.state.collectLatest { state ->
                if (isForeground) {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(state)
                    )
                }
            }
        }
    }

    private fun buildNotification(state: FreeModeUiState): Notification {
        val presentation = voiceNotificationPresentation(state)
        val openAppIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(presentation.title)
            .setContentText(presentation.text)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_notification_stop,
                "Arrêter l’écoute",
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Conversation vocale",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Maintient l’écoute et les réponses de larp actives écran verrouillé"
                setShowBadge(false)
            }
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VoiceConversation"
            )
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        private const val ACTION_START = "com.anis.larp.action.START_VOICE_CONVERSATION"
        private const val ACTION_STOP = "com.anis.larp.action.STOP_VOICE_CONVERSATION"
        private const val CHANNEL_ID = "voice_conversation"
        private const val NOTIFICATION_ID = 5_100
        private const val OPEN_APP_REQUEST_CODE = 5_101
        private const val STOP_REQUEST_CODE = 5_102

        fun startIntent(context: Context): Intent =
            Intent(context, VoiceConversationService::class.java)
                .setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, VoiceConversationService::class.java)
                .setAction(ACTION_STOP)
    }
}

internal data class VoiceNotificationPresentation(
    val title: String,
    val text: String
)

internal fun voiceNotificationPresentation(
    state: FreeModeUiState
): VoiceNotificationPresentation = when (state.phase) {
    SpeechPhase.LISTENING -> VoiceNotificationPresentation(
        title = "larp vous écoute",
        text = "Parlez en ${state.locale.getDisplayLanguage(Locale.FRENCH)}"
    )
    SpeechPhase.THINKING -> VoiceNotificationPresentation(
        title = "larp prépare sa réponse",
        text = "${state.promptModelName ?: "Le modèle"} réfléchit…"
    )
    SpeechPhase.SPEAKING -> VoiceNotificationPresentation(
        title = "larp vous répond",
        text = "Réponse vocale en cours"
    )
    SpeechPhase.ERROR -> VoiceNotificationPresentation(
        title = "Conversation vocale active",
        text = state.statusMessage ?: "Nouvelle tentative d’écoute…"
    )
    SpeechPhase.IDLE,
    SpeechPhase.PREPARING -> VoiceNotificationPresentation(
        title = "Conversation vocale active",
        text = state.statusMessage ?: "Préparation de l’écoute…"
    )
}
