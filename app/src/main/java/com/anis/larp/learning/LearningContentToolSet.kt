package com.anis.larp.learning

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only generation tool needed for an interactive lesson. Its deliberately
 * short, explicit wire fields keep small on-device models from having to
 * invent a nested serialization format.
 */
class LearningContentToolSet(
    private val repository: LearningContentRepository,
    private val targetLanguageTag: String? = null,
    private val onActionExecuted: (LearningContentAction) -> Unit = {}
) : ToolSet {
    @Tool(
        description = "Save a small language pack. The app builds all lesson steps, instructions, answers and distractors locally."
    )
    fun submitLessonContent(
        @ToolParam(description = "Everyday topic.")
        t: String,
        @ToolParam(description = "Target 1 text.") x1: String,
        @ToolParam(description = "Target 1 native meaning.") m1: String,
        @ToolParam(description = "Target 1 reading aid; Hanyu Pinyin with tone marks for Chinese, otherwise empty if not needed.") r1: String = "",
        @ToolParam(description = "Target 2 text.") x2: String,
        @ToolParam(description = "Target 2 native meaning.") m2: String,
        @ToolParam(description = "Target 2 reading aid; Hanyu Pinyin with tone marks for Chinese, otherwise empty if not needed.") r2: String = "",
        @ToolParam(description = "Target 3 text.") x3: String,
        @ToolParam(description = "Target 3 native meaning.") m3: String,
        @ToolParam(description = "Target 3 reading aid; Hanyu Pinyin with tone marks for Chinese, otherwise empty if not needed.") r3: String = "",
        @ToolParam(description = "Short sentence 1.") s1: String,
        @ToolParam(description = "Sentence 1 native meaning.") sm1: String,
        @ToolParam(description = "Sentence 1 target indexes, comma separated.") i1: String,
        @ToolParam(description = "Sentence 1 ordered chunks separated by /; required for Chinese.") c1: String = "",
        @ToolParam(description = "Short sentence 2.") s2: String,
        @ToolParam(description = "Sentence 2 native meaning.") sm2: String,
        @ToolParam(description = "Sentence 2 target indexes, comma separated.") i2: String,
        @ToolParam(description = "Sentence 2 ordered chunks separated by /; required for Chinese.") c2: String = ""
    ): Map<String, String> {
        val languageTag = targetLanguageTag.orEmpty().ifBlank { "und" }
        val requested = explicitLessonContent(
            topic = t,
            targets = listOf(
                normalizedGeneratedTarget(x1, m1, r1, languageTag),
                normalizedGeneratedTarget(x2, m2, r2, languageTag),
                normalizedGeneratedTarget(x3, m3, r3, languageTag)
            ),
            sentences = listOf(
                explicitSentence(s1, sm1, i1, c1),
                explicitSentence(s2, sm2, i2, c2)
            )
        )
        val validator = LessonContentValidator()
        val content = validator.repair(
            normalizeGeneratedTargetIndexes(requested),
            languageTag
        )
        val validation = validator.validate(content, languageTag)
        require(validation is LessonContentValidation.Valid) {
            (validation as LessonContentValidation.Invalid).reasons.joinToString("; ")
        }
        val action = LearningContentAction.CreateLessonContent(
            content = content,
            languageTag = languageTag
        )
        val exercise = repository.createLessonContent(
            requestedContent = action.content,
            languageTag = action.languageTag,
            difficulty = action.difficulty,
            desiredStepCount = action.desiredStepCount
        )
        onActionExecuted(action)
        return mapOf(
            "status" to "created",
            "kind" to "exercise",
            "id" to exercise.id,
            "title" to exercise.title,
            "steps" to exercise.compiledSteps.size.toString()
        )
    }
}

fun explicitLessonContent(
    topic: String,
    targets: List<LearningTarget>,
    sentences: List<LearningSentence>
): LessonContent = LessonContent(
    topic = topic.trim(),
    targets = targets,
    sentences = sentences
)

fun explicitSentence(
    text: String,
    meaning: String,
    indexes: String,
    chunks: String = ""
): LearningSentence = LearningSentence(
    text = text.trim(),
    meaning = meaning.trim(),
    targetIndexes = indexes.split(',').mapNotNull { it.trim().toIntOrNull() },
    chunks = chunks.optionalGeneratedValue()
        .orEmpty()
        .split('/')
        .map(String::trim)
        .filter(String::isNotBlank)
)

private fun String.optionalGeneratedValue(): String? = trim()
    .takeIf(String::isNotBlank)
    ?.takeUnless { it.equals("NONE", ignoreCase = true) }

/**
 * Small local models commonly put Chinese reading aids directly after the Han
 * target even when the schema provides a separate reading field. Preserve the
 * linguistic information while restoring the canonical split expected by the
 * validator and deterministic lesson compiler.
 */
internal fun normalizedGeneratedTarget(
    text: String,
    meaning: String,
    reading: String?,
    languageTag: String
): LearningTarget {
    val cleanText = text.trim()
    val explicitReading = reading.orEmpty().optionalGeneratedValue()
    if (!isChineseLanguageTag(languageTag) || explicitReading != null) {
        return LearningTarget(cleanText, meaning.trim(), explicitReading)
    }

    val (targetText, inlineReading) = splitInlineChineseReading(cleanText)
        ?: return LearningTarget(cleanText, meaning.trim())
    return LearningTarget(targetText, meaning.trim(), inlineReading)
}

private fun splitInlineChineseReading(text: String): Pair<String, String>? {
    val pair = CHINESE_READING_DELIMITERS.firstNotNullOfOrNull { (opening, closing) ->
        if (!text.endsWith(closing)) return@firstNotNullOfOrNull null
        val openingIndex = text.lastIndexOf(opening)
        if (openingIndex <= 0) return@firstNotNullOfOrNull null
        val target = text.substring(0, openingIndex).trim()
        val reading = text.substring(openingIndex + 1, text.lastIndex).trim()
        (target to reading).takeIf {
            containsHanCharacters(target) && reading.codePoints().anyMatch { codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            }
        }
    }
    return pair
}

private val CHINESE_READING_DELIMITERS = listOf('(' to ')', '（' to '）')

/** Converts the common 1..N model convention before the app validates 0..N-1 indexes. */
internal fun normalizeGeneratedTargetIndexes(content: LessonContent): LessonContent {
    val generatedIndexes = content.sentences.flatMap(LearningSentence::targetIndexes)
    val appearsOneBased = generatedIndexes.isNotEmpty() &&
        0 !in generatedIndexes &&
        generatedIndexes.all { it in 1..content.targets.size }
    if (!appearsOneBased) return content
    return content.copy(
        sentences = content.sentences.map { sentence ->
            sentence.copy(targetIndexes = sentence.targetIndexes.map { it - 1 })
        }
    )
}

fun decodeLessonContent(topic: String, targets: String, sentences: String): LessonContent =
    LessonContent(
        topic = topic.trim(),
        targets = decodeJsonTargets(targets) ?: splitPacked(targets).map { packed ->
            val fields = packed.split('~', limit = 3)
            LearningTarget(
                text = fields.getOrElse(0) { "" }.trim(),
                meaning = fields.getOrElse(1) { "" }.trim(),
                reading = fields.getOrNull(2)?.trim()?.ifBlank { null }
            )
        },
        sentences = decodeJsonSentences(sentences) ?: splitPacked(sentences).map { packed ->
            val fields = packed.split('~', limit = 4)
            LearningSentence(
                text = fields.getOrElse(0) { "" }.trim(),
                meaning = fields.getOrElse(1) { "" }.trim(),
                targetIndexes = fields.getOrElse(2) { "" }
                    .split(',').mapNotNull { it.trim().toIntOrNull() },
                chunks = fields.getOrNull(3).orEmpty()
                    .split('/').map(String::trim).filter(String::isNotBlank)
            )
        }
    )

private fun decodeJsonTargets(value: String): List<LearningTarget>? = runCatching {
    val array = JSONArray(value.trim())
    List(array.length()) { index ->
        when (val item = array.get(index)) {
            is JSONObject -> LearningTarget(
                text = item.firstString("text", "t", "target"),
                meaning = item.firstString("meaning", "m", "translation"),
                reading = item.firstString("reading", "r").ifBlank { null }
            )
            is JSONArray -> LearningTarget(
                text = item.optString(0),
                meaning = item.optString(1),
                reading = item.optString(2).ifBlank { null }
            )
            else -> LearningTarget(item.toString(), "")
        }
    }
}.getOrNull()

private fun decodeJsonSentences(value: String): List<LearningSentence>? = runCatching {
    val array = JSONArray(value.trim())
    List(array.length()) { index ->
        when (val item = array.get(index)) {
            is JSONObject -> LearningSentence(
                text = item.firstString("text", "s", "sentence"),
                meaning = item.firstString("meaning", "m", "translation"),
                targetIndexes = item.firstArray("targetIndexes", "indexes", "i").toInts(),
                chunks = item.firstArray("chunks", "c").toStrings()
            )
            is JSONArray -> LearningSentence(
                text = item.optString(0),
                meaning = item.optString(1),
                targetIndexes = item.optJSONArray(2).toInts(),
                chunks = item.optJSONArray(3).toStrings()
            )
            else -> LearningSentence(item.toString(), "", emptyList())
        }
    }
}.getOrNull()

private fun JSONObject.firstString(vararg keys: String): String = keys
    .firstNotNullOfOrNull { key -> optString(key).takeIf(String::isNotBlank) }
    .orEmpty()

private fun JSONObject.firstArray(vararg keys: String): JSONArray? = keys
    .firstNotNullOfOrNull(::optJSONArray)

private fun JSONArray?.toInts(): List<Int> = buildList {
    val array = this@toInts ?: return@buildList
    for (index in 0 until array.length()) {
        array.opt(index)?.toString()?.toIntOrNull()?.let(::add)
    }
}

private fun JSONArray?.toStrings(): List<String> = buildList {
    val array = this@toStrings ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun splitPacked(value: String): List<String> = value
    .split("||")
    .map(String::trim)
    .filter(String::isNotBlank)
