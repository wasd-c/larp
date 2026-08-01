package com.anis.larp.learning

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ExerciseType(val wireValue: String, val frenchLabel: String) {
    FREE_RESPONSE("FREE_RESPONSE", "Réponse libre"),
    MULTIPLE_CHOICE("MULTIPLE_CHOICE", "Choix multiple"),
    FILL_BLANK("FILL_BLANK", "Texte à trous"),
    WORD_ORDER("WORD_ORDER", "Remettre dans l'ordre"),
    MATCHING("MATCHING", "Associer les mots"),
    TRANSLATION("TRANSLATION", "Traduction");

    companion object {
        fun fromWireValue(value: String?): ExerciseType = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true)
        } ?: FREE_RESPONSE
    }
}

enum class ExerciseDifficulty(val wireValue: String, val frenchLabel: String) {
    BEGINNER("BEGINNER", "Débutant"),
    INTERMEDIATE("INTERMEDIATE", "Intermédiaire"),
    ADVANCED("ADVANCED", "Avancé");

    companion object {
        fun fromWireValue(value: String?): ExerciseDifficulty = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true)
        } ?: INTERMEDIATE
    }
}

data class Exercise(
    val id: String,
    val title: String,
    val instructions: String,
    val prompt: String,
    val expectedAnswer: String,
    val languageTag: String,
    val createdAtMillis: Long,
    val archivedAtMillis: Long? = null,
    val type: ExerciseType = ExerciseType.FREE_RESPONSE,
    val choices: List<String> = emptyList(),
    val difficulty: ExerciseDifficulty = ExerciseDifficulty.INTERMEDIATE,
    val topic: String = "Culture",
    val plan: ExercisePlan = fallbackExercisePlan(prompt, expectedAnswer, choices),
    val completion: ExerciseCompletion? = null
)

data class Lesson(
    val id: String,
    val title: String,
    val objective: String,
    val content: String,
    val languageTag: String,
    val createdAtMillis: Long,
    val archivedAtMillis: Long? = null,
    val topic: String = "Culture"
)

data class LearningContentState(
    val exercises: List<Exercise> = emptyList(),
    val lessons: List<Lesson> = emptyList()
)

sealed interface LearningContentAction {
    data class CreateExercise(
        val title: String,
        val instructions: String,
        val prompt: String,
        val expectedAnswer: String,
        val languageTag: String,
        val type: ExerciseType = ExerciseType.FREE_RESPONSE,
        val choices: List<String> = emptyList(),
        val difficulty: ExerciseDifficulty = ExerciseDifficulty.INTERMEDIATE,
        val topic: String = "Culture",
        val plan: ExercisePlan = fallbackExercisePlan(prompt, expectedAnswer, choices)
    ) : LearningContentAction

    data class CreateLesson(
        val title: String,
        val objective: String,
        val content: String,
        val languageTag: String,
        val topic: String = "Culture"
    ) : LearningContentAction
}

class LearningContentRepository private constructor(
    private val contentFile: File
) {
    private constructor(context: Context) : this(
        File(context.applicationContext.filesDir, CONTENT_FILE_NAME)
    )
    private val mutableState = MutableStateFlow(readContent())

    val state: StateFlow<LearningContentState> = mutableState.asStateFlow()

    fun createExercise(
        title: String,
        instructions: String,
        prompt: String,
        expectedAnswer: String,
        languageTag: String,
        type: ExerciseType = ExerciseType.FREE_RESPONSE,
        choices: List<String> = emptyList(),
        difficulty: ExerciseDifficulty = ExerciseDifficulty.INTERMEDIATE,
        topic: String = "Culture",
        plan: ExercisePlan = fallbackExercisePlan(prompt, expectedAnswer, choices)
    ): Exercise = synchronized(FILE_LOCK) {
        val cleanedPrompt = clean(prompt, MAX_PROMPT_LENGTH, "À vous de jouer.")
        val cleanedAnswer = clean(
            expectedAnswer,
            MAX_ANSWER_LENGTH,
            "Réponse libre"
        )
        val cleanedChoices = choices
            .map { clean(it, MAX_CHOICE_LENGTH, "") }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_CHOICES)
        validateExerciseDefinition(
            type = type,
            prompt = cleanedPrompt,
            expectedAnswer = cleanedAnswer,
            choices = cleanedChoices
        )
        val exercise = Exercise(
            id = "exercise:${UUID.randomUUID()}",
            title = clean(title, MAX_TITLE_LENGTH, "Nouvel exercice"),
            instructions = clean(
                instructions,
                MAX_INSTRUCTIONS_LENGTH,
                "Répondez à la consigne."
            ),
            prompt = cleanedPrompt,
            expectedAnswer = cleanedAnswer,
            languageTag = normalizeLanguageTag(languageTag),
            createdAtMillis = System.currentTimeMillis(),
            type = type,
            choices = cleanedChoices,
            difficulty = difficulty,
            topic = LearningTopics.choose(
                requested = topic,
                context = "$title $instructions $prompt"
            ),
            plan = normalizeExercisePlan(
                requested = plan,
                prompt = cleanedPrompt,
                expectedAnswer = cleanedAnswer,
                choices = cleanedChoices
            )
        )
        val updated = mutableState.value.copy(
            exercises = listOf(exercise) + mutableState.value.exercises
        )
        persist(updated)
        mutableState.value = updated
        exercise
    }

    fun createLesson(
        title: String,
        objective: String,
        content: String,
        languageTag: String,
        topic: String = "Culture"
    ): Lesson = synchronized(FILE_LOCK) {
        val lesson = Lesson(
            id = "lesson:${UUID.randomUUID()}",
            title = clean(title, MAX_TITLE_LENGTH, "Nouvelle leçon"),
            objective = clean(
                objective,
                MAX_OBJECTIVE_LENGTH,
                "Découvrir un nouveau point de langue."
            ),
            content = clean(
                content,
                MAX_LESSON_LENGTH,
                "Le contenu de cette leçon reste à compléter."
            ),
            languageTag = normalizeLanguageTag(languageTag),
            createdAtMillis = System.currentTimeMillis(),
            topic = LearningTopics.choose(
                requested = topic,
                context = "$title $objective $content"
            )
        )
        val updated = mutableState.value.copy(
            lessons = listOf(lesson) + mutableState.value.lessons
        )
        persist(updated)
        mutableState.value = updated
        lesson
    }

    fun archiveExercise(id: String) = synchronized(FILE_LOCK) {
        val archivedAt = System.currentTimeMillis()
        val updated = mutableState.value.copy(
            exercises = mutableState.value.exercises.map { exercise ->
                if (exercise.id == id && exercise.archivedAtMillis == null) {
                    exercise.copy(archivedAtMillis = archivedAt)
                } else {
                    exercise
                }
            }
        )
        if (updated != mutableState.value) {
            persist(updated)
            mutableState.value = updated
        }
    }

    fun completeExercise(
        id: String,
        mistakes: Int,
        elapsedMillis: Long,
        hintsUsed: Int
    ) = synchronized(FILE_LOCK) {
        updateExercise(id) { exercise ->
            exercise.copy(
                completion = ExerciseCompletion(
                    completedAtMillis = System.currentTimeMillis(),
                    mistakes = mistakes.coerceAtLeast(0),
                    elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                    hintsUsed = hintsUsed.coerceAtLeast(0),
                    difficultyRating = exercise.completion?.difficultyRating
                )
            )
        }
    }

    fun rateExercise(id: String, rating: Int) = synchronized(FILE_LOCK) {
        updateExercise(id) { exercise ->
            val completion = exercise.completion ?: return@updateExercise exercise
            exercise.copy(
                completion = completion.copy(difficultyRating = rating.coerceIn(1, 5))
            )
        }
    }

    private fun updateExercise(id: String, transform: (Exercise) -> Exercise) {
        val updated = mutableState.value.copy(
            exercises = mutableState.value.exercises.map { exercise ->
                if (exercise.id == id) transform(exercise) else exercise
            }
        )
        if (updated != mutableState.value) {
            persist(updated)
            mutableState.value = updated
        }
    }

    fun archiveLesson(id: String) = synchronized(FILE_LOCK) {
        val archivedAt = System.currentTimeMillis()
        val updated = mutableState.value.copy(
            lessons = mutableState.value.lessons.map { lesson ->
                if (lesson.id == id && lesson.archivedAtMillis == null) {
                    lesson.copy(archivedAtMillis = archivedAt)
                } else {
                    lesson
                }
            }
        )
        if (updated != mutableState.value) {
            persist(updated)
            mutableState.value = updated
        }
    }

    fun execute(action: LearningContentAction) {
        when (action) {
            is LearningContentAction.CreateExercise -> createExercise(
                title = action.title,
                instructions = action.instructions,
                prompt = action.prompt,
                expectedAnswer = action.expectedAnswer,
                languageTag = action.languageTag,
                type = action.type,
                choices = action.choices,
                difficulty = action.difficulty,
                topic = action.topic,
                plan = action.plan
            )
            is LearningContentAction.CreateLesson -> createLesson(
                title = action.title,
                objective = action.objective,
                content = action.content,
                languageTag = action.languageTag,
                topic = action.topic
            )
        }
    }

    private fun readContent(): LearningContentState = synchronized(FILE_LOCK) {
        if (!contentFile.isFile) return@synchronized LearningContentState()
        runCatching {
            val root = JSONObject(contentFile.readText())
            LearningContentState(
                exercises = root.optJSONArray(KEY_EXERCISES)
                    .toExercises(),
                lessons = root.optJSONArray(KEY_LESSONS)
                    .toLessons()
            )
        }.getOrDefault(LearningContentState())
    }

    private fun persist(state: LearningContentState) {
        contentFile.parentFile?.mkdirs()
        val root = JSONObject()
            .put(
                KEY_EXERCISES,
                JSONArray().apply {
                    state.exercises.forEach { exercise ->
                        put(
                            JSONObject()
                                .put(KEY_ID, exercise.id)
                                .put(KEY_TITLE, exercise.title)
                                .put(KEY_INSTRUCTIONS, exercise.instructions)
                                .put(KEY_PROMPT, exercise.prompt)
                                .put(KEY_EXPECTED_ANSWER, exercise.expectedAnswer)
                                .put(KEY_EXERCISE_TYPE, exercise.type.wireValue)
                                .put(KEY_DIFFICULTY, exercise.difficulty.wireValue)
                                .put(KEY_TOPIC, exercise.topic)
                                .put(KEY_PLAN, exercise.plan.toJson())
                                .put(
                                    KEY_COMPLETION,
                                    exercise.completion?.toJson() ?: JSONObject.NULL
                                )
                                .put(
                                    KEY_CHOICES,
                                    JSONArray().apply {
                                        exercise.choices.forEach(::put)
                                    }
                                )
                                .put(KEY_LANGUAGE_TAG, exercise.languageTag)
                                .put(KEY_CREATED_AT, exercise.createdAtMillis)
                                .put(
                                    KEY_ARCHIVED_AT,
                                    exercise.archivedAtMillis ?: JSONObject.NULL
                                )
                        )
                    }
                }
            )
            .put(
                KEY_LESSONS,
                JSONArray().apply {
                    state.lessons.forEach { lesson ->
                        put(
                            JSONObject()
                                .put(KEY_ID, lesson.id)
                                .put(KEY_TITLE, lesson.title)
                                .put(KEY_OBJECTIVE, lesson.objective)
                                .put(KEY_CONTENT, lesson.content)
                                .put(KEY_TOPIC, lesson.topic)
                                .put(KEY_LANGUAGE_TAG, lesson.languageTag)
                                .put(KEY_CREATED_AT, lesson.createdAtMillis)
                                .put(
                                    KEY_ARCHIVED_AT,
                                    lesson.archivedAtMillis ?: JSONObject.NULL
                                )
                        )
                    }
                }
            )
        val temporary = File(contentFile.parentFile, "${contentFile.name}.partial")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(contentFile)) {
            temporary.copyTo(contentFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun JSONArray?.toExercises(): List<Exercise> = buildList {
        val array = this@toExercises ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            runCatching {
                Exercise(
                    id = item.getString(KEY_ID),
                    title = item.getString(KEY_TITLE),
                    instructions = item.getString(KEY_INSTRUCTIONS),
                    prompt = item.getString(KEY_PROMPT),
                    expectedAnswer = item.getString(KEY_EXPECTED_ANSWER),
                    languageTag = item.getString(KEY_LANGUAGE_TAG),
                    createdAtMillis = item.getLong(KEY_CREATED_AT),
                    archivedAtMillis = item.optionalArchivedAtMillis(),
                    type = ExerciseType.fromWireValue(
                        item.optString(KEY_EXERCISE_TYPE)
                    ),
                    choices = item.optJSONArray(KEY_CHOICES).toStrings(),
                    difficulty = ExerciseDifficulty.fromWireValue(
                        item.optString(KEY_DIFFICULTY)
                    ),
                    topic = LearningTopics.choose(
                        requested = item.optString(KEY_TOPIC),
                        context = listOf(
                            item.optString(KEY_TITLE),
                            item.optString(KEY_INSTRUCTIONS),
                            item.optString(KEY_PROMPT)
                        ).joinToString(" ")
                    ),
                    plan = normalizeExercisePlan(
                        requested = item.optJSONObject(KEY_PLAN)?.toExercisePlan()
                            ?: fallbackExercisePlan(
                                prompt = item.getString(KEY_PROMPT),
                                expectedAnswer = item.getString(KEY_EXPECTED_ANSWER),
                                choices = item.optJSONArray(KEY_CHOICES).toStrings()
                            ),
                        prompt = item.getString(KEY_PROMPT),
                        expectedAnswer = item.getString(KEY_EXPECTED_ANSWER),
                        choices = item.optJSONArray(KEY_CHOICES).toStrings()
                    ),
                    completion = item.optJSONObject(KEY_COMPLETION)?.toExerciseCompletion()
                )
            }.getOrNull()?.let(::add)
        }
    }

    private fun JSONArray?.toLessons(): List<Lesson> = buildList {
        val array = this@toLessons ?: return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            runCatching {
                Lesson(
                    id = item.getString(KEY_ID),
                    title = item.getString(KEY_TITLE),
                    objective = item.getString(KEY_OBJECTIVE),
                    content = item.getString(KEY_CONTENT),
                    languageTag = item.getString(KEY_LANGUAGE_TAG),
                    createdAtMillis = item.getLong(KEY_CREATED_AT),
                    archivedAtMillis = item.optionalArchivedAtMillis(),
                    topic = LearningTopics.choose(
                        requested = item.optString(KEY_TOPIC),
                        context = listOf(
                            item.optString(KEY_TITLE),
                            item.optString(KEY_OBJECTIVE),
                            item.optString(KEY_CONTENT)
                        ).joinToString(" ")
                    )
                )
            }.getOrNull()?.let(::add)
        }
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        val array = this@toStrings ?: return@buildList
        for (index in 0 until array.length()) {
            array.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private fun ExercisePlan.toJson(): JSONObject = JSONObject()
        .put(
            KEY_WORDS,
            JSONArray().apply {
                words.forEach { word ->
                    put(
                        JSONObject()
                            .put(KEY_TEXT, word.text)
                            .put(KEY_PRONUNCIATION, word.pronunciation)
                            .put(KEY_DEFINITION, word.definition)
                            .put(KEY_GAP_SENTENCE, word.gapSentence)
                            .put(KEY_DISTRACTORS, JSONArray(word.distractors))
                            .put(KEY_RECALL_PROMPT, word.recallPrompt)
                            .put(KEY_RECALL_ANSWER, word.recallAnswer)
                    )
                }
            }
        )
        .put(KEY_HARD_PROMPT, hardPrompt)
        .put(KEY_HARD_ANSWER, hardAnswer)
        .put(KEY_FINAL_SENTENCE, finalSentence)
        .put(KEY_FINAL_ANSWERS, JSONArray(finalAnswers))

    private fun JSONObject.toExercisePlan(): ExercisePlan {
        val wordsArray = optJSONArray(KEY_WORDS)
        val words = buildList {
            if (wordsArray != null) {
                for (index in 0 until wordsArray.length()) {
                    val word = wordsArray.optJSONObject(index) ?: continue
                    add(
                        LearnedWord(
                            text = word.optString(KEY_TEXT),
                            pronunciation = word.optString(KEY_PRONUNCIATION),
                            definition = word.optString(KEY_DEFINITION),
                            gapSentence = word.optString(KEY_GAP_SENTENCE),
                            distractors = word.optJSONArray(KEY_DISTRACTORS).toStrings(),
                            recallPrompt = word.optString(KEY_RECALL_PROMPT),
                            recallAnswer = word.optString(KEY_RECALL_ANSWER)
                        )
                    )
                }
            }
        }
        return ExercisePlan(
            words = words,
            hardPrompt = optString(KEY_HARD_PROMPT),
            hardAnswer = optString(KEY_HARD_ANSWER),
            finalSentence = optString(KEY_FINAL_SENTENCE),
            finalAnswers = optJSONArray(KEY_FINAL_ANSWERS).toStrings()
        )
    }

    private fun ExerciseCompletion.toJson(): JSONObject = JSONObject()
        .put(KEY_COMPLETED_AT, completedAtMillis)
        .put(KEY_MISTAKES, mistakes)
        .put(KEY_ELAPSED, elapsedMillis)
        .put(KEY_HINTS, hintsUsed)
        .put(KEY_RATING, difficultyRating ?: JSONObject.NULL)

    private fun JSONObject.toExerciseCompletion(): ExerciseCompletion = ExerciseCompletion(
        completedAtMillis = optLong(KEY_COMPLETED_AT),
        mistakes = optInt(KEY_MISTAKES),
        elapsedMillis = optLong(KEY_ELAPSED),
        hintsUsed = optInt(KEY_HINTS),
        difficultyRating = if (isNull(KEY_RATING)) null else optInt(KEY_RATING).takeIf { it in 1..5 }
    )

    private fun clean(value: String, maxLength: Int, fallback: String): String =
        value.trim()
            .replace(Regex("[\\t ]+"), " ")
            .take(maxLength)
            .ifBlank { fallback }

    private fun normalizeLanguageTag(value: String): String =
        value.trim()
            .take(MAX_LANGUAGE_TAG_LENGTH)
            .ifBlank { "und" }

    private fun JSONObject.optionalArchivedAtMillis(): Long? =
        if (has(KEY_ARCHIVED_AT) && !isNull(KEY_ARCHIVED_AT)) {
            optLong(KEY_ARCHIVED_AT).takeIf { it > 0L }
        } else {
            null
        }

    companion object {
        @Volatile
        private var instance: LearningContentRepository? = null
        private val FILE_LOCK = Any()

        private const val CONTENT_FILE_NAME = "learning_content.json"
        private const val KEY_EXERCISES = "exercises"
        private const val KEY_LESSONS = "lessons"
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_INSTRUCTIONS = "instructions"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_EXPECTED_ANSWER = "expectedAnswer"
        private const val KEY_EXERCISE_TYPE = "exerciseType"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_TOPIC = "topic"
        private const val KEY_PLAN = "plan"
        private const val KEY_COMPLETION = "completion"
        private const val KEY_WORDS = "words"
        private const val KEY_TEXT = "text"
        private const val KEY_PRONUNCIATION = "pronunciation"
        private const val KEY_DEFINITION = "definition"
        private const val KEY_GAP_SENTENCE = "gapSentence"
        private const val KEY_DISTRACTORS = "distractors"
        private const val KEY_RECALL_PROMPT = "recallPrompt"
        private const val KEY_RECALL_ANSWER = "recallAnswer"
        private const val KEY_HARD_PROMPT = "hardPrompt"
        private const val KEY_HARD_ANSWER = "hardAnswer"
        private const val KEY_FINAL_SENTENCE = "finalSentence"
        private const val KEY_FINAL_ANSWERS = "finalAnswers"
        private const val KEY_COMPLETED_AT = "completedAtMillis"
        private const val KEY_MISTAKES = "mistakes"
        private const val KEY_ELAPSED = "elapsedMillis"
        private const val KEY_HINTS = "hintsUsed"
        private const val KEY_RATING = "difficultyRating"
        private const val KEY_CHOICES = "choices"
        private const val KEY_OBJECTIVE = "objective"
        private const val KEY_CONTENT = "content"
        private const val KEY_LANGUAGE_TAG = "languageTag"
        private const val KEY_CREATED_AT = "createdAtMillis"
        private const val KEY_ARCHIVED_AT = "archivedAtMillis"
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_TOPIC_LENGTH = 80
        private const val MAX_INSTRUCTIONS_LENGTH = 800
        private const val MAX_PROMPT_LENGTH = 1_500
        private const val MAX_ANSWER_LENGTH = 1_500
        private const val MAX_CHOICE_LENGTH = 180
        private const val MAX_CHOICES = 16
        private const val MAX_OBJECTIVE_LENGTH = 800
        private const val MAX_LESSON_LENGTH = 6_000
        private const val MAX_LANGUAGE_TAG_LENGTH = 35

        fun getInstance(context: Context): LearningContentRepository =
            instance ?: synchronized(this) {
                instance ?: LearningContentRepository(context).also {
                    instance = it
                }
            }

        internal fun createForTests(contentFile: File): LearningContentRepository =
            LearningContentRepository(contentFile)
    }
}

fun decodeExerciseChoices(value: String?): List<String> {
    val rawValue = value.orEmpty().trim()
    if (rawValue.isBlank() || rawValue.equals("NONE", ignoreCase = true)) return emptyList()
    val jsonChoices = runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrNull()
    if (!jsonChoices.isNullOrEmpty()) return jsonChoices
    val separator = when {
        "||" in rawValue -> "||"
        '\n' in rawValue -> "\n"
        else -> null
    }
    return (separator?.let { rawValue.split(it) } ?: listOf(rawValue))
        .map(String::trim)
        .filter { it.isNotBlank() && !it.equals("NONE", ignoreCase = true) }
}

fun requireExerciseType(value: String): ExerciseType = ExerciseType.entries
    .firstOrNull {
        it.wireValue == value.trim()
            .replace(Regex("[^A-Za-z]+"), "_")
            .trim('_')
            .uppercase()
    }
    ?: throw IllegalArgumentException(
        "Type d'exercice inconnu : ${value.trim().take(40)}"
    )

data class GeneratedExerciseDefinition(
    val type: ExerciseType,
    val choices: List<String>
)

/** Repairs harmless small-model formatting mistakes before strict persistence validation. */
fun normalizeGeneratedExerciseDefinition(
    typeValue: String?,
    expectedAnswer: String,
    choicesValue: String?
): GeneratedExerciseDefinition {
    val requestedType = typeValue
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { requireExerciseType(it) }.getOrNull() }
        ?: ExerciseType.FREE_RESPONSE
    val choices = decodeExerciseChoices(choicesValue).distinct()
    return when (requestedType) {
        ExerciseType.MULTIPLE_CHOICE -> {
            val repaired = choices.toMutableList()
            if (repaired.none { it.equals(expectedAnswer, ignoreCase = true) }) {
                if (repaired.size >= 6) repaired.removeAt(repaired.lastIndex)
                repaired += expectedAnswer
            }
            if (repaired.size >= 2) {
                GeneratedExerciseDefinition(requestedType, repaired.take(6))
            } else {
                GeneratedExerciseDefinition(ExerciseType.FREE_RESPONSE, emptyList())
            }
        }

        ExerciseType.WORD_ORDER -> {
            val orderedSegments = choices.takeIf { it.size >= 2 }
                ?: expectedAnswer.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (orderedSegments.size >= 2) {
                GeneratedExerciseDefinition(requestedType, orderedSegments.take(16))
            } else {
                GeneratedExerciseDefinition(ExerciseType.FREE_RESPONSE, emptyList())
            }
        }

        ExerciseType.MATCHING -> if (choices.size in 4..16 && choices.size % 2 == 0) {
            GeneratedExerciseDefinition(requestedType, choices)
        } else {
            GeneratedExerciseDefinition(ExerciseType.FREE_RESPONSE, emptyList())
        }

        ExerciseType.FREE_RESPONSE,
        ExerciseType.FILL_BLANK,
        ExerciseType.TRANSLATION -> GeneratedExerciseDefinition(requestedType, emptyList())
    }
}

fun validateExerciseDefinition(
    type: ExerciseType,
    prompt: String,
    expectedAnswer: String,
    choices: List<String>
) {
    require(prompt.isNotBlank()) { "La question de l'exercice est vide." }
    require(expectedAnswer.isNotBlank()) { "La réponse de référence est vide." }
    when (type) {
        ExerciseType.MULTIPLE_CHOICE -> {
            require(choices.size in 2..6) {
                "Un choix multiple doit proposer entre 2 et 6 réponses."
            }
            require(choices.any { it.equals(expectedAnswer, ignoreCase = true) }) {
                "La réponse correcte doit figurer parmi les choix."
            }
        }

        ExerciseType.WORD_ORDER -> require(choices.size in 2..16) {
            "Un exercice de remise en ordre doit fournir entre 2 et 16 segments."
        }

        ExerciseType.MATCHING -> require(
            choices.size in 4..16 && choices.size % 2 == 0
        ) {
            "Un exercice d'association doit fournir 2 à 8 paires alternées."
        }

        ExerciseType.FREE_RESPONSE,
        ExerciseType.FILL_BLANK,
        ExerciseType.TRANSLATION -> Unit
    }
}
