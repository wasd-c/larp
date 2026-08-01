package com.anis.larp.learning

import android.text.Html
import android.util.Xml
import io.github.thoroldvix.api.Transcript
import io.github.thoroldvix.api.TranscriptApiFactory
import io.github.thoroldvix.api.TranscriptRetrievalException
import io.github.thoroldvix.api.YoutubeClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

data class YoutubeTranscriptSource(
    val videoId: String,
    val languageCode: String,
    val text: String
)

/**
 * Android adapter for trldvix/youtube-transcript-api.
 *
 * The library's default client uses Java's desktop HttpClient, so Android must
 * inject its own YoutubeClient implementation through TranscriptApiFactory.
 */
class YoutubeTranscriptProvider(
    private val client: YoutubeClient = AndroidYoutubeClient()
) {
    suspend fun fetch(
        videoUrlOrId: String,
        preferredLanguages: List<String>
    ): YoutubeTranscriptSource = withContext(Dispatchers.IO) {
        val videoId = parseYoutubeVideoId(videoUrlOrId)
        try {
            val transcripts = TranscriptApiFactory
                .createWithClient(client)
                .listTranscripts(videoId)
            val available = transcripts.toList()
            if (available.isEmpty()) {
                throw IllegalArgumentException(
                    "Cette vidéo ne propose aucune transcription utilisable."
                )
            }
            val transcript = selectTranscript(available, preferredLanguages)
            val completeText = parseTranscriptXml(
                client.get(
                    transcript.apiUrl,
                    mapOf("Accept-Language" to "en-US")
                )
            )
                .asSequence()
                .filter(String::isNotBlank)
                .joinToString(" ")
                .normalizeTranscriptWhitespace()
            if (completeText.isBlank()) {
                throw IllegalArgumentException(
                    "La transcription de cette vidéo est vide."
                )
            }
            YoutubeTranscriptSource(
                videoId = videoId,
                languageCode = transcript.languageCode,
                text = completeText
            )
        } catch (error: TranscriptRetrievalException) {
            throw IllegalArgumentException(error.toFrenchMessage(), error)
        }
    }
}

private fun parseTranscriptXml(xml: String): List<String> {
    if (xml.isBlank()) {
        throw IllegalArgumentException("YouTube a renvoyé une transcription vide.")
    }
    return try {
        val fragments = mutableListOf<String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "text") {
                val text = Html
                    .fromHtml(parser.nextText(), Html.FROM_HTML_MODE_LEGACY)
                    .toString()
                    .normalizeTranscriptWhitespace()
                if (text.isNotBlank()) fragments += text
            }
            event = parser.next()
        }
        fragments
    } catch (error: Exception) {
        throw IllegalArgumentException(
            "La transcription YouTube reçue est illisible.",
            error
        )
    }
}

internal fun parseYoutubeVideoId(value: String): String {
    val candidate = value.trim()
    if (YOUTUBE_VIDEO_ID.matches(candidate)) return candidate

    val uri = runCatching {
        URI(if (candidate.contains("://")) candidate else "https://$candidate")
    }.getOrNull() ?: throw invalidYoutubeUrl()
    if (!uri.scheme.equals("https", ignoreCase = true)) throw invalidYoutubeUrl()
    val host = uri.host?.lowercase(Locale.ROOT) ?: throw invalidYoutubeUrl()
    val videoId = when {
        host == "youtu.be" || host.endsWith(".youtu.be") ->
            uri.path.trim('/').substringBefore('/')

        host == "youtube.com" || host.endsWith(".youtube.com") -> when {
            uri.path == "/watch" -> uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val pieces = part.split('=', limit = 2)
                    pieces.takeIf { it.size == 2 && it[0] == "v" }?.get(1)
                }
                ?.firstOrNull()

            uri.path.startsWith("/shorts/") ->
                uri.path.removePrefix("/shorts/").substringBefore('/')

            uri.path.startsWith("/embed/") ->
                uri.path.removePrefix("/embed/").substringBefore('/')

            uri.path.startsWith("/live/") ->
                uri.path.removePrefix("/live/").substringBefore('/')

            else -> null
        }

        else -> null
    }
    return videoId
        ?.substringBefore('?')
        ?.substringBefore('&')
        ?.takeIf(YOUTUBE_VIDEO_ID::matches)
        ?: throw invalidYoutubeUrl()
}

internal fun compactTranscript(
    transcript: String,
    maxCharacters: Int = MAX_TRANSCRIPT_CHARACTERS
): String {
    require(maxCharacters >= 300) { "La taille maximale est trop petite." }
    val normalized = transcript.normalizeTranscriptWhitespace()
    if (normalized.length <= maxCharacters) return normalized

    val separator = " […] "
    val usable = maxCharacters - separator.length * 2
    val firstLength = usable * 2 / 5
    val middleLength = usable / 5
    val lastLength = usable - firstLength - middleLength
    val middleStart = (normalized.length / 2 - middleLength / 2)
        .coerceIn(firstLength, normalized.length - lastLength - middleLength)
    return buildString(maxCharacters) {
        append(normalized.take(firstLength).trimToWordEnd())
        append(separator)
        append(
            normalized.substring(middleStart, middleStart + middleLength)
                .trimToWordBoundaries()
        )
        append(separator)
        append(normalized.takeLast(lastLength).trimToWordStart())
    }.take(maxCharacters)
}

private fun selectTranscript(
    transcripts: List<Transcript>,
    preferredLanguages: List<String>
): Transcript {
    val preferred = preferredLanguages
        .flatMap { languageTag ->
            val locale = Locale.forLanguageTag(languageTag)
            listOf(languageTag, locale.language)
        }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
    return preferred.firstNotNullOfOrNull { language ->
        transcripts.firstOrNull {
            it.languageCode.equals(language, ignoreCase = true)
        }
    } ?: transcripts.firstOrNull { !it.isGenerated }
        ?: transcripts.first()
}

private class AndroidYoutubeClient : YoutubeClient {
    override fun get(url: String, headers: Map<String, String>): String =
        request(url = url, method = "GET", headers = headers)

    override fun post(url: String, body: String): String =
        request(url = url, method = "POST", body = body)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): String {
        val endpoint = URL(url)
        if (endpoint.protocol != "https" || !endpoint.host.isYoutubeHost()) {
            throw TranscriptRetrievalException(
                "Refus d'une adresse de transcription non sécurisée."
            )
        }
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ANDROID_USER_AGENT)
            setRequestProperty("Accept", "*/*")
            headers.forEach(::setRequestProperty)
        }
        return try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw TranscriptRetrievalException(
                    "YouTube a refusé la transcription (HTTP $status)."
                )
            }
            response
        } catch (error: TranscriptRetrievalException) {
            throw error
        } catch (error: IOException) {
            throw TranscriptRetrievalException(
                "Impossible de contacter YouTube.",
                error
            )
        } finally {
            connection.disconnect()
        }
    }
}

private fun String.isYoutubeHost(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return normalized == "youtube.com" || normalized.endsWith(".youtube.com")
}

private fun TranscriptRetrievalException.toFrenchMessage(): String {
    val detail = message.orEmpty().lowercase(Locale.ROOT)
    return when {
        "disabled" in detail ->
            "Les sous-titres sont désactivés pour cette vidéo."
        "age restricted" in detail ->
            "Les vidéos avec restriction d'âge ne peuvent pas être importées."
        "bot" in detail || "captcha" in detail ->
            "YouTube bloque temporairement la récupération de cette transcription."
        "unavailable" in detail -> "Cette vidéo YouTube n'est pas disponible."
        "no transcript" in detail || "not found" in detail ->
            "Aucune transcription n'est disponible pour cette vidéo."
        else -> "La transcription YouTube n'a pas pu être récupérée."
    }
}

private fun String.normalizeTranscriptWhitespace(): String =
    replace(WHITESPACE, " ").trim()

private fun String.trimToWordEnd(): String =
    substringBeforeLast(' ', missingDelimiterValue = this).trimEnd()

private fun String.trimToWordStart(): String =
    substringAfter(' ', missingDelimiterValue = this).trimStart()

private fun String.trimToWordBoundaries(): String =
    trimToWordStart().trimToWordEnd()

private fun invalidYoutubeUrl() = IllegalArgumentException(
    "Collez un lien YouTube valide ou l'identifiant à 11 caractères de la vidéo."
)

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val WHITESPACE = Regex("\\s+")
private const val MAX_TRANSCRIPT_CHARACTERS = 4_200
private const val NETWORK_TIMEOUT_MILLIS = 20_000
private const val ANDROID_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
