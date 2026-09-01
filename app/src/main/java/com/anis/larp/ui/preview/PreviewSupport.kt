package com.anis.larp.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anis.larp.learning.Exercise
import com.anis.larp.learning.ExerciseCompletion
import com.anis.larp.learning.ExerciseDifficulty
import com.anis.larp.learning.ExercisePlan
import com.anis.larp.learning.ExerciseType
import com.anis.larp.learning.LearnedWord
import com.anis.larp.learning.LearningSentence
import com.anis.larp.learning.LearningTarget
import com.anis.larp.learning.Lesson
import com.anis.larp.learning.LessonConfiguration
import com.anis.larp.learning.LessonContent
import com.anis.larp.learning.LessonDifficulty
import com.anis.larp.model.InstalledModelOption
import com.anis.larp.model.ModelInventory
import com.anis.larp.ui.components.AppDestination
import com.anis.larp.ui.components.ExpressiveNavigationBar
import com.anis.larp.ui.freemode.ChatMessageAuthor
import com.anis.larp.ui.freemode.FreeModeUiState
import com.anis.larp.ui.freemode.SpeechPhase
import com.anis.larp.ui.freemode.TutorChatMessage
import com.anis.larp.ui.theme.LarpTheme
import java.util.Locale

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Téléphone · Clair",
    group = "Téléphone",
    widthDp = 412,
    heightDp = 915,
    locale = "fr-FR",
    showBackground = true,
    showSystemUi = true
)
@Preview(
    name = "Téléphone · Sombre",
    group = "Téléphone",
    widthDp = 412,
    heightDp = 915,
    locale = "fr-FR",
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
annotation class LarpPhonePreviews

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Tablette · Clair",
    group = "Tablette",
    widthDp = 800,
    heightDp = 1280,
    locale = "fr-FR",
    showBackground = true,
    showSystemUi = true
)
annotation class LarpTabletPreview

@Composable
internal fun LarpPreviewTheme(content: @Composable () -> Unit) {
    LarpTheme(dynamicColor = false, content = content)
}

@Composable
internal fun PreviewDestinationFrame(
    selectedDestination: AppDestination,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        ExpressiveNavigationBar(
            selectedDestination = selectedDestination,
            onDestinationSelected = {},
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
        )
    }
}

internal object LarpPreviewData {
    private const val CREATED_AT = 1_775_000_000_000L

    private val presentationPlan = ExercisePlan(
        words = listOf(
            LearnedWord(
                text = "encantado",
                pronunciation = "èn-kan-ta-do",
                definition = "Enchanté de faire votre connaissance.",
                gapSentence = "Mucho gusto, ___.",
                distractors = listOf("gracias", "mañana"),
                recallPrompt = "Présentez-vous puis dites « enchanté ».",
                recallAnswer = "Me llamo Alex, encantado."
            ),
            LearnedWord(
                text = "vengo",
                pronunciation = "bèn-go",
                definition = "Je viens.",
                gapSentence = "___ de París.",
                distractors = listOf("soy", "tengo"),
                recallPrompt = "Dites que vous venez de Paris.",
                recallAnswer = "Vengo de París."
            )
        ),
        hardPrompt = "Présentez-vous en deux phrases.",
        hardAnswer = "Me llamo Alex. Vengo de París.",
        finalSentence = "___ ___ · ___ ___",
        finalAnswers = listOf("Me", "llamo", "Alex", "encantado")
    )

    private val presentationContent = LessonContent(
        topic = "Se présenter",
        targets = listOf(
            LearningTarget("Me llamo", "Je m'appelle", "mé ya-mo"),
            LearningTarget("Encantado", "Enchanté", "èn-kan-ta-do"),
            LearningTarget("Vengo de", "Je viens de", "bèn-go dé")
        ),
        sentences = listOf(
            LearningSentence(
                text = "Me llamo Alex.",
                meaning = "Je m'appelle Alex.",
                targetIndexes = listOf(0),
                chunks = listOf("Me", "llamo", "Alex.")
            ),
            LearningSentence(
                text = "Encantado de conocerte.",
                meaning = "Enchanté de faire ta connaissance.",
                targetIndexes = listOf(1),
                chunks = listOf("Encantado", "de", "conocerte.")
            ),
            LearningSentence(
                text = "Vengo de París.",
                meaning = "Je viens de Paris.",
                targetIndexes = listOf(2),
                chunks = listOf("Vengo", "de", "París.")
            )
        )
    )

    val activeExercise = Exercise(
        id = "preview:exercise:presentation",
        title = "Se présenter en espagnol",
        instructions = "Apprenez à donner votre nom et votre ville d'origine.",
        prompt = "Présentez-vous en espagnol.",
        expectedAnswer = "Me llamo Alex. Vengo de París.",
        languageTag = "es-ES",
        createdAtMillis = CREATED_AT,
        type = ExerciseType.TRANSLATION,
        difficulty = ExerciseDifficulty.BEGINNER,
        topic = "Conversation",
        plan = presentationPlan,
        lessonContent = presentationContent,
        lessonConfiguration = LessonConfiguration(
            desiredStepCount = 10,
            difficulty = LessonDifficulty.BEGINNER,
            seed = 42L
        )
    )

    private val completedExercise = Exercise(
        id = "preview:exercise:cafe",
        title = "Commander au café",
        instructions = "Choisissez les formulations adaptées à une commande polie.",
        prompt = "Demandez un café et l'addition.",
        expectedAnswer = "Un café, s'il vous plaît. L'addition, merci.",
        languageTag = "fr-FR",
        createdAtMillis = CREATED_AT - 86_400_000L,
        type = ExerciseType.MULTIPLE_CHOICE,
        choices = listOf(
            "Un café, s'il vous plaît.",
            "Je veux café.",
            "Donne-moi ça."
        ),
        difficulty = ExerciseDifficulty.INTERMEDIATE,
        topic = "Voyage",
        plan = ExercisePlan(
            words = listOf(
                LearnedWord(
                    "addition",
                    "a-di-sion",
                    "La note à payer.",
                    "L'___, s'il vous plaît.",
                    listOf("adresse", "attention"),
                    "Demandez la note.",
                    "L'addition, s'il vous plaît."
                ),
                LearnedWord(
                    "volontiers",
                    "vo-lon-tié",
                    "Avec plaisir.",
                    "Oui, ___.",
                    listOf("jamais", "ensuite"),
                    "Acceptez poliment.",
                    "Oui, volontiers."
                )
            ),
            hardPrompt = "Commandez et demandez la note.",
            hardAnswer = "Un café, s'il vous plaît. L'addition, merci.",
            finalSentence = "___ ___ · ___ ___",
            finalAnswers = listOf("Un", "café", "s'il", "vous plaît")
        ),
        completion = ExerciseCompletion(
            completedAtMillis = CREATED_AT + 3_600_000L,
            mistakes = 1,
            elapsedMillis = 245_000L,
            hintsUsed = 1,
            difficultyRating = 3
        )
    )

    private val grammarExercise = Exercise(
        id = "preview:exercise:past",
        title = "Le passé composé",
        instructions = "Conjuguez les verbes avec l'auxiliaire correct.",
        prompt = "Hier, nous ___ au cinéma.",
        expectedAnswer = "sommes allés",
        languageTag = "fr-FR",
        createdAtMillis = CREATED_AT - 172_800_000L,
        type = ExerciseType.FILL_BLANK,
        difficulty = ExerciseDifficulty.ADVANCED,
        topic = "Grammaire"
    )

    val exercises = listOf(activeExercise, grammarExercise, completedExercise)

    val lessons = listOf(
        Lesson(
            id = "preview:lesson:restaurant",
            title = "Au restaurant",
            objective = "Commander avec assurance et demander des précisions sur le menu.",
            content = "Pour commander poliment, utilisez « Je voudrais… » ou « Est-ce que je pourrais avoir… ? ».\n\nPour demander une précision : « Qu'est-ce qu'il y a dans ce plat ? »",
            languageTag = "fr-FR",
            createdAtMillis = CREATED_AT,
            topic = "Voyage"
        ),
        Lesson(
            id = "preview:lesson:small-talk",
            title = "Small talk au travail",
            objective = "Engager une conversation naturelle avec un collègue.",
            content = "Commencez par une question ouverte et rebondissez sur un détail de la réponse.",
            languageTag = "en-US",
            createdAtMillis = CREATED_AT - 86_400_000L,
            topic = "Travail"
        )
    )

    val freeModeState = FreeModeUiState(
        phase = SpeechPhase.IDLE,
        locale = Locale.FRENCH,
        targetLocale = Locale.forLanguageTag("es-ES"),
        promptModelName = "Gemma 4",
        promptAcceleration = "NPU",
        modelsReady = true,
        chatMessages = listOf(
            TutorChatMessage(
                ChatMessageAuthor.LEARNER,
                "Comment me présenter naturellement en espagnol ?"
            ),
            TutorChatMessage(
                ChatMessageAuthor.TUTOR,
                "Commencez par « Hola, me llamo… », puis ajoutez votre ville avec « Vengo de… »."
            )
        )
    )

    val modelInventory = ModelInventory(
        ttsModels = listOf(
            InstalledModelOption(
                id = "es-es-x-eef-local",
                label = "Español · voix locale",
                description = "es-ES · hors ligne · qualité élevée"
            )
        ),
        promptModels = listOf(
            InstalledModelOption(
                id = "preview:gemma-4",
                label = "Gemma 4",
                description = "Download/Models · 2,8 Go · NPU"
            ),
            InstalledModelOption(
                id = "preview:gemini-nano",
                label = "Gemini Nano",
                description = "Android AI Core · installé sur l'appareil"
            )
        ),
        sttModels = listOf(
            InstalledModelOption(
                id = "preview:qwen",
                label = "Qwen",
                description = "Qwen3-ASR 0.6B · fr-FR · hors ligne"
            )
        )
    )
}
