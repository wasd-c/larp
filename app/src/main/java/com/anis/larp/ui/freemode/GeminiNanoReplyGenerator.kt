package com.anis.larp.ui.freemode

import com.anis.larp.learning.LearningTopics
import com.anis.larp.learning.ExercisePlan
import com.anis.larp.learning.LearnedWord
import com.anis.larp.learning.decodeExerciseChoices
import com.anis.larp.learning.normalizedGeneratedTarget
import com.anis.larp.learning.normalizeGeneratedTargetIndexes
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class GeminiNanoReplyGenerator {
    private val modelLock = Any()
    private val availabilityMutex = Mutex()
    @Volatile private var model: GenerativeModel? = null
    @Volatile private var available = false

    suspend fun prepare(onPreparingModel: () -> Unit) {
        ensureAvailable(onPreparingModel)
    }

    internal suspend fun generateReply(
        transcript: String,
        recognitionLocale: Locale,
        tutorContext: TutorContext,
        conversationHistory: List<ConversationTurn> = emptyList(),
        requestedContentKind: LearningContentRequestKind? = null,
        lessonContext: LessonChatContext? = null,
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
                toolMode = if (lessonContext == null) {
                    TutorToolMode.TAGGED_ACTIONS
                } else {
                    TutorToolMode.NONE
                },
                lessonContext = lessonContext
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
            getOrCreateModel().generateContent(prompt)
        }
        return response.candidates.firstOrNull()?.text?.trim()
            .orEmpty()
            .ifBlank {
                throw IllegalStateException("Gemini Nano n'a produit aucune réponse.")
            }
    }

    fun release() {
        val modelToClose = synchronized(modelLock) {
            model.also { model = null }
        }
        available = false
        modelToClose?.close()
    }

    private suspend fun ensureAvailable(onPreparingModel: () -> Unit) {
        if (available) return
        availabilityMutex.withLock {
            if (available) return
            val activeModel = getOrCreateModel()
            when (withTimeout(10_000) { activeModel.checkStatus() }) {
                FeatureStatus.AVAILABLE -> available = true
                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING -> {
                    onPreparingModel()
                    val result = withTimeout(300_000) {
                        activeModel.download().first { status ->
                            status is DownloadStatus.DownloadCompleted ||
                                status is DownloadStatus.DownloadFailed
                        }
                    }
                    if (result is DownloadStatus.DownloadFailed) throw result.e
                    available = true
                }

                else -> throw IllegalStateException(
                    "Gemini Nano n'est pas disponible sur cet appareil."
                )
            }
        }
    }

    private fun getOrCreateModel(): GenerativeModel = model ?: synchronized(modelLock) {
        model ?: Generation.getClient().also { model = it }
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

    val requestedLocale = languageTag
        ?.let(::localeForSpeechTag)
        ?.takeIf { it.language.isNotBlank() }
        ?: fallbackLocale
    val parsedLocale = localeMatchingSpokenText(text, requestedLocale)
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
        "SUBMIT_LESSON_CONTENT" -> {
            val validator = com.anis.larp.learning.LessonContentValidator()
            val requested = if (fields["ACTION_X1"].isNullOrBlank()) {
                com.anis.larp.learning.decodeLessonContent(
                    topic = fields.requireActionField("ACTION_TOPIC"),
                    targets = fields.requireActionField("ACTION_TARGETS"),
                    sentences = fields.requireActionField("ACTION_SENTENCES")
                )
            } else {
                com.anis.larp.learning.explicitLessonContent(
                    topic = fields.requireActionField("ACTION_TOPIC"),
                    targets = listOf(
                        normalizedGeneratedTarget(
                            text = fields.requireActionField("ACTION_X1"),
                            meaning = fields.requireActionField("ACTION_M1"),
                            reading = fields.optionalActionField("ACTION_R1"),
                            languageTag = fallbackLanguageTag
                        ),
                        normalizedGeneratedTarget(
                            text = fields.requireActionField("ACTION_X2"),
                            meaning = fields.requireActionField("ACTION_M2"),
                            reading = fields.optionalActionField("ACTION_R2"),
                            languageTag = fallbackLanguageTag
                        ),
                        normalizedGeneratedTarget(
                            text = fields.requireActionField("ACTION_X3"),
                            meaning = fields.requireActionField("ACTION_M3"),
                            reading = fields.optionalActionField("ACTION_R3"),
                            languageTag = fallbackLanguageTag
                        )
                    ),
                    sentences = listOf(
                        com.anis.larp.learning.explicitSentence(
                            fields.requireActionField("ACTION_S1"),
                            fields.requireActionField("ACTION_SM1"),
                            fields.requireActionField("ACTION_I1"),
                            fields.optionalActionField("ACTION_C1").orEmpty()
                        ),
                        com.anis.larp.learning.explicitSentence(
                            fields.requireActionField("ACTION_S2"),
                            fields.requireActionField("ACTION_SM2"),
                            fields.requireActionField("ACTION_I2"),
                            fields.optionalActionField("ACTION_C2").orEmpty()
                        )
                    )
                )
            }
            val content = validator.repair(
                normalizeGeneratedTargetIndexes(requested),
                fallbackLanguageTag
            )
            val validation = validator.validate(content, fallbackLanguageTag)
            require(validation is com.anis.larp.learning.LessonContentValidation.Valid) {
                (validation as com.anis.larp.learning.LessonContentValidation.Invalid)
                    .reasons.joinToString("; ")
            }
            com.anis.larp.learning.LearningContentAction.CreateLessonContent(
                content = content,
                languageTag = fallbackLanguageTag
            )
        }
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
            activeKey in MULTILINE_ACTION_FIELDS -> {
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

private val MULTILINE_ACTION_FIELDS = setOf(
    "ACTION_CONTENT",
    "ACTION_TARGETS",
    "ACTION_SENTENCES"
)

private fun Map<String, String>.requireActionField(key: String): String =
    get(key)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException(
            "Le modèle a demandé une création incomplète ($key manquant)."
        )

private fun Map<String, String>.optionalActionField(key: String): String? =
    get(key)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it.equals("NONE", ignoreCase = true) }

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
