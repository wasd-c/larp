package com.anis.larp.learning

data class LearnedWord(
    val text: String,
    val pronunciation: String,
    val definition: String,
    val gapSentence: String,
    val distractors: List<String>,
    val recallPrompt: String,
    val recallAnswer: String
)

data class ExercisePlan(
    val words: List<LearnedWord>,
    val hardPrompt: String,
    val hardAnswer: String,
    val finalSentence: String,
    val finalAnswers: List<String>
)

data class ExerciseCompletion(
    val completedAtMillis: Long,
    val mistakes: Int,
    val elapsedMillis: Long,
    val hintsUsed: Int,
    val difficultyRating: Int? = null
)

fun fallbackExercisePlan(
    prompt: String,
    expectedAnswer: String,
    choices: List<String> = emptyList()
): ExercisePlan {
    val candidates = (listOf(expectedAnswer) + choices + prompt)
        .flatMap { it.split(Regex("[^\\p{L}\\p{N}'’-]+")) }
        .map(String::trim)
        .filter { it.length >= 2 }
        .distinctBy(String::lowercase)
    val first = candidates.getOrNull(0) ?: "mot"
    val second = candidates.firstOrNull { !it.equals(first, ignoreCase = true) } ?: "phrase"
    val fillers = candidates.filterNot {
        it.equals(first, ignoreCase = true) || it.equals(second, ignoreCase = true)
    }
    fun learnedWord(word: String, alternate: String, offset: Int) = LearnedWord(
        text = word,
        pronunciation = word,
        definition = "Mot utile dans le contexte de cet exercice.",
        gapSentence = "___ — $prompt",
        distractors = listOf(
            fillers.getOrNull(offset) ?: alternate,
            fillers.getOrNull(offset + 1) ?: "autre"
        ),
        recallPrompt = "Écrivez une courte phrase avec « $word ».",
        recallAnswer = word
    )
    val fillerOne = fillers.getOrNull(0) ?: "et"
    val fillerTwo = fillers.getOrNull(1) ?: "dans"
    return ExercisePlan(
        words = listOf(
            learnedWord(first, second, 0),
            learnedWord(second, first, 2)
        ),
        hardPrompt = prompt.ifBlank { "Utilisez les deux mots appris dans une phrase." },
        hardAnswer = expectedAnswer.ifBlank { "$first $second" },
        finalSentence = "___ ___ · ___ ___",
        finalAnswers = listOf(first, fillerOne, second, fillerTwo)
    )
}

fun normalizeExercisePlan(
    requested: ExercisePlan,
    prompt: String,
    expectedAnswer: String,
    choices: List<String> = emptyList()
): ExercisePlan {
    val fallback = fallbackExercisePlan(prompt, expectedAnswer, choices)
    val words = requested.words
        .mapIndexedNotNull { index, word ->
            val text = word.text.trim().take(80)
            if (text.isBlank()) return@mapIndexedNotNull null
            val backup = fallback.words[index.coerceAtMost(1)]
            val distractors = word.distractors
                .map { it.trim().take(80) }
                .filter { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
                .distinctBy(String::lowercase)
                .take(2)
                .toMutableList()
            backup.distractors.forEach { candidate ->
                if (distractors.size < 2 && distractors.none {
                        it.equals(candidate, ignoreCase = true)
                    }) {
                    distractors += candidate
                }
            }
            LearnedWord(
                text = text,
                pronunciation = word.pronunciation.trim().take(120).ifBlank { text },
                definition = word.definition.trim().take(300).ifBlank { backup.definition },
                gapSentence = word.gapSentence.trim().take(500)
                    .takeIf { it.countGaps() == 1 } ?: backup.gapSentence,
                distractors = distractors.take(2),
                recallPrompt = word.recallPrompt.trim().take(500)
                    .ifBlank { backup.recallPrompt },
                recallAnswer = word.recallAnswer.trim().take(300).ifBlank { text }
            )
        }
        .distinctBy { it.text.lowercase() }
        .toMutableList()
    fallback.words.forEach { fallbackWord ->
        if (words.size < 2 && words.none {
                it.text.equals(fallbackWord.text, ignoreCase = true)
            }) {
            words += fallbackWord
        }
    }
    val exactlyTwo = words.take(2)
    val requestedAnswers = requested.finalAnswers
        .map { it.trim().take(120) }
        .filter(String::isNotBlank)
    val validFinal = requested.finalSentence.countGaps() == 4 &&
        requestedAnswers.size == 4 && exactlyTwo.all { learned ->
            requestedAnswers.any { it.equals(learned.text, ignoreCase = true) }
        }
    return ExercisePlan(
        words = exactlyTwo,
        hardPrompt = requested.hardPrompt.trim().take(600).ifBlank { fallback.hardPrompt },
        hardAnswer = requested.hardAnswer.trim().take(600).ifBlank { fallback.hardAnswer },
        finalSentence = if (validFinal) requested.finalSentence.trim().take(800)
            else fallback.finalSentence,
        finalAnswers = if (validFinal) requestedAnswers else listOf(
            exactlyTwo[0].text,
            fallback.finalAnswers[1],
            exactlyTwo[1].text,
            fallback.finalAnswers[3]
        )
    )
}

fun String.countGaps(): Int = "___".toRegex().findAll(this).count()
