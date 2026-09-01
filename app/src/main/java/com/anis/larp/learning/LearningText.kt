package com.anis.larp.learning

import java.text.Normalizer
import java.util.Locale

internal const val MAX_SPACED_SENTENCE_UNITS = 16
internal const val MAX_DENSE_SENTENCE_UNITS = 24
internal const val MAX_LEARNING_CHUNKS = 8

internal fun isChineseLanguageTag(languageTag: String): Boolean {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    return language == "zh" || language == "cmn"
}

internal fun containsHanCharacters(text: String): Boolean = text.codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

internal fun learningUnitCount(text: String, languageTag: String): Int =
    if (isChineseLanguageTag(languageTag) || containsHanCharacters(text)) {
        text.codePoints().filter(Character::isLetterOrDigit).count().toInt()
    } else {
        text.trim().split(Regex("\\s+")).count(String::isNotBlank)
    }

internal fun maximumSentenceUnits(text: String, languageTag: String): Int =
    if (isChineseLanguageTag(languageTag) || containsHanCharacters(text)) {
        MAX_DENSE_SENTENCE_UNITS
    } else {
        MAX_SPACED_SENTENCE_UNITS
    }

/**
 * Produces stable learning blocks for scripts which do not normally use spaces.
 * Generated target expressions stay intact; remaining Han characters become
 * small deterministic blocks instead of one impossible reorder card.
 */
internal fun learningChunks(
    sentence: LearningSentence,
    targets: List<LearningTarget>
): List<String> {
    val explicit = sentence.chunks.map(String::trim).filter(String::isNotBlank)
    if (
        explicit.size in 2..MAX_LEARNING_CHUNKS &&
        chunksRepresent(explicit, sentence.text)
    ) return explicit

    val protectedTargets = sentence.targetIndexes
        .mapNotNull(targets::getOrNull)
        .map(LearningTarget::text)
    val targetAware = automaticLearningChunks(sentence.text, protectedTargets)
    return if (targetAware.size >= 2 || !containsHanCharacters(sentence.text)) {
        targetAware
    } else {
        automaticLearningChunks(sentence.text)
    }
}

internal fun automaticLearningChunks(
    text: String,
    protectedTargets: List<String> = emptyList()
): List<String> {
    val cleanText = text.trim()
    if (cleanText.isBlank()) return emptyList()
    if (!containsHanCharacters(cleanText)) {
        return cleanText.split(Regex("\\s+")).filter(String::isNotBlank)
    }

    val targets = protectedTargets
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
    val chunks = mutableListOf<String>()
    var index = 0
    while (index < cleanText.length) {
        val target = targets.firstOrNull { candidate ->
            index + candidate.length <= cleanText.length &&
                cleanText.regionMatches(index, candidate, 0, candidate.length)
        }
        if (target != null) {
            chunks += target
            index += target.length
            continue
        }

        val codePoint = cleanText.codePointAt(index)
        val width = Character.charCount(codePoint)
        when {
            Character.isWhitespace(codePoint) -> index += width
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> {
                chunks += String(Character.toChars(codePoint))
                index += width
            }
            Character.isLetterOrDigit(codePoint) -> {
                val start = index
                index += width
                while (index < cleanText.length) {
                    val next = cleanText.codePointAt(index)
                    if (
                        Character.isWhitespace(next) ||
                        Character.UnicodeScript.of(next) == Character.UnicodeScript.HAN ||
                        !Character.isLetterOrDigit(next)
                    ) break
                    index += Character.charCount(next)
                }
                chunks += cleanText.substring(start, index)
            }
            else -> {
                val punctuation = String(Character.toChars(codePoint))
                if (chunks.isEmpty()) chunks += punctuation
                else chunks[chunks.lastIndex] = chunks.last() + punctuation
                index += width
            }
        }
    }
    return chunks
        .filter { chunk -> chunk.codePoints().anyMatch(Character::isLetterOrDigit) }
        .coalescedTo(MAX_LEARNING_CHUNKS)
}

internal fun chunksRepresent(chunks: List<String>, sentence: String): Boolean =
    comparableLearningText(chunks.joinToString("")) == comparableLearningText(sentence)

internal fun normalizedLearningAnswer(value: String, languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag)
        .takeIf { it.language.isNotBlank() }
        ?: Locale.ROOT
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace('’', '\'')
        .trim()
        .replace(Regex("[.!?。！？]+$"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase(locale)
    return if (isChineseLanguageTag(languageTag) || containsHanCharacters(normalized)) {
        normalized.replace(Regex("\\s+"), "")
    } else {
        normalized
    }
}

internal fun learningTextTokens(text: String): List<String> =
    if (containsHanCharacters(text)) {
        automaticLearningChunks(text)
    } else {
        text.split(Regex("[^\\p{L}\\p{N}'’-]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }

internal fun progressiveLearningTextFrames(text: String): List<String> {
    if (!containsHanCharacters(text)) {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        return words.indices.map { index -> words.take(index + 1).joinToString(" ") }
    }
    val widths = text.codePoints().map(Character::charCount).toArray()
    var end = 0
    return widths.map { width ->
        end += width
        text.substring(0, end)
    }
}

private fun comparableLearningText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .codePoints()
    .filter(Character::isLetterOrDigit)
    .collect(
        ::StringBuilder,
        { builder, codePoint -> builder.appendCodePoint(codePoint) },
        StringBuilder::append
    )
    .toString()
    .lowercase(Locale.ROOT)

private fun List<String>.coalescedTo(maximumSize: Int): List<String> {
    if (size <= maximumSize) return this
    val result = ArrayList<String>(maximumSize)
    var index = 0
    repeat(maximumSize) { groupIndex ->
        val remainingItems = size - index
        val remainingGroups = maximumSize - groupIndex
        val groupSize = (remainingItems + remainingGroups - 1) / remainingGroups
        result += subList(index, index + groupSize).joinToString("")
        index += groupSize
    }
    return result
}
