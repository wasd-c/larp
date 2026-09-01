package com.anis.larp.learning

import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import kotlin.random.Random

data class LessonContent(
    val topic: String,
    val targets: List<LearningTarget>,
    val sentences: List<LearningSentence>
)

data class LearningTarget(
    val text: String,
    val meaning: String,
    val reading: String? = null
)

data class LearningSentence(
    val text: String,
    val meaning: String,
    val targetIndexes: List<Int>,
    val chunks: List<String> = emptyList()
)

enum class LessonDifficulty { BEGINNER, INTERMEDIATE, ADVANCED }

enum class LessonRecipe { AUTO, VOCABULARY, SENTENCE_MASTERY }

data class LessonConfiguration(
    val desiredStepCount: Int = 10,
    val enableListening: Boolean = true,
    val enableSpeaking: Boolean = true,
    val difficulty: LessonDifficulty = LessonDifficulty.BEGINNER,
    val seed: Long = 0L,
    val recipe: LessonRecipe = LessonRecipe.AUTO,
    val previousErrorTargetIndexes: Set<Int> = emptySet()
)

sealed interface LessonStep {
    val assistance: Int

    data class IntroduceTarget(val targetIndex: Int) : LessonStep {
        override val assistance = 3
    }

    data class MeaningChoice(
        val targetIndex: Int,
        val choices: List<String>
    ) : LessonStep {
        override val assistance = 2
    }

    data class AudioRecognition(
        val targetIndex: Int,
        val choices: List<String>
    ) : LessonStep {
        override val assistance = 2
    }

    data class MatchTargets(val targetIndexes: List<Int>) : LessonStep {
        override val assistance = 2
    }

    data class ListenSentence(val sentenceIndex: Int) : LessonStep {
        override val assistance = 3
    }

    data class SentenceMeaningChoice(
        val sentenceIndex: Int,
        val choices: List<String>,
        val playAudio: Boolean
    ) : LessonStep {
        override val assistance = if (playAudio) 2 else 3
    }

    data class ClozeSentence(
        val sentenceIndex: Int,
        val missingTargetIndex: Int,
        val choices: List<String>
    ) : LessonStep {
        override val assistance = 2
    }

    data class ReorderChunks(
        val sentenceIndex: Int,
        val shuffledChunks: List<String>
    ) : LessonStep {
        override val assistance = 1
    }

    data class MeaningToSentence(
        val sentenceIndex: Int,
        val shuffledChunks: List<String>
    ) : LessonStep {
        override val assistance = 1
    }

    data class SpeakSentence(
        val sentenceIndex: Int,
        val showText: Boolean
    ) : LessonStep {
        override val assistance = if (showText) 1 else 0
    }

    data class TypeAnswer(
        val sentenceIndex: Int,
        val showMeaning: Boolean = true
    ) : LessonStep {
        override val assistance = if (showMeaning) 1 else 0
    }
}

sealed interface LessonContentValidation {
    data object Valid : LessonContentValidation
    data class Invalid(val reasons: List<String>) : LessonContentValidation
}

class LessonContentValidator(
    private val normalizer: AnswerNormalizer = DefaultAnswerNormalizer()
) {
    fun validate(content: LessonContent, language: String): LessonContentValidation {
        val reasons = buildList {
            if (content.targets.size !in 2..4) add("targets must contain between 2 and 4 items")
            content.targets.forEachIndexed { index, target ->
                if (target.text.isBlank()) add("target $index has empty text")
                if (target.meaning.isBlank()) add("target $index has empty meaning")
                if (
                    isChineseLanguageTag(language) &&
                    target.reading.orEmpty().codePoints().noneMatch { codePoint ->
                        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                    }
                ) {
                    add("target $index needs a Latin pinyin reading")
                }
            }
            if (content.sentences.isEmpty()) add("at least one sentence is required")
            content.sentences.forEachIndexed { sentenceIndex, sentence ->
                if (sentence.text.isBlank()) add("sentence $sentenceIndex has empty text")
                if (sentence.meaning.isBlank()) add("sentence $sentenceIndex has empty meaning")
                sentence.targetIndexes.forEach { targetIndex ->
                    if (targetIndex !in content.targets.indices) {
                        add("sentence $sentenceIndex references missing target $targetIndex")
                    } else if (!containsTarget(sentence.text, content.targets[targetIndex].text, language)) {
                        add("sentence $sentenceIndex does not contain target $targetIndex")
                    }
                }
                if (
                    learningUnitCount(sentence.text, language) >
                    maximumSentenceUnits(sentence.text, language)
                ) {
                    add("sentence $sentenceIndex is too long")
                }
            }
            content.targets.indices.forEach { targetIndex ->
                if (content.sentences.none { targetIndex in it.targetIndexes }) {
                    add("target $targetIndex is not used by any sentence")
                }
            }
            duplicates(content.targets.map { it.text }, language).forEach {
                add("duplicate target: $it")
            }
            duplicates(content.sentences.map { it.text }, language).forEach {
                add("duplicate sentence: $it")
            }
        }
        return if (reasons.isEmpty()) LessonContentValidation.Valid
        else LessonContentValidation.Invalid(reasons.distinct())
    }

    fun repair(content: LessonContent, language: String): LessonContent {
        val targets = content.targets
            .map {
                it.copy(
                    text = it.text.trim(),
                    meaning = it.meaning.trim(),
                    reading = it.reading
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.takeUnless { value -> value.equals("NONE", ignoreCase = true) }
                )
            }
            .filter { it.text.isNotBlank() && it.meaning.isNotBlank() }
            .distinctBy { normalizer.normalize(it.text, language) }
            .take(4)
        val sentences = content.sentences.mapNotNull { sentence ->
            val text = sentence.text.trim()
            val meaning = sentence.meaning.trim()
            if (text.isBlank() || meaning.isBlank()) return@mapNotNull null
            val indexes = (sentence.targetIndexes + targets.indices.filter { index ->
                containsTarget(text, targets[index].text, language)
            }).distinct().filter { index ->
                index in targets.indices && containsTarget(text, targets[index].text, language)
            }
            val requestedChunks = sentence.chunks
                .map(String::trim)
                .filter {
                    it.isNotBlank() && !it.equals("NONE", ignoreCase = true)
                }
            val repairedSentence = sentence.copy(
                text = text,
                meaning = meaning,
                targetIndexes = indexes,
                chunks = requestedChunks.takeIf { chunksRepresent(it, text) }.orEmpty()
            )
            repairedSentence.copy(
                chunks = if (
                    isChineseLanguageTag(language) || containsHanCharacters(text)
                ) {
                    learningChunks(repairedSentence, targets)
                } else {
                    repairedSentence.chunks
                }
            )
        }.distinctBy { normalizer.normalize(it.text, language) }
        return content.copy(topic = content.topic.trim(), targets = targets, sentences = sentences)
    }

    private fun containsTarget(sentence: String, target: String, language: String): Boolean =
        normalizer.normalize(sentence, language).contains(normalizer.normalize(target, language))

    private fun duplicates(values: List<String>, language: String): List<String> = values
        .groupBy { normalizer.normalize(it, language) }
        .filterKeys(String::isNotBlank)
        .filterValues { it.size > 1 }
        .values.map { it.first() }
}

interface DistractorProvider {
    fun meaningsFor(correctTargetIndex: Int, content: LessonContent, count: Int): List<String>
    fun targetTextsFor(correctTargetIndex: Int, content: LessonContent, count: Int): List<String>
}

class LocalDistractorProvider(
    private val recentlyLearned: List<LearningTarget> = emptyList(),
    private val knownTargets: List<LearningTarget> = emptyList()
) : DistractorProvider {
    override fun meaningsFor(correctTargetIndex: Int, content: LessonContent, count: Int) = select(
        correct = content.targets[correctTargetIndex].meaning,
        primary = content.targets.map { it.meaning },
        secondary = recentlyLearned.map { it.meaning },
        tertiary = knownTargets.map { it.meaning },
        fallback = GENERIC_MEANINGS,
        count = count
    )

    override fun targetTextsFor(correctTargetIndex: Int, content: LessonContent, count: Int) = select(
        correct = content.targets[correctTargetIndex].text,
        primary = content.targets.map { it.text },
        secondary = recentlyLearned.map { it.text },
        tertiary = knownTargets.map { it.text },
        fallback = if (containsHanCharacters(content.targets[correctTargetIndex].text)) {
            GENERIC_CHINESE_TARGETS
        } else {
            GENERIC_TARGETS
        },
        count = count
    )

    private fun select(
        correct: String,
        primary: List<String>,
        secondary: List<String>,
        tertiary: List<String>,
        fallback: List<String>,
        count: Int
    ): List<String> {
        val correctKey = choiceKey(correct)
        return (primary + secondary + tertiary + fallback)
            .map(String::trim)
            .filter { it.isNotBlank() && choiceKey(it) != correctKey }
            .distinctBy(::choiceKey)
            .take(count.coerceAtLeast(0))
    }

    private fun choiceKey(text: String) = Normalizer.normalize(
        text.lowercase(Locale.ROOT), Normalizer.Form.NFKC
    ).replace(Regex("[\\p{P}\\s]+"), "")

    private companion object {
        val GENERIC_MEANINGS = listOf("autre chose", "maintenant", "plus tard", "personne")
        val GENERIC_TARGETS = listOf("other", "another", "example", "option")
        val GENERIC_CHINESE_TARGETS = listOf("今天", "朋友", "喜欢", "学习")
    }
}

interface AnswerNormalizer {
    fun normalize(text: String, language: String): String
}

class DefaultAnswerNormalizer : AnswerNormalizer {
    override fun normalize(text: String, language: String): String =
        normalizedLearningAnswer(text, language)
}

class LessonStepAnswerValidator(
    private val normalizer: AnswerNormalizer = DefaultAnswerNormalizer()
) {
    fun isCorrect(answer: String, expected: String, language: String, alternatives: List<String> = emptyList()): Boolean {
        val normalized = normalizer.normalize(answer, language)
        return (listOf(expected) + alternatives).any { normalizer.normalize(it, language) == normalized }
    }
}

interface LessonCompiler {
    fun compile(content: LessonContent, configuration: LessonConfiguration): List<LessonStep>
}

class DeterministicLessonCompiler(
    private val distractors: DistractorProvider = LocalDistractorProvider()
) : LessonCompiler {
    override fun compile(content: LessonContent, configuration: LessonConfiguration): List<LessonStep> {
        require(LessonContentValidator().validate(content, "und") is LessonContentValidation.Valid) {
            "LessonContent must be valid before compilation"
        }
        val random = Random(configuration.seed)
        val recipe = when (configuration.recipe) {
            LessonRecipe.AUTO -> if (content.targets.size == 2 && content.sentences.size == 1) LessonRecipe.SENTENCE_MASTERY else LessonRecipe.VOCABULARY
            else -> configuration.recipe
        }
        val base = when (recipe) {
            LessonRecipe.VOCABULARY -> vocabularySteps(content, configuration, random)
            LessonRecipe.SENTENCE_MASTERY -> sentenceSteps(content, configuration, random)
            LessonRecipe.AUTO -> error("resolved above")
        }
        return resize(base, content, configuration, random)
    }

    private fun vocabularySteps(content: LessonContent, config: LessonConfiguration, random: Random): List<LessonStep> {
        val steps = mutableListOf<LessonStep>()
        content.targets.forEachIndexed { index, _ ->
            steps += LessonStep.IntroduceTarget(index)
            if (index == 0) steps += LessonStep.MeaningChoice(index, meaningChoices(index, content, random))
        }
        steps += LessonStep.MatchTargets(content.targets.indices.toList())
        val reviewTarget = config.previousErrorTargetIndexes
            .firstOrNull { it in content.targets.indices }
            ?: content.targets.lastIndex
        if (config.enableListening) {
            steps += LessonStep.AudioRecognition(
                reviewTarget,
                targetChoices(reviewTarget, content, random)
            )
        } else {
            steps += LessonStep.MeaningChoice(
                reviewTarget,
                meaningChoices(reviewTarget, content, random)
            )
        }
        content.sentences.firstOrNull()?.let { sentence ->
            val sentenceIndex = content.sentences.indexOf(sentence)
            val missing = sentence.targetIndexes.firstOrNull() ?: 0
            steps += LessonStep.ClozeSentence(sentenceIndex, missing, targetChoices(missing, content, random))
            steps += LessonStep.SentenceMeaningChoice(sentenceIndex, sentenceMeaningChoices(sentenceIndex, content, random), config.enableListening)
            steps += LessonStep.ReorderChunks(
                sentenceIndex,
                shuffledChunks(sentence, content.targets, random)
            )
        }
        val recapIndex = content.sentences.lastIndex
        steps += if (config.enableSpeaking) LessonStep.SpeakSentence(recapIndex, showText = false)
        else LessonStep.TypeAnswer(recapIndex, showMeaning = true)
        return steps
    }

    private fun sentenceSteps(content: LessonContent, config: LessonConfiguration, random: Random): List<LessonStep> {
        val sentenceIndex = content.sentences.lastIndex
        val sentence = content.sentences[sentenceIndex]
        val importantTarget = sentence.targetIndexes.firstOrNull() ?: 0
        return buildList {
            if (config.enableListening) add(LessonStep.ListenSentence(sentenceIndex))
            else add(LessonStep.SentenceMeaningChoice(sentenceIndex, sentenceMeaningChoices(sentenceIndex, content, random), false))
            add(LessonStep.SentenceMeaningChoice(sentenceIndex, sentenceMeaningChoices(sentenceIndex, content, random), config.enableListening))
            if (config.enableListening) add(LessonStep.AudioRecognition(importantTarget, targetChoices(importantTarget, content, random)))
            else add(LessonStep.MeaningChoice(importantTarget, meaningChoices(importantTarget, content, random)))
            add(
                LessonStep.ReorderChunks(
                    sentenceIndex,
                    shuffledChunks(sentence, content.targets, random)
                )
            )
            add(LessonStep.ClozeSentence(sentenceIndex, importantTarget, targetChoices(importantTarget, content, random)))
            add(
                LessonStep.MeaningToSentence(
                    sentenceIndex,
                    shuffledChunks(sentence, content.targets, random)
                )
            )
            add(LessonStep.TypeAnswer(sentenceIndex, showMeaning = true))
            if (config.enableSpeaking) {
                add(LessonStep.SpeakSentence(sentenceIndex, showText = true))
                add(LessonStep.SpeakSentence(sentenceIndex, showText = false))
            } else {
                add(LessonStep.TypeAnswer(sentenceIndex, showMeaning = false))
                add(
                    LessonStep.MeaningToSentence(
                        sentenceIndex,
                        shuffledChunks(sentence, content.targets, random)
                    )
                )
            }
            add(LessonStep.TypeAnswer(sentenceIndex, showMeaning = true))
        }
    }

    private fun resize(
        base: List<LessonStep>,
        content: LessonContent,
        config: LessonConfiguration,
        random: Random
    ): List<LessonStep> {
        val desired = config.desiredStepCount.coerceIn(1, 30)
        if (base.size >= desired) {
            if (desired == 1) return listOf(base.last())
            val productionTailSize = minOf(4, desired - 1, base.size)
            val early = base.take(desired - productionTailSize)
            return early + base.takeLast(productionTailSize)
        }
        val result = base.toMutableList()
        var cursor = 0
        while (result.size < desired) {
            val targetIndex = cursor % content.targets.size
            val remediation = if (config.enableListening && cursor % 2 == 0) {
                LessonStep.AudioRecognition(targetIndex, targetChoices(targetIndex, content, random))
            } else {
                LessonStep.MeaningChoice(targetIndex, meaningChoices(targetIndex, content, random))
            }
            result.add((result.lastIndex).coerceAtLeast(0), remediation)
            cursor++
        }
        return result
    }

    private fun meaningChoices(index: Int, content: LessonContent, random: Random) =
        (distractors.meaningsFor(index, content, 3) + content.targets[index].meaning).distinct().shuffled(random)

    private fun targetChoices(index: Int, content: LessonContent, random: Random) =
        (distractors.targetTextsFor(index, content, 3) + content.targets[index].text).distinct().shuffled(random)

    private fun sentenceMeaningChoices(index: Int, content: LessonContent, random: Random): List<String> {
        val correct = content.sentences[index].meaning
        val alternatives = content.sentences.map { it.meaning }.filterNot { it == correct } +
            content.targets.map { it.meaning }
        return (alternatives.distinct().take(3) + correct).distinct().shuffled(random)
    }

    private fun shuffledChunks(
        sentence: LearningSentence,
        targets: List<LearningTarget>,
        random: Random
    ): List<String> {
        val chunks = learningChunks(sentence, targets)
        if (chunks.size < 2) return chunks
        var shuffled = chunks.shuffled(random)
        if (shuffled == chunks) shuffled = shuffled.drop(1) + shuffled.first()
        return shuffled
    }

}

object LessonContentCodec {
    fun encode(content: LessonContent): String = buildList {
        add("LARP_LESSON_1")
        add(encoded(content.topic))
        add(content.targets.size.toString())
        content.targets.forEach { target ->
            add(listOf(target.text, target.meaning, target.reading.orEmpty()).joinToString("|") { encoded(it) })
        }
        add(content.sentences.size.toString())
        content.sentences.forEach { sentence ->
            add(listOf(
                encoded(sentence.text), encoded(sentence.meaning),
                sentence.targetIndexes.joinToString(","),
                sentence.chunks.joinToString(",") { encoded(it) }
            ).joinToString("|"))
        }
    }.joinToString("\n")

    fun decode(value: String): LessonContent {
        val lines = value.lines()
        require(lines.firstOrNull() == "LARP_LESSON_1") { "Unsupported lesson cache format" }
        var cursor = 1
        val topic = decoded(lines[cursor++])
        val targets = List(lines[cursor++].toInt()) {
            val fields = lines[cursor++].split('|')
            LearningTarget(decoded(fields[0]), decoded(fields[1]), decoded(fields[2]).ifBlank { null })
        }
        val sentences = List(lines[cursor++].toInt()) {
            val fields = lines[cursor++].split('|')
            LearningSentence(
                text = decoded(fields[0]), meaning = decoded(fields[1]),
                targetIndexes = fields[2].split(',').mapNotNull(String::toIntOrNull),
                chunks = fields.getOrNull(3).orEmpty().split(',').filter(String::isNotBlank).map(::decoded)
            )
        }
        return LessonContent(topic, targets, sentences)
    }

    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun decoded(value: String) = String(Base64.getUrlDecoder().decode(value))
}
