package com.anis.larp.ui.freemode

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.anis.larp.model.QwenAsrModel
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
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var recentServerLog = ""

    suspend fun preload(onStatus: (String) -> Unit = {}) {
        ensureServer(onStatus)
    }

    suspend fun recognize(
        locale: Locale,
        onListening: () -> Unit = {}
    ): String {
        ensureServer()
        val wav = recordUtterance(onListening)
        return try {
            transcribe(wav, locale)
        } finally {
            wav.delete()
        }
    }

    fun cancelRecognition() {
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
        }
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
            if (isHealthy(port)) return
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
                serverProcess = process
                serverPort = port
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                recentServerLog = line.takeLast(500)
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
        repeat(SERVER_START_ATTEMPTS) {
            if (serverProcess?.isAlive != true) {
                throw IllegalStateException(
                    "Qwen ASR s'est arrêté pendant son chargement. $recentServerLog"
                )
            }
            if (isHealthy(port)) {
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
                    val bytes = ByteBuffer.allocate(count * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    repeat(count) { bytes.putShort(buffer[it]) }
                    pcm.write(bytes.array())
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

    private suspend fun transcribe(wav: File, locale: Locale): String =
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
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=$boundary"
                )
                connection.outputStream.buffered().use { output ->
                    fun field(name: String, value: String) {
                        output.write("--$boundary\r\n".toByteArray())
                        output.write(
                            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray()
                        )
                        output.write(value.toByteArray())
                        output.write("\r\n".toByteArray())
                    }
                    field("language", locale.toLanguageTag())
                    field("response_format", "json")
                    field("temperature", "0")
                    field("max_tokens", "256")
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
                val body = (if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }).bufferedReader().use { it.readText() }
                if (responseCode !in 200..299) {
                    throw IOException("Qwen ASR a répondu $responseCode : ${body.take(300)}")
                }
                JSONObject(body).optString("text").trim().ifBlank {
                    throw IOException("Qwen ASR n'a renvoyé aucune transcription.")
                }
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun isHealthy(port: Int): Boolean = withContext(Dispatchers.IO) {
        isHealthyBlocking(port)
    }

    private fun isHealthyBlocking(port: Int): Boolean {
        val connection = URI("http://$LOOPBACK_HOST:$port/health")
            .toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 250
            connection.readTimeout = 250
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun stopServerLocked() {
        serverProcess?.destroy()
        serverProcess = null
        serverPort = null
    }

    companion object {
        private const val TAG = "QwenSpeechRecognizer"
        private const val SERVER_LIBRARY_NAME = "libllama-qwen-server.so"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320
        private const val SPEECH_PEAK_THRESHOLD = 700
        private const val END_OF_SPEECH_MILLIS = 900L
        private const val MAX_RECORDING_MILLIS = 30_000L
        private const val SERVER_START_ATTEMPTS = 240
        private const val SERVER_START_POLL_MILLIS = 250L

        @Volatile private var instance: QwenSpeechRecognizer? = null

        fun getInstance(context: Context): QwenSpeechRecognizer =
            instance ?: synchronized(this) {
                instance ?: QwenSpeechRecognizer(context).also { instance = it }
            }
    }
}
