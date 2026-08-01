package com.anis.larp.ui.freemode

import com.anis.larp.learning.LearningTopics
import com.anis.larp.learning.ExercisePlan
import com.anis.larp.learning.LearnedWord
import com.anis.larp.learning.decodeExerciseChoices
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class GeminiNanoReplyGenerator {
    private val model: GenerativeModel = Generation.getClient()

    suspend fun prepare(onPreparingModel: () -> Unit) {
        ensureAvailable(onPreparingModel)
    }

    internal suspend fun generateReply(
        transcript: String,
        recognitionLocale: Locale,
        tutorContext: TutorContext,
        conversationHistory: List<ConversationTurn> = emptyList(),
        requestedContentKind: LearningContentRequestKind? = null,
        onPreparingModel: () -> Unit
    ): GeneratedReply {
        ensureAvailable(onPreparingModel)

        val generatedReply = if (requestedContentKind != null) {
            generateVerifiedLearningContentReply(
                kind = requestedContentKind,
                transcript = transcript,
                tutorContext = tutorContext,
                conversationHistory = conversationHistory,
                modelLabel = "Gemini"
            ) { prompt -> generateRawReply(prompt) }
        } else {
            val prompt = tutorPrompt(
                transcript = transcript,
                recognitionLocale = recognitionLocale,
                tutorContext = tutorContext,
                conversationHistory = conversationHistory,
                toolMode = TutorToolMode.TAGGED_ACTIONS
            )
            parseGeneratedReply(
                rawReply = generateRawReply(prompt),
                fallbackLocale = tutorContext.targetLanguage,
                contentLanguageTag = tutorContext.targetLanguage.toLanguageTag()
            )
        }
        return generatedReply.copy(
            modelName = "Gemini",
            acceleration = "Android AI Core"
        )
    }

    private suspend fun generateRawReply(prompt: String): String {
        val response = withTimeout(60_000) {
            model.generateContent(prompt)
        }
        return response.candidates.firstOrNull()?.text?.trim()
            .orEmpty()
            .ifBlank {
                throw IllegalStateException("Gemini Nano n'a produit aucune réponse.")
            }
    }

    fun close() {
        model.close()
    }

    private suspend fun ensureAvailable(onPreparingModel: () -> Unit) {
        when (withTimeout(10_000) { model.checkStatus() }) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> {
                onPreparingModel()
                val result = withTimeout(300_000) {
                    model.download().first { status ->
                        status is DownloadStatus.DownloadCompleted ||
                            status is DownloadStatus.DownloadFailed
                    }
                }
                if (result is DownloadStatus.DownloadFailed) {
                    throw result.e
                }
            }

            else -> throw IllegalStateException(
                "Gemini Nano n'est pas disponible sur cet appareil."
            )
        }
    }
}

data class GeneratedReply(
    val text: String,
    val locale: Locale,
    val modelName: String = "",
    val acceleration: String? = null,
    val contentAction: com.anis.larp.learning.LearningContentAction? = null,
    val contentActionAlreadyExecuted: Boolean = false
)

internal fun parseGeneratedReply(
    rawReply: String,
    fallbackLocale: Locale,
    contentLanguageTag: String = fallbackLocale.toLanguageTag()
): GeneratedReply {
    val lines = rawReply.lines()
    val languageTag = lines
        .asSequence()
        .filter { it.trimStart().startsWith("LANGUAGE_TAG:", ignoreCase = true) }
        .lastOrNull()
        ?.substringAfter(':')
        ?.trim()
        ?.substringBefore(' ')
        ?.ifBlank { null }
    val replyLineIndex = lines.indexOfLast { line ->
        line.trimStart().startsWith("REPLY:", ignoreCase = true)
    }
    val candidateLines = if (replyLineIndex >= 0) {
        val replyLine = lines[replyLineIndex]
        listOf(replyLine.substringAfter(':')) + lines.drop(replyLineIndex + 1)
    } else {
        lines
    }
    val text = sanitizeTextForSpeech(candidateLines.joinToString("\n"))
    if (text.isBlank()) {
        throw IllegalArgumentException("Le modèle n'a produit aucune réponse à prononcer.")
    }

    val parsedLocale = languageTag
        ?.let(::localeForSpeechTag)
        ?.takeIf { it.language.isNotBlank() }
        ?: inferReplyLocale(text, fallbackLocale)
    return GeneratedReply(
        text = text,
        locale = parsedLocale,
        contentAction = parseLearningContentAction(
            rawReply = rawReply,
            fallbackLanguageTag = contentLanguageTag
        )
    )
}

internal fun parseLearningContentAction(
    rawReply: String,
    fallbackLanguageTag: String
): com.anis.larp.learning.LearningContentAction? {
    val fields = parseLearningContentFields(rawReply)
    return when (fields["ACTION"]?.uppercase(Locale.ROOT)) {
        "CREATE_EXERCISE" -> {
            val prompt = fields.requireActionField("ACTION_PROMPT")
            val expectedAnswer = fields.requireActionField("ACTION_EXPECTED_ANSWER")
            val definition = com.anis.larp.learning.normalizeGeneratedExerciseDefinition(
                typeValue = fields["ACTION_EXERCISE_TYPE"],
                expectedAnswer = expectedAnswer,
                choicesValue = fields["ACTION_CHOICES"]
            )
            com.anis.larp.learning.validateExerciseDefinition(
                type = definition.type,
                prompt = prompt,
                expectedAnswer = expectedAnswer,
                choices = definition.choices
            )
            com.anis.larp.learning.LearningContentAction.CreateExercise(
                title = fields.requireActionField("ACTION_TITLE"),
                instructions = fields.requireActionField("ACTION_INSTRUCTIONS"),
                prompt = prompt,
                expectedAnswer = expectedAnswer,
                languageTag = fields["ACTION_LANGUAGE_TAG"]
                    .orEmpty()
                    .ifBlank { fallbackLanguageTag },
                type = definition.type,
                choices = definition.choices,
                difficulty = com.anis.larp.learning.ExerciseDifficulty.fromWireValue(
                    fields["ACTION_DIFFICULTY"]
                ),
                topic = LearningTopics.choose(
                    requested = fields["ACTION_TOPIC"],
                    context = listOf(
                        fields["ACTION_TITLE"],
                        fields["ACTION_INSTRUCTIONS"],
                        prompt
                    ).joinToString(" ")
                ),
                plan = ExercisePlan(
                    words = listOf(
                        LearnedWord(
                            text = fields["ACTION_WORD_1"].orEmpty(),
                            pronunciation = fields["ACTION_WORD_1_PRONUNCIATION"].orEmpty(),
                            definition = fields["ACTION_WORD_1_DEFINITION"].orEmpty(),
                            gapSentence = fields["ACTION_WORD_1_GAP_SENTENCE"].orEmpty(),
                            distractors = decodeExerciseChoices(fields["ACTION_WORD_1_DISTRACTORS"]),
                            recallPrompt = fields["ACTION_WORD_1_RECALL_PROMPT"].orEmpty(),
                            recallAnswer = fields["ACTION_WORD_1_RECALL_ANSWER"].orEmpty()
                        ),
                        LearnedWord(
                            text = fields["ACTION_WORD_2"].orEmpty(),
                            pronunciation = fields["ACTION_WORD_2_PRONUNCIATION"].orEmpty(),
                            definition = fields["ACTION_WORD_2_DEFINITION"].orEmpty(),
                            gapSentence = fields["ACTION_WORD_2_GAP_SENTENCE"].orEmpty(),
                            distractors = decodeExerciseChoices(fields["ACTION_WORD_2_DISTRACTORS"]),
                            recallPrompt = fields["ACTION_WORD_2_RECALL_PROMPT"].orEmpty(),
                            recallAnswer = fields["ACTION_WORD_2_RECALL_ANSWER"].orEmpty()
                        )
                    ),
                    hardPrompt = fields["ACTION_HARD_PROMPT"].orEmpty(),
                    hardAnswer = fields["ACTION_HARD_ANSWER"].orEmpty(),
                    finalSentence = fields["ACTION_FINAL_SENTENCE"].orEmpty(),
                    finalAnswers = decodeExerciseChoices(fields["ACTION_FINAL_ANSWERS"])
                )
            )
        }
        "CREATE_LESSON" -> com.anis.larp.learning.LearningContentAction.CreateLesson(
            title = fields.requireActionField("ACTION_TITLE"),
            objective = fields.requireActionField("ACTION_OBJECTIVE"),
            content = fields.requireActionField("ACTION_CONTENT"),
            languageTag = fields["ACTION_LANGUAGE_TAG"]
                .orEmpty()
                .ifBlank { fallbackLanguageTag },
            topic = LearningTopics.choose(
                requested = fields["ACTION_TOPIC"],
                context = listOf(
                    fields["ACTION_TITLE"],
                    fields["ACTION_OBJECTIVE"],
                    fields["ACTION_CONTENT"]
                ).joinToString(" ")
            )
        )
        else -> null
    }
}

private fun parseLearningContentFields(rawReply: String): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    var activeKey: String? = null
    val activeValue = StringBuilder()

    fun flushActiveField() {
        val key = activeKey ?: return
        fields[key] = activeValue.toString()
            .trim()
            .trimEnd(',')
            .trim()
            .trimSurroundingActionQuotes()
            .unescapeActionValue()
        activeKey = null
        activeValue.clear()
    }

    rawReply.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line == "```" || line == "{" || line == "}") return@forEach
        val protocolField = parseLearningProtocolField(line)
        when {
            protocolField?.first?.isActionField() == true -> {
                flushActiveField()
                activeKey = protocolField.first
                activeValue.append(protocolField.second)
            }
            protocolField?.first in ACTION_SECTION_END_FIELDS -> {
                flushActiveField()
            }
            activeKey == "ACTION_CONTENT" -> {
                if (activeValue.isNotEmpty()) activeValue.append('\n')
                activeValue.append(rawLine.trimEnd())
            }
        }
    }
    flushActiveField()
    return fields
}

private fun parseLearningProtocolField(line: String): Pair<String, String>? {
    val separator = line.indexOf(':')
    if (separator <= 0) return null
    val key = line.substring(0, separator)
        .trim(' ', '\t', '"', '\'', '`', '*', '-', '_')
        .replace('-', '_')
        .replace(' ', '_')
        .uppercase(Locale.ROOT)
    val value = line.substring(separator + 1).trim()
    return key to value
}

private fun String.isActionField(): Boolean =
    this == "ACTION" || startsWith("ACTION_")

private val ACTION_SECTION_END_FIELDS = setOf(
    "LANGUAGE_TAG",
    "LANGUAGE",
    "LOCALE",
    "REPLY",
    "RESPONSE",
    "ANSWER"
)

private fun Map<String, String>.requireActionField(key: String): String =
    get(key)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException(
            "Le modèle a demandé une création incomplète ($key manquant)."
        )

private fun String.unescapeActionValue(): String =
    replace("\\\\n", "\n")
        .replace("\\n", "\n")

private fun String.trimSurroundingActionQuotes(): String =
    if (
        length >= 2 &&
        ((first() == '"' && last() == '"') ||
            (first() == '\'' && last() == '\''))
    ) {
        substring(1, length - 1).trim()
    } else {
        this
    }

private fun localeForSpeechTag(tag: String): Locale =
    if (tag.startsWith("cmn", ignoreCase = true)) {
        Locale.forLanguageTag("zh${tag.drop(3)}")
    } else {
        Locale.forLanguageTag(tag)
    }

private fun inferReplyLocale(text: String, fallbackLocale: Locale): Locale =
    if (text.any { character -> character.code in 0x3400..0x9FFF }) {
        Locale.SIMPLIFIED_CHINESE
    } else {
        fallbackLocale
    }
