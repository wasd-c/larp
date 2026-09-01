package com.anis.larp.ui.freemode

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.anis.larp.model.QwenAsrModel
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class QwenSpeechRecognizer private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val startupMutex = Mutex()
    @Volatile private var serverProcess: Process? = null
    @Volatile private var serverPort: Int? = null
    @Volatile private var serverReady = false
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var recentServerLog = ""

    suspend fun preload(onStatus: (String) -> Unit = {}) {
        ensureServer(onStatus)
    }

    internal suspend fun recognize(
        locale: Locale?,
        onListening: () -> Unit = {},
        onTranscribing: () -> Unit = {},
        onPartialTranscript: (String) -> Unit = {}
    ): QwenRecognitionResult {
        ensureServer()
        val wav = recordUtterance(onListening)
        return try {
            onTranscribing()
            transcribe(wav, locale, onPartialTranscript)
        } finally {
            wav.delete()
        }
    }

    fun cancelRecognition() {
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
        }
        activeConnection?.disconnect()
    }

    /** Releases the native ASR process when another STT engine is selected. */
    fun release() {
        cancelRecognition()
        synchronized(lock) { stopServerLocked() }
    }

    private suspend fun ensureServer(onStatus: (String) -> Unit = {}) =
        startupMutex.withLock {
            ensureServerLocked(onStatus)
        }

    private suspend fun ensureServerLocked(onStatus: (String) -> Unit) {
        if (!QwenAsrModel.isAvailable(applicationContext)) {
            throw IllegalStateException(
                "Qwen ASR est sélectionné mais son téléchargement n'est pas terminé."
            )
        }
        serverPort?.takeIf { serverProcess?.isAlive == true }?.let { port ->
            if (serverReady || isHealthy(port)) return
            onStatus("Chargement de Qwen ASR en mémoire…")
            awaitServerReady(port, onStatus)
            return
        }
        onStatus("Préparation des fichiers Qwen ASR…")
        val model = QwenAsrModel.materializeForRuntime(
            applicationContext,
            QwenAsrModel.MODEL_FILE
        )
        val projector = QwenAsrModel.materializeForRuntime(
            applicationContext,
            QwenAsrModel.PROJECTOR_FILE
        )
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                serverPort?.takeIf {
                    serverProcess?.isAlive == true && runCatching { isHealthyBlocking(it) }.getOrDefault(false)
                }?.let { return@synchronized }

                onStatus("Chargement de Qwen ASR en mémoire…")
                stopServerLocked()
                require(model.isFile && projector.isFile) {
                    "Les fichiers Qwen ne sont pas accessibles dans Download/Models."
                }
                val nativeDirectory = File(applicationContext.applicationInfo.nativeLibraryDir)
                val executable = File(nativeDirectory, SERVER_LIBRARY_NAME)
                require(executable.isFile) {
                    "Le moteur Android Qwen n'est pas présent dans cette version de l'application."
                }
                val port = ServerSocket(0).use { it.localPort }
                val process = ProcessBuilder(
                    executable.absolutePath,
                    "--model", model.absolutePath,
                    "--mmproj", projector.absolutePath,
                    "--host", LOOPBACK_HOST,
                    "--port", port.toString(),
                    "--ctx-size", "2048",
                    "--threads", Runtime.getRuntime().availableProcessors().coerceIn(2, 8).toString(),
                    "--n-gpu-layers", "0",
                    "--no-webui"
                ).apply {
                    redirectErrorStream(true)
                    environment()["LD_LIBRARY_PATH"] = nativeDirectory.absolutePath
                    environment()["GGML_BACKEND_PATH"] = nativeDirectory.absolutePath
                }.start()
                serverReady = false
                serverProcess = process
                serverPort = port
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                recentServerLog = line.takeLast(500)
                                if (isServerReadyLog(line)) serverReady = true
                                Log.d(TAG, line)
                            }
                        }
                    } catch (error: IOException) {
                        // Closing or replacing the native process interrupts this read.
                        // That is normal lifecycle cleanup and must never crash the app.
                        Log.d(TAG, "Flux de logs Qwen fermé: ${error.message}")
                    }
                }, "qwen-asr-log").apply { isDaemon = true }.start()
            }
        }

        awaitServerReady(checkNotNull(serverPort), onStatus)
    }

    private suspend fun awaitServerReady(port: Int, onStatus: (String) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + SERVER_START_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (serverProcess?.isAlive != true) {
                throw IllegalStateException(
                    "Qwen ASR s'est arrêté pendant son chargement. $recentServerLog"
                )
            }
            if (serverReady || isHealthy(port)) {
                onStatus("Qwen ASR est prêt et reste chargé en mémoire.")
                return
            }
            delay(SERVER_START_POLL_MILLIS)
        }
        synchronized(lock) { stopServerLocked() }
        throw IllegalStateException(
            "Qwen ASR n'a pas fini de se charger. $recentServerLog"
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordUtterance(onListening: () -> Unit): File =
        withContext(Dispatchers.IO) {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBuffer > 0) { "Le microphone ne fournit aucun format PCM compatible." }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, SAMPLE_RATE)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                "Le microphone n'a pas pu être initialisé pour Qwen."
            }
            activeRecorder = recorder
            val pcm = ByteArrayOutputStream()
            val buffer = ShortArray(FRAME_SAMPLES)
            val pcmFrame = ByteArray(FRAME_SAMPLES * 2)
            var speechStarted = false
            var silenceStartedAt = 0L
            val startedAt = SystemClock.elapsedRealtime()
            try {
                recorder.startRecording()
                onListening()
                while (SystemClock.elapsedRealtime() - startedAt < MAX_RECORDING_MILLIS) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    val peak = (0 until count).maxOf { kotlin.math.abs(buffer[it].toInt()) }
                    val now = SystemClock.elapsedRealtime()
                    if (peak >= SPEECH_PEAK_THRESHOLD) {
                        speechStarted = true
                        silenceStartedAt = 0L
                    } else if (speechStarted && silenceStartedAt == 0L) {
                        silenceStartedAt = now
                    }
                    repeat(count) { index ->
                        val sample = buffer[index].toInt()
                        pcmFrame[index * 2] = (sample and 0xFF).toByte()
                        pcmFrame[index * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
                    }
                    pcm.write(pcmFrame, 0, count * 2)
                    if (
                        speechStarted && silenceStartedAt > 0L &&
                        now - silenceStartedAt >= END_OF_SPEECH_MILLIS
                    ) break
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                if (activeRecorder === recorder) activeRecorder = null
            }
            check(speechStarted) { "Aucune parole n'a été détectée." }
            writeWav(pcm.toByteArray())
        }

    private fun writeWav(pcm: ByteArray): File {
        val file = File.createTempFile("qwen-utterance-", ".wav", applicationContext.cacheDir)
        file.outputStream().buffered().use { output ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + pcm.size)
            header.put("WAVEfmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(pcm.size)
            output.write(header.array())
            output.write(pcm)
        }
        return file
    }

    private suspend fun transcribe(
        wav: File,
        locale: Locale?,
        onPartialTranscript: (String) -> Unit
    ): QwenRecognitionResult =
        runInterruptible(Dispatchers.IO) {
            val port = checkNotNull(serverPort)
            val boundary = "larp-${System.nanoTime()}"
            val connection = URI(
                "http://$LOOPBACK_HOST:$port/v1/audio/transcriptions"
            ).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 5_000
                connection.readTimeout = 120_000
                connection.setRequestProperty("Accept", "text/event-stream")
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                activeConnection = connection
                connection.outputStream.buffered().use { output ->
                    fun field(name: String, value: String) {
                        output.write("--$boundary\r\n".toByteArray())
                        output.write(
                            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray()
                        )
                        output.write(value.toByteArray())
                        output.write("\r\n".toByteArray())
                    }
                    qwenTranscriptionFields(locale).forEach { (name, value) ->
                        field(name, value)
                    }
                    output.write("--$boundary\r\n".toByteArray())
                    output.write(
                        ("Content-Disposition: form-data; name=\"file\"; " +
                            "filename=\"utterance.wav\"\r\n" +
                            "Content-Type: audio/wav\r\n\r\n").toByteArray()
                    )
                    wav.inputStream().buffered().use { it.copyTo(output) }
                    output.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val body = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                    throw IOException("Qwen ASR a répondu $responseCode : ${body.take(300)}")
                }
                val result = if (
                    connection.contentType.orEmpty().contains(
                        "text/event-stream",
                        ignoreCase = true
                    )
                ) {
                    readStreamingTranscription(
                        reader = connection.inputStream.bufferedReader(),
                        forcedLocale = locale,
                        onPartialTranscript = onPartialTranscript
                    )
                } else {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parseQwenTranscript(
                        rawText = JSONObject(body).optString("text"),
                        forcedLocale = locale
                    )
                }
                result.takeIf { it.text.isNotBlank() } ?: run {
                    throw IOException("Qwen ASR n'a renvoyé aucune transcription.")
                }
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }

    private fun readStreamingTranscription(
        reader: BufferedReader,
        forcedLocale: Locale?,
        onPartialTranscript: (String) -> Unit
    ): QwenRecognitionResult = reader.use {
        val rawTranscript = StringBuilder()
        var finalRawTranscript = ""
        var lastVisibleTranscript = ""
        while (true) {
            val line = it.readLine() ?: break
            if (!line.startsWith(SSE_DATA_PREFIX)) continue
            val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
            if (payload.isBlank() || payload == SSE_DONE) continue

            val event = JSONObject(payload)
            event.optJSONObject("error")?.let { error ->
                throw IOException(
                    error.optString("message").ifBlank { "Qwen ASR a interrompu la transcription." }
                )
            }
            when (event.optString("type")) {
                "transcript.text.delta" -> rawTranscript.append(event.optString("delta"))
                "transcript.text.done" -> {
                    finalRawTranscript = event.optString("text")
                }
            }

            val visibleTranscript = qwenVisiblePartialTranscript(rawTranscript.toString())
            if (
                visibleTranscript.isNotBlank() &&
                visibleTranscript != lastVisibleTranscript
            ) {
                lastVisibleTranscript = visibleTranscript
                onPartialTranscript(visibleTranscript)
            }
        }

        parseQwenTranscript(
            rawText = finalRawTranscript.ifBlank { rawTranscript.toString() },
            forcedLocale = forcedLocale
        )
    }

    private suspend fun isHealthy(port: Int): Boolean = withContext(Dispatchers.IO) {
        isHealthyBlocking(port)
    }

    private fun isHealthyBlocking(port: Int): Boolean {
        val connection = URI("http://$LOOPBACK_HOST:$port/health")
            .toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun stopServerLocked() {
        serverProcess?.destroy()
        serverReady = false
        serverProcess = null
        serverPort = null
    }

    companion object {
        private const val TAG = "QwenSpeechRecognizer"
        private const val SERVER_LIBRARY_NAME = "libllama-qwen-server.so"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val SPEECH_PEAK_THRESHOLD = 700
        private const val END_OF_SPEECH_MILLIS = 900L
        private const val MAX_RECORDING_MILLIS = 30_000L
        private const val SERVER_START_TIMEOUT_MILLIS = 120_000L
        private const val SERVER_START_POLL_MILLIS = 250L

        @Volatile private var instance: QwenSpeechRecognizer? = null

        internal fun isServerReadyLog(line: String): Boolean =
            line.contains("server is listening", ignoreCase = true) ||
                line.contains("all slots are idle", ignoreCase = true)

        fun getInstance(context: Context): QwenSpeechRecognizer =
            instance ?: synchronized(this) {
                instance ?: QwenSpeechRecognizer(context).also { instance = it }
            }
    }
}

internal data class QwenRecognitionResult(
    val text: String,
    val detectedLocale: Locale?
)

internal fun parseQwenTranscript(
    rawText: String,
    forcedLocale: Locale? = null
): QwenRecognitionResult {
    val marker = "<asr_text>"
    val markerIndex = rawText.indexOf(marker, ignoreCase = true)
    val detectedLanguage = if (markerIndex >= 0) {
        QWEN_LANGUAGE_METADATA.find(rawText.substring(0, markerIndex))
            ?.groupValues
            ?.getOrNull(1)
    } else {
        null
    }
    val transcription = if (markerIndex >= 0) {
        rawText.substring(markerIndex + marker.length)
    } else {
        rawText
    }
    return QwenRecognitionResult(
        text = sanitizeRecognizedSpeech(
            transcription
                .replace(Regex("</?asr_text>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<\\|[^>]+\\|>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        ),
        detectedLocale = forcedLocale ?: detectedLanguage?.let(::qwenLocaleForLanguage)
    )
}

internal fun sanitizeQwenTranscript(rawText: String): String =
    parseQwenTranscript(rawText).text

internal fun qwenVisiblePartialTranscript(rawText: String): String {
    val normalized = rawText.trim().lowercase(Locale.ROOT)
    if (
        !rawText.contains("<asr_text>", ignoreCase = true) &&
        isQwenLanguageMetadataPrefix(normalized)
    ) return ""
    return sanitizeQwenTranscript(rawText)
}

internal fun qwenTranscriptionFields(locale: Locale?): Map<String, String> = buildMap {
    locale?.let { put("language", qwenLanguageHint(it)) }
    put("response_format", "json")
    put("stream", "true")
    put("temperature", "0")
    put("max_tokens", "256")
}

internal fun qwenLanguageHint(locale: Locale): String = when (
    locale.language.lowercase(Locale.ROOT)
) {
    "zh", "cmn" -> "Chinese"
    "en" -> "English"
    "fr" -> "French"
    "es" -> "Spanish"
    "de" -> "German"
    "it" -> "Italian"
    "pt" -> "Portuguese"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "ar" -> "Arabic"
    "nl" -> "Dutch"
    "ru" -> "Russian"
    else -> locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
}

private fun qwenLocaleForLanguage(language: String): Locale? = when (
    language.trim().substringBefore(',').lowercase(Locale.ROOT)
) {
    "chinese" -> Locale.SIMPLIFIED_CHINESE
    "english" -> Locale.US
    "french" -> Locale.FRANCE
    "spanish" -> Locale.forLanguageTag("es-ES")
    "german" -> Locale.GERMANY
    "italian" -> Locale.ITALY
    "portuguese" -> Locale.forLanguageTag("pt-PT")
    "japanese" -> Locale.JAPAN
    "korean" -> Locale.KOREA
    "arabic" -> Locale.forLanguageTag("ar-SA")
    "dutch" -> Locale.forLanguageTag("nl-NL")
    "russian" -> Locale.forLanguageTag("ru-RU")
    "cantonese" -> Locale.forLanguageTag("yue-HK")
    else -> null
}

private val QWEN_LANGUAGE_METADATA = Regex(
    pattern = "(?:^|\\R)\\s*language\\s+([^<\\r\\n]+)",
    option = RegexOption.IGNORE_CASE
)
private const val QWEN_PROTOCOL_PREFIX = "language "
private val QWEN_LANGUAGE_NAMES = setOf(
    "chinese",
    "english",
    "french",
    "spanish",
    "german",
    "italian",
    "portuguese",
    "japanese",
    "korean",
    "arabic",
    "dutch",
    "russian",
    "cantonese"
)

private fun isQwenLanguageMetadataPrefix(text: String): Boolean {
    if (text.isEmpty()) return false
    if (QWEN_PROTOCOL_PREFIX.startsWith(text)) return true
    if (!text.startsWith(QWEN_PROTOCOL_PREFIX)) return false
    val languagePrefix = text.removePrefix(QWEN_PROTOCOL_PREFIX).trim()
    return QWEN_LANGUAGE_NAMES.any { language ->
        language.startsWith(languagePrefix) ||
            languagePrefix == language ||
            languagePrefix.removePrefix(language).trimStart().startsWith("<")
    }
}
