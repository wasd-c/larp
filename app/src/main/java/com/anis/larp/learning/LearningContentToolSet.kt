package com.anis.larp.learning

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class LearningContentToolSet(
    private val repository: LearningContentRepository,
    private val onActionExecuted: (LearningContentAction) -> Unit = {}
) : ToolSet {
    @Tool(
        description =
            "Create and save one fixed ten-step vocabulary exercise in larp. It teaches two related " +
                "words through learn, speak/type, gap drag, contextual recall cycles, then a hard " +
                "joint task and a final four-gap mixed typed/drag task."
    )
    fun createExercise(
        @ToolParam(description = "Short title shown in the Exercises tab.")
        title: String,
        @ToolParam(description = "Clear instructions in the learner's native language.")
        instructions: String,
        @ToolParam(description = "The question or task the learner must answer.")
        prompt: String,
        @ToolParam(description = "A concise reference answer used for correction.")
        expectedAnswer: String,
        @ToolParam(
            description =
                "Exercise type: FREE_RESPONSE, MULTIPLE_CHOICE, FILL_BLANK, " +
                    "WORD_ORDER, MATCHING, or TRANSLATION."
        )
        exerciseType: String,
        @ToolParam(
            description =
                "Options separated by ||. MULTIPLE_CHOICE: all answers; " +
                    "WORD_ORDER: correct ordered segments; MATCHING: alternating left and right terms; " +
                    "use NONE for other types."
        )
        choices: String,
        @ToolParam(description = "BCP-47 tag of the language being practiced.")
        languageTag: String,
        @ToolParam(description = "Difficulty: BEGINNER, INTERMEDIATE, or ADVANCED.")
        difficulty: String = "INTERMEDIATE",
        @ToolParam(
            description =
                "Choose exactly one topic tag from: " + APPROVED_TOPIC_TAGS_PROMPT
        )
        topic: String = "Culture",
        @ToolParam(description = "First target-language word to teach.")
        word1: String = "",
        @ToolParam(description = "Pronunciation guide for the first word.")
        word1Pronunciation: String = "",
        @ToolParam(description = "Short native-language definition of the first word.")
        word1Definition: String = "",
        @ToolParam(description = "Sentence containing exactly one ___ gap whose answer is the first word.")
        word1GapSentence: String = "",
        @ToolParam(description = "Exactly two wrong words separated by || for the first gap.")
        word1Distractors: String = "",
        @ToolParam(description = "Harder contextual recall prompt for the first word.")
        word1RecallPrompt: String = "",
        @ToolParam(description = "Reference answer for the first contextual recall.")
        word1RecallAnswer: String = "",
        @ToolParam(description = "Second related target-language word to teach.")
        word2: String = "",
        @ToolParam(description = "Pronunciation guide for the second word.")
        word2Pronunciation: String = "",
        @ToolParam(description = "Short native-language definition of the second word.")
        word2Definition: String = "",
        @ToolParam(description = "Sentence containing exactly one ___ gap whose answer is the second word.")
        word2GapSentence: String = "",
        @ToolParam(description = "Exactly two wrong words separated by || for the second gap.")
        word2Distractors: String = "",
        @ToolParam(description = "Harder contextual recall prompt for the second word.")
        word2RecallPrompt: String = "",
        @ToolParam(description = "Reference answer for the second contextual recall.")
        word2RecallAnswer: String = "",
        @ToolParam(description = "Challenging ninth-step prompt using both learned words.")
        hardPrompt: String = "",
        @ToolParam(description = "Reference answer for the ninth step.")
        hardAnswer: String = "",
        @ToolParam(description = "Final sentence containing exactly four ___ gaps.")
        finalSentence: String = "",
        @ToolParam(description = "Four gap answers in order separated by ||; must include both learned words.")
        finalAnswers: String = ""
    ): Map<String, String> {
        val definition = normalizeGeneratedExerciseDefinition(
            typeValue = exerciseType,
            expectedAnswer = expectedAnswer,
            choicesValue = choices
        )
        validateExerciseDefinition(
            type = definition.type,
            prompt = prompt,
            expectedAnswer = expectedAnswer,
            choices = definition.choices
        )
        val action = LearningContentAction.CreateExercise(
            title = title,
            instructions = instructions,
            prompt = prompt,
            expectedAnswer = expectedAnswer,
            languageTag = languageTag,
            type = definition.type,
            choices = definition.choices,
            difficulty = ExerciseDifficulty.fromWireValue(difficulty),
            topic = LearningTopics.choose(
                requested = topic,
                context = "$title $instructions $prompt"
            ),
            plan = ExercisePlan(
                words = listOf(
                    LearnedWord(
                        text = word1,
                        pronunciation = word1Pronunciation,
                        definition = word1Definition,
                        gapSentence = word1GapSentence,
                        distractors = decodeExerciseChoices(word1Distractors),
                        recallPrompt = word1RecallPrompt,
                        recallAnswer = word1RecallAnswer
                    ),
                    LearnedWord(
                        text = word2,
                        pronunciation = word2Pronunciation,
                        definition = word2Definition,
                        gapSentence = word2GapSentence,
                        distractors = decodeExerciseChoices(word2Distractors),
                        recallPrompt = word2RecallPrompt,
                        recallAnswer = word2RecallAnswer
                    )
                ),
                hardPrompt = hardPrompt,
                hardAnswer = hardAnswer,
                finalSentence = finalSentence,
                finalAnswers = decodeExerciseChoices(finalAnswers)
            )
        )
        val exercise = repository.createExercise(
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
        onActionExecuted(action)
        return mapOf(
            "status" to "created",
            "kind" to "exercise",
            "id" to exercise.id,
            "title" to exercise.title,
            "location" to "Exercises tab"
        )
    }

    @Tool(
        description =
            "Create and save one concise language lesson in larp when the learner asks for a lesson."
    )
    fun createLesson(
        @ToolParam(description = "Short title shown in the Lessons tab.")
        title: String,
        @ToolParam(description = "One clear learning objective.")
        objective: String,
        @ToolParam(
            description =
                "Self-contained lesson content with explanations and useful examples."
        )
        content: String,
        @ToolParam(description = "BCP-47 tag of the language being taught.")
        languageTag: String,
        @ToolParam(
            description =
                "Choose exactly one topic tag from: " + APPROVED_TOPIC_TAGS_PROMPT
        )
        topic: String = "Culture"
    ): Map<String, String> {
        val action = LearningContentAction.CreateLesson(
            title = title,
            objective = objective,
            content = content,
            languageTag = languageTag,
            topic = LearningTopics.choose(
                requested = topic,
                context = "$title $objective $content"
            )
        )
        val lesson = repository.createLesson(
            title = action.title,
            objective = action.objective,
            content = action.content,
            languageTag = action.languageTag,
            topic = action.topic
        )
        onActionExecuted(action)
        return mapOf(
            "status" to "created",
            "kind" to "lesson",
            "id" to lesson.id,
            "title" to lesson.title,
            "location" to "Lessons tab"
        )
    }
}
