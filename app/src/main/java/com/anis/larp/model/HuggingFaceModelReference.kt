package com.anis.larp.model

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class HuggingFaceModelReference(
    val repository: String,
    val requestedFileName: String? = null
)

/** Parses either `owner/repository` or a Hugging Face blob/resolve model URL. */
fun parseHuggingFaceModelReference(value: String): HuggingFaceModelReference? {
    val input = value.trim()
    if (REPOSITORY_PATTERN.matches(input)) {
        return HuggingFaceModelReference(repository = input)
    }

    val uri = runCatching { URI(input) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (
        !uri.host.equals("huggingface.co", ignoreCase = true) &&
        !uri.host.equals("www.huggingface.co", ignoreCase = true)
    ) {
        return null
    }
    val segments = uri.rawPath.orEmpty()
        .split('/')
        .filter(String::isNotBlank)
        .map(::decodePathSegment)
    if (segments.size < 5) return null
    val repository = "${segments[0]}/${segments[1]}"
    if (!REPOSITORY_PATTERN.matches(repository)) return null
    if (
        !segments[2].equals("blob", ignoreCase = true) &&
        !segments[2].equals("resolve", ignoreCase = true)
    ) {
        return null
    }
    // Downloads currently use the repository's main revision. Reject another
    // revision instead of silently downloading a different file than requested.
    if (!segments[3].equals("main", ignoreCase = true)) return null
    val artifactName = segments.drop(4).joinToString("/")
    if (
        artifactName.isBlank() ||
        !artifactName.endsWith(".litertlm", ignoreCase = true) ||
        segments.drop(4).any { it == "." || it == ".." }
    ) {
        return null
    }
    return HuggingFaceModelReference(
        repository = repository,
        requestedFileName = artifactName
    )
}

private fun decodePathSegment(value: String): String = URLDecoder.decode(
    value.replace("+", "%2B"),
    StandardCharsets.UTF_8.name()
)

private val REPOSITORY_PATTERN =
    Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
